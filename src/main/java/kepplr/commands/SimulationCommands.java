package kepplr.commands;

import kepplr.camera.CameraFrame;
import kepplr.render.RenderQuality;
import kepplr.render.vector.VectorType;

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

    // ── Camera navigation commands (Step 19c) ──
    //
    // All non-blocking; all use TransitionController.  If durationSeconds is zero
    // or negative, the camera snaps instantly on the next frame.

    /**
     * Change the camera distance from the focus body by a multiplicative factor.
     *
     * <p>Example: {@code zoom(2.0, 1.0)} doubles the distance over one second. {@code zoom(0.5, 1.0)} halves it. Factor
     * must be positive. Clamped to [{@code 1.1 × focusBodyRadius}, {@code 1e15 km}] per zoom rules in §10.
     *
     * @param factor multiplicative distance factor; must be positive
     * @param durationSeconds transition duration in wall-clock seconds
     */
    void zoom(double factor, double durationSeconds);

    /**
     * Set the camera field of view.
     *
     * <p>Example: {@code setFov(45.0, 1.0)} transitions to a 45-degree FOV over one second. Clamped to
     * [{@link kepplr.util.KepplrConstants#FOV_MIN_DEG}, {@link kepplr.util.KepplrConstants#FOV_MAX_DEG}].
     *
     * @param degrees desired field of view in degrees
     * @param durationSeconds transition duration in wall-clock seconds
     */
    void setFov(double degrees, double durationSeconds);

    /**
     * Orbit the camera around the focus body by the given camera-relative angles.
     *
     * <p>Equivalent to a right-drag gesture. Positive {@code rightDegrees} orbits clockwise when viewed from above.
     * Positive {@code upDegrees} orbits toward the camera's screen-up direction.
     *
     * <p>Example: {@code orbit(45.0, 0.0, 2.0)} orbits 45 degrees to the right over two seconds.
     *
     * @param rightDegrees degrees to orbit around the camera's screen-up axis
     * @param upDegrees degrees to orbit around the camera's screen-right axis
     * @param durationSeconds transition duration in wall-clock seconds
     */
    void orbit(double rightDegrees, double upDegrees, double durationSeconds);

    /**
     * Tilt the camera in place around its screen-right axis.
     *
     * <p>Example: {@code tilt(10.0, 0.5)} tilts up 10 degrees over half a second.
     *
     * @param degrees tilt angle in degrees; positive tilts up
     * @param durationSeconds transition duration in wall-clock seconds
     */
    void tilt(double degrees, double durationSeconds);

    /**
     * Yaw the camera in place around its screen-up axis.
     *
     * <p>Example: {@code yaw(45.0, 1.0)} rotates the camera's look direction 45 degrees to the right over one second.
     *
     * @param degrees yaw angle in degrees; positive yaws right
     * @param durationSeconds transition duration in wall-clock seconds
     */
    void yaw(double degrees, double durationSeconds);

    /**
     * Roll the camera around its look axis.
     *
     * <p>Example: {@code roll(90.0, 1.0)} rotates the camera's up vector 90 degrees clockwise over one second.
     *
     * @param degrees roll angle in degrees; positive rolls clockwise
     * @param durationSeconds transition duration in wall-clock seconds
     */
    void roll(double degrees, double durationSeconds);

    /**
     * Set the camera position relative to the current focus body in the active camera frame.
     *
     * <p>The offset vector is interpreted in the active camera frame and transformed to J2000 internally: in INERTIAL,
     * (0,0,10000) is +Z in J2000; in BODY_FIXED, it is the body-fixed +Z axis (north pole); in SYNODIC, it is the
     * synodic +Z axis.
     *
     * <p>Example: {@code setCameraPosition(0, 0, 10000, 2.0)} — in body-fixed frame, moves the camera to 10,000 km
     * above the focus body's north pole over two seconds.
     *
     * @param x x component in km
     * @param y y component in km
     * @param z z component in km
     * @param durationSeconds transition duration in wall-clock seconds
     */
    void setCameraPosition(double x, double y, double z, double durationSeconds);

    /**
     * Set the camera position relative to an explicit origin body in the active camera frame.
     *
     * <p>Does not change the focused body. Useful for positioning the camera relative to a body other than the current
     * focus.
     *
     * <p>Example: {@code setCameraPosition(0, 0, 50000, 301, 3.0)} moves the camera to 50,000 km above the Moon
     * regardless of which body is currently focused.
     *
     * @param x x component in km
     * @param y y component in km
     * @param z z component in km
     * @param originNaifId NAIF ID of the origin body
     * @param durationSeconds transition duration in wall-clock seconds
     */
    void setCameraPosition(double x, double y, double z, int originNaifId, double durationSeconds);

    /**
     * Set the camera look direction and up vector in the active camera frame.
     *
     * <p>Vectors are interpreted in the active camera frame and transformed to J2000 internally: in INERTIAL, (1,0,0)
     * is the vernal equinox; in SYNODIC, (1,0,0) points toward the synodic target body; in BODY_FIXED, (1,0,0) is the
     * body-fixed +X axis.
     *
     * <p>Vectors need not be normalized — they are normalized internally. The up vector must not be parallel to the
     * look vector.
     *
     * <p>Example — in synodic frame, look toward the target body with Z up:
     *
     * <pre>
     *   setCameraOrientation(1, 0, 0,   // look toward synodic +X (target body)
     *                          0, 0, 1,   // up toward synodic +Z
     *                          2.0);
     * </pre>
     *
     * @param lookX x component of look direction
     * @param lookY y component of look direction
     * @param lookZ z component of look direction
     * @param upX x component of up vector
     * @param upY y component of up vector
     * @param upZ z component of up vector
     * @param durationSeconds transition duration in wall-clock seconds
     */
    void setCameraOrientation(
            double lookX, double lookY, double lookZ, double upX, double upY, double upZ, double durationSeconds);

    /**
     * Switch to the synodic camera frame defined by explicit focus and target bodies, without changing focused,
     * targeted, or selected body state.
     *
     * <p>Use this when a script needs a specific synodic view without disturbing interaction state. To switch to the
     * synodic frame using the current SimulationState focus and target, use {@code setCameraFrame(CameraFrame.SYNODIC)}
     * instead.
     *
     * <p>Example — Earth-Moon synodic view:
     *
     * <pre>
     *   setSynodicFrame(399, 301); // focus=Earth, target=Moon
     * </pre>
     *
     * @param focusNaifId NAIF ID of the focus body defining the frame origin
     * @param targetNaifId NAIF ID of the target body defining the +X axis
     */
    void setSynodicFrame(int focusNaifId, int targetNaifId);

    // ── Cinematic camera commands (Step 24) ──
    //
    // All non-blocking; all use TransitionController.  If durationSeconds is zero
    // or negative, the translation is applied instantly on the next frame.
    // Camera orientation is not modified.

    /**
     * Translate the camera along its current screen-right axis by {@code km} kilometres over {@code durationSeconds}.
     *
     * <p>Positive values move right; negative move left. Non-blocking. The translation axis is captured at the moment
     * the command is issued and held fixed for the duration.
     *
     * @param km distance to translate in kilometres
     * @param durationSeconds transition duration in wall-clock seconds
     */
    void truck(double km, double durationSeconds);

    /**
     * Translate the camera along its current screen-up axis by {@code km} kilometres over {@code durationSeconds}.
     *
     * <p>Positive values move up; negative move down. Non-blocking. The translation axis is captured at the moment the
     * command is issued and held fixed for the duration.
     *
     * @param km distance to translate in kilometres
     * @param durationSeconds transition duration in wall-clock seconds
     */
    void crane(double km, double durationSeconds);

    /**
     * Translate the camera along its current look direction by {@code km} kilometres over {@code durationSeconds}.
     *
     * <p>Positive values move forward; negative move back. Non-blocking. Note: dolly is a pure spatial translation — it
     * does not modify apparent radius relative to any body, unlike {@link #goTo}.
     *
     * @param km distance to translate in kilometres
     * @param durationSeconds transition duration in wall-clock seconds
     */
    void dolly(double km, double durationSeconds);

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

    // ── Overlay commands (REDESIGN.md §7.8, §7.9, §7.5, §7.6, Step 19b) ──

    /**
     * Toggle label visibility for a specific body (§7.8).
     *
     * @param naifId NAIF ID of the body
     * @param visible {@code true} to show the label, {@code false} to hide it
     */
    void setLabelVisible(int naifId, boolean visible);

    /**
     * Toggle the HUD time display (§7.9).
     *
     * @param visible {@code true} to show the time HUD, {@code false} to hide it
     */
    void setHudTimeVisible(boolean visible);

    /**
     * Toggle the HUD info display (§7.9).
     *
     * @param visible {@code true} to show the info HUD, {@code false} to hide it
     */
    void setHudInfoVisible(boolean visible);

    /**
     * Toggle trail visibility for a specific body (§7.5).
     *
     * @param naifId NAIF ID of the body
     * @param visible {@code true} to show the trail, {@code false} to hide it
     */
    void setTrailVisible(int naifId, boolean visible);

    /**
     * Set the trail duration for a specific body (§7.5).
     *
     * @param naifId NAIF ID of the body
     * @param seconds trail duration in simulation seconds; use {@code -1} for the default (orbital period or 30 days)
     */
    void setTrailDuration(int naifId, double seconds);

    /**
     * Toggle vector overlay visibility for a specific body and vector type (§7.6).
     *
     * @param naifId NAIF ID of the body
     * @param type the vector type strategy
     * @param visible {@code true} to show, {@code false} to hide
     */
    void setVectorVisible(int naifId, VectorType type, boolean visible);

    // ── Instrument frustum overlays (Step 22) ──

    /**
     * Toggle instrument frustum overlay visibility by NAIF code.
     *
     * @param instrumentNaifCode NAIF code of the instrument (e.g. −98300 for NH_LORRI)
     * @param visible {@code true} to show, {@code false} to hide
     */
    void setFrustumVisible(int instrumentNaifCode, boolean visible);

    /**
     * Toggle instrument frustum overlay visibility by instrument name.
     *
     * <p>The name is resolved via {@link kepplr.ephemeris.BodyLookupService} (SPICE kernel pool lookup,
     * case-insensitive).
     *
     * @param instrumentName instrument name as registered in the kernel pool (e.g. {@code "NH_LORRI"})
     * @param visible {@code true} to show, {@code false} to hide
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    void setFrustumVisible(String instrumentName, boolean visible);

    // ── Transition control (Step 20) ──

    /**
     * Cancel the active camera transition and discard any pending transition requests.
     *
     * <p>Thread-safe — may be called from any thread. The cancel is posted to the transition controller's inbox and
     * processed on the next JME render frame.
     *
     * <p>Example: a Groovy script runner calls this on interrupt to ensure no partial transitions remain active after a
     * script is stopped.
     *
     * <pre>{@code
     * commands.cancelTransition();
     * }</pre>
     */
    void cancelTransition();

    // ── Screenshot capture (Step 25) ──

    /**
     * Capture the current JME framebuffer to a PNG file at the specified path (Step 25).
     *
     * <p>The capture waits for the current frame to finish rendering before writing. When called from a background
     * thread (e.g., the Groovy script thread), this method <b>blocks</b> until the screenshot is written. When called
     * from the FX thread (e.g., a menu action), it is fire-and-forget.
     *
     * <p>Recorded by {@link kepplr.scripting.CommandRecorder} as:
     *
     * <pre>{@code kepplr.saveScreenshot("/path/to/output.png") }</pre>
     *
     * @param outputPath file system path for the output PNG file
     */
    void saveScreenshot(String outputPath);

    // ── Configuration reload (Step 27) ──

    /**
     * Reload the KEPPLR configuration from the given properties file and rebuild the scene (Step 27).
     *
     * <p>This is the first {@link SimulationCommands} method that triggers a render-manager rebuild. It is added here
     * for script loggability — the implementation is a thin dispatcher that contains no render logic.
     *
     * <p>The method calls {@link kepplr.config.KEPPLRConfiguration#reload(java.nio.file.Path)}, then enqueues
     * {@code rebuildBodyScene()} on the JME render thread and blocks the calling thread until the rebuild completes (or
     * the timeout elapses). If the file cannot be parsed the error is logged and the method returns immediately without
     * blocking; the previous configuration remains active.
     *
     * <p>Thread-safe — designed for the Groovy script thread ({@code kepplr-groovy-script}). Note: the
     * {@code KEPPLRConfiguration.instance} static field is non-volatile; the race window during reload is the same as
     * for the interactive File → Load Configuration path.
     *
     * <p>Recorded by {@link kepplr.scripting.CommandRecorder} as:
     *
     * <pre>{@code kepplr.loadConfiguration("/path/to/config.properties") }</pre>
     *
     * @param path file system path to the {@code .properties} configuration file
     */
    void loadConfiguration(String path);

    /**
     * Display a message on the JME HUD overlay.
     *
     * <p>The message appears in the lower-center of the screen for the specified duration, then fades out. Only one
     * message is visible at a time — a new message replaces any existing one. The text may contain {@code \n} for line
     * breaks.
     *
     * <pre>{@code kepplr.displayMessage("Hello, world!", 5.0) }</pre>
     *
     * @param text message text; may contain {@code \n} for line breaks
     * @param durationSeconds display duration in seconds before fade-out begins
     */
    void displayMessage(String text, double durationSeconds);

    /**
     * Resize the JME render window.
     *
     * <pre>{@code kepplr.setWindowSize(1920, 1080) }</pre>
     *
     * @param width window width in pixels
     * @param height window height in pixels
     */
    void setWindowSize(int width, int height);
}
