package kepplr.state;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import kepplr.camera.CameraFrame;
import kepplr.render.RenderQuality;
import kepplr.render.vector.VectorType;
import kepplr.util.KepplrConstants;

/**
 * Concrete, mutable implementation of {@link SimulationState} (REDESIGN.md §4, §10).
 *
 * <p>Holds JavaFX properties for all UI-visible simulation state. The {@link SimulationState} interface exposes only
 * read-only views; this class provides public setters so the simulation core and
 * {@link kepplr.commands.DefaultSimulationCommands} can write state without violating the one-direction flow (CLAUDE.md
 * Rule 2).
 *
 * <p>All property mutations are expected to happen on the JME thread. The JavaFX bridge layer marshals reads to the FX
 * thread (CLAUDE.md Rule 4).
 */
public final class DefaultSimulationState implements SimulationState {

    // ── Interaction state (§4.2) ──

    private final SimpleIntegerProperty selectedBodyId = new SimpleIntegerProperty(-1);
    private final SimpleIntegerProperty focusedBodyId = new SimpleIntegerProperty(-1);
    private final SimpleIntegerProperty targetedBodyId = new SimpleIntegerProperty(-1);
    // ── Time state (§1.2, §2.3) ──

    private final SimpleDoubleProperty currentEt = new SimpleDoubleProperty(0.0);
    private final SimpleDoubleProperty timeRate = new SimpleDoubleProperty(KepplrConstants.DEFAULT_TIME_RATE);
    private final SimpleBooleanProperty paused = new SimpleBooleanProperty(false);
    private final SimpleDoubleProperty deltaSimSeconds = new SimpleDoubleProperty(0.0);

    // ── Camera state (§1.4, §1.5) ──

    private final SimpleObjectProperty<CameraFrame> cameraFrame = new SimpleObjectProperty<>(CameraFrame.INERTIAL);
    private final SimpleObjectProperty<CameraFrame> activeCameraFrame =
            new SimpleObjectProperty<>(CameraFrame.INERTIAL);
    private final SimpleBooleanProperty cameraFrameFallbackActive = new SimpleBooleanProperty(false);
    private final SimpleObjectProperty<double[]> cameraPositionJ2000 =
            new SimpleObjectProperty<>(new double[] {0.0, 0.0, 0.0});
    private final SimpleObjectProperty<double[]> cameraBodyFixedSpherical = new SimpleObjectProperty<>(null);

    // ── Synodic frame overrides (Step 19c) ──

    private final SimpleIntegerProperty synodicFrameFocusId = new SimpleIntegerProperty(-1);
    private final SimpleIntegerProperty synodicFrameSelectedId = new SimpleIntegerProperty(-1);

    // ── Render state (§7.3, §10.2) ──

    private final SimpleObjectProperty<List<BodyInView>> bodiesInView =
            new SimpleObjectProperty<>(Collections.emptyList());

    // ── Render quality (§9.4) ──

    private final SimpleObjectProperty<RenderQuality> renderQuality = new SimpleObjectProperty<>(RenderQuality.HIGH);

    // ── Transition state (Step 18) ──

    private final SimpleBooleanProperty transitionActive = new SimpleBooleanProperty(false);
    private final SimpleDoubleProperty transitionProgress = new SimpleDoubleProperty(0.0);
    private final SimpleDoubleProperty fovDeg = new SimpleDoubleProperty(KepplrConstants.CAMERA_FOV_Y_DEG);
    private final SimpleObjectProperty<float[]> cameraOrientationJ2000 =
            new SimpleObjectProperty<>(new float[] {0f, 0f, 0f, 1f});

    // ── Overlay state (Step 19b) ──

    private final ConcurrentHashMap<Integer, SimpleBooleanProperty> labelVisibility = new ConcurrentHashMap<>();
    private final SimpleBooleanProperty hudTimeVisible = new SimpleBooleanProperty(true);
    private final SimpleBooleanProperty hudInfoVisible = new SimpleBooleanProperty(true);
    private final ConcurrentHashMap<Integer, SimpleBooleanProperty> trailVisibility = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, SimpleDoubleProperty> trailDuration = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, SimpleIntegerProperty> trailReferenceBody = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<VectorKey, SimpleBooleanProperty> vectorVisibility = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, SimpleBooleanProperty> frustumVisibility = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, SimpleBooleanProperty> frustumPersistenceEnabled =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, SimpleObjectProperty<FrustumColor>> frustumColors =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, SimpleBooleanProperty> bodyVisibility = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<Integer> pendingFrustumFootprintClears =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static final int CLEAR_ALL_FRUSTUM_FOOTPRINTS = Integer.MIN_VALUE;

    {
        // Barycenters (NAIF 0–9) hidden by default
        for (int i = 0; i <= 9; i++) {
            bodyVisibility.put(i, new SimpleBooleanProperty(false));
        }
    }

    // ── HUD message (Step 28) ──

    /**
     * An immutable snapshot of a pending camera restore from a state string (Step 26).
     *
     * @param posJ2000 absolute heliocentric J2000 camera position in km [x, y, z]
     * @param orientJ2000 camera orientation quaternion [x, y, z, w]
     * @param fovDeg camera field of view in degrees
     * @param restoreDone optional latch to count down after the restore is applied and all state properties have been
     *     updated; {@code null} when no synchronisation is needed (e.g. tests)
     */
    public record PendingCameraRestore(
            double[] posJ2000, float[] orientJ2000, double fovDeg, java.util.concurrent.CountDownLatch restoreDone) {}

    private final AtomicReference<PendingCameraRestore> pendingCameraRestore = new AtomicReference<>();

    /** Post a camera restore to be applied on the next JME render frame. Thread-safe. */
    public void setPendingCameraRestore(PendingCameraRestore restore) {
        pendingCameraRestore.set(restore);
    }

    /** Atomically retrieve and clear the pending camera restore. Returns null if none pending. */
    public PendingCameraRestore consumePendingCameraRestore() {
        return pendingCameraRestore.getAndSet(null);
    }

    /** An immutable snapshot of a pending HUD message. */
    public record HudMessage(String text, double durationSeconds) {}

    private final AtomicReference<HudMessage> pendingHudMessage = new AtomicReference<>();

    /** Set a message to be displayed on the JME HUD. Thread-safe. */
    public void setHudMessage(String text, double durationSeconds) {
        pendingHudMessage.set(new HudMessage(text, durationSeconds));
    }

    /** Atomically retrieve and clear the pending HUD message. Returns null if none pending. */
    public HudMessage consumeHudMessage() {
        return pendingHudMessage.getAndSet(null);
    }

    /** Composite key for per-body per-type vector visibility. */
    public record VectorKey(int naifId, VectorType type) {
        @Override
        public boolean equals(Object o) {
            return o instanceof VectorKey that && this.naifId == that.naifId && Objects.equals(this.type, that.type);
        }

        @Override
        public int hashCode() {
            return 31 * naifId + Objects.hashCode(type);
        }
    }

    // ── SimulationState read-only interface ──

    @Override
    public ReadOnlyIntegerProperty selectedBodyIdProperty() {
        return selectedBodyId;
    }

    @Override
    public ReadOnlyIntegerProperty focusedBodyIdProperty() {
        return focusedBodyId;
    }

    @Override
    public ReadOnlyIntegerProperty targetedBodyIdProperty() {
        return targetedBodyId;
    }

    @Override
    public ReadOnlyDoubleProperty currentEtProperty() {
        return currentEt;
    }

    @Override
    public ReadOnlyDoubleProperty timeRateProperty() {
        return timeRate;
    }

    @Override
    public ReadOnlyBooleanProperty pausedProperty() {
        return paused;
    }

    @Override
    public ReadOnlyDoubleProperty deltaSimSecondsProperty() {
        return deltaSimSeconds;
    }

    @Override
    public ReadOnlyObjectProperty<CameraFrame> cameraFrameProperty() {
        return cameraFrame;
    }

    @Override
    public ReadOnlyObjectProperty<CameraFrame> activeCameraFrameProperty() {
        return activeCameraFrame;
    }

    @Override
    public ReadOnlyBooleanProperty cameraFrameFallbackActiveProperty() {
        return cameraFrameFallbackActive;
    }

    @Override
    public ReadOnlyObjectProperty<double[]> cameraPositionJ2000Property() {
        return cameraPositionJ2000;
    }

    @Override
    public ReadOnlyObjectProperty<double[]> cameraBodyFixedSphericalProperty() {
        return cameraBodyFixedSpherical;
    }

    @Override
    public ReadOnlyObjectProperty<List<BodyInView>> bodiesInViewProperty() {
        return bodiesInView;
    }

    @Override
    public ReadOnlyIntegerProperty synodicFrameFocusIdProperty() {
        return synodicFrameFocusId;
    }

    @Override
    public ReadOnlyIntegerProperty synodicFrameSelectedIdProperty() {
        return synodicFrameSelectedId;
    }

    @Override
    public ReadOnlyObjectProperty<RenderQuality> renderQualityProperty() {
        return renderQuality;
    }

    @Override
    public ReadOnlyBooleanProperty transitionActiveProperty() {
        return transitionActive;
    }

    @Override
    public ReadOnlyDoubleProperty transitionProgressProperty() {
        return transitionProgress;
    }

    @Override
    public ReadOnlyDoubleProperty fovDegProperty() {
        return fovDeg;
    }

    @Override
    public ReadOnlyObjectProperty<float[]> cameraOrientationJ2000Property() {
        return cameraOrientationJ2000;
    }

    // ── Setters (used by DefaultSimulationCommands and the simulation core) ──

    /** Set the selected body NAIF ID, or -1 for none. */
    public void setSelectedBodyId(int id) {
        selectedBodyId.set(id);
    }

    /** Set the focused body NAIF ID, or -1 for none. */
    public void setFocusedBodyId(int id) {
        focusedBodyId.set(id);
    }

    /** Set the targeted body NAIF ID, or -1 for none. */
    public void setTargetedBodyId(int id) {
        targetedBodyId.set(id);
    }

    /** Set the current simulation epoch (ET, TDB seconds past J2000). */
    public void setCurrentEt(double et) {
        currentEt.set(et);
    }

    /**
     * Set the simulation time rate (§2.3).
     *
     * <p>"3x" means {@code timeRate = 3.0} — this is an absolute assignment, never multiplicative.
     */
    public void setTimeRate(double rate) {
        timeRate.set(rate);
    }

    /** Set the paused state of the simulation clock. */
    public void setPaused(boolean value) {
        paused.set(value);
    }

    /**
     * Set the signed simulation seconds elapsed in the most recent frame.
     *
     * <p>Called by {@link kepplr.core.SimulationClock#advance()} on the JME thread each frame.
     */
    public void setDeltaSimSeconds(double delta) {
        deltaSimSeconds.set(delta);
    }

    /** Set the requested camera frame (§1.5). */
    public void setCameraFrame(CameraFrame frame) {
        cameraFrame.set(frame);
    }

    /** Set the camera frame actually in use this frame (§1.5). */
    public void setActiveCameraFrame(CameraFrame frame) {
        activeCameraFrame.set(frame);
    }

    /** Set whether the body-fixed frame fell back to inertial due to missing PCK data (§1.5). */
    public void setCameraFrameFallbackActive(boolean value) {
        cameraFrameFallbackActive.set(value);
    }

    /**
     * Set the heliocentric J2000 camera position in km.
     *
     * @param pos length-3 array [x, y, z] in km; must not be null
     */
    public void setCameraPositionJ2000(double[] pos) {
        cameraPositionJ2000.set(pos);
    }

    /**
     * Set the camera position in the focused body's body-fixed frame.
     *
     * @param sph {@code [r_km, lat_deg, lon_deg]}, or {@code null} if unavailable
     */
    public void setCameraBodyFixedSpherical(double[] sph) {
        cameraBodyFixedSpherical.set(sph);
    }

    /**
     * Set the list of bodies currently visible in the scene.
     *
     * <p>Called by {@code KepplrApp.simpleUpdate()} each frame with the result from {@code BodySceneManager.update()}.
     *
     * @param bodies non-null list, sorted by ascending distance; pass an empty list when nothing visible
     */
    public void setBodiesInView(List<BodyInView> bodies) {
        bodiesInView.set(bodies);
    }

    /** Set the explicit synodic frame focus NAIF ID, or -1 to use interaction state (Step 19c). */
    public void setSynodicFrameFocusId(int id) {
        synodicFrameFocusId.set(id);
    }

    /** Set the explicit synodic frame target NAIF ID, or -1 to use interaction state (Step 19c). */
    public void setSynodicFrameSelectedId(int id) {
        synodicFrameSelectedId.set(id);
    }

    /** Set the render quality preset (§9.4). */
    public void setRenderQuality(RenderQuality quality) {
        renderQuality.set(quality);
    }

    /**
     * Set whether a camera transition is currently active (Step 18).
     *
     * <p>Called each frame by {@link kepplr.camera.TransitionController} on the JME render thread.
     */
    public void setTransitionActive(boolean active) {
        transitionActive.set(active);
    }

    /**
     * Set the progress of the active camera transition as a value in {@code [0.0, 1.0]} (Step 18).
     *
     * <p>Called each frame by {@link kepplr.camera.TransitionController} on the JME render thread.
     */
    public void setTransitionProgress(double progress) {
        transitionProgress.set(progress);
    }

    /** Set the current camera field of view in degrees. Called each frame by KepplrApp on the JME render thread. */
    public void setFovDeg(double degrees) {
        fovDeg.set(degrees);
    }

    /**
     * Set the camera orientation in J2000 as a unit quaternion {@code [x, y, z, w]} (Step 26).
     *
     * @param orientation length-4 array; must not be null
     */
    public void setCameraOrientationJ2000(float[] orientation) {
        cameraOrientationJ2000.set(orientation);
    }

    // ── Overlay property accessors (Step 19b) ──

    @Override
    public ReadOnlyBooleanProperty labelVisibleProperty(int naifId) {
        return labelVisibility.computeIfAbsent(naifId, id -> new SimpleBooleanProperty(false));
    }

    @Override
    public ReadOnlyBooleanProperty hudTimeVisibleProperty() {
        return hudTimeVisible;
    }

    @Override
    public ReadOnlyBooleanProperty hudInfoVisibleProperty() {
        return hudInfoVisible;
    }

    @Override
    public ReadOnlyBooleanProperty trailVisibleProperty(int naifId) {
        return trailVisibility.computeIfAbsent(naifId, id -> new SimpleBooleanProperty(false));
    }

    @Override
    public ReadOnlyDoubleProperty trailDurationProperty(int naifId) {
        return trailDuration.computeIfAbsent(naifId, id -> new SimpleDoubleProperty(-1.0));
    }

    @Override
    public ReadOnlyIntegerProperty trailReferenceBodyProperty(int naifId) {
        return trailReferenceBody.computeIfAbsent(naifId, id -> new SimpleIntegerProperty(-1));
    }

    @Override
    public ReadOnlyBooleanProperty vectorVisibleProperty(int naifId, VectorType type) {
        return vectorVisibility.computeIfAbsent(new VectorKey(naifId, type), k -> new SimpleBooleanProperty(false));
    }

    // ── Overlay setters (Step 19b) ──

    public void setLabelVisible(int naifId, boolean visible) {
        labelVisibility
                .computeIfAbsent(naifId, id -> new SimpleBooleanProperty(false))
                .set(visible);
    }

    public void setHudTimeVisible(boolean visible) {
        hudTimeVisible.set(visible);
    }

    public void setHudInfoVisible(boolean visible) {
        hudInfoVisible.set(visible);
    }

    public void setTrailVisible(int naifId, boolean visible) {
        trailVisibility
                .computeIfAbsent(naifId, id -> new SimpleBooleanProperty(false))
                .set(visible);
    }

    public void setTrailDuration(int naifId, double seconds) {
        trailDuration
                .computeIfAbsent(naifId, id -> new SimpleDoubleProperty(-1.0))
                .set(seconds);
    }

    public void setTrailReferenceBody(int naifId, int referenceBodyId) {
        trailReferenceBody
                .computeIfAbsent(naifId, id -> new SimpleIntegerProperty(-1))
                .set(referenceBodyId);
    }

    public void setVectorVisible(int naifId, VectorType type, boolean visible) {
        vectorVisibility
                .computeIfAbsent(new VectorKey(naifId, type), k -> new SimpleBooleanProperty(false))
                .set(visible);
    }

    /** Returns a snapshot of all label-visible entries. Package-private for render-loop access. */
    public ConcurrentHashMap<Integer, SimpleBooleanProperty> getLabelVisibilityMap() {
        return labelVisibility;
    }

    /** Returns a snapshot of all trail-visible entries. Package-private for render-loop access. */
    public ConcurrentHashMap<Integer, SimpleBooleanProperty> getTrailVisibilityMap() {
        return trailVisibility;
    }

    /** Returns the trail duration map. */
    public ConcurrentHashMap<Integer, SimpleDoubleProperty> getTrailDurationMap() {
        return trailDuration;
    }

    /** Returns the vector visibility map. */
    public ConcurrentHashMap<VectorKey, SimpleBooleanProperty> getVectorVisibilityMap() {
        return vectorVisibility;
    }

    @Override
    public ReadOnlyBooleanProperty frustumVisibleProperty(int naifCode) {
        return frustumVisibility.computeIfAbsent(naifCode, id -> new SimpleBooleanProperty(false));
    }

    @Override
    public ReadOnlyBooleanProperty frustumPersistenceEnabledProperty(int naifCode) {
        return frustumPersistenceEnabled.computeIfAbsent(naifCode, id -> new SimpleBooleanProperty(false));
    }

    @Override
    public ReadOnlyObjectProperty<FrustumColor> frustumColorProperty(int naifCode) {
        return frustumColors.computeIfAbsent(naifCode, id -> new SimpleObjectProperty<>(null));
    }

    public void setFrustumVisible(int naifCode, boolean visible) {
        frustumVisibility
                .computeIfAbsent(naifCode, id -> new SimpleBooleanProperty(false))
                .set(visible);
    }

    public void setFrustumPersistenceEnabled(int naifCode, boolean enabled) {
        frustumPersistenceEnabled
                .computeIfAbsent(naifCode, id -> new SimpleBooleanProperty(false))
                .set(enabled);
    }

    public void setFrustumColor(int naifCode, FrustumColor color) {
        frustumColors
                .computeIfAbsent(naifCode, id -> new SimpleObjectProperty<>(null))
                .set(Objects.requireNonNull(color, "color"));
    }

    /** Returns the frustum visibility map. Package-private for render-loop and bridge access. */
    public ConcurrentHashMap<Integer, SimpleBooleanProperty> getFrustumVisibilityMap() {
        return frustumVisibility;
    }

    /** Returns the frustum persistence-enabled map. Package-private for render-loop access. */
    public ConcurrentHashMap<Integer, SimpleBooleanProperty> getFrustumPersistenceEnabledMap() {
        return frustumPersistenceEnabled;
    }

    /** Returns the frustum color map. Package-private for render-loop access. */
    public ConcurrentHashMap<Integer, SimpleObjectProperty<FrustumColor>> getFrustumColorMap() {
        return frustumColors;
    }

    public void requestClearFrustumFootprints(int naifCode) {
        pendingFrustumFootprintClears.add(naifCode);
    }

    public void requestClearAllFrustumFootprints() {
        pendingFrustumFootprintClears.add(CLEAR_ALL_FRUSTUM_FOOTPRINTS);
    }

    public Integer pollPendingFrustumFootprintClear() {
        return pendingFrustumFootprintClears.poll();
    }

    @Override
    public ReadOnlyBooleanProperty bodyVisibleProperty(int naifId) {
        return bodyVisibility.computeIfAbsent(naifId, id -> new SimpleBooleanProperty(true));
    }

    public void setBodyVisible(int naifId, boolean visible) {
        bodyVisibility
                .computeIfAbsent(naifId, id -> new SimpleBooleanProperty(true))
                .set(visible);
    }

    /** Returns the body visibility map. */
    public ConcurrentHashMap<Integer, SimpleBooleanProperty> getBodyVisibilityMap() {
        return bodyVisibility;
    }

    /**
     * Clear all overlay visibility state — labels, trails, trail durations, vectors, frustums, and body visibility.
     *
     * <p>Called during configuration reload to give scripts a clean slate. After clearing, barycenters (NAIF 0–9) are
     * re-hidden by default.
     */
    public void clearOverlayState() {
        labelVisibility.clear();
        trailVisibility.clear();
        trailDuration.clear();
        trailReferenceBody.clear();
        vectorVisibility.clear();
        frustumVisibility.clear();
        frustumPersistenceEnabled.clear();
        frustumColors.clear();
        pendingFrustumFootprintClears.clear();
        bodyVisibility.clear();
        // Restore default: barycenters hidden
        for (int i = 0; i <= 9; i++) {
            bodyVisibility.put(i, new SimpleBooleanProperty(false));
        }
    }
}
