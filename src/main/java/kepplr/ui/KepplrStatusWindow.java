package kepplr.ui;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import javafx.animation.AnimationTimer;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import kepplr.camera.CameraFrame;
import kepplr.commands.SimulationCommands;
import kepplr.config.KEPPLRConfiguration;
import kepplr.core.CaptureService;
import kepplr.ephemeris.BodyLookupService;
import kepplr.ephemeris.Instrument;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.ephemeris.spice.SpiceBundle;
import kepplr.render.RenderQuality;
import kepplr.render.vector.VectorTypes;
import kepplr.scripting.CommandRecorder;
import kepplr.scripting.KepplrScript;
import kepplr.scripting.ScriptRunner;
import kepplr.state.SimulationState;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.EphemerisID;

/**
 * Programmatic JavaFX control window displaying simulation status, body list, and interactive controls (REDESIGN.md
 * §10.2, Step 19).
 *
 * <p>Contains no simulation logic, no ephemeris calls beyond body-list population, and no camera math (CLAUDE.md Rule
 * 1). Every displayed value is bound to a {@link javafx.beans.property.ReadOnlyStringProperty} exposed by
 * {@link SimulationStateFxBridge}. All user actions are forwarded to {@link SimulationCommands}.
 *
 * <p>Body list population reads {@code KEPPLREphemeris.getKnownBodies()} at construction time to build the tree; this
 * is a one-time read, not ongoing logic. The tree refreshes on configuration reload.
 *
 * <p>Must be created and shown on the JavaFX application thread.
 */
public final class KepplrStatusWindow {

    private static final Logger logger = LogManager.getLogger();

    private static final double WINDOW_WIDTH = 440.0;
    private static final double WINDOW_HEIGHT = 720.0;

    private final SimulationStateFxBridge bridge;
    private final SimulationCommands commands;
    private Runnable jmeShutdown;
    private BiConsumer<Integer, Integer> jmeResizeCallback;
    private Runnable configReloadCallback;
    private ScriptRunner scriptRunner;
    private CommandRecorder commandRecorder;
    private static final int SCRIPT_OUTPUT_MAX_LINES = 200;

    private Stage stage;
    private TreeView<BodyTreeEntry> bodyTree;
    private TreeItem<BodyTreeEntry> masterRoot;
    private Menu instrumentsMenu;
    private CheckMenuItem labelsCheckItem;
    private TextArea scriptOutputArea;
    private final ConcurrentLinkedQueue<String> scriptOutputQueue = new ConcurrentLinkedQueue<>();
    private int scriptOutputLineCount;
    private CustomMenuItem captureSeqItem;
    private CustomMenuItem saveScreenshotItem;
    private volatile Thread captureSequenceThread;
    private volatile Thread consoleThread;
    /** Set by {@link #signalConfigRefresh()} from any thread; drained by the AnimationTimer on the FX thread. */
    private volatile boolean pendingConfigRefresh = false;

    private volatile boolean pendingWindowPosition = false;
    private volatile double pendingWindowX = 0.0;
    private volatile double pendingWindowY = 0.0;

    private TextArea consoleInput;
    private LogWindow logWindow;

    /**
     * @param bridge the bridge exposing FX-thread-safe display properties; must not be null
     * @param commands the simulation commands interface for user-initiated actions; must not be null
     */
    public KepplrStatusWindow(SimulationStateFxBridge bridge, SimulationCommands commands) {
        this.bridge = bridge;
        this.commands = commands;
    }

    /**
     * Set the callback to shut down JME when the JavaFX window is closed.
     *
     * @param shutdown runnable that stops the JME application; called from the FX thread
     */
    public void setJmeShutdown(Runnable shutdown) {
        this.jmeShutdown = shutdown;
    }

    /**
     * Set the callback to resize the JME render window.
     *
     * @param resizeCallback accepts (width, height) in pixels; called from the FX thread
     */
    public void setJmeResizeCallback(BiConsumer<Integer, Integer> resizeCallback) {
        this.jmeResizeCallback = resizeCallback;
    }

    /**
     * Set the callback invoked on the JME render thread after a configuration file is successfully loaded.
     *
     * @param callback enqueues a JME-thread rebuild of the body scene; must not be null
     */
    public void setConfigReloadCallback(Runnable callback) {
        this.configReloadCallback = callback;
    }

    /**
     * Set the script runner for "Run Script..." menu action.
     *
     * @param runner the script runner instance; must not be null
     */
    public void setScriptRunner(ScriptRunner runner) {
        this.scriptRunner = runner;
        runner.setOutputListener(line -> scriptOutputQueue.add(line));
    }

    /**
     * Set the command recorder for "Start/Stop Recording" menu toggle.
     *
     * @param recorder the command recorder instance; must not be null
     */
    public void setCommandRecorder(CommandRecorder recorder) {
        this.commandRecorder = recorder;
    }

    /**
     * Signal that the body tree and instruments menu should be refreshed on the next animation frame.
     *
     * <p>Thread-safe; may be called from any thread (script thread, background load thread, etc.). The actual UI
     * refresh runs on the JavaFX thread inside the {@code AnimationTimer}.
     */
    public void signalConfigRefresh() {
        pendingConfigRefresh = true;
    }

    /**
     * Create and show the control window.
     *
     * <p>Must be called on the JavaFX application thread. Safe to call multiple times — subsequent calls bring the
     * existing window to the front.
     */
    public void show() {
        if (stage != null) {
            stage.show();
            stage.toFront();
            return;
        }

        stage = new Stage();
        stage.setTitle("KEPPLR");

        MenuBar menuBar = buildMenuBar();
        VBox bodyReadout = buildBodyReadout();
        VBox statusSection = buildStatusSection();
        VBox bodyListSection = buildBodyListSection();
        SplitPane scriptPanel = buildScriptOutputPanel();

        SplitPane splitPane = new SplitPane(bodyListSection, scriptPanel);
        splitPane.setOrientation(Orientation.VERTICAL);
        splitPane.setDividerPositions(0.75);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        VBox root = new VBox(6, menuBar, bodyReadout, new Separator(), statusSection, new Separator(), splitPane);

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);

        // BUG 3: Suppress Escape-closes-stage default behavior
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) e.consume();
        });

        stage.setScene(scene);

        // BUG 2: When the JavaFX window is closed, shut down JME too
        stage.setOnCloseRequest(e -> {
            if (jmeShutdown != null) jmeShutdown.run();
        });

        // Install the log4j2 appender and create the log window
        LogAppender.install();
        logWindow = new LogWindow();

        stage.show();
        stage.toFront();
        applyPendingPosition();
        bridge.startPolling();

        // Drain script output queue, log window, and check capture thread status on each FX frame
        // (avoids Platform.runLater per CLAUDE.md Rule 2)
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                applyPendingPosition();
                drainScriptOutput();
                checkCaptureThreadDone();
                drainConfigRefresh();
                logWindow.drain();
            }
        }.start();
    }

    /** Close the window programmatically (called from JME destroy() hook). */
    public void close() {
        if (logWindow != null) {
            logWindow.close();
        }
        if (stage != null) {
            stage.close();
        }
    }

    /**
     * Reposition the window. Must be called on the FX thread.
     *
     * @param x screen x coordinate
     * @param y screen y coordinate
     */
    public void setPosition(double x, double y) {
        if (stage != null) {
            stage.setX(x);
            stage.setY(y);
        }
    }

    /**
     * Request a window position update from any thread.
     *
     * <p>The position is applied on the next JavaFX pulse after the window exists.
     */
    public void requestPosition(double x, double y) {
        pendingWindowX = x;
        pendingWindowY = y;
        pendingWindowPosition = true;
    }

    private void applyPendingPosition() {
        if (pendingWindowPosition && stage != null) {
            stage.setX(pendingWindowX);
            stage.setY(pendingWindowY);
            pendingWindowPosition = false;
        }
    }

    // ── Body Readout (Focused, Targeted, Selected with action buttons) ────────

    private VBox buildBodyReadout() {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(6, 10, 6, 10));
        grid.setHgap(8);
        grid.setVgap(4);

        ColumnConstraints labelCol = new ColumnConstraints(70);
        ColumnConstraints nameCol = new ColumnConstraints();
        nameCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints distCol = new ColumnConstraints();
        ColumnConstraints buttonCol = new ColumnConstraints();
        grid.getColumnConstraints().addAll(labelCol, nameCol, distCol, buttonCol);

        int row = 0;

        // Row 0: Center
        grid.add(boldLabel("Center:"), 0, row);
        Label focValue = monoLabel();
        focValue.textProperty().bind(bridge.focusedBodyNameProperty());
        grid.add(focValue, 1, row);
        Label focDist = dimLabel();
        focDist.textProperty().bind(bridge.focusedBodyDistanceProperty());
        grid.add(focDist, 2, row);
        row++;

        // Row 1: Targeted
        grid.add(boldLabel("Targeted:"), 0, row);
        Label tgtValue = monoLabel();
        tgtValue.textProperty().bind(bridge.targetedBodyNameProperty());
        grid.add(tgtValue, 1, row);
        Label tgtDist = dimLabel();
        tgtDist.textProperty().bind(bridge.targetedBodyDistanceProperty());
        grid.add(tgtDist, 2, row);
        row++;

        // Row 2: Selected — with Center, Go To, Point At buttons
        grid.add(boldLabel("Selected:"), 0, row);
        Label selValue = monoLabel();
        selValue.textProperty().bind(bridge.selectedBodyNameProperty());
        grid.add(selValue, 1, row);
        Label selDist = dimLabel();
        selDist.textProperty().bind(bridge.selectedBodyDistanceProperty());
        grid.add(selDist, 2, row);

        Button centerBtn = smallButton("Center");
        centerBtn.setOnAction(e -> {
            int id = bridge.selectedBodyIdProperty().get();
            if (id != -1) commands.centerBody(id);
        });
        Button goToBtn = smallButton("Go To");
        goToBtn.setOnAction(e -> {
            int id = bridge.selectedBodyIdProperty().get();
            if (id != -1) {
                commands.goTo(
                        id,
                        KepplrConstants.DEFAULT_GOTO_APPARENT_RADIUS_DEG,
                        KepplrConstants.DEFAULT_GOTO_DURATION_SECONDS);
            }
        });
        Button pointAtBtn = smallButton("Point At");
        pointAtBtn.setOnAction(e -> {
            int id = bridge.selectedBodyIdProperty().get();
            if (id != -1) {
                commands.pointAt(id, KepplrConstants.DEFAULT_SLEW_DURATION_SECONDS);
            }
        });
        HBox selButtons = new HBox(4, centerBtn, goToBtn, pointAtBtn);
        selButtons.visibleProperty().bind(bridge.selectedBodyActiveProperty());
        selButtons.managedProperty().bind(bridge.selectedBodyActiveProperty());
        grid.add(selButtons, 3, row);

        return new VBox(grid);
    }

    // ── Status Section (time, camera frame, camera position) ─────────────────

    private VBox buildStatusSection() {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(4, 10, 4, 10));
        grid.setHgap(8);
        grid.setVgap(3);

        ColumnConstraints labelCol = new ColumnConstraints(90);
        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol, valueCol);

        int row = 0;
        row = addStatusRow(grid, row, "UTC:", bridge.utcTimeTextProperty());
        row = addStatusRow(grid, row, "Time rate:", bridge.timeRateTextProperty());
        row = addStatusRow(grid, row, "Clock:", bridge.pausedTextProperty());
        row = addStatusRow(grid, row, "Cam frame:", bridge.cameraFrameTextProperty());
        row = addStatusRow(grid, row, "FOV:", bridge.fovTextProperty());
        row = addStatusRow(grid, row, "Cam pos:", bridge.cameraPositionTextProperty());
        addStatusRow(grid, row, "BF pos:", bridge.cameraBodyFixedTextProperty());

        return new VBox(grid);
    }

    // ── Body List TreeView ───────────────────────────────────────────────────

    private VBox buildBodyListSection() {
        Label header = boldLabel("Select Body");
        header.setPadding(new Insets(4, 10, 2, 10));

        TextField searchField = new TextField();
        searchField.setPromptText("Filter...");

        ToggleButton inViewToggle = new ToggleButton("In View");
        inViewToggle.setStyle("-fx-font-size: 10px; -fx-padding: 2 6 2 6;");
        Tooltip.install(inViewToggle, new Tooltip("Show only bodies currently in the camera's field of view"));

        bodyTree = new TreeView<>();
        bodyTree.setShowRoot(false);
        populateBodyTree();
        VBox.setVgrow(bodyTree, Priority.ALWAYS);

        // Cell factory: highlight bodies currently in the camera FOV
        bodyTree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(BodyTreeEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item.displayName());
                    boolean inView = item.naifId() != -1
                            && bridge.inViewNaifIdsProperty().get().contains(item.naifId());
                    setStyle(inView ? "-fx-font-weight: bold; -fx-text-fill: #4d9eff;" : "");
                }
            }
        });

        // Shared filter logic: reads current text + toggle state, rebuilds tree root
        Runnable applyFilters = () -> {
            String raw = searchField.getText();
            String textFilter =
                    (raw == null || raw.trim().isEmpty()) ? null : raw.trim().toLowerCase();
            Set<Integer> inViewConstraint =
                    inViewToggle.isSelected() ? bridge.inViewNaifIdsProperty().get() : null;
            if (textFilter == null && inViewConstraint == null) {
                bodyTree.setRoot(masterRoot);
            } else {
                bodyTree.setRoot(buildFilteredRoot(textFilter, inViewConstraint));
            }
        };

        // Live filtering on text changes
        searchField.textProperty().addListener((obs, oldText, newText) -> applyFilters.run());

        // Enter resolves by body name or exact NAIF ID
        searchField.setOnAction(e -> {
            String text = searchField.getText().trim();
            if (text.isEmpty()) return;
            try {
                int naifId = BodyLookupService.resolve(text);
                commands.selectBody(naifId);
                searchField.clear();
            } catch (IllegalArgumentException ex) {
                logger.debug("Body search failed: {}", ex.getMessage());
            }
        });

        // Toggle: switch between all bodies and in-view only
        inViewToggle.selectedProperty().addListener((obs, wasOn, isOn) -> applyFilters.run());

        // When the in-view set changes: repaint cells and re-filter if toggle is on
        bridge.inViewNaifIdsProperty().addListener((obs, oldIds, newIds) -> {
            bodyTree.refresh();
            if (inViewToggle.isSelected()) applyFilters.run();
        });

        // Single click → select; double-click → center + goTo
        bodyTree.setOnMouseClicked(evt -> {
            TreeItem<BodyTreeEntry> item = bodyTree.getSelectionModel().getSelectedItem();
            if (item == null || item.getValue() == null || item.getValue().naifId == -1) return;
            int naifId = item.getValue().naifId;
            if (evt.getClickCount() == 2) {
                commands.centerBody(naifId);
                commands.goTo(
                        naifId,
                        KepplrConstants.DEFAULT_GOTO_APPARENT_RADIUS_DEG,
                        KepplrConstants.DEFAULT_GOTO_DURATION_SECONDS);
            } else if (evt.getClickCount() == 1) {
                commands.selectBody(naifId);
            }
        });

        // Right-click context menu — rebuilt on each show to reflect current toggle state
        ContextMenu contextMenu = new ContextMenu();
        bodyTree.setContextMenu(contextMenu);
        bodyTree.setOnContextMenuRequested(evt -> {
            TreeItem<BodyTreeEntry> item = bodyTree.getSelectionModel().getSelectedItem();
            if (item == null || item.getValue() == null || item.getValue().naifId == -1) {
                contextMenu.hide();
                evt.consume();
            } else {
                populateBodyTreeContextMenu(contextMenu, item.getValue().naifId());
            }
        });

        HBox searchRow = new HBox(4, searchField, inViewToggle);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchRow.setPadding(new Insets(0, 10, 4, 10));

        VBox section = new VBox(4, header, searchRow, bodyTree);
        VBox.setVgrow(section, Priority.ALWAYS);
        return section;
    }

    private void populateBodyTreeContextMenu(ContextMenu menu, int naifId) {
        menu.getItems().clear();

        MenuItem centerItem = new MenuItem("Center");
        centerItem.setOnAction(e -> commands.centerBody(naifId));

        MenuItem goToItem = new MenuItem("Go To");
        goToItem.setOnAction(e -> {
            commands.goTo(
                    naifId,
                    KepplrConstants.DEFAULT_GOTO_APPARENT_RADIUS_DEG,
                    KepplrConstants.DEFAULT_GOTO_DURATION_SECONDS);
        });

        MenuItem pointAtItem = new MenuItem("Point At");
        pointAtItem.setOnAction(e -> commands.pointAt(naifId, KepplrConstants.DEFAULT_SLEW_DURATION_SECONDS));

        CheckMenuItem trailItem = new CheckMenuItem("Trail");
        trailItem.setSelected(bridge.getState().trailVisibleProperty(naifId).get());
        trailItem.setOnAction(e -> commands.setTrailVisible(naifId, trailItem.isSelected()));

        CheckMenuItem labelItem = new CheckMenuItem("Label");
        labelItem.setSelected(bridge.getState().labelVisibleProperty(naifId).get());
        labelItem.setOnAction(e -> commands.setLabelVisible(naifId, labelItem.isSelected()));

        CheckMenuItem axesItem = new CheckMenuItem("Axes");
        axesItem.setSelected(bridge.getState()
                .vectorVisibleProperty(naifId, VectorTypes.bodyAxisX())
                .get());
        axesItem.setOnAction(e -> {
            boolean show = axesItem.isSelected();
            commands.setVectorVisible(naifId, VectorTypes.bodyAxisX(), show);
            commands.setVectorVisible(naifId, VectorTypes.bodyAxisY(), show);
            commands.setVectorVisible(naifId, VectorTypes.bodyAxisZ(), show);
        });

        CheckMenuItem visibleItem = new CheckMenuItem("Visible");
        visibleItem.setSelected(bridge.getState().bodyVisibleProperty(naifId).get());
        visibleItem.setOnAction(e -> commands.setBodyVisible(naifId, visibleItem.isSelected()));

        menu.getItems()
                .addAll(
                        centerItem,
                        goToItem,
                        pointAtItem,
                        new SeparatorMenuItem(),
                        trailItem,
                        labelItem,
                        new SeparatorMenuItem(),
                        axesItem,
                        visibleItem);
    }

    private int getSelectedTreeNaifId() {
        TreeItem<BodyTreeEntry> item = bodyTree.getSelectionModel().getSelectedItem();
        if (item == null || item.getValue() == null) return -1;
        return item.getValue().naifId();
    }

    /**
     * Build a filtered copy of the master tree.
     *
     * <p>Items are included only when they satisfy both constraints:
     *
     * <ul>
     *   <li>{@code textFilter} — display name or NAIF ID contains the string (null = no text filter)
     *   <li>{@code inViewConstraint} — NAIF ID is in the set (null = no in-view filter)
     * </ul>
     *
     * Group nodes are included (expanded) if any child passes both constraints. When a group name matches the text
     * filter, all its children are candidates for the in-view constraint only.
     */
    private TreeItem<BodyTreeEntry> buildFilteredRoot(String textFilter, Set<Integer> inViewConstraint) {
        TreeItem<BodyTreeEntry> filteredRoot = new TreeItem<>(masterRoot.getValue());
        filteredRoot.setExpanded(true);

        for (TreeItem<BodyTreeEntry> topItem : masterRoot.getChildren()) {
            if (topItem.getChildren().isEmpty()) {
                // Leaf node (Sun, spacecraft, etc.)
                boolean textOk = textFilter == null || matchesFilter(topItem.getValue(), textFilter);
                boolean inViewOk = inViewConstraint == null
                        || inViewConstraint.contains(topItem.getValue().naifId());
                if (textOk && inViewOk) {
                    filteredRoot.getChildren().add(new TreeItem<>(topItem.getValue()));
                }
            } else {
                // Group node — include expanded if any child passes all constraints
                boolean groupMatchesText = textFilter == null || matchesFilter(topItem.getValue(), textFilter);
                List<TreeItem<BodyTreeEntry>> matchingChildren = new ArrayList<>();
                for (TreeItem<BodyTreeEntry> child : topItem.getChildren()) {
                    boolean childTextOk = groupMatchesText || matchesFilter(child.getValue(), textFilter);
                    boolean childInViewOk = inViewConstraint == null
                            || inViewConstraint.contains(child.getValue().naifId());
                    if (childTextOk && childInViewOk) {
                        matchingChildren.add(new TreeItem<>(child.getValue()));
                    }
                }
                if (!matchingChildren.isEmpty()) {
                    TreeItem<BodyTreeEntry> groupCopy = new TreeItem<>(topItem.getValue());
                    groupCopy.setExpanded(true);
                    groupCopy.getChildren().addAll(matchingChildren);
                    filteredRoot.getChildren().add(groupCopy);
                }
            }
        }
        return filteredRoot;
    }

    private static boolean matchesFilter(BodyTreeEntry entry, String filter) {
        if (entry == null) return false;
        if (entry.displayName().toLowerCase().contains(filter)) return true;
        if (entry.naifId() != -1 && String.valueOf(entry.naifId()).contains(filter)) return true;
        return false;
    }

    /** Populate the body tree from the current ephemeris. */
    void populateBodyTree() {
        TreeItem<BodyTreeEntry> root = new TreeItem<>(new BodyTreeEntry("Bodies", -1));
        root.setExpanded(true);

        try {
            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
            SpiceBundle bundle = eph.getSpiceBundle();
            Set<EphemerisID> knownBodies = eph.getKnownBodies();

            // Collect spacecraft codes first so we can skip them in the knownBodies loop
            // (spacecraft appear in both getKnownBodies() and getSpacecraft(), causing duplicates).
            Set<Integer> spacecraftCodes = new java.util.HashSet<>();
            for (var sc : eph.getSpacecraft()) {
                spacecraftCodes.add(sc.code());
            }

            // Collect NAIF IDs and names — skip spacecraft (handled separately below)
            Map<Integer, String> bodyNames = new TreeMap<>();
            for (EphemerisID id : knownBodies) {
                Optional<Integer> codeOpt = bundle.getObjectCode(id);
                if (codeOpt.isEmpty()) continue;
                int code = codeOpt.get();
                if (spacecraftCodes.contains(code)) continue;
                String name = bundle.getObjectName(id)
                        .map(BodyLookupService::titleCase)
                        .orElse(id.getName());
                bodyNames.put(code, name);
            }

            // Group: planets/Sun at top level, satellites under their parent barycenter
            // Sun (10) is top-level. Planet bodies (x99) grouped under barycenter (x/100).
            // Satellites (x01-x98) grouped under barycenter.
            Map<Integer, List<int[]>> groups = new TreeMap<>(); // groupKey → list of [naifId]

            for (int code : bodyNames.keySet()) {
                if (code == 10) {
                    // Sun — standalone top-level
                    groups.computeIfAbsent(10, k -> new ArrayList<>()).add(new int[] {code});
                } else if (code >= 1 && code <= 9) {
                    // Barycenter — ensure group exists
                    groups.computeIfAbsent(code, k -> new ArrayList<>());
                } else if (code >= 100 && code <= 999) {
                    int bary = code / 100;
                    groups.computeIfAbsent(bary, k -> new ArrayList<>()).add(new int[] {code});
                } else {
                    // Anything else — top-level
                    groups.computeIfAbsent(code, k -> new ArrayList<>()).add(new int[] {code});
                }
            }

            // Add spacecraft using SpacecraftBlock.name() for the display name.
            // Fall back to titleCase of the SPICE internal name when the block name is blank.
            for (var sc : eph.getSpacecraft()) {
                int code = sc.code();
                String name;
                try {
                    var block = KEPPLRConfiguration.getInstance().spacecraftBlock(code);
                    name = (block != null
                                    && block.name() != null
                                    && !block.name().isBlank())
                            ? block.name()
                            : BodyLookupService.titleCase(sc.id().getName());
                } catch (Exception ex) {
                    name = BodyLookupService.titleCase(sc.id().getName());
                }
                bodyNames.put(code, name);
                groups.computeIfAbsent(code, k -> new ArrayList<>()).add(new int[] {code});
            }

            // Compute heliocentric distance for each group; skip groups with no position data.
            double et = bridge.currentEtProperty().get();
            Map<Integer, Double> groupDist = new java.util.HashMap<>();
            for (int k : groups.keySet()) {
                if (k == 10) {
                    groupDist.put(k, 0.0); // Sun always at distance 0
                    continue;
                }
                int representative = (k >= 1 && k <= 9) ? k * 100 + 99 : k;
                try {
                    VectorIJK pos = eph.getHeliocentricPositionJ2000(representative, et);
                    if (pos == null && k >= 1 && k <= 9) {
                        pos = eph.getHeliocentricPositionJ2000(k, et); // fall back to barycenter
                    }
                    if (pos != null) {
                        groupDist.put(k, pos.getLength());
                    }
                    // No entry added when pos == null → group is excluded from the list
                } catch (Exception ex) {
                    // Position unavailable → exclude from list
                }
            }

            List<Integer> topLevel = new ArrayList<>(groupDist.keySet());
            topLevel.sort(Comparator.comparingDouble(groupDist::get));

            for (int groupKey : topLevel) {
                List<int[]> members = groups.get(groupKey);

                if (groupKey == 10 || groupKey < 0 || groupKey > 999) {
                    // Sun or spacecraft — single item
                    for (int[] m : members) {
                        String name = bodyNames.getOrDefault(m[0], "NAIF " + m[0]);
                        root.getChildren().add(new TreeItem<>(new BodyTreeEntry(name, m[0])));
                    }
                } else if (groupKey >= 1 && groupKey <= 9) {
                    // Planet group
                    String baryName = bodyNames.getOrDefault(groupKey, "Barycenter " + groupKey);
                    TreeItem<BodyTreeEntry> groupItem = new TreeItem<>(new BodyTreeEntry(baryName, groupKey));

                    // Planet body (x99) first, then satellites by NAIF ID
                    members.sort(Comparator.comparingInt(a -> {
                        if (a[0] % 100 == 99) return -1;
                        return a[0];
                    }));

                    for (int[] m : members) {
                        String name = bodyNames.getOrDefault(m[0], "NAIF " + m[0]);
                        groupItem.getChildren().add(new TreeItem<>(new BodyTreeEntry(name, m[0])));
                    }
                    root.getChildren().add(groupItem);
                } else {
                    for (int[] m : members) {
                        String name = bodyNames.getOrDefault(m[0], "NAIF " + m[0]);
                        root.getChildren().add(new TreeItem<>(new BodyTreeEntry(name, m[0])));
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to populate body tree: {}", e.getMessage());
        }

        masterRoot = root;
        bodyTree.setRoot(masterRoot);
    }

    /**
     * Rebuild the Instruments menu from the current ephemeris after a configuration reload.
     *
     * <p>Also tells the bridge to rebuild its per-instrument FX property map so that CheckMenuItem checked-state
     * bindings remain live for the new instrument set.
     *
     * <p>Must be called on the JavaFX application thread.
     */
    void populateInstrumentsMenu() {
        bridge.reloadInstruments();

        instrumentsMenu.getItems().clear();

        List<Instrument> instruments;
        try {
            instruments = new ArrayList<>(
                    KEPPLRConfiguration.getInstance().getEphemeris().getInstruments());
            instruments.sort(Comparator.comparing(i -> i.id().getName()));
        } catch (Exception e) {
            instruments = List.of();
        }

        if (instruments.isEmpty()) {
            MenuItem noInstruments = new MenuItem("No instruments defined");
            noInstruments.setDisable(true);
            instrumentsMenu.getItems().add(noInstruments);
            return;
        }

        for (Instrument instrument : instruments) {
            String name = instrument.id().getName();
            int code = instrument.code();
            CheckMenuItem item = new CheckMenuItem(name);
            item.setSelected(false);

            javafx.beans.property.ReadOnlyBooleanProperty bridgeProp = bridge.frustumVisibleProperty(code);
            if (bridgeProp != null) {
                bridgeProp.addListener((obs, oldVal, newVal) -> item.setSelected(newVal));
            }

            item.setOnAction(e -> commands.setFrustumVisible(code, item.isSelected()));
            instrumentsMenu.getItems().add(item);
        }
    }

    // ── Script Output Panel ────────────────────────────────────────────────

    private SplitPane buildScriptOutputPanel() {
        Label header = boldLabel("Script Console");
        header.setPadding(new Insets(4, 10, 2, 10));

        consoleInput = new TextArea();
        consoleInput.setPromptText("Enter Groovy commands... (Enter to run, Shift+Enter for newline)");
        consoleInput.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        consoleInput.setPrefRowCount(4);
        consoleInput.setWrapText(true);
        VBox.setVgrow(consoleInput, Priority.ALWAYS);
        // Enter runs the console; Shift+Enter inserts a newline
        consoleInput.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                e.consume();
                if (e.isShiftDown()) {
                    consoleInput.insertText(consoleInput.getCaretPosition(), "\n");
                } else {
                    evaluateConsoleInput();
                }
            }
        });

        Button runButton = new Button("Run");
        runButton.setOnAction(e -> evaluateConsoleInput());
        runButton.setStyle("-fx-font-size: 10px;");

        Button clearButton = new Button("Clear");
        clearButton.setOnAction(e -> consoleInput.clear());
        clearButton.setStyle("-fx-font-size: 10px;");

        HBox buttonBar = new HBox(6, runButton, clearButton);
        buttonBar.setPadding(new Insets(0, 10, 0, 10));

        VBox inputPane = new VBox(4, header, consoleInput, buttonBar);

        scriptOutputArea = new TextArea();
        scriptOutputArea.setEditable(false);
        scriptOutputArea.setWrapText(true);
        scriptOutputArea.setStyle("-fx-font-family: monospace; -fx-font-size: 10px;");

        SplitPane innerSplit = new SplitPane(inputPane, scriptOutputArea);
        innerSplit.setOrientation(Orientation.VERTICAL);
        innerSplit.setDividerPositions(0.35);
        return innerSplit;
    }

    private void evaluateConsoleInput() {
        String code = consoleInput.getText().trim();
        if (code.isEmpty()) return;

        if (scriptRunner != null && scriptRunner.isRunning()) {
            scriptOutputQueue.add("⚠ Cannot evaluate: a script is running");
            return;
        }

        Thread ct = consoleThread;
        if (ct != null && ct.isAlive()) {
            scriptOutputQueue.add("⚠ Previous console command still running");
            return;
        }

        // Echo the input (abbreviated if multi-line)
        String[] lines = code.split("\n");
        if (lines.length == 1) {
            scriptOutputQueue.add("› " + code);
        } else {
            scriptOutputQueue.add("› " + lines[0] + " ... (" + lines.length + " lines)");
        }

        // Wrap in kepplr.with { ... } so the "kepplr." prefix is optional
        String wrappedCode = "kepplr.with {\n" + code + "\n}";

        Thread thread = new Thread(
                () -> {
                    try {
                        ScriptEngineManager manager = new ScriptEngineManager();
                        ScriptEngine engine = manager.getEngineByName("groovy");
                        if (engine == null) {
                            scriptOutputQueue.add("ERROR: Groovy engine not available");
                            return;
                        }

                        StringWriter outputWriter = new StringWriter();
                        engine.getContext().setWriter(outputWriter);
                        engine.getContext().setErrorWriter(outputWriter);

                        KepplrScript api = new KepplrScript(commands, bridge.getState());
                        javax.script.Bindings bindings = engine.createBindings();
                        bindings.put("kepplr", api);
                        bindings.put("VectorTypes", VectorTypes.class);
                        bindings.put("CameraFrame", CameraFrame.class);
                        bindings.put("RenderQuality", RenderQuality.class);

                        Object result = engine.eval(wrappedCode, bindings);

                        // Flush any print output from the script
                        String printed = outputWriter.toString();
                        if (!printed.isEmpty()) {
                            for (String line : printed.split("\n")) {
                                scriptOutputQueue.add(line);
                            }
                        }

                        if (result != null) {
                            scriptOutputQueue.add("= " + result);
                        }
                        scriptOutputQueue.add("✓ Done");
                    } catch (Exception ex) {
                        Throwable cause = ex;
                        while (cause.getCause() != null && cause.getCause() != cause) {
                            cause = cause.getCause();
                        }
                        String msg = cause.getMessage();
                        scriptOutputQueue.add(
                                "✗ " + (msg != null ? msg : cause.getClass().getSimpleName()));
                    }
                },
                "kepplr-console");
        thread.setDaemon(true);
        consoleThread = thread;
        thread.start();
    }

    private void drainScriptOutput() {
        String line;
        boolean changed = false;
        while ((line = scriptOutputQueue.poll()) != null) {
            if (scriptOutputLineCount > 0) {
                scriptOutputArea.appendText("\n");
            }
            scriptOutputArea.appendText(line);
            scriptOutputLineCount++;
            changed = true;

            // Trim old lines when the buffer grows too large
            if (scriptOutputLineCount > SCRIPT_OUTPUT_MAX_LINES) {
                String text = scriptOutputArea.getText();
                int firstNewline = text.indexOf('\n');
                if (firstNewline >= 0) {
                    scriptOutputArea.setText(text.substring(firstNewline + 1));
                    scriptOutputLineCount--;
                }
            }
        }
        if (changed) {
            scriptOutputArea.positionCaret(scriptOutputArea.getLength());
        }
    }

    /**
     * Re-enable capture-related menu items when the capture thread finishes. Called on the FX thread by the
     * AnimationTimer, avoiding Platform.runLater() per CLAUDE.md Rule 2.
     */
    private void checkCaptureThreadDone() {
        Thread ct = captureSequenceThread;
        if (ct != null && !ct.isAlive()) {
            captureSequenceThread = null;
            if (captureSeqItem != null) captureSeqItem.setDisable(false);
            if (saveScreenshotItem != null) saveScreenshotItem.setDisable(false);
        }
    }

    /**
     * Called from the AnimationTimer (FX thread) to refresh the body tree and instruments menu after a configuration
     * reload. The flag is set by {@link #signalConfigRefresh()} from any thread.
     */
    private void drainConfigRefresh() {
        if (!pendingConfigRefresh) return;
        pendingConfigRefresh = false;
        populateBodyTree();
        populateInstrumentsMenu();
        if (labelsCheckItem != null) {
            applyLabelVisibility(labelsCheckItem.isSelected());
        }
    }

    // ── Menu Bar ─────────────────────────────────────────────────────────────

    private MenuBar buildMenuBar() {
        Menu fileMenu = buildFileMenu();
        Menu viewMenu = buildViewMenu();
        Menu timeMenu = buildTimeMenu();
        Menu overlaysMenu = buildOverlaysMenu();
        instrumentsMenu = buildInstrumentsMenu();
        Menu windowMenu = buildWindowMenu();

        MenuBar bar = new MenuBar(fileMenu, viewMenu, timeMenu, overlaysMenu, instrumentsMenu, windowMenu);
        bar.setUseSystemMenuBar(false);
        return bar;
    }

    private Menu buildFileMenu() {
        CustomMenuItem loadConfig = tipItem("Load Configuration...", "Load a KEPPLR configuration properties file");
        loadConfig.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Load KEPPLR Configuration");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All Files", "*.*"));
            File file = chooser.showOpenDialog(stage);
            if (file != null) {
                // Run on a background thread so the FX thread is never blocked by the
                // latch inside DefaultSimulationCommands.loadConfiguration().  The
                // postReloadCallback wired by KepplrApp will call signalConfigRefresh()
                // when the scene rebuild completes, and the AnimationTimer will then call
                // populateBodyTree() / populateInstrumentsMenu() on the next FX frame.
                String path = file.getAbsolutePath();
                Thread loadThread = new Thread(() -> commands.loadConfiguration(path), "config-load");
                loadThread.setDaemon(true);
                loadThread.start();
            }
        });

        // ── Run Script... ────────────────────────────────────────────────
        CustomMenuItem runScript = tipItem("Run Script...", "Execute a Groovy script file");
        runScript.setOnAction(e -> {
            if (scriptRunner == null) return;

            // Block if a capture sequence is running
            Thread ct = captureSequenceThread;
            if (ct != null && ct.isAlive()) {
                Alert warn = new Alert(
                        Alert.AlertType.WARNING,
                        "A capture sequence is in progress. Wait for it to complete before running a script.",
                        ButtonType.OK);
                warn.setTitle("Capture Running");
                warn.setHeaderText(null);
                warn.showAndWait();
                return;
            }

            // If a script is already running, confirm before interrupting
            if (scriptRunner.isRunning()) {
                Alert confirm = new Alert(
                        Alert.AlertType.CONFIRMATION,
                        "A script is already running. Stop it and run a new one?",
                        ButtonType.OK,
                        ButtonType.CANCEL);
                confirm.setTitle("Script Running");
                confirm.setHeaderText(null);
                Optional<ButtonType> result = confirm.showAndWait();
                if (result.isEmpty() || result.get() != ButtonType.OK) return;
            }

            FileChooser chooser = new FileChooser();
            chooser.setTitle("Run Groovy Script");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Groovy scripts", "*.groovy"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All Files", "*.*"));
            File file = chooser.showOpenDialog(stage);
            if (file != null) {
                scriptRunner.runScript(file.toPath());
            }
        });

        // ── Start/Stop Recording ─────────────────────────────────────────
        CheckMenuItem recordToggle = new CheckMenuItem("Record Session");
        recordToggle.setOnAction(e -> {
            if (commandRecorder == null) return;

            if (recordToggle.isSelected()) {
                commandRecorder.startRecording();
                logger.info("Command recording started");
            } else {
                commandRecorder.stopRecording();
                String script = commandRecorder.getScript();
                logger.info("Command recording stopped");

                FileChooser chooser = new FileChooser();
                chooser.setTitle("Save Recorded Script");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Groovy scripts", "*.groovy"));
                // Use no extension in the initial name; the OS may auto-append from the filter,
                // and we add it ourselves below only when absent — avoids "recorded.groovy.groovy".
                chooser.setInitialFileName("recorded");
                File file = chooser.showSaveDialog(stage);
                if (file != null) {
                    if (!file.getName().endsWith(".groovy")) {
                        file = new File(file.getParentFile(), file.getName() + ".groovy");
                    }
                    try {
                        Files.writeString(file.toPath(), script);
                        logger.info("Recorded script saved to {}", file.getAbsolutePath());
                    } catch (IOException ex) {
                        logger.error("Failed to save recorded script: {}", ex.getMessage());
                    }
                }
            }
        });

        // ── Save Screenshot ──────────────────────────────────────────────
        CustomMenuItem saveScreenshot =
                tipItem("Save Screenshot...", "Capture the current JME framebuffer to a PNG file");
        saveScreenshot.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save Screenshot");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG images", "*.png"));
            chooser.setInitialFileName("screenshot.png");
            File file = chooser.showSaveDialog(stage);
            if (file != null) {
                String path = file.getAbsolutePath();
                if (!path.toLowerCase().endsWith(".png")) {
                    path = path + ".png";
                }
                commands.saveScreenshot(path);
            }
        });

        // ── Capture Sequence... ─────────────────────────────────────────
        CustomMenuItem captureSeq = tipItem("Capture Sequence...", "Capture a sequence of frames as PNG files");
        captureSeq.setOnAction(e -> showCaptureSequenceDialog(captureSeq, saveScreenshot));

        // ── Quit ─────────────────────────────────────────────────────────
        CustomMenuItem quit = tipItem("Quit", "Exit KEPPLR");
        Runnable doQuit = () -> {
            if (jmeShutdown != null) jmeShutdown.run();
        };
        quit.setOnAction(e -> doQuit.run());

        // Store references for mutual exclusion
        this.captureSeqItem = captureSeq;
        this.saveScreenshotItem = saveScreenshot;

        CustomMenuItem showLog = tipItem("Show Log", "Show the application log window");
        showLog.setOnAction(e -> logWindow.show());

        CustomMenuItem copyState = tipItem("Copy State", "Copy the current simulation state to the clipboard");
        copyState.setOnAction(e -> {
            String stateString = commands.getStateString();
            ClipboardContent content = new ClipboardContent();
            content.putString(stateString);
            Clipboard.getSystemClipboard().setContent(content);
            logger.info("State string copied to clipboard ({} chars)", stateString.length());
        });

        CustomMenuItem pasteState = tipItem("Paste State", "Restore simulation state from the clipboard");
        pasteState.setOnAction(e -> {
            String text = Clipboard.getSystemClipboard().getString();
            if (text == null || text.isBlank()) {
                Alert warn = new Alert(Alert.AlertType.WARNING, "Clipboard is empty.", ButtonType.OK);
                warn.setTitle("Paste State");
                warn.setHeaderText(null);
                warn.showAndWait();
                return;
            }
            try {
                commands.setStateString(text.strip());
                logger.info("State restored from clipboard");
            } catch (IllegalArgumentException ex) {
                Alert warn =
                        new Alert(Alert.AlertType.WARNING, "Invalid state string: " + ex.getMessage(), ButtonType.OK);
                warn.setTitle("Paste State");
                warn.setHeaderText(null);
                warn.showAndWait();
            }
        });

        return new Menu(
                "File",
                null,
                loadConfig,
                new SeparatorMenuItem(),
                runScript,
                recordToggle,
                new SeparatorMenuItem(),
                saveScreenshot,
                captureSeq,
                new SeparatorMenuItem(),
                copyState,
                pasteState,
                new SeparatorMenuItem(),
                showLog,
                new SeparatorMenuItem(),
                quit);
    }

    /**
     * Show the Capture Sequence dialog. Validates inputs, opens a directory chooser, and launches the capture on a
     * dedicated daemon thread.
     */
    private void showCaptureSequenceDialog(CustomMenuItem captureItem, CustomMenuItem screenshotItem) {
        // Block if a script is running
        if (scriptRunner != null && scriptRunner.isRunning()) {
            Alert warn = new Alert(
                    Alert.AlertType.WARNING,
                    "A script is currently running. Stop it before starting a capture sequence.",
                    ButtonType.OK);
            warn.setTitle("Script Running");
            warn.setHeaderText(null);
            warn.showAndWait();
            return;
        }

        // Block if a capture is already running
        Thread ct = captureSequenceThread;
        if (ct != null && ct.isAlive()) {
            Alert warn =
                    new Alert(Alert.AlertType.WARNING, "A capture sequence is already in progress.", ButtonType.OK);
            warn.setTitle("Capture Running");
            warn.setHeaderText(null);
            warn.showAndWait();
            return;
        }

        // Build dialog
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        TextField utcField = new TextField(bridge.utcTimeTextProperty().get());
        TextField stepField = new TextField();
        stepField.setPromptText("ET step (seconds)");
        TextField countField = new TextField();
        countField.setPromptText("Number of frames");

        grid.add(new Label("Start UTC:"), 0, 0);
        grid.add(utcField, 1, 0);
        grid.add(new Label("Time step (s):"), 0, 1);
        grid.add(stepField, 1, 1);
        grid.add(new Label("Frame count:"), 0, 2);
        grid.add(countField, 1, 2);

        Alert dialog = new Alert(Alert.AlertType.NONE);
        dialog.setTitle("Capture Sequence");
        dialog.setHeaderText("Configure the capture sequence");
        dialog.getDialogPane().setContent(grid);
        dialog.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        // Validate inputs
        double startET;
        try {
            startET = KEPPLRConfiguration.getInstance()
                    .getTimeConversion()
                    .utcStringToTDB(utcField.getText().trim());
        } catch (Exception ex) {
            scriptOutputQueue.add("✗ Invalid Start UTC: " + ex.getMessage());
            return;
        }

        double etStep;
        try {
            etStep = Double.parseDouble(stepField.getText().trim());
            if (etStep == 0) throw new NumberFormatException("must be non-zero");
        } catch (NumberFormatException ex) {
            scriptOutputQueue.add("✗ Invalid time step: " + ex.getMessage());
            return;
        }

        int frameCount;
        try {
            frameCount = Integer.parseInt(countField.getText().trim());
            if (frameCount <= 0) throw new NumberFormatException("must be positive");
        } catch (NumberFormatException ex) {
            scriptOutputQueue.add("✗ Invalid frame count: " + ex.getMessage());
            return;
        }

        // Directory chooser
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Select Output Directory");
        File dir = dirChooser.showDialog(stage);
        if (dir == null) return;

        String outputDir = dir.getAbsolutePath();
        int fc = frameCount;
        double step = etStep;
        double start = startET;

        // Disable menu items during capture
        captureItem.setDisable(true);
        screenshotItem.setDisable(true);

        scriptOutputQueue.add("▶ Capture sequence: " + fc + " frames to " + outputDir);

        Thread thread = new Thread(
                () -> {
                    try {
                        CaptureService.captureSequence(outputDir, start, fc, step, commands, bridge.getState());
                        scriptOutputQueue.add("✓ Captured " + fc + " frames to " + outputDir);
                    } catch (Exception ex) {
                        scriptOutputQueue.add("✗ Capture error: " + ex.getMessage());
                    }
                    // Menu items are re-enabled by checkCaptureThreadDone() on the FX thread
                },
                "kepplr-capture-sequence");
        thread.setDaemon(true);
        captureSequenceThread = thread;
        thread.start();
    }

    private Menu buildViewMenu() {
        // Camera Frame submenu — RadioButtons inside CustomMenuItems to support tooltips
        ToggleGroup frameGroup = new ToggleGroup();

        RadioButton inertialBtn = menuRadioButton("Inertial");
        inertialBtn.setToggleGroup(frameGroup);
        Tooltip.install(inertialBtn, new Tooltip("Camera orientation fixed in J2000 inertial frame"));
        CustomMenuItem inertialItem = new CustomMenuItem(inertialBtn);
        inertialItem.setHideOnClick(false);
        inertialItem.setOnAction(e -> commands.setCameraFrame(CameraFrame.INERTIAL));

        RadioButton bodyFixedBtn = menuRadioButton("Body-Fixed");
        bodyFixedBtn.setToggleGroup(frameGroup);
        Tooltip.install(bodyFixedBtn, new Tooltip("Camera co-rotates with the focused body"));
        CustomMenuItem bodyFixedItem = new CustomMenuItem(bodyFixedBtn);
        bodyFixedItem.setHideOnClick(false);
        bodyFixedItem.setOnAction(e -> commands.setCameraFrame(CameraFrame.BODY_FIXED));

        RadioButton synodicBtn = menuRadioButton("Synodic");
        synodicBtn.setToggleGroup(frameGroup);
        Tooltip.install(synodicBtn, new Tooltip("Camera maintains focus-to-selected body geometry"));
        CustomMenuItem synodicItem = new CustomMenuItem(synodicBtn);
        synodicItem.setHideOnClick(false);
        synodicItem.setOnAction(e -> commands.setCameraFrame(CameraFrame.SYNODIC));

        bridge.activeCameraFrameObjectProperty().addListener((obs, old, val) -> {
            inertialBtn.setSelected(val == CameraFrame.INERTIAL);
            bodyFixedBtn.setSelected(val == CameraFrame.BODY_FIXED);
            synodicBtn.setSelected(val == CameraFrame.SYNODIC);
        });
        CameraFrame initial = bridge.activeCameraFrameObjectProperty().get();
        inertialBtn.setSelected(initial == CameraFrame.INERTIAL);
        bodyFixedBtn.setSelected(initial == CameraFrame.BODY_FIXED);
        synodicBtn.setSelected(initial == CameraFrame.SYNODIC);

        Menu frameSubMenu = new Menu("Camera Frame", null, inertialItem, bodyFixedItem, synodicItem);

        CustomMenuItem setFovItem = tipItem("Set FOV…", "Set the camera field of view in degrees");
        setFovItem.setOnAction(e -> {
            double currentFov = bridge.fovDegProperty().get();
            new SetFovDialog(commands, currentFov).showAndWait();
        });

        return new Menu("View", null, frameSubMenu, new SeparatorMenuItem(), setFovItem);
    }

    private Menu buildTimeMenu() {
        // Pause/Resume — label text is bound so the display updates live
        Label pauseLabel = new Label();
        pauseLabel
                .textProperty()
                .bind(Bindings.when(bridge.pausedTextProperty().isEqualTo("Paused"))
                        .then("Resume")
                        .otherwise("Pause"));
        pauseLabel.setMaxWidth(Double.MAX_VALUE);
        Tooltip.install(pauseLabel, new Tooltip("Pause or resume simulation time"));
        CustomMenuItem pauseItem = new CustomMenuItem(pauseLabel);
        pauseItem.setHideOnClick(true);
        pauseItem.setOnAction(
                e -> commands.setPaused(bridge.pausedTextProperty().get().equals("Running")));

        CustomMenuItem setTimeItem = tipItem("Set Time...", "Set the simulation time to a specific UTC date/time");
        setTimeItem.setOnAction(
                e -> new SetTimeDialog(commands, bridge.utcTimeTextProperty().get()).showAndWait());

        CustomMenuItem setRateItem =
                tipItem("Set Time Rate...", "Set the simulation time rate in seconds per wall second");
        setRateItem.setOnAction(e ->
                new SetTimeRateDialog(commands, bridge.timeRateTextProperty().get()).showAndWait());

        return new Menu("Time", null, pauseItem, new SeparatorMenuItem(), setTimeItem, setRateItem);
    }

    /** Enable or disable labels for all currently known bodies and spacecraft. */
    private void applyLabelVisibility(boolean show) {
        try {
            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
            for (EphemerisID id : eph.getKnownBodies()) {
                eph.getSpiceBundle().getObjectCode(id).ifPresent(code -> {
                    // When turning on, skip barycenters (1–9) except Pluto barycenter (9)
                    if (show && code >= 1 && code <= 9 && code != KepplrConstants.PLUTO_BARYCENTER_NAIF_ID) return;
                    commands.setLabelVisible(code, show);
                });
            }
            for (var sc : eph.getSpacecraft()) {
                commands.setLabelVisible(sc.code(), show);
            }
        } catch (Exception ex) {
            logger.warn("Failed to apply label visibility: {}", ex.getMessage(), ex);
        }
    }

    private Menu buildOverlaysMenu() {
        // ── Labels ────────────────────────────────────────────────────────────
        labelsCheckItem = new CheckMenuItem("Labels");
        labelsCheckItem.setSelected(false);
        labelsCheckItem.setOnAction(e -> applyLabelVisibility(labelsCheckItem.isSelected()));

        // ── HUD toggles ──────────────────────────────────────────────────────
        CheckMenuItem hudInfoItem = new CheckMenuItem("HUD / Info");
        hudInfoItem.setSelected(bridge.hudInfoVisibleProperty().get());
        bridge.hudInfoVisibleProperty().addListener((obs, old, val) -> hudInfoItem.setSelected(val));
        hudInfoItem.setOnAction(e -> commands.setHudInfoVisible(hudInfoItem.isSelected()));

        CheckMenuItem showTimeItem = new CheckMenuItem("Show Time");
        showTimeItem.setSelected(bridge.hudTimeVisibleProperty().get());
        bridge.hudTimeVisibleProperty().addListener((obs, old, val) -> showTimeItem.setSelected(val));
        showTimeItem.setOnAction(e -> commands.setHudTimeVisible(showTimeItem.isSelected()));

        // ── Trajectories (global toggle) ─────────────────────────────────────
        CheckMenuItem trajItem = new CheckMenuItem("Trajectories");
        trajItem.setSelected(false);
        trajItem.setOnAction(e -> {
            boolean show = trajItem.isSelected();
            try {
                KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
                for (EphemerisID id : eph.getKnownBodies()) {
                    eph.getSpiceBundle().getObjectCode(id).ifPresent(code -> {
                        // Skip barycenters (1–9) except Pluto barycenter (9)
                        if (code >= 1 && code <= 9 && code != KepplrConstants.PLUTO_BARYCENTER_NAIF_ID) return;
                        commands.setTrailVisible(code, show);
                    });
                }
                for (var sc : eph.getSpacecraft()) {
                    commands.setTrailVisible(sc.code(), show);
                }
            } catch (Exception ex) {
                logger.debug("Failed to toggle trajectories: {}", ex.getMessage());
            }
        });

        // ── Current Focus submenu ─────────────────────────────────────────────
        CheckMenuItem sunDirItem = new CheckMenuItem("Sun Direction");
        sunDirItem.setOnAction(e -> {
            int focId = bridge.focusedBodyIdProperty().get();
            if (focId != -1)
                commands.setVectorVisible(
                        focId, VectorTypes.towardBody(KepplrConstants.SUN_NAIF_ID), sunDirItem.isSelected());
        });

        CheckMenuItem earthDirItem = new CheckMenuItem("Earth Direction");
        earthDirItem.setOnAction(e -> {
            int focId = bridge.focusedBodyIdProperty().get();
            if (focId != -1) commands.setVectorVisible(focId, VectorTypes.towardBody(399), earthDirItem.isSelected());
        });

        CheckMenuItem velocityItem = new CheckMenuItem("Velocity Direction");
        velocityItem.setOnAction(e -> {
            int focId = bridge.focusedBodyIdProperty().get();
            if (focId != -1) commands.setVectorVisible(focId, VectorTypes.velocity(), velocityItem.isSelected());
        });

        CheckMenuItem targetTrailItem = new CheckMenuItem("Trajectory");
        targetTrailItem.setOnAction(e -> {
            int focId = bridge.focusedBodyIdProperty().get();
            if (focId != -1) commands.setTrailVisible(focId, targetTrailItem.isSelected());
        });

        CheckMenuItem axesItem = new CheckMenuItem("Axes");
        axesItem.setOnAction(e -> {
            int focId = bridge.focusedBodyIdProperty().get();
            if (focId != -1) {
                boolean show = axesItem.isSelected();
                commands.setVectorVisible(focId, VectorTypes.bodyAxisX(), show);
                commands.setVectorVisible(focId, VectorTypes.bodyAxisY(), show);
                commands.setVectorVisible(focId, VectorTypes.bodyAxisZ(), show);
            }
        });

        // Track state-property listeners so we can unbind them when focus changes
        Runnable[] unbindPrev = {() -> {}};

        // Bind menu checkmarks to the state properties of the given body so that
        // changes made from other sources (e.g. context menu) are reflected here.
        Runnable bindToFocused = () -> {
            unbindPrev[0].run();
            int id = bridge.focusedBodyIdProperty().get();
            if (id == -1) {
                unbindPrev[0] = () -> {};
                return;
            }
            SimulationState st = bridge.getState();

            ChangeListener<Boolean> sunL = (o, ov, nv) -> sunDirItem.setSelected(nv);
            ChangeListener<Boolean> earthL = (o, ov, nv) -> earthDirItem.setSelected(nv);
            ChangeListener<Boolean> velL = (o, ov, nv) -> velocityItem.setSelected(nv);
            ChangeListener<Boolean> trailL = (o, ov, nv) -> targetTrailItem.setSelected(nv);
            ChangeListener<Boolean> axesL = (o, ov, nv) -> axesItem.setSelected(nv);

            ReadOnlyBooleanProperty sunP =
                    st.vectorVisibleProperty(id, VectorTypes.towardBody(KepplrConstants.SUN_NAIF_ID));
            ReadOnlyBooleanProperty earthP = st.vectorVisibleProperty(id, VectorTypes.towardBody(399));
            ReadOnlyBooleanProperty velP = st.vectorVisibleProperty(id, VectorTypes.velocity());
            ReadOnlyBooleanProperty trailP = st.trailVisibleProperty(id);
            ReadOnlyBooleanProperty axesP = st.vectorVisibleProperty(id, VectorTypes.bodyAxisX());

            sunP.addListener(sunL);
            earthP.addListener(earthL);
            velP.addListener(velL);
            trailP.addListener(trailL);
            axesP.addListener(axesL);

            // Sync initial state
            sunDirItem.setSelected(sunP.get());
            earthDirItem.setSelected(earthP.get());
            velocityItem.setSelected(velP.get());
            targetTrailItem.setSelected(trailP.get());
            axesItem.setSelected(axesP.get());

            unbindPrev[0] = () -> {
                sunP.removeListener(sunL);
                earthP.removeListener(earthL);
                velP.removeListener(velL);
                trailP.removeListener(trailL);
                axesP.removeListener(axesL);
            };
        };

        // Bind for the initial focus body (if any)
        bindToFocused.run();

        // When focus body changes, hide overlays on the old body and rebind to the new one
        bridge.focusedBodyIdProperty().addListener((obs, oldVal, newVal) -> {
            int oldId = oldVal.intValue();
            if (oldId != -1) {
                if (sunDirItem.isSelected())
                    commands.setVectorVisible(oldId, VectorTypes.towardBody(KepplrConstants.SUN_NAIF_ID), false);
                if (earthDirItem.isSelected()) commands.setVectorVisible(oldId, VectorTypes.towardBody(399), false);
                if (velocityItem.isSelected()) commands.setVectorVisible(oldId, VectorTypes.velocity(), false);
                if (targetTrailItem.isSelected()) commands.setTrailVisible(oldId, false);
                if (axesItem.isSelected()) {
                    commands.setVectorVisible(oldId, VectorTypes.bodyAxisX(), false);
                    commands.setVectorVisible(oldId, VectorTypes.bodyAxisY(), false);
                    commands.setVectorVisible(oldId, VectorTypes.bodyAxisZ(), false);
                }
            }
            bindToFocused.run();
        });

        Menu currentFocus = new Menu("Current Focus");
        currentFocus.getItems().addAll(sunDirItem, earthDirItem, velocityItem, targetTrailItem, axesItem);
        bridge.focusedBodyNameProperty().addListener((obs, old, val) -> {
            currentFocus.setText("—".equals(val) ? "Current Focus" : "Current Focus: " + val);
        });
        String initFocName = bridge.focusedBodyNameProperty().get();
        if (!"—".equals(initFocName)) {
            currentFocus.setText("Current Focus: " + initFocName);
        }

        return new Menu(
                "Overlays",
                null,
                labelsCheckItem,
                hudInfoItem,
                showTimeItem,
                new SeparatorMenuItem(),
                trajItem,
                new SeparatorMenuItem(),
                currentFocus);
    }

    private Menu buildInstrumentsMenu() {
        Menu menu = new Menu("Instruments");

        List<Instrument> instruments;
        try {
            instruments = new ArrayList<>(
                    KEPPLRConfiguration.getInstance().getEphemeris().getInstruments());
            instruments.sort(Comparator.comparing(i -> i.id().getName()));
        } catch (Exception e) {
            instruments = List.of();
        }

        if (instruments.isEmpty()) {
            MenuItem noInstruments = new MenuItem("No instruments defined");
            noInstruments.setDisable(true);
            menu.getItems().add(noInstruments);
            return menu;
        }

        for (Instrument instrument : instruments) {
            String name = instrument.id().getName();
            int code = instrument.code();
            CheckMenuItem item = new CheckMenuItem(name);
            item.setSelected(false);

            // Bind checked state to SimulationState via the bridge (Rule 2: no direct state access from UI).
            javafx.beans.property.ReadOnlyBooleanProperty bridgeProp = bridge.frustumVisibleProperty(code);
            if (bridgeProp != null) {
                bridgeProp.addListener((obs, oldVal, newVal) -> item.setSelected(newVal));
            }

            item.setOnAction(e -> commands.setFrustumVisible(code, item.isSelected()));
            menu.getItems().add(item);
        }

        return menu;
    }

    private Menu buildWindowMenu() {
        CustomMenuItem size720 = tipItem("1280 \u00d7 720", "Resize the render window to 1280\u00d7720 (HD)");
        size720.setOnAction(e -> resizeJmeWindow(1280, 720));

        CustomMenuItem size1024 = tipItem("1280 \u00d7 1024", "Resize the render window to 1280\u00d71024");
        size1024.setOnAction(e -> resizeJmeWindow(1280, 1024));

        CustomMenuItem size1080 = tipItem("1920 \u00d7 1080", "Resize the render window to 1920\u00d71080 (Full HD)");
        size1080.setOnAction(e -> resizeJmeWindow(1920, 1080));

        CustomMenuItem size1440 = tipItem("2560 \u00d7 1440", "Resize the render window to 2560\u00d71440 (QHD)");
        size1440.setOnAction(e -> resizeJmeWindow(2560, 1440));

        return new Menu("Window", null, size720, size1024, size1080, size1440);
    }

    private void resizeJmeWindow(int width, int height) {
        if (jmeResizeCallback != null) {
            jmeResizeCallback.accept(width, height);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Create a {@link CustomMenuItem} wrapping a {@link Label} with a tooltip.
     *
     * <p>Standard {@link MenuItem} has no {@code setTooltip()} — wrapping a {@code Label} inside a
     * {@code CustomMenuItem} is the JavaFX workaround that enables tooltip support on menu items.
     */
    private static CustomMenuItem tipItem(String text, String tooltip) {
        Label label = new Label(text);
        label.setMaxWidth(Double.MAX_VALUE);
        Tooltip.install(label, new Tooltip(tooltip));
        CustomMenuItem item = new CustomMenuItem(label);
        item.setHideOnClick(true);
        // Work around a JavaFX timing issue: CustomMenuItem.onAction sometimes does not fire
        // because the menu hides before the action event propagates to the item.
        // Two interaction patterns reach a menu item:
        //   1. Click (press+release on item): MOUSE_PRESSED fires on the label — handle it there.
        //   2. Drag-to-select (press on menu title, drag to item, release): only MOUSE_RELEASED
        //      fires on the label — handle it in the RELEASED filter when PRESSED did not fire.
        // The normal onAction path may also fire, but all current handlers are idempotent.
        boolean[] pressedHere = {false};
        label.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            pressedHere[0] = true;
            javafx.event.EventHandler<javafx.event.ActionEvent> handler = item.getOnAction();
            if (handler != null) {
                handler.handle(new javafx.event.ActionEvent(item, item));
            }
        });
        label.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
            if (!pressedHere[0]) {
                javafx.event.EventHandler<javafx.event.ActionEvent> handler = item.getOnAction();
                if (handler != null) {
                    handler.handle(new javafx.event.ActionEvent(item, item));
                }
            }
            pressedHere[0] = false;
        });
        return item;
    }

    /** Create a styled {@link RadioButton} suitable for use inside a {@link CustomMenuItem}. */
    private static RadioButton menuRadioButton(String text) {
        RadioButton rb = new RadioButton(text);
        rb.setMaxWidth(Double.MAX_VALUE);
        rb.setStyle("-fx-text-fill: -fx-text-base-color;");
        return rb;
    }

    private static int addStatusRow(
            GridPane grid, int row, String labelText, javafx.beans.property.ReadOnlyStringProperty valueProp) {
        Label name = boldLabel(labelText);
        Label value = monoLabel();
        value.textProperty().bind(valueProp);
        grid.add(name, 0, row);
        grid.add(value, 1, row);
        return row + 1;
    }

    private static Label boldLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-family: monospace; -fx-font-weight: bold; -fx-font-size: 11px;");
        return label;
    }

    private static Label monoLabel() {
        Label label = new Label("—");
        label.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        return label;
    }

    private static Label dimLabel() {
        Label label = new Label("—");
        label.setStyle("-fx-font-family: monospace; -fx-font-size: 10px; -fx-text-fill: #888888;");
        return label;
    }

    private static Button smallButton(String text) {
        Button btn = new Button(text);
        btn.setStyle("-fx-font-size: 10px; -fx-padding: 1 6 1 6;");
        return btn;
    }

    // ── Body tree entry record ───────────────────────────────────────────────

    /** Data class for body tree items. */
    record BodyTreeEntry(String displayName, int naifId) {
        @Override
        public String toString() {
            return displayName;
        }
    }
}
