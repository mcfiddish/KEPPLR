package kepplr.commands;

import kepplr.camera.CameraFrame;
import kepplr.core.SimulationClock;
import kepplr.state.DefaultSimulationState;

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
 *   <li>{@code focusBody(id)}: sets selected, focused, and targeted; clears tracked (§4.5, §4.6)
 *   <li>{@code targetBody(id)}: sets selected and targeted; clears tracked (§4.4, §4.6)
 *   <li>{@code trackBody(id)}: sets tracked only (§4.6)
 *   <li>{@code stopTracking()}: clears tracked (§4.6)
 *   <li>{@code setTimeRate(r)}: delegates to {@link SimulationClock} — absolute, no-jump (§2.3)
 *   <li>{@code setPaused(b)}: delegates to {@link SimulationClock} — clamp/resume logic (§1.2)
 *   <li>{@code setET(et)}: delegates to {@link SimulationClock} (§1.2)
 *   <li>{@code setUTC(s)}: converts via Picante then delegates to {@link SimulationClock} (§1.2)
 * </ul>
 */
public final class DefaultSimulationCommands implements SimulationCommands {

    private final DefaultSimulationState state;
    private final SimulationClock clock;

    /**
     * @param state mutable state object this instance will write to for interaction commands
     * @param clock simulation clock this instance will delegate time commands to
     */
    public DefaultSimulationCommands(DefaultSimulationState state, SimulationClock clock) {
        this.state = state;
        this.clock = clock;
    }

    /** Select a body for HUD display only (§4.3). Does not change focused, targeted, or tracked state. */
    @Override
    public void selectBody(int naifId) {
        state.setSelectedBodyId(naifId);
    }

    /**
     * Focus the camera on a body (§4.5). Implicitly selects and targets the same body. Clears tracking and the tracking
     * anchor because focusing implies a new point-at target (§4.6).
     */
    @Override
    public void focusBody(int naifId) {
        state.setSelectedBodyId(naifId);
        state.setFocusedBodyId(naifId);
        state.setTargetedBodyId(naifId);
        state.setTrackedBodyId(-1);
        state.setTrackingAnchor(null);
    }

    /**
     * Target a body — "point at" (§4.4). Implicitly selects the body. Clears tracking and the tracking anchor because a
     * new point-at disables tracking (§4.6).
     */
    @Override
    public void targetBody(int naifId) {
        state.setSelectedBodyId(naifId);
        state.setTargetedBodyId(naifId);
        state.setTrackedBodyId(-1);
        state.setTrackingAnchor(null);
    }

    /**
     * Track a body — lock its screen position (§4.6). Resets the tracking anchor to {@code null} so the JME render loop
     * establishes a fresh anchor on the next frame.
     */
    @Override
    public void trackBody(int naifId) {
        state.setTrackedBodyId(naifId);
        state.setTrackingAnchor(null);
    }

    /** Stop tracking the currently tracked body (§4.6). Clears both the tracked body ID and the anchor. */
    @Override
    public void stopTracking() {
        state.setTrackedBodyId(-1);
        state.setTrackingAnchor(null);
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

    /** Switch the active camera frame (§1.5). All three values are now supported. */
    @Override
    public void setCameraFrame(CameraFrame frame) {
        state.setCameraFrame(frame);
    }
}
