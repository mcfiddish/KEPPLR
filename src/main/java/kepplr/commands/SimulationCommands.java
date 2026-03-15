package kepplr.commands;

import kepplr.camera.CameraFrame;
import kepplr.render.RenderQuality;

/**
 * All user-initiated actions enter the simulation through this interface (REDESIGN.md §4, CLAUDE.md Rule 2).
 *
 * <p>JavaFX controllers call these methods to forward user input to the simulation core. Implementations may queue,
 * validate, or execute commands immediately — that is the core's concern, not the UI's.
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
     * <p>The camera orientation tracks the target body center while camera position remains fixed. Targeting also
     * selects the body and disables tracking (§4.6).
     *
     * @param naifId NAIF ID of the body to target
     */
    void targetBody(int naifId);

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

    /**
     * Jump the simulation clock to the specified ET (§1.2).
     *
     * <p>The time rate and paused state are preserved; only the current epoch changes.
     *
     * @param et target ET (TDB seconds past J2000)
     */
    void setET(double et);

    /**
     * Convert a UTC string to ET and jump the simulation clock to that epoch (§1.2).
     *
     * <p>The time rate and paused state are preserved.
     *
     * @param utcString UTC time string in a format accepted by Picante (e.g., {@code "2015 Jul 14 07:59:00"})
     */
    void setUTC(String utcString);

    // ── Camera transition commands (Step 18) ──

    /**
     * Slew the camera orientation to point at the given body (Step 18).
     *
     * <p>Non-blocking. Initiates a transition and returns immediately. If a transition is already in progress, it is
     * cancelled and the new slew begins from the camera's current orientation. Camera position is not changed.
     *
     * <p>If {@code durationSeconds} is zero or negative, the camera snaps to the end orientation instantly on the next
     * frame.
     *
     * @param naifId NAIF ID of the body to point at
     * @param durationSeconds slew duration in seconds
     */
    void pointAt(int naifId, double durationSeconds);

    /**
     * Translate the camera toward the given body until it subtends the requested apparent radius (Step 18).
     *
     * <p>Non-blocking. If a {@code pointAt} transition is currently in progress, the {@code goTo} is queued and begins
     * automatically when the {@code pointAt} completes. Otherwise the translation begins immediately.
     *
     * <p>No light-time correction is applied to the translation path (per KEPPLR_Roadmap.md).
     *
     * @param naifId NAIF ID of the body to approach
     * @param apparentRadiusDeg desired apparent radius in degrees at end of translation
     * @param durationSeconds translation duration in seconds
     */
    void goTo(int naifId, double apparentRadiusDeg, double durationSeconds);

    // ── Camera frame commands (§1.5) ──

    /**
     * Switch the active camera frame (§1.5).
     *
     * @param frame the desired camera frame
     */
    void setCameraFrame(CameraFrame frame);

    // ── Render quality commands (§9.4) ──

    /**
     * Set the render quality preset (§9.4).
     *
     * <p>Adjusts shadow fidelity, trail sample density, and star magnitude cutoff. Takes effect on the next JME render
     * frame — managers read the property directly from {@link kepplr.state.SimulationState}.
     *
     * @param quality the desired quality preset; must not be null
     */
    void setRenderQuality(RenderQuality quality);
}
