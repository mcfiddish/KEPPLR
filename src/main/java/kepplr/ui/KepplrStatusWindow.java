package kepplr.ui;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Programmatic JavaFX control window displaying simulation status (REDESIGN.md §10.2).
 *
 * <p>Contains no simulation logic, no ephemeris calls, and no camera math (CLAUDE.md Rule 1). Every displayed value is
 * bound to a {@link ReadOnlyStringProperty} exposed by {@link SimulationStateFxBridge} — this class never calls
 * {@link javafx.application.Platform#runLater} and never touches {@link kepplr.state.SimulationState} directly
 * (CLAUDE.md Rule 2).
 *
 * <p>Must be created and shown on the JavaFX application thread.
 */
public final class KepplrStatusWindow {

    private static final double WINDOW_MARGIN = 10.0;
    private static final double WINDOW_WIDTH = 520.0;
    private static final double WINDOW_HEIGHT = 260.0;

    private final SimulationStateFxBridge bridge;
    private Stage stage;

    /** @param bridge the bridge exposing FX-thread-safe display properties; must not be null */
    public KepplrStatusWindow(SimulationStateFxBridge bridge) {
        this.bridge = bridge;
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

        Scene scene = new Scene(grid, WINDOW_WIDTH, WINDOW_HEIGHT);
        stage.setScene(scene);
        positionTopLeft();

        stage.show();
        stage.toFront();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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
