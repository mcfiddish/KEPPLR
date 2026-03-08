package kepplr.state;

/**
 * Snapshot of a single visible body for the status display.
 *
 * <p>Collected by {@link kepplr.render.body.BodySceneManager} each frame and pushed through
 * {@link SimulationState#bodiesInViewProperty()} to the FX bridge. A body appears in this list when it is not culled
 * (apparent radius &ge; 2 px, or a non-satellite sprite).
 *
 * @param name SPICE body name (e.g. {@code "EARTH"}, {@code "MOON"})
 * @param naifId integer NAIF ID
 * @param distanceKm camera-to-body-center distance in km
 */
public record BodyInView(String name, int naifId, double distanceKm) {}
