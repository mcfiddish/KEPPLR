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
 * Modal dialog for setting the simulation time rate (REDESIGN.md §2.3).
 *
 * <p>Contains no simulation logic (CLAUDE.md Rule 1). The entered value is an absolute rate in sim-seconds per
 * wall-second — "3x" means rate = 3.0, not "multiply current rate by 3" (REDESIGN.md §2.3). Delegates to
 * {@link SimulationCommands#setTimeRate(double)}.
 *
 * <p>Must be created and shown on the JavaFX application thread.
 */
final class SetTimeRateDialog extends Dialog<ButtonType> {

    /**
     * @param commands the simulation commands interface; must not be null
     * @param currentRateText the formatted rate string from the bridge (e.g. {@code "3600.00×"}); used to pre-populate
     *     the field; may be null or blank
     */
    SetTimeRateDialog(SimulationCommands commands, String currentRateText) {
        setTitle("Set Time Rate");
        setHeaderText("Enter a time rate (simulation seconds per wall-second).\nExample: 86400 = one day per second.");

        ButtonType okButton = new ButtonType("Set", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(okButton, ButtonType.CANCEL);

        TextField rateField = new TextField();
        rateField.setPrefWidth(200);
        rateField.setText(parseRateText(currentRateText));

        Label errorLabel = new Label();
        errorLabel.setTextFill(Color.RED);
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(280);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10, 14, 4, 14));
        grid.add(new Label("Time rate:"), 0, 0);
        grid.add(rateField, 1, 0);
        grid.add(errorLabel, 1, 1);

        getDialogPane().setContent(grid);

        Button setBtn = (Button) getDialogPane().lookupButton(okButton);
        setBtn.setDisable(rateField.getText().isBlank());
        rateField.textProperty().addListener((obs, o, n) -> setBtn.setDisable(n.isBlank()));

        // Override the default close-on-OK behaviour: only close if the value parses and the command succeeds
        setBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            errorLabel.setText("");
            double rate;
            try {
                rate = Double.parseDouble(rateField.getText().trim());
            } catch (NumberFormatException ex) {
                errorLabel.setText("Not a valid number: " + rateField.getText().trim());
                return;
            }
            try {
                commands.setTimeRate(rate);
                setResult(okButton);
                close();
            } catch (Exception ex) {
                errorLabel.setText(
                        ex.getMessage() != null
                                ? ex.getMessage()
                                : ex.getClass().getSimpleName());
            }
        });

        rateField.requestFocus();
    }

    /**
     * Strip the "×" suffix (if present) from a bridge-formatted rate string and return the numeric portion, ready for
     * pre-populating the text field.
     */
    private static String parseRateText(String rateText) {
        if (rateText == null || rateText.isBlank()) return "";
        String s = rateText.trim();
        if (s.endsWith("×")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }
}
