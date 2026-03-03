package kepplr.commands;

import kepplr.state.DefaultSimulationState;

/**
 * Concrete implementation of {@link SimulationCommands} that applies state-transition rules directly to a {@link
 * DefaultSimulationState} (REDESIGN.md §4, CLAUDE.md Rule 2).
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
 *   <li>{@code setTimeRate(r)}: absolute assignment — "3x" means {@code timeRate = 3.0} (§2.3)
 *   <li>{@code setPaused(b)}: direct assignment (§1.2)
 * </ul>
 */
public final class DefaultSimulationCommands implements SimulationCommands {

    private final DefaultSimulationState state;

    /**
     * @param state mutable state object this instance will write to
     */
    public DefaultSimulationCommands(DefaultSimulationState state) {
        this.state = state;
    }

    /**
     * Select a body for HUD display only (§4.3). Does not change focused, targeted, or tracked state.
     */
    @Override
    public void selectBody(int naifId) {
        state.setSelectedBodyId(naifId);
    }

    /**
     * Focus the camera on a body (§4.5). Implicitly selects and targets the same body. Clears tracking and the
     * tracking anchor because focusing implies a new point-at target (§4.6).
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
     * Target a body — "point at" (§4.4). Implicitly selects the body. Clears tracking and the tracking anchor because
     * a new point-at disables tracking (§4.6).
     */
    @Override
    public void targetBody(int naifId) {
        state.setSelectedBodyId(naifId);
        state.setTargetedBodyId(naifId);
        state.setTrackedBodyId(-1);
        state.setTrackingAnchor(null);
    }

    /**
     * Track a body — lock its screen position (§4.6). Resets the tracking anchor to {@code null} so the JME render
     * loop establishes a fresh anchor on the next frame.
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
     * <p>"3x" means {@code timeRate = 3.0}, <b>not</b> "multiply current rate by 3".
     */
    @Override
    public void setTimeRate(double simSecondsPerWallSecond) {
        state.setTimeRate(simSecondsPerWallSecond);
    }

    /** Pause or unpause the simulation clock (§1.2). */
    @Override
    public void setPaused(boolean paused) {
        state.setPaused(paused);
    }
}
