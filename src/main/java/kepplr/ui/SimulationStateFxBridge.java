package kepplr.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import kepplr.camera.CameraFrame;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.BodyLookupService;
import kepplr.ephemeris.Instrument;
import kepplr.state.BodyInView;
import kepplr.state.SimulationState;
import kepplr.util.KepplrConstants;
import picante.math.vectorspace.VectorIJK;

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
    private final SimpleStringProperty utcTimeText = new SimpleStringProperty("");
    private final SimpleStringProperty timeRateText = new SimpleStringProperty("");
    private final SimpleStringProperty pausedText = new SimpleStringProperty("");
    private final SimpleStringProperty cameraFrameText = new SimpleStringProperty("");
    private final SimpleObjectProperty<CameraFrame> activeCameraFrameObj =
            new SimpleObjectProperty<>(CameraFrame.INERTIAL);
    private final SimpleStringProperty cameraPositionText = new SimpleStringProperty("");
    private final SimpleStringProperty cameraBodyFixedText = new SimpleStringProperty("N/A");
    private final SimpleStringProperty bodiesInViewText = new SimpleStringProperty("");
    private final SimpleBooleanProperty transitionActive = new SimpleBooleanProperty(false);
    private final SimpleDoubleProperty transitionProgress = new SimpleDoubleProperty(0.0);
    private final SimpleDoubleProperty fovDeg = new SimpleDoubleProperty(KepplrConstants.CAMERA_FOV_Y_DEG);
    private final SimpleDoubleProperty currentEt = new SimpleDoubleProperty(0.0);

    // ── Step 19 additions ────────────────────────────────────────────────────
    private final SimpleStringProperty selectedBodyName = new SimpleStringProperty("—");
    private final SimpleStringProperty focusedBodyName = new SimpleStringProperty("—");
    private final SimpleStringProperty targetedBodyName = new SimpleStringProperty("—");
    private final SimpleBooleanProperty selectedBodyActive = new SimpleBooleanProperty(false);

    // ── Camera-to-body distance (Step 42) ────────────────────────────────────
    private final SimpleStringProperty selectedBodyDistance = new SimpleStringProperty("—");
    private final SimpleStringProperty focusedBodyDistance = new SimpleStringProperty("—");
    private final SimpleStringProperty targetedBodyDistance = new SimpleStringProperty("—");
    private final SimpleBooleanProperty cameraFrameFallbackActive = new SimpleBooleanProperty(false);
    private final SimpleIntegerProperty selectedBodyIdFx = new SimpleIntegerProperty(-1);
    private final SimpleIntegerProperty focusedBodyIdFx = new SimpleIntegerProperty(-1);
    private final SimpleIntegerProperty targetedBodyIdFx = new SimpleIntegerProperty(-1);

    // ── Step 19b additions ───────────────────────────────────────────────────
    private final SimpleBooleanProperty hudTimeVisibleFx = new SimpleBooleanProperty(true);
    private final SimpleBooleanProperty hudInfoVisibleFx = new SimpleBooleanProperty(true);

    // ── Step 22: per-instrument frustum visibility ────────────────────────────
    /** Keyed by instrument NAIF code. Populated once at construction from getInstruments(). */
    private final Map<Integer, SimpleBooleanProperty> frustumVisibleFxMap = new HashMap<>();

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
        currentEt.set(state.currentEtProperty().get());
        utcTimeText.set(formatEt(state.currentEtProperty().get()));
        timeRateText.set(formatTimeRate(state.timeRateProperty().get()));
        pausedText.set(formatPaused(state.pausedProperty().get()));
        cameraFrameText.set(currentCameraFrameText());
        activeCameraFrameObj.set(state.activeCameraFrameProperty().get());
        cameraPositionText.set(
                formatCameraPosition(state.cameraPositionJ2000Property().get()));
        cameraBodyFixedText.set(
                formatBodyFixed(state.cameraBodyFixedSphericalProperty().get()));
        bodiesInViewText.set(formatBodiesInView(state.bodiesInViewProperty().get()));
        transitionActive.set(state.transitionActiveProperty().get());
        transitionProgress.set(state.transitionProgressProperty().get());
        fovDeg.set(state.fovDegProperty().get());
        selectedBodyName.set(formatBodyNameWithId(state.selectedBodyIdProperty().get()));
        focusedBodyName.set(formatBodyNameWithId(state.focusedBodyIdProperty().get()));
        targetedBodyName.set(formatBodyNameWithId(state.targetedBodyIdProperty().get()));
        selectedBodyActive.set(state.selectedBodyIdProperty().get() != -1);
        cameraFrameFallbackActive.set(state.cameraFrameFallbackActiveProperty().get());
        selectedBodyIdFx.set(state.selectedBodyIdProperty().get());
        focusedBodyIdFx.set(state.focusedBodyIdProperty().get());
        targetedBodyIdFx.set(state.targetedBodyIdProperty().get());
        {
            double[] initCamPos = state.cameraPositionJ2000Property().get();
            double initEt = state.currentEtProperty().get();
            selectedBodyDistance.set(formatDistance(
                    computeCameraToBodyDistanceKm(state.selectedBodyIdProperty().get(), initCamPos, initEt)));
            focusedBodyDistance.set(formatDistance(
                    computeCameraToBodyDistanceKm(state.focusedBodyIdProperty().get(), initCamPos, initEt)));
            targetedBodyDistance.set(formatDistance(
                    computeCameraToBodyDistanceKm(state.targetedBodyIdProperty().get(), initCamPos, initEt)));
        }
        hudTimeVisibleFx.set(state.hudTimeVisibleProperty().get());
        hudInfoVisibleFx.set(state.hudInfoVisibleProperty().get());
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
        state.currentEtProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            double et = newVal.doubleValue();
            String s = formatEt(et);
            dispatcher.accept(() -> {
                utcTimeText.set(s);
                currentEt.set(et);
            });
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
            String s = currentCameraFrameText();
            dispatcher.accept(() -> cameraFrameText.set(s));
        });
        state.focusedBodyIdProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            if (state.cameraFrameProperty().get() != CameraFrame.SYNODIC) return;
            String s = currentCameraFrameText();
            dispatcher.accept(() -> cameraFrameText.set(s));
        });
        state.selectedBodyIdProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            if (state.cameraFrameProperty().get() != CameraFrame.SYNODIC) return;
            String s = currentCameraFrameText();
            dispatcher.accept(() -> cameraFrameText.set(s));
        });
        state.activeCameraFrameProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            dispatcher.accept(() -> activeCameraFrameObj.set(newVal));
        });
        state.cameraPositionJ2000Property().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            String s = formatCameraPosition(newVal);
            dispatcher.accept(() -> cameraPositionText.set(s));
        });
        state.cameraBodyFixedSphericalProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            String s = formatBodyFixed(newVal);
            dispatcher.accept(() -> cameraBodyFixedText.set(s));
        });
        state.bodiesInViewProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            String s = formatBodiesInView(newVal);
            dispatcher.accept(() -> bodiesInViewText.set(s));
        });
        state.transitionActiveProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            dispatcher.accept(() -> transitionActive.set(newVal));
        });
        state.transitionProgressProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            dispatcher.accept(() -> transitionProgress.set(newVal.doubleValue()));
        });
        state.fovDegProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            dispatcher.accept(() -> fovDeg.set(newVal.doubleValue()));
        });
        state.selectedBodyIdProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            int id = newVal.intValue();
            String n = formatBodyNameWithId(id);
            boolean active = id != -1;
            double[] camPos = state.cameraPositionJ2000Property().get();
            double et = state.currentEtProperty().get();
            String dist = formatDistance(computeCameraToBodyDistanceKm(id, camPos, et));
            dispatcher.accept(() -> {
                selectedBodyName.set(n);
                selectedBodyActive.set(active);
                selectedBodyIdFx.set(id);
                selectedBodyDistance.set(dist);
            });
        });
        state.focusedBodyIdProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            int id = newVal.intValue();
            String n = formatBodyNameWithId(id);
            double[] camPos = state.cameraPositionJ2000Property().get();
            double et = state.currentEtProperty().get();
            String dist = formatDistance(computeCameraToBodyDistanceKm(id, camPos, et));
            dispatcher.accept(() -> {
                focusedBodyName.set(n);
                focusedBodyIdFx.set(id);
                focusedBodyDistance.set(dist);
            });
        });
        state.targetedBodyIdProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            int id = newVal.intValue();
            String n = formatBodyNameWithId(id);
            double[] camPos = state.cameraPositionJ2000Property().get();
            double et = state.currentEtProperty().get();
            String dist = formatDistance(computeCameraToBodyDistanceKm(id, camPos, et));
            dispatcher.accept(() -> {
                targetedBodyName.set(n);
                targetedBodyIdFx.set(id);
                targetedBodyDistance.set(dist);
            });
        });
        state.cameraFrameFallbackActiveProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            dispatcher.accept(() -> cameraFrameFallbackActive.set(newVal));
        });
        state.hudTimeVisibleProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            dispatcher.accept(() -> hudTimeVisibleFx.set(newVal));
        });
        state.hudInfoVisibleProperty().addListener((obs, oldVal, newVal) -> {
            if (polling) return;
            dispatcher.accept(() -> hudInfoVisibleFx.set(newVal));
        });

        // Initialise per-instrument frustum visibility FX properties and attach listeners.
        // getInstruments() is read once at bridge construction time; the instrument set is fixed for
        // the lifetime of the current kernel configuration.
        try {
            for (Instrument inst :
                    KEPPLRConfiguration.getInstance().getEphemeris().getInstruments()) {
                int code = inst.code();
                SimpleBooleanProperty fxProp = new SimpleBooleanProperty(false);
                frustumVisibleFxMap.put(code, fxProp);
                state.frustumVisibleProperty(code).addListener((obs, oldVal, newVal) -> {
                    if (polling) return;
                    dispatcher.accept(() -> fxProp.set(newVal));
                });
            }
        } catch (Exception e) {
            // No instruments available (no IK loaded) — frustumVisibleFxMap remains empty.
        }
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
        currentEt.set(state.currentEtProperty().get());
        utcTimeText.set(formatEt(state.currentEtProperty().get()));
        timeRateText.set(formatTimeRate(state.timeRateProperty().get()));
        pausedText.set(formatPaused(state.pausedProperty().get()));
        cameraFrameText.set(currentCameraFrameText());
        activeCameraFrameObj.set(state.activeCameraFrameProperty().get());
        cameraPositionText.set(
                formatCameraPosition(state.cameraPositionJ2000Property().get()));
        cameraBodyFixedText.set(
                formatBodyFixed(state.cameraBodyFixedSphericalProperty().get()));
        bodiesInViewText.set(formatBodiesInView(state.bodiesInViewProperty().get()));
        transitionActive.set(state.transitionActiveProperty().get());
        transitionProgress.set(state.transitionProgressProperty().get());
        fovDeg.set(state.fovDegProperty().get());
        selectedBodyName.set(formatBodyNameWithId(state.selectedBodyIdProperty().get()));
        focusedBodyName.set(formatBodyNameWithId(state.focusedBodyIdProperty().get()));
        targetedBodyName.set(formatBodyNameWithId(state.targetedBodyIdProperty().get()));
        selectedBodyActive.set(state.selectedBodyIdProperty().get() != -1);
        cameraFrameFallbackActive.set(state.cameraFrameFallbackActiveProperty().get());
        selectedBodyIdFx.set(state.selectedBodyIdProperty().get());
        focusedBodyIdFx.set(state.focusedBodyIdProperty().get());
        targetedBodyIdFx.set(state.targetedBodyIdProperty().get());
        {
            double[] camPos = state.cameraPositionJ2000Property().get();
            double et = state.currentEtProperty().get();
            selectedBodyDistance.set(formatDistance(
                    computeCameraToBodyDistanceKm(state.selectedBodyIdProperty().get(), camPos, et)));
            focusedBodyDistance.set(formatDistance(
                    computeCameraToBodyDistanceKm(state.focusedBodyIdProperty().get(), camPos, et)));
            targetedBodyDistance.set(formatDistance(
                    computeCameraToBodyDistanceKm(state.targetedBodyIdProperty().get(), camPos, et)));
        }
        hudTimeVisibleFx.set(state.hudTimeVisibleProperty().get());
        hudInfoVisibleFx.set(state.hudInfoVisibleProperty().get());
        frustumVisibleFxMap.forEach(
                (code, fxProp) -> fxProp.set(state.frustumVisibleProperty(code).get()));
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

    /** Camera frame actually in use (may differ from requested frame when BODY_FIXED falls back). */
    public ReadOnlyObjectProperty<CameraFrame> activeCameraFrameObjectProperty() {
        return activeCameraFrameObj;
    }

    /** Heliocentric J2000 camera position in km, formatted for display (§1.4). */
    public ReadOnlyStringProperty cameraPositionTextProperty() {
        return cameraPositionText;
    }

    /** Camera position in focus body's body-fixed frame as r/lat/lon, or "N/A" if unavailable. */
    public ReadOnlyStringProperty cameraBodyFixedTextProperty() {
        return cameraBodyFixedText;
    }

    /** Bodies visible in the scene this frame, formatted as a multi-line string (§7.3, §10.2). */
    public ReadOnlyStringProperty bodiesInViewTextProperty() {
        return bodiesInViewText;
    }

    /** {@code true} while a camera transition is active (Step 18). */
    public ReadOnlyBooleanProperty transitionActiveProperty() {
        return transitionActive;
    }

    /** Progress of the active camera transition in {@code [0.0, 1.0]}, or {@code 0.0} if none (Step 18). */
    public ReadOnlyDoubleProperty transitionProgressProperty() {
        return transitionProgress;
    }

    /** Current camera field of view in degrees. */
    public ReadOnlyDoubleProperty fovDegProperty() {
        return fovDeg;
    }

    public ReadOnlyDoubleProperty currentEtProperty() {
        return currentEt;
    }

    // ── Step 19 properties ───────────────────────────────────────────────────

    /** Selected body name resolved via {@link BodyLookupService#formatName(int)}. */
    public ReadOnlyStringProperty selectedBodyNameProperty() {
        return selectedBodyName;
    }

    /** Focused body name resolved via {@link BodyLookupService#formatName(int)}. */
    public ReadOnlyStringProperty focusedBodyNameProperty() {
        return focusedBodyName;
    }

    /** Targeted body name resolved via {@link BodyLookupService#formatName(int)}. */
    public ReadOnlyStringProperty targetedBodyNameProperty() {
        return targetedBodyName;
    }

    /** {@code true} when a body is currently selected (selectedBodyId != -1). */
    public ReadOnlyBooleanProperty selectedBodyActiveProperty() {
        return selectedBodyActive;
    }

    /** Camera-to-selected-body distance, formatted with auto-switching units. */
    public ReadOnlyStringProperty selectedBodyDistanceProperty() {
        return selectedBodyDistance;
    }

    /** Camera-to-focused-body distance, formatted with auto-switching units. */
    public ReadOnlyStringProperty focusedBodyDistanceProperty() {
        return focusedBodyDistance;
    }

    /** Camera-to-targeted-body distance, formatted with auto-switching units. */
    public ReadOnlyStringProperty targetedBodyDistanceProperty() {
        return targetedBodyDistance;
    }

    /** {@code true} when the camera frame fell back from BODY_FIXED to INERTIAL. */
    public ReadOnlyBooleanProperty cameraFrameFallbackActiveProperty() {
        return cameraFrameFallbackActive;
    }

    /** NAIF ID of the currently selected body (-1 if none). */
    public ReadOnlyIntegerProperty selectedBodyIdProperty() {
        return selectedBodyIdFx;
    }

    /** NAIF ID of the currently focused body (-1 if none). */
    public ReadOnlyIntegerProperty focusedBodyIdProperty() {
        return focusedBodyIdFx;
    }

    /** NAIF ID of the currently targeted body (-1 if none). */
    public ReadOnlyIntegerProperty targetedBodyIdProperty() {
        return targetedBodyIdFx;
    }

    /** Whether the HUD time display is visible (Step 19b). */
    public ReadOnlyBooleanProperty hudTimeVisibleProperty() {
        return hudTimeVisibleFx;
    }

    /** Whether the HUD info display is visible (Step 19b). */
    public ReadOnlyBooleanProperty hudInfoVisibleProperty() {
        return hudInfoVisibleFx;
    }

    /**
     * Whether the instrument frustum overlay is visible for the given instrument NAIF code (Step 22).
     *
     * @param naifCode NAIF code of the instrument
     * @return the FX property, or {@code null} if the instrument is not known (no IK loaded)
     */
    public ReadOnlyBooleanProperty frustumVisibleProperty(int naifCode) {
        return frustumVisibleFxMap.get(naifCode);
    }

    /**
     * Rebuild the per-instrument frustum visibility FX property map after a configuration reload.
     *
     * <p>Clears the existing map and re-reads {@code getInstruments()} from the current kernel. New listeners are
     * registered on any newly discovered instrument state properties. Listeners on properties from the previous kernel
     * are orphaned but harmless — they target replaced map entries that will never be observed again.
     *
     * <p>Must be called on the JavaFX application thread.
     */
    public void reloadInstruments() {
        frustumVisibleFxMap.clear();
        try {
            for (Instrument inst :
                    KEPPLRConfiguration.getInstance().getEphemeris().getInstruments()) {
                int code = inst.code();
                SimpleBooleanProperty fxProp = new SimpleBooleanProperty(false);
                frustumVisibleFxMap.put(code, fxProp);
                state.frustumVisibleProperty(code).addListener((obs, oldVal, newVal) -> {
                    if (polling) return;
                    dispatcher.accept(() -> fxProp.set(newVal));
                });
            }
        } catch (Exception e) {
            // No instruments in new config — map stays empty.
        }
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
     * Format a NAIF ID as a human-readable body name via {@link BodyLookupService}.
     *
     * @param id NAIF ID, or -1 for "no body"
     * @return the SPICE name (title-cased), {@code "—"} for -1, or {@code "NAIF <id>"} as fallback
     */
    public static String formatBodyName(int id) {
        return BodyLookupService.formatName(id);
    }

    /**
     * Format a NAIF ID as {@code "Name (ID)"}, e.g. {@code "Earth (399)"}.
     *
     * @param id NAIF ID, or -1 for "no body"
     * @return formatted name with ID, or {@code "—"} for -1
     */
    static String formatBodyNameWithId(int id) {
        if (id == -1) return "—";
        String name = BodyLookupService.formatName(id);
        return name + " (" + id + ")";
    }

    /**
     * Format a camera-to-body distance with auto-switching units.
     *
     * <ul>
     *   <li>&lt; 1 km → metres (e.g. {@code "342 m"})
     *   <li>&lt; 0.01 AU → km (e.g. {@code "1.23e+05 km"})
     *   <li>≥ 0.01 AU → AU (e.g. {@code "1.024 AU"})
     * </ul>
     *
     * @param distanceKm distance in kilometres, or {@code Double.NaN} / negative for unavailable
     * @return formatted distance string, or {@code "—"} if unavailable
     */
    static String formatDistance(double distanceKm) {
        if (Double.isNaN(distanceKm) || distanceKm < 0) return "—";

        if (distanceKm < KepplrConstants.DISTANCE_DISPLAY_M_THRESHOLD_KM) {
            double metres = distanceKm * 1000.0;
            if (metres < 0.1) {
                return String.format("%.3f m", metres);
            }
            return String.format("%.1f m", metres);
        }

        double au = distanceKm / KepplrConstants.KM_PER_AU;
        if (au >= KepplrConstants.DISTANCE_DISPLAY_AU_THRESHOLD_AU) {
            return String.format("%.4f AU", au);
        }

        if (distanceKm < 1000.0) {
            return String.format("%.2f km", distanceKm);
        }
        return String.format("%.3e km", distanceKm);
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

    private String currentCameraFrameText() {
        CameraFrame frame = state.cameraFrameProperty().get();
        if (frame == CameraFrame.SYNODIC) {
            int focusId = state.focusedBodyIdProperty().get();
            int selectedId = state.selectedBodyIdProperty().get();
            String focusStr = focusId == -1 ? "—" : "NAIF " + focusId;
            String targetStr = (selectedId == -1 || selectedId == focusId) ? "NAIF 10 (Sun)" : "NAIF " + selectedId;
            return "SYNODIC [" + focusStr + " → " + targetStr + "]";
        }
        return formatCameraFrame(frame);
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
     * Format body-fixed spherical coordinates for display.
     *
     * @param sph {@code [r_km, lat_deg, lon_deg]}, or {@code null} if unavailable
     */
    static String formatBodyFixed(double[] sph) {
        if (sph == null) return "N/A";
        return String.format("r=%.3e km  φ=%.2f°  λ=%.2f°", sph[0], sph[1], sph[2]);
    }

    /**
     * Compute the distance in km from the camera to a body, both in heliocentric J2000.
     *
     * @param bodyId NAIF body code, or -1 for "no body"
     * @param cameraHelioJ2000 camera position [x, y, z] in km (heliocentric J2000)
     * @param et current ephemeris time
     * @return distance in km, or {@code Double.NaN} if unavailable
     */
    static double computeCameraToBodyDistanceKm(int bodyId, double[] cameraHelioJ2000, double et) {
        if (bodyId == -1 || cameraHelioJ2000 == null || cameraHelioJ2000.length < 3) return Double.NaN;
        try {
            VectorIJK bodyPos =
                    KEPPLRConfiguration.getInstance().getEphemeris().getHeliocentricPositionJ2000(bodyId, et);
            if (bodyPos == null) return Double.NaN;
            double dx = cameraHelioJ2000[0] - bodyPos.getI();
            double dy = cameraHelioJ2000[1] - bodyPos.getJ();
            double dz = cameraHelioJ2000[2] - bodyPos.getK();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        } catch (Exception e) {
            return Double.NaN;
        }
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
