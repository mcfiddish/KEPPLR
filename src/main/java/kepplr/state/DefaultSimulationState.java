package kepplr.state;

import java.util.Collections;
import java.util.List;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import kepplr.camera.CameraFrame;
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
    private final SimpleIntegerProperty trackedBodyId = new SimpleIntegerProperty(-1);

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

    // ── Render state (§7.3, §10.2) ──

    private final SimpleObjectProperty<List<BodyInView>> bodiesInView =
            new SimpleObjectProperty<>(Collections.emptyList());

    // ── Tracking anchor (§4.6) ──

    private final SimpleObjectProperty<double[]> trackingAnchor = new SimpleObjectProperty<>(null);

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
    public ReadOnlyIntegerProperty trackedBodyIdProperty() {
        return trackedBodyId;
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
    public ReadOnlyObjectProperty<double[]> trackingAnchorProperty() {
        return trackingAnchor;
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

    /** Set the tracked body NAIF ID, or -1 for none. */
    public void setTrackedBodyId(int id) {
        trackedBodyId.set(id);
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

    /**
     * Set the tracking anchor to normalized screen coordinates, or {@code null} to clear it.
     *
     * <p>Called by the JME render thread each frame while a body is being tracked, and by
     * {@link kepplr.commands.DefaultSimulationCommands} on any transition that ends tracking.
     *
     * @param anchor length-2 array [normalizedX, normalizedY], or {@code null}
     */
    public void setTrackingAnchor(double[] anchor) {
        trackingAnchor.set(anchor);
    }
}
