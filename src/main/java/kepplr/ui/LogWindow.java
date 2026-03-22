package kepplr.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A JavaFX window that displays log4j2 output captured by {@link LogAppender}, rendering ANSI color codes from the
 * {@code %highlight} pattern as colored text on a dark background.
 *
 * <p>Log lines are drained from the shared queue on each FX frame pulse via {@link #drain()}, which must be called from
 * the existing {@code AnimationTimer} in {@link KepplrStatusWindow}. This avoids {@code Platform.runLater()} per
 * CLAUDE.md Rule 2.
 *
 * <p>Must be created on the JavaFX application thread.
 */
final class LogWindow {

    private static final Logger logger = LogManager.getLogger();

    private static final double WIDTH = 900.0;
    private static final double HEIGHT = 400.0;
    private static final int MAX_NODES = 50_000;
    private static final String FONT_FAMILY = "monospace";
    private static final double FONT_SIZE = 12.0;

    /** Matches ANSI escape sequences: ESC [ (params) m */
    private static final Pattern ANSI_PATTERN = Pattern.compile("\033\\[([0-9;]*)m");

    /** Strip all ANSI escape sequences for plain-text export. */
    private static final Pattern ANSI_STRIP = Pattern.compile("\033\\[[0-9;]*m");

    private static final Color DEFAULT_COLOR = Color.web("#d4d4d4");
    private static final String BG_HEX = "#1e1e1e";

    private final Stage stage;
    private final TextFlow textFlow;
    private final ScrollPane scrollPane;
    private final ConcurrentLinkedQueue<String> queue;

    // Plain-text log for Save Log (ANSI stripped)
    private final StringBuilder plainLog = new StringBuilder();

    // Current ANSI state
    private Color currentColor = DEFAULT_COLOR;
    private boolean bold;

    LogWindow() {
        this.queue = LogAppender.queue();

        textFlow = new TextFlow();
        textFlow.setStyle("-fx-padding: 6; -fx-background-color: " + BG_HEX + ";");

        scrollPane = new ScrollPane(textFlow);
        scrollPane.setFitToWidth(true);
        scrollPane.setVvalue(1.0);
        scrollPane.setStyle("-fx-background: " + BG_HEX + "; -fx-background-color: " + BG_HEX + ";");

        Button saveButton = new Button("Save Log...");
        saveButton.setOnAction(e -> saveLog());

        HBox toolbar = new HBox(saveButton);
        toolbar.setPadding(new Insets(4, 6, 4, 6));

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setCenter(scrollPane);
        root.setStyle("-fx-background-color: " + BG_HEX + ";");

        Scene scene = new Scene(root, WIDTH, HEIGHT);

        stage = new Stage();
        stage.setTitle("KEPPLR \u2014 Log");
        stage.setScene(scene);
    }

    void show() {
        stage.show();
        stage.toFront();
    }

    void close() {
        stage.close();
    }

    /** Drain pending log lines from the appender queue, parse ANSI codes, and append colored {@link Text} nodes. */
    void drain() {
        String entry;
        boolean appended = false;
        while ((entry = queue.poll()) != null) {
            appendAnsi(entry);
            plainLog.append(ANSI_STRIP.matcher(entry).replaceAll(""));
            appended = true;
        }
        // Trim oldest nodes if over limit
        int size = textFlow.getChildren().size();
        if (size > MAX_NODES) {
            textFlow.getChildren().remove(0, size - MAX_NODES);
        }
        // Auto-scroll to bottom
        if (appended) {
            scrollPane.setVvalue(1.0);
        }
    }

    private void saveLog() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Log");
        chooser.setInitialFileName("kepplr.log");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Log files", "*.log", "*.txt"));
        File file = chooser.showSaveDialog(stage);
        if (file != null) {
            try {
                Files.writeString(file.toPath(), plainLog.toString(), StandardCharsets.UTF_8);
                logger.info("Log saved to {}", file.getAbsolutePath());
            } catch (IOException ex) {
                logger.error("Failed to save log: {}", ex.getMessage());
            }
        }
    }

    /** Parse a string containing ANSI escape sequences and append styled {@link Text} nodes to the text flow. */
    private void appendAnsi(String s) {
        Matcher m = ANSI_PATTERN.matcher(s);
        int pos = 0;
        while (m.find()) {
            if (m.start() > pos) {
                addText(s.substring(pos, m.start()));
            }
            applySgr(m.group(1));
            pos = m.end();
        }
        if (pos < s.length()) {
            addText(s.substring(pos));
        }
    }

    private void addText(String content) {
        if (content.isEmpty()) return;
        Text text = new Text(content);
        text.setFill(currentColor);
        text.setFont(bold ? Font.font(FONT_FAMILY, FontWeight.BOLD, FONT_SIZE) : Font.font(FONT_FAMILY, FONT_SIZE));
        textFlow.getChildren().add(text);
    }

    /** Apply SGR (Select Graphic Rendition) parameters. */
    private void applySgr(String params) {
        if (params.isEmpty()) {
            resetStyle();
            return;
        }
        for (String code : params.split(";")) {
            int n;
            try {
                n = Integer.parseInt(code);
            } catch (NumberFormatException e) {
                continue;
            }
            switch (n) {
                case 0 -> resetStyle();
                case 1 -> bold = true;
                case 22 -> bold = false;
                // Standard foreground colors
                case 30 -> currentColor = Color.web("#555555"); // black (visible on dark bg)
                case 31 -> currentColor = Color.web("#f44747"); // red
                case 32 -> currentColor = Color.web("#6a9955"); // green
                case 33 -> currentColor = Color.web("#dcdcaa"); // yellow
                case 34 -> currentColor = Color.web("#569cd6"); // blue
                case 35 -> currentColor = Color.web("#c586c0"); // magenta
                case 36 -> currentColor = Color.web("#4ec9b0"); // cyan
                case 37 -> currentColor = DEFAULT_COLOR; // white
                case 39 -> currentColor = DEFAULT_COLOR; // default
                // Bright foreground colors
                case 90 -> currentColor = Color.web("#808080");
                case 91 -> currentColor = Color.web("#ff6b6b"); // bright red
                case 92 -> currentColor = Color.web("#98c379"); // bright green
                case 93 -> currentColor = Color.web("#e5c07b"); // bright yellow
                case 94 -> currentColor = Color.web("#61afef"); // bright blue
                case 95 -> currentColor = Color.web("#c678dd"); // bright magenta
                case 96 -> currentColor = Color.web("#56b6c2"); // bright cyan
                case 97 -> currentColor = Color.WHITE; // bright white
                default -> {} // ignore background codes and others
            }
        }
    }

    private void resetStyle() {
        currentColor = DEFAULT_COLOR;
        bold = false;
    }
}
