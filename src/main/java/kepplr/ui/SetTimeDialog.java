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

/**
 * Modal dialog for setting the simulation time to a UTC string (REDESIGN.md §1.2).
 *
 * <p>Contains no simulation logic (CLAUDE.md Rule 1). Delegates to {@link SimulationCommands#setUTC(String)}; all SPICE
 * conversion happens in {@link kepplr.core.SimulationClock} at point-of-use (CLAUDE.md Rule 3).
 *
 * <p>Must be created and shown on the JavaFX application thread.
 */
final class SetTimeDialog extends Dialog<ButtonType> {

    /**
     * @param commands the simulation commands interface; must not be null
     * @param currentUtc the current UTC string to pre-populate the field; may be null or blank
     */
    SetTimeDialog(SimulationCommands commands, String currentUtc) {
        setTitle("Set Simulation Time");
        setHeaderText("Enter a UTC time string (e.g. \"2015 JUL 14 11:50:00\")");

        ButtonType okButton = new ButtonType("Set", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(okButton, ButtonType.CANCEL);

        TextField utcField = new TextField();
        utcField.setPrefWidth(320);
        if (currentUtc != null && !currentUtc.isBlank() && !currentUtc.equals("—")) {
            utcField.setText(currentUtc);
        }

        Label errorLabel = new Label();
        errorLabel.setTextFill(Color.RED);
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(320);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10, 14, 4, 14));
        grid.add(new Label("UTC time:"), 0, 0);
        grid.add(utcField, 1, 0);
        grid.add(errorLabel, 1, 1);

        getDialogPane().setContent(grid);

        // Disable the Set button while the field is empty
        Button setBtn = (Button) getDialogPane().lookupButton(okButton);
        setBtn.setDisable(utcField.getText().isBlank());
        utcField.textProperty().addListener((obs, o, n) -> setBtn.setDisable(n.isBlank()));

        // Override the default close-on-OK behaviour: only close if the command succeeds
        setBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume(); // prevent automatic close
            errorLabel.setText("");
            try {
                commands.setUTC(utcField.getText().trim());
                // success — close the dialog
                setResult(okButton);
                close();
            } catch (Exception ex) {
                errorLabel.setText(
                        ex.getMessage() != null
                                ? ex.getMessage()
                                : ex.getClass().getSimpleName());
            }
        });

        // Request focus on the text field when the dialog opens
        utcField.requestFocus();
    }
}
