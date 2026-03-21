package kepplr.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import kepplr.camera.CameraFrame;
import kepplr.commands.SimulationCommands;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.BodyLookupService;
import kepplr.ephemeris.Instrument;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.ephemeris.spice.SpiceBundle;
import kepplr.render.vector.VectorTypes;
import kepplr.scripting.CommandRecorder;
import kepplr.scripting.ScriptRunner;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    private static final double WINDOW_WIDTH = 380.0;
    private static final double WINDOW_HEIGHT = 720.0;

    private final SimulationStateFxBridge bridge;
    private final SimulationCommands commands;
    private Runnable jmeShutdown;
    private BiConsumer<Integer, Integer> jmeResizeCallback;
    private Runnable configReloadCallback;
    private ScriptRunner scriptRunner;
    private CommandRecorder commandRecorder;
    private Stage stage;
    private TreeView<BodyTreeEntry> bodyTree;
    private Menu instrumentsMenu;

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
        VBox transitionBar = buildTransitionBar();
        VBox bodyListSection = buildBodyListSection();

        VBox root = new VBox(6, menuBar, bodyReadout, statusSection, transitionBar, bodyListSection);
        VBox.setVgrow(bodyListSection, Priority.ALWAYS);

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

        stage.show();
        stage.toFront();
        bridge.startPolling();
    }

    /** Close the window programmatically (called from JME destroy() hook). */
    public void close() {
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

    // ── Body Readout (Selected, Focused, Targeted with action buttons) ────────

    private VBox buildBodyReadout() {
        GridPane grid = new GridPane();
        grid.setPadding(new Insets(6, 10, 6, 10));
        grid.setHgap(8);
        grid.setVgap(4);

        ColumnConstraints labelCol = new ColumnConstraints(70);
        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints buttonCol = new ColumnConstraints();
        grid.getColumnConstraints().addAll(labelCol, valueCol, buttonCol);

        // Row 0: Selected — with Focus, Target, Clear buttons
        Label selLabel = boldLabel("Selected:");
        Label selValue = monoLabel();
        selValue.textProperty().bind(bridge.selectedBodyNameProperty());

        Button focusBtn = smallButton("Focus");
        focusBtn.setOnAction(e -> {
            int id = bridge.selectedBodyIdProperty().get();
            if (id != -1) commands.focusBody(id);
        });
        Button targetBtn = smallButton("Target");
        targetBtn.setOnAction(e -> {
            int id = bridge.selectedBodyIdProperty().get();
            if (id != -1) commands.targetBody(id);
        });
        Button clearSelBtn = smallButton("Clear");
        clearSelBtn.setOnAction(e -> commands.selectBody(-1));
        HBox selButtons = new HBox(4, focusBtn, targetBtn, clearSelBtn);
        selButtons.visibleProperty().bind(bridge.selectedBodyActiveProperty());
        selButtons.managedProperty().bind(bridge.selectedBodyActiveProperty());

        grid.add(selLabel, 0, 0);
        grid.add(selValue, 1, 0);
        grid.add(selButtons, 2, 0);

        // Row 1: Focused
        Label focLabel = boldLabel("Focused:");
        Label focValue = monoLabel();
        focValue.textProperty().bind(bridge.focusedBodyNameProperty());
        grid.add(focLabel, 0, 1);
        grid.add(focValue, 1, 1);

        // Row 2: Targeted
        Label tgtLabel = boldLabel("Targeted:");
        Label tgtValue = monoLabel();
        tgtValue.textProperty().bind(bridge.targetedBodyNameProperty());
        grid.add(tgtLabel, 0, 2);
        grid.add(tgtValue, 1, 2);

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
        row = addStatusRow(grid, row, "Cam pos:", bridge.cameraPositionTextProperty());
        addStatusRow(grid, row, "BF pos:", bridge.cameraBodyFixedTextProperty());

        return new VBox(grid);
    }

    // ── Transition Progress Bar ──────────────────────────────────────────────

    private VBox buildTransitionBar() {
        ProgressBar progressBar = new ProgressBar();
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.progressProperty().bind(bridge.transitionProgressProperty());

        VBox box = new VBox(progressBar);
        box.setPadding(new Insets(0, 10, 0, 10));
        box.visibleProperty().bind(bridge.transitionActiveProperty());
        box.managedProperty().bind(bridge.transitionActiveProperty());
        return box;
    }

    // ── Body List TreeView ───────────────────────────────────────────────────

    private VBox buildBodyListSection() {
        Label header = boldLabel("Bodies");
        header.setPadding(new Insets(4, 10, 2, 10));

        TextField searchField = new TextField();
        searchField.setPromptText("Search body name or NAIF ID...");
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

        bodyTree = new TreeView<>();
        bodyTree.setShowRoot(false);
        populateBodyTree();
        VBox.setVgrow(bodyTree, Priority.ALWAYS);

        // Single click → select; double-click → focus
        bodyTree.setOnMouseClicked(evt -> {
            TreeItem<BodyTreeEntry> item = bodyTree.getSelectionModel().getSelectedItem();
            if (item == null || item.getValue() == null || item.getValue().naifId == -1) return;
            int naifId = item.getValue().naifId;
            if (evt.getClickCount() == 2) {
                commands.focusBody(naifId);
            } else if (evt.getClickCount() == 1) {
                commands.selectBody(naifId);
            }
        });

        VBox searchBox = new VBox(searchField);
        searchBox.setPadding(new Insets(0, 10, 4, 10));

        VBox section = new VBox(4, header, searchBox, bodyTree);
        VBox.setVgrow(section, Priority.ALWAYS);
        return section;
    }

    /** Populate the body tree from the current ephemeris. */
    void populateBodyTree() {
        TreeItem<BodyTreeEntry> root = new TreeItem<>(new BodyTreeEntry("Bodies", -1));
        root.setExpanded(true);

        try {
            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
            SpiceBundle bundle = eph.getSpiceBundle();
            Set<EphemerisID> knownBodies = eph.getKnownBodies();

            // Collect NAIF IDs and names
            Map<Integer, String> bodyNames = new TreeMap<>();
            for (EphemerisID id : knownBodies) {
                Optional<Integer> codeOpt = bundle.getObjectCode(id);
                if (codeOpt.isEmpty()) continue;
                int code = codeOpt.get();
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

            // Add spacecraft
            for (var sc : eph.getSpacecraft()) {
                int code = sc.code();
                String name = BodyLookupService.titleCase(sc.id().getName());
                bodyNames.put(code, name);
                groups.computeIfAbsent(code, k -> new ArrayList<>()).add(new int[] {code});
            }

            // Build tree items — Sun first, then barycenters in order, then others
            List<Integer> topLevel = new ArrayList<>(groups.keySet());
            topLevel.sort(Comparator.comparingInt(k -> {
                if (k == 10) return -1; // Sun first
                return k;
            }));

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

        bodyTree.setRoot(root);
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
        MenuItem loadConfig = new MenuItem("Load Configuration...");
        loadConfig.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Load KEPPLR Configuration");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Properties files", "*.properties"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All Files", "*.*"));
            File file = chooser.showOpenDialog(stage);
            if (file != null) {
                try {
                    KEPPLRConfiguration.getInstance().reload(Path.of(file.getAbsolutePath()));
                    populateBodyTree();
                    populateInstrumentsMenu();
                    if (configReloadCallback != null) {
                        configReloadCallback.run();
                    }
                } catch (Exception ex) {
                    logger.error("Failed to load configuration: {}", ex.getMessage());
                }
            }
        });

        // ── Run Script... ────────────────────────────────────────────────
        MenuItem runScript = new MenuItem("Run Script...");
        runScript.setOnAction(e -> {
            if (scriptRunner == null) return;

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
                chooser.setInitialFileName("recorded.groovy");
                File file = chooser.showSaveDialog(stage);
                if (file != null) {
                    try {
                        Files.writeString(file.toPath(), script);
                        logger.info("Recorded script saved to {}", file.getAbsolutePath());
                    } catch (IOException ex) {
                        logger.error("Failed to save recorded script: {}", ex.getMessage());
                    }
                }
            }
        });

        return new Menu("File", null, loadConfig, new SeparatorMenuItem(), runScript, recordToggle);
    }

    private Menu buildViewMenu() {
        // Camera Frame submenu
        ToggleGroup frameGroup = new ToggleGroup();
        RadioMenuItem inertialItem = new RadioMenuItem("Inertial");
        RadioMenuItem bodyFixedItem = new RadioMenuItem("Body-Fixed");
        RadioMenuItem synodicItem = new RadioMenuItem("Synodic");
        inertialItem.setToggleGroup(frameGroup);
        bodyFixedItem.setToggleGroup(frameGroup);
        synodicItem.setToggleGroup(frameGroup);

        inertialItem.setOnAction(e -> commands.setCameraFrame(CameraFrame.INERTIAL));
        bodyFixedItem.setOnAction(e -> commands.setCameraFrame(CameraFrame.BODY_FIXED));
        synodicItem.setOnAction(e -> commands.setCameraFrame(CameraFrame.SYNODIC));

        bridge.activeCameraFrameObjectProperty().addListener((obs, old, val) -> {
            inertialItem.setSelected(val == CameraFrame.INERTIAL);
            bodyFixedItem.setSelected(val == CameraFrame.BODY_FIXED);
            synodicItem.setSelected(val == CameraFrame.SYNODIC);
        });
        CameraFrame initial = bridge.activeCameraFrameObjectProperty().get();
        inertialItem.setSelected(initial == CameraFrame.INERTIAL);
        bodyFixedItem.setSelected(initial == CameraFrame.BODY_FIXED);
        synodicItem.setSelected(initial == CameraFrame.SYNODIC);

        Menu frameSubMenu = new Menu("Camera Frame", null, inertialItem, bodyFixedItem, synodicItem);

        MenuItem setFovItem = new MenuItem("Set FOV…");
        setFovItem.setOnAction(e -> {
            double currentFov = bridge.fovDegProperty().get();
            new SetFovDialog(commands, currentFov).showAndWait();
        });

        return new Menu("View", null, frameSubMenu, new SeparatorMenuItem(), setFovItem);
    }

    private Menu buildTimeMenu() {
        MenuItem pauseItem = new MenuItem();
        pauseItem
                .textProperty()
                .bind(Bindings.when(bridge.pausedTextProperty().isEqualTo("Paused"))
                        .then("Resume")
                        .otherwise("Pause"));
        pauseItem.setOnAction(
                e -> commands.setPaused(bridge.pausedTextProperty().get().equals("Running")));

        MenuItem setTimeItem = new MenuItem("Set Time...");
        setTimeItem.setOnAction(
                e -> new SetTimeDialog(commands, bridge.utcTimeTextProperty().get()).showAndWait());

        MenuItem setRateItem = new MenuItem("Set Time Rate...");
        setRateItem.setOnAction(e ->
                new SetTimeRateDialog(commands, bridge.timeRateTextProperty().get()).showAndWait());

        return new Menu("Time", null, pauseItem, new SeparatorMenuItem(), setTimeItem, setRateItem);
    }

    private Menu buildOverlaysMenu() {
        // ── Labels ────────────────────────────────────────────────────────────
        CheckMenuItem labelsItem = new CheckMenuItem("Show Labels");
        labelsItem.setSelected(false);
        labelsItem.setOnAction(e -> {
            boolean show = labelsItem.isSelected();
            try {
                KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
                for (EphemerisID id : eph.getKnownBodies()) {
                    eph.getSpiceBundle().getObjectCode(id).ifPresent(code -> commands.setLabelVisible(code, show));
                }
                for (var sc : eph.getSpacecraft()) {
                    commands.setLabelVisible(sc.code(), show);
                }
            } catch (Exception ex) {
                logger.debug("Failed to toggle labels: {}", ex.getMessage());
            }
        });

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
        CheckMenuItem trajItem = new CheckMenuItem("Show Trajectories");
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

        // Reset all Current Focus checkmarks when focus body changes
        bridge.focusedBodyIdProperty().addListener((obs, oldVal, newVal) -> {
            sunDirItem.setSelected(false);
            earthDirItem.setSelected(false);
            velocityItem.setSelected(false);
            targetTrailItem.setSelected(false);
            axesItem.setSelected(false);
        });

        Menu currentFocus =
                new Menu("Current Focus", null, sunDirItem, earthDirItem, velocityItem, targetTrailItem, axesItem);

        return new Menu(
                "Overlays",
                null,
                labelsItem,
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
        MenuItem size720 = new MenuItem("1280 \u00d7 720");
        size720.setOnAction(e -> resizeJmeWindow(1280, 720));

        MenuItem size1024 = new MenuItem("1280 \u00d7 1024");
        size1024.setOnAction(e -> resizeJmeWindow(1280, 1024));

        MenuItem size1080 = new MenuItem("1920 \u00d7 1080");
        size1080.setOnAction(e -> resizeJmeWindow(1920, 1080));

        MenuItem size1440 = new MenuItem("2560 \u00d7 1440");
        size1440.setOnAction(e -> resizeJmeWindow(2560, 1440));

        return new Menu("Window", null, size720, size1024, size1080, size1440);
    }

    private void resizeJmeWindow(int width, int height) {
        if (jmeResizeCallback != null) {
            jmeResizeCallback.accept(width, height);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
