package kepplr.ui;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import kepplr.camera.CameraFrame;
import kepplr.commands.SimulationCommands;

/**
 * Programmatic JavaFX control window displaying simulation status and interactive time controls (REDESIGN.md §10.2).
 *
 * <p>Contains no simulation logic, no ephemeris calls, and no camera math (CLAUDE.md Rule 1). Every displayed value is
 * bound to a {@link javafx.beans.property.ReadOnlyStringProperty} exposed by {@link SimulationStateFxBridge}. All user
 * actions are forwarded to {@link SimulationCommands} — this class never calls
 * {@link javafx.application.Platform#runLater} and never touches {@link kepplr.state.SimulationState} directly
 * (CLAUDE.md Rule 2).
 *
 * <p>Must be created and shown on the JavaFX application thread.
 */
public final class KepplrStatusWindow {

    private static final double WINDOW_MARGIN = 10.0;
    private static final double WINDOW_WIDTH = 520.0;
    private static final double WINDOW_HEIGHT = 490.0;
    private static final double BODIES_TEXT_AREA_HEIGHT = 160.0;

    private final SimulationStateFxBridge bridge;
    private final SimulationCommands commands;
    private Stage stage;

    /**
     * @param bridge the bridge exposing FX-thread-safe display properties; must not be null
     * @param commands the simulation commands interface for user-initiated actions; must not be null
     */
    public KepplrStatusWindow(SimulationStateFxBridge bridge, SimulationCommands commands) {
        this.bridge = bridge;
        this.commands = commands;
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
        stage.setTitle("KEPPLR — Status");

        MenuBar menuBar = buildMenuBar();

        GridPane grid = new GridPane();
        grid.setPadding(new Insets(10, 14, 10, 14));
        grid.setHgap(10);
        grid.setVgap(6);

        ColumnConstraints labelCol = new ColumnConstraints(130);
        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol, valueCol);

        int row = 0;
        row = addRow(grid, row, "UTC time:", bridge.utcTimeTextProperty());
        row = addRow(grid, row, "Time rate:", bridge.timeRateTextProperty());
        row = addRow(grid, row, "Clock:", bridge.pausedTextProperty());
        row = addRow(grid, row, "Selected:", bridge.selectedBodyTextProperty());
        row = addRow(grid, row, "Focused:", bridge.focusedBodyTextProperty());
        row = addRow(grid, row, "Targeted:", bridge.targetedBodyTextProperty());
        row = addRow(grid, row, "Tracking:", bridge.trackedTextProperty());
        row = addRow(grid, row, "Camera frame:", bridge.cameraFrameTextProperty());
        row = addRow(grid, row, "Camera pos:", bridge.cameraPositionTextProperty());
        row = addRow(grid, row, "BF pos:", bridge.cameraBodyFixedTextProperty());

        // ── Bodies in view ────────────────────────────────────────────────────
        Label bodiesLabel = new Label("Bodies in view:");
        bodiesLabel.setStyle("-fx-font-family: monospace; -fx-font-weight: bold;");
        bodiesLabel.setPadding(new Insets(8, 14, 2, 14));

        TextArea bodiesArea = new TextArea();
        bodiesArea.setEditable(false);
        bodiesArea.setWrapText(false);
        bodiesArea.setPrefHeight(BODIES_TEXT_AREA_HEIGHT);
        bodiesArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        bodiesArea.textProperty().bind(bridge.bodiesInViewTextProperty());
        VBox.setVgrow(bodiesArea, Priority.ALWAYS);

        VBox root = new VBox(menuBar, grid, bodiesLabel, bodiesArea);
        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        stage.setScene(scene);
        positionTopLeft();

        stage.show();
        stage.toFront();
        bridge.startPolling();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private MenuBar buildMenuBar() {
        // ── Time menu ────────────────────────────────────────────────────────
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

        Menu timeMenu = new Menu("Time");
        timeMenu.getItems().addAll(pauseItem, new SeparatorMenuItem(), setTimeItem, setRateItem);

        // ── Camera Frame submenu ──────────────────────────────────────────────
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

        // Keep radio selection in sync with the frame actually in use (not just requested)
        bridge.activeCameraFrameObjectProperty().addListener((obs, old, val) -> {
            inertialItem.setSelected(val == CameraFrame.INERTIAL);
            bodyFixedItem.setSelected(val == CameraFrame.BODY_FIXED);
            synodicItem.setSelected(val == CameraFrame.SYNODIC);
        });
        CameraFrame initial = bridge.activeCameraFrameObjectProperty().get();
        inertialItem.setSelected(initial == CameraFrame.INERTIAL);
        bodyFixedItem.setSelected(initial == CameraFrame.BODY_FIXED);
        synodicItem.setSelected(initial == CameraFrame.SYNODIC);

        Menu frameSubMenu = new Menu("Camera Frame");
        frameSubMenu.getItems().addAll(inertialItem, bodyFixedItem, synodicItem);
        Menu cameraMenu = new Menu("Camera");
        cameraMenu.getItems().add(frameSubMenu);

        MenuBar bar = new MenuBar(timeMenu, cameraMenu);
        bar.setUseSystemMenuBar(false);
        return bar;
    }

    private static int addRow(
            GridPane grid, int row, String labelText, javafx.beans.property.ReadOnlyStringProperty valueProp) {
        Label name = new Label(labelText);
        name.setStyle("-fx-font-family: monospace; -fx-font-weight: bold;");

        Label value = new Label();
        value.setStyle("-fx-font-family: monospace;");
        value.textProperty().bind(valueProp);

        grid.add(name, 0, row);
        grid.add(value, 1, row);
        return row + 1;
    }

    private void positionTopLeft() {
        if (stage == null) return;
        var bounds = Screen.getPrimary().getVisualBounds();
        stage.setX(bounds.getMinX() + WINDOW_MARGIN);
        stage.setY(bounds.getMinY() + WINDOW_MARGIN);
    }
}
