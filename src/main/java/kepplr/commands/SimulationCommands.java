package kepplr.commands;

/**
 * All user-initiated actions enter the simulation through this interface
 * (REDESIGN.md §4, CLAUDE.md Rule 2).
 *
 * <p>JavaFX controllers call these methods to forward user input to the
 * simulation core. Implementations may queue, validate, or execute commands
 * immediately — that is the core's concern, not the UI's.
 *
 * <p>Body references use NAIF IDs ({@code int}).
 */
public interface SimulationCommands {

    // ── Interaction commands (§4.2–§4.6) ──

    /**
     * Select a body for HUD display. Does <b>not</b> change camera pose (§4.3).
     *
     * @param naifId NAIF ID of the body to select
     */
    void selectBody(int naifId);

    /**
     * Focus the camera on a body (orbit camera mode, §4.5).
     *
     * <p>Focusing also selects and targets the body.
     *
     * @param naifId NAIF ID of the body to focus
     */
    void focusBody(int naifId);

    /**
     * Target a body — "point at" (§4.4).
     *
     * <p>The camera orientation tracks the target body center while
     * camera position remains fixed. Targeting also selects the body
     * and disables tracking (§4.6).
     *
     * @param naifId NAIF ID of the body to target
     */
    void targetBody(int naifId);

    /**
     * Track a body — lock its screen position (§4.6).
     *
     * <p>The tracked body maintains a constant normalized screen position
     * over time.
     *
     * @param naifId NAIF ID of the body to track
     */
    void trackBody(int naifId);

    /**
     * Stop tracking the currently tracked body (§4.6).
     */
    void stopTracking();

    // ── Time commands (§1.2, §2.3) ──

    /**
     * Set the simulation time rate as an absolute value (§2.3).
     *
     * <p>"3x" means {@code timeRate = 3.0}, <b>not</b> "multiply current rate by 3".
     *
     * @param simSecondsPerWallSecond simulation seconds per wall-clock second
     */
    void setTimeRate(double simSecondsPerWallSecond);

    /**
     * Pause or unpause the simulation clock (§1.2).
     *
     * <p>When paused, ET must not change.
     *
     * @param paused {@code true} to pause, {@code false} to resume
     */
    void setPaused(boolean paused);
}
