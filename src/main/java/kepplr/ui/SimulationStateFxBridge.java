package kepplr.ui;

import java.util.List;
import java.util.function.Consumer;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import kepplr.camera.CameraFrame;
import kepplr.config.KEPPLRConfiguration;
import kepplr.state.BodyInView;
import kepplr.state.SimulationState;

/**
 * Sole location of {@link Platform#runLater(Runnable)} calls (CLAUDE.md Rule 2).
 *
 * <p>Observes every {@link SimulationState} property on the <b>JME thread</b> (the thread that mutates those
 * properties). On each change, the new value is formatted into a display string on the JME thread, then dispatched to
 * the JavaFX thread via the injected {@code dispatcher} (which defaults to {@link Platform#runLater(Runnable)} in
 * production).
 *
 * <p>The {@link KepplrStatusWindow} binds only to the {@link ReadOnlyStringProperty} objects exposed here — it never
 * calls {@link Platform#runLater} itself and never touches {@link SimulationState} directly.
 *
 * <h3>Threading contract</h3>
 *
 * <ul>
 *   <li>Constructor and listener callbacks — JME thread.
 *   <li>{@link SimpleStringProperty#set} calls — always inside the dispatcher (FX thread in production).
 * </ul>
 */
public final class SimulationStateFxBridge {

    private final SimulationState state;
    private final Consumer<Runnable> dispatcher;

    /**
     * Set to {@code true} by {@link #startPolling()} when the {@link AnimationTimer} is active. While polling, reactive
     * listeners skip their {@code dispatcher.accept()} calls to avoid flooding the FX run queue at 60 fps; the
     * AnimationTimer handles all updates instead.
     */
    private volatile boolean polling = false;

    // ── Bridge properties (set on FX thread via dispatcher) ──────────────────

    private final SimpleStringProperty selectedBodyText = new SimpleStringProperty("");
    private final SimpleStringProperty focusedBodyText = new SimpleStringProperty("");
    private final SimpleStringProperty targetedBodyText = new SimpleStringProperty("");
    private final SimpleStringProperty trackedText = new SimpleStringProperty("");
    private final SimpleStringProperty utcTimeText = new SimpleStringProperty("");
    private final SimpleStringProperty timeRateText = new SimpleStringProperty("");
    private final SimpleStringProperty pausedText = new SimpleStringProperty("");
    private final SimpleStringProperty cameraFrameText = new SimpleStringProperty("");
    private final SimpleStringProperty cameraPositionText = new SimpleStringProperty("");
    private final SimpleStringProperty bodiesInViewText = new SimpleStringProperty("");

    // ── Production constructor ────────────────────────────────────────────────

    /**
     * Create a bridge using {@link Platform#runLater(Runnable)} as the dispatcher.
     *
     * @param state the simulation state to observe; must not be null
     */
    public SimulationStateFxBridge(SimulationState state) {
        this(state, Platform::runLater);
    }

    // ── Package-private constructor for testing ───────────────────────────────

    /**
     * Create a bridge with an injectable dispatcher — for unit testing.
     *
     * <p>Passing {@code Runnable::run} as the dispatcher makes all updates synchronous, removing the need for a running
     * JavaFX toolkit in tests.
     *
     * @param state the simulation state to observe
     * @param dispatcher receives the update {@link Runnable}; called on the JME thread
     */
    SimulationStateFxBridge(SimulationState state, Consumer<Runnable> dispatcher) {
        this.state = state;
        this.dispatcher = dispatcher;

        // Initialise properties with current state values (bridge created on JME thread,
        // before FX bindings exist — safe to set directly here)
        selectedBodyText.set(formatBodyId(state.selectedBodyIdProperty().get()));
        focusedBodyText.set(formatBodyId(state.focusedBodyIdProperty().get()));
        targetedBodyText.set(formatBodyId(state.targetedBodyIdProperty().get()));
        trackedText.set(formatTracked(state.trackedBodyIdProperty().get()));
        utcTimeText.set(formatEt(state.currentEtProperty().get()));
        timeRateText.set(formatTimeRate(state.timeRateProperty().get()));
        pausedText.set(formatPaused(state.pausedProperty().get()));
        cameraFrameText.set(formatCameraFrame(state.cameraFrameProperty().get()));
        cameraPositionText.set(
                formatCameraPosition(state.cameraPositionJ2000Property().get()));
        bodiesInViewText.set(formatBodiesInView(state.bodiesInViewProperty().get()));

        // Attach listeners — fire on the thread that mutates state (JME thread).
        // In production, polling=true once startPolling() is called; listeners skip dispatcher to
        // avoid flooding the FX run queue at 60 fps (the AnimationTimer handles all updates).
        // In tests, polling=false and the injected synchronous dispatcher is used instead.
        state.selectedBodyIdProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            String s = formatBodyId(newVal.intValue());
            dispatcher.accept(() -> selectedBodyText.set(s));
        });
        state.focusedBodyIdProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            String s = formatBodyId(newVal.intValue());
            dispatcher.accept(() -> focusedBodyText.set(s));
        });
        state.targetedBodyIdProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            String s = formatBodyId(newVal.intValue());
            dispatcher.accept(() -> targetedBodyText.set(s));
        });
        state.trackedBodyIdProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            String s = formatTracked(newVal.intValue());
            dispatcher.accept(() -> trackedText.set(s));
        });
        state.currentEtProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            String s = formatEt(newVal.doubleValue());
            dispatcher.accept(() -> utcTimeText.set(s));
        });
        state.timeRateProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            String s = formatTimeRate(newVal.doubleValue());
            dispatcher.accept(() -> timeRateText.set(s));
        });
        state.pausedProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            String s = formatPaused(newVal);
            dispatcher.accept(() -> pausedText.set(s));
        });
        state.cameraFrameProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            String s = formatCameraFrame(newVal);
            dispatcher.accept(() -> cameraFrameText.set(s));
        });
        state.cameraPositionJ2000Property().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            String s = formatCameraPosition(newVal);
            dispatcher.accept(() -> cameraPositionText.set(s));
        });
        state.bodiesInViewProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            String s = formatBodiesInView(newVal);
            dispatcher.accept(() -> bodiesInViewText.set(s));
        });
    }

    // ── FX-thread polling (AnimationTimer) ───────────────────────────────────

    /**
     * Start an {@link AnimationTimer} that refreshes all display properties each JavaFX frame.
     *
     * <p>Must be called on the JavaFX application thread (e.g., from {@link KepplrStatusWindow#show()}). This is the
     * primary update path in production: it reads {@link SimulationState} directly on the FX thread, bypassing
     * {@link Platform#runLater} which can stall on macOS when GLFW holds the main thread.
     *
     * <p>The reactive listeners added in the constructor remain active for unit-test compatibility (tests use a
     * synchronous dispatcher and never call this method).
     */
    public void startPolling() {
        polling = true;
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                refreshAll();
            }
        }.start();
    }

    private void refreshAll() {
        selectedBodyText.set(formatBodyId(state.selectedBodyIdProperty().get()));
        focusedBodyText.set(formatBodyId(state.focusedBodyIdProperty().get()));
        targetedBodyText.set(formatBodyId(state.targetedBodyIdProperty().get()));
        trackedText.set(formatTracked(state.trackedBodyIdProperty().get()));
        utcTimeText.set(formatEt(state.currentEtProperty().get()));
        timeRateText.set(formatTimeRate(state.timeRateProperty().get()));
        pausedText.set(formatPaused(state.pausedProperty().get()));
        cameraFrameText.set(formatCameraFrame(state.cameraFrameProperty().get()));
        cameraPositionText.set(
                formatCameraPosition(state.cameraPositionJ2000Property().get()));
        bodiesInViewText.set(formatBodiesInView(state.bodiesInViewProperty().get()));
    }

    // ── Exposed read-only properties ──────────────────────────────────────────

    /** NAIF ID of the selected body, formatted for display. */
    public ReadOnlyStringProperty selectedBodyTextProperty() {
        return selectedBodyText;
    }

    /** NAIF ID of the focused body, formatted for display. */
    public ReadOnlyStringProperty focusedBodyTextProperty() {
        return focusedBodyText;
    }

    /** NAIF ID of the targeted body, formatted for display. */
    public ReadOnlyStringProperty targetedBodyTextProperty() {
        return targetedBodyText;
    }

    /** Tracking status, formatted for display. */
    public ReadOnlyStringProperty trackedTextProperty() {
        return trackedText;
    }

    /** Current simulation time as a UTC string (§1.2). */
    public ReadOnlyStringProperty utcTimeTextProperty() {
        return utcTimeText;
    }

    /** Current time rate, formatted for display (§2.3). */
    public ReadOnlyStringProperty timeRateTextProperty() {
        return timeRateText;
    }

    /** Paused / running state, formatted for display. */
    public ReadOnlyStringProperty pausedTextProperty() {
        return pausedText;
    }

    /** Active camera frame name (§1.5). */
    public ReadOnlyStringProperty cameraFrameTextProperty() {
        return cameraFrameText;
    }

    /** Heliocentric J2000 camera position in km, formatted for display (§1.4). */
    public ReadOnlyStringProperty cameraPositionTextProperty() {
        return cameraPositionText;
    }

    /** Bodies visible in the scene this frame, formatted as a multi-line string (§7.3, §10.2). */
    public ReadOnlyStringProperty bodiesInViewTextProperty() {
        return bodiesInViewText;
    }

    // ── Formatting helpers (called on JME thread) ─────────────────────────────

    /**
     * Format a NAIF ID for display.
     *
     * @param id NAIF ID, or -1 for "no body"
     * @return {@code "—"} if {@code id == -1}, otherwise {@code "NAIF <id>"}
     */
    static String formatBodyId(int id) {
        return id == -1 ? "—" : "NAIF " + id;
    }

    /**
     * Format the tracked-body state.
     *
     * @param trackedId NAIF ID of tracked body, or -1
     */
    static String formatTracked(int trackedId) {
        return trackedId == -1 ? "Not tracking" : "Tracking NAIF " + trackedId;
    }

    /**
     * Convert ET to a UTC display string.
     *
     * <p>Acquires {@link picante.time.TimeConversion} at point-of-use (CLAUDE.md Rule 3). Returns {@code "—"} if the
     * configuration is not yet initialised.
     */
    static String formatEt(double et) {
        try {
            return KEPPLRConfiguration.getInstance()
                    .getTimeConversion()
                    .format("C")
                    .apply(et);
        } catch (Exception e) {
            return "—";
        }
    }

    /**
     * Format a time rate for display (§2.3).
     *
     * <p>Uses scientific notation for magnitudes ≥ 1 000 000 or &lt; 0.001.
     */
    static String formatTimeRate(double rate) {
        double abs = Math.abs(rate);
        String formatted;
        if (abs == 0.0 || (abs >= 0.001 && abs < 1_000_000.0)) {
            formatted = String.format("%.2f×", rate);
        } else {
            formatted = String.format("%.3e×", rate);
        }
        return formatted;
    }

    static String formatPaused(boolean paused) {
        return paused ? "Paused" : "Running";
    }

    static String formatCameraFrame(CameraFrame frame) {
        return frame == null ? "—" : frame.name();
    }

    /**
     * Format a heliocentric J2000 camera position for display.
     *
     * @param pos length-3 array [x, y, z] in km, or null
     */
    static String formatCameraPosition(double[] pos) {
        if (pos == null || pos.length < 3) return "—";
        return String.format("[%.3e, %.3e, %.3e] km", pos[0], pos[1], pos[2]);
    }

    /**
     * Format the bodies-in-view list as a multi-line string, one body per line.
     *
     * <p>Each line: {@code NAME dist km}. Returns {@code "—"} if the list is null or empty.
     *
     * @param bodies sorted by ascending distance; may be null
     */
    static String formatBodiesInView(List<BodyInView> bodies) {
        if (bodies == null || bodies.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (BodyInView b : bodies) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(String.format("%-14s  %.3e km", b.name(), b.distanceKm()));
        }
        return sb.toString();
    }
}
