package kepplr.state;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import kepplr.util.KepplrConstants;

/**
 * Concrete, mutable implementation of {@link SimulationState} (REDESIGN.md §4, §10).
 *
 * <p>Holds JavaFX properties for all UI-visible simulation state. The {@link SimulationState} interface exposes only
 * read-only views; this class provides public setters so the simulation core and {@link
 * kepplr.commands.DefaultSimulationCommands} can write state without violating the one-direction flow (CLAUDE.md Rule
 * 2).
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

    // ── Camera state (§1.4, §1.5) ──

    private final SimpleObjectProperty<String> cameraFrame = new SimpleObjectProperty<>("J2000");
    private final SimpleObjectProperty<double[]> cameraPositionJ2000 =
            new SimpleObjectProperty<>(new double[] {0.0, 0.0, 0.0});

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
    public ReadOnlyObjectProperty<String> cameraFrameProperty() {
        return cameraFrame;
    }

    @Override
    public ReadOnlyObjectProperty<double[]> cameraPositionJ2000Property() {
        return cameraPositionJ2000;
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

    /** Set the active camera frame name (e.g., "J2000", "BODY_FIXED", "SYNODIC"). */
    public void setCameraFrame(String frame) {
        cameraFrame.set(frame);
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
