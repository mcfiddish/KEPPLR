package kepplr.commands;

import kepplr.camera.CameraFrame;
import kepplr.camera.TransitionController;
import kepplr.core.SimulationClock;
import kepplr.render.RenderQuality;
import kepplr.render.vector.VectorType;
import kepplr.state.DefaultSimulationState;
import kepplr.util.KepplrConstants;

/**
 * Concrete implementation of {@link SimulationCommands} that applies state-transition rules directly to a
 * {@link DefaultSimulationState} (REDESIGN.md §4, CLAUDE.md Rule 2).
 *
 * <p>Holds a reference to {@link DefaultSimulationState} (not the read-only {@link kepplr.state.SimulationState}
 * interface) so it can call the mutable setters. This keeps the one-direction state flow intact: the UI only sees the
 * read-only interface; commands write via this class.
 *
 * <h3>State-transition rules (§4.3–§4.6)</h3>
 *
 * <ul>
 *   <li>{@code selectBody(id)}: sets selected only; no camera change (§4.3)
 *   <li>{@code focusBody(id)}: sets selected, focused, and targeted (§4.5)
 *   <li>{@code targetBody(id)}: sets selected and targeted (§4.4)
 *   <li>{@code setTimeRate(r)}: delegates to {@link SimulationClock} — absolute, no-jump (§2.3)
 *   <li>{@code setPaused(b)}: delegates to {@link SimulationClock} — clamp/resume logic (§1.2)
 *   <li>{@code setET(et)}: delegates to {@link SimulationClock} (§1.2)
 *   <li>{@code setUTC(s)}: converts via Picante then delegates to {@link SimulationClock} (§1.2)
 * </ul>
 */
public final class DefaultSimulationCommands implements SimulationCommands {

    private final DefaultSimulationState state;
    private final SimulationClock clock;
    private final TransitionController transitionController;

    /**
     * @param state mutable state object this instance will write to for interaction commands
     * @param clock simulation clock this instance will delegate time commands to
     * @param transitionController camera transition controller; receives {@code pointAt}/{@code goTo} requests
     */
    public DefaultSimulationCommands(
            DefaultSimulationState state, SimulationClock clock, TransitionController transitionController) {
        this.state = state;
        this.clock = clock;
        this.transitionController = transitionController;
    }

    /** Select a body for HUD display only (§4.3). Does not change focused or targeted state. */
    @Override
    public void selectBody(int naifId) {
        state.setSelectedBodyId(naifId);
    }

    /**
     * Focus the camera on a body (§4.5). Implicitly selects and targets the same body.
     *
     * <p>Initiates a {@code pointAt} slew followed by a {@code goTo} translation (Step 18). The {@code goTo} is queued
     * and begins automatically when the {@code pointAt} completes.
     */
    @Override
    public void focusBody(int naifId) {
        state.setSelectedBodyId(naifId);
        state.setFocusedBodyId(naifId);
        state.setTargetedBodyId(naifId);
        transitionController.requestPointAt(naifId, KepplrConstants.DEFAULT_SLEW_DURATION_SECONDS);
        transitionController.requestGoTo(
                naifId,
                KepplrConstants.DEFAULT_GOTO_APPARENT_RADIUS_DEG,
                KepplrConstants.DEFAULT_GOTO_DURATION_SECONDS);
    }

    /**
     * Target a body — "point at" (§4.4). Implicitly selects the body.
     *
     * <p>Initiates a {@code pointAt} slew (Step 18).
     */
    @Override
    public void targetBody(int naifId) {
        state.setSelectedBodyId(naifId);
        state.setTargetedBodyId(naifId);
        transitionController.requestPointAt(naifId, KepplrConstants.DEFAULT_SLEW_DURATION_SECONDS);
    }

    /** Initiate a {@code pointAt} slew (Step 18). Delegates to {@link TransitionController}. */
    @Override
    public void pointAt(int naifId, double durationSeconds) {
        transitionController.requestPointAt(naifId, durationSeconds);
    }

    /** Initiate a {@code goTo} translation (Step 18). Delegates to {@link TransitionController}. */
    @Override
    public void goTo(int naifId, double apparentRadiusDeg, double durationSeconds) {
        transitionController.requestGoTo(naifId, apparentRadiusDeg, durationSeconds);
    }

    // ── Camera navigation commands (Step 19c) ──

    @Override
    public void zoom(double factor, double durationSeconds) {
        transitionController.requestZoom(factor, durationSeconds);
    }

    @Override
    public void setFov(double degrees, double durationSeconds) {
        transitionController.requestFov(degrees, durationSeconds);
    }

    @Override
    public void orbit(double rightDegrees, double upDegrees, double durationSeconds) {
        transitionController.requestOrbit(rightDegrees, upDegrees, durationSeconds);
    }

    @Override
    public void tilt(double degrees, double durationSeconds) {
        transitionController.requestTilt(degrees, durationSeconds);
    }

    @Override
    public void yaw(double degrees, double durationSeconds) {
        transitionController.requestYaw(degrees, durationSeconds);
    }

    @Override
    public void roll(double degrees, double durationSeconds) {
        transitionController.requestRoll(degrees, durationSeconds);
    }

    @Override
    public void setCameraPosition(double x, double y, double z, double durationSeconds) {
        transitionController.requestCameraPosition(x, y, z, -1, durationSeconds);
    }

    @Override
    public void setCameraPosition(double x, double y, double z, int originNaifId, double durationSeconds) {
        transitionController.requestCameraPosition(x, y, z, originNaifId, durationSeconds);
    }

    @Override
    public void setCameraLookDirection(
            double lookX, double lookY, double lookZ, double upX, double upY, double upZ, double durationSeconds) {
        transitionController.requestCameraLookDirection(lookX, lookY, lookZ, upX, upY, upZ, durationSeconds);
    }

    @Override
    public void setSynodicFrame(int focusNaifId, int targetNaifId) {
        state.setSynodicFrameFocusId(focusNaifId);
        state.setSynodicFrameTargetId(targetNaifId);
        state.setCameraFrame(CameraFrame.SYNODIC);
    }

    /**
     * Set the simulation time rate as an absolute value (§2.3).
     *
     * <p>"3x" means {@code timeRate = 3.0}, <b>not</b> "multiply current rate by 3". Delegates to
     * {@link SimulationClock} which replaces the anchor atomically so no ET jump occurs.
     */
    @Override
    public void setTimeRate(double simSecondsPerWallSecond) {
        clock.setTimeRate(simSecondsPerWallSecond);
    }

    /** Pause or unpause the simulation clock (§1.2). Delegates to {@link SimulationClock}. */
    @Override
    public void setPaused(boolean paused) {
        clock.setPaused(paused);
    }

    /** Jump the simulation clock to the specified ET (§1.2). Delegates to {@link SimulationClock}. */
    @Override
    public void setET(double et) {
        clock.setET(et);
    }

    /**
     * Convert a UTC string to ET and jump the clock to that epoch (§1.2).
     *
     * <p>Delegates to {@link SimulationClock#setUTC(String)} which acquires ephemeris at point-of-use (CLAUDE.md Rule
     * 3).
     */
    @Override
    public void setUTC(String utcString) {
        clock.setUTC(utcString);
    }

    /**
     * Switch the active camera frame (§1.5). Clears synodic frame override IDs so the frame reverts to using
     * interaction state for focus/target.
     */
    @Override
    public void setCameraFrame(CameraFrame frame) {
        state.setSynodicFrameFocusId(-1);
        state.setSynodicFrameTargetId(-1);
        state.setCameraFrame(frame);
    }

    /** Set the render quality preset (§9.4). Takes effect on the next JME frame. */
    @Override
    public void setRenderQuality(RenderQuality quality) {
        state.setRenderQuality(quality);
    }

    // ── Overlay commands (Step 19b) ──

    @Override
    public void setLabelVisible(int naifId, boolean visible) {
        state.setLabelVisible(naifId, visible);
    }

    @Override
    public void setHudTimeVisible(boolean visible) {
        state.setHudTimeVisible(visible);
    }

    @Override
    public void setHudInfoVisible(boolean visible) {
        state.setHudInfoVisible(visible);
    }

    @Override
    public void setTrailVisible(int naifId, boolean visible) {
        state.setTrailVisible(naifId, visible);
    }

    @Override
    public void setTrailDuration(int naifId, double seconds) {
        state.setTrailDuration(naifId, seconds);
    }

    @Override
    public void setVectorVisible(int naifId, VectorType type, boolean visible) {
        state.setVectorVisible(naifId, type, visible);
    }

    // ── Transition control (Step 20) ──

    @Override
    public void cancelTransition() {
        transitionController.requestCancel();
    }
}
