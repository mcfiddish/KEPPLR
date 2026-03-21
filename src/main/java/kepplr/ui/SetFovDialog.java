package kepplr.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import kepplr.commands.SimulationCommands;
import kepplr.util.KepplrConstants;

/**
 * Modal dialog for setting the camera field of view in degrees.
 *
 * <p>Contains no simulation logic (CLAUDE.md Rule 1). Delegates to {@link SimulationCommands#setFov(double, double)}
 * with an instant transition (duration = 0).
 *
 * <p>Must be created and shown on the JavaFX application thread.
 */
final class SetFovDialog extends Dialog<ButtonType> {

    /**
     * @param commands the simulation commands interface; must not be null
     * @param currentFovDeg the current camera FOV in degrees, used to pre-populate the field
     */
    SetFovDialog(SimulationCommands commands, double currentFovDeg) {
        setTitle("Set Field of View");
        setHeaderText(String.format(
                "Enter the vertical field of view in degrees (%.0f–%.0f).",
                KepplrConstants.FOV_MIN_DEG, KepplrConstants.FOV_MAX_DEG));

        ButtonType okButton = new ButtonType("Set", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(okButton, ButtonType.CANCEL);

        TextField fovField = new TextField(String.format("%.1f", currentFovDeg));
        fovField.setPrefWidth(200);

        Label errorLabel = new Label();
        errorLabel.setTextFill(Color.RED);
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(280);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10, 14, 4, 14));
        grid.add(new Label("FOV (degrees):"), 0, 0);
        grid.add(fovField, 1, 0);
        grid.add(errorLabel, 1, 1);

        getDialogPane().setContent(grid);

        Button setBtn = (Button) getDialogPane().lookupButton(okButton);
        setBtn.setDisable(fovField.getText().isBlank());
        fovField.textProperty().addListener((obs, o, n) -> setBtn.setDisable(n.isBlank()));

        // Override default close-on-OK: only close if the value parses
        setBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            errorLabel.setText("");
            double fov;
            try {
                fov = Double.parseDouble(fovField.getText().trim());
            } catch (NumberFormatException ex) {
                errorLabel.setText("Not a valid number: " + fovField.getText().trim());
                return;
            }
            try {
                commands.setFov(fov, 0.0);
                setResult(okButton);
                close();
            } catch (Exception ex) {
                errorLabel.setText(
                        ex.getMessage() != null
                                ? ex.getMessage()
                                : ex.getClass().getSimpleName());
            }
        });

        fovField.selectAll();
        fovField.requestFocus();
    }
}
