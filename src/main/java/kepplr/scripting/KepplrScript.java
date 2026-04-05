package kepplr.scripting;

import kepplr.camera.CameraFrame;
import kepplr.commands.SimulationCommands;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.BodyLookupService;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.render.RenderQuality;
import kepplr.render.vector.VectorType;
import kepplr.render.vector.VectorTypes;
import kepplr.state.SimulationState;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.mechanics.EphemerisID;

/**
 * Groovy-friendly scripting API object (REDESIGN.md §11, Step 20).
 *
 * <p>This class is bound as {@code kepplr} in the Groovy script context. It delegates every action to
 * {@link SimulationCommands} and provides:
 *
 * <ul>
 *   <li>String overloads for all NAIF-ID methods, resolved via {@link BodyLookupService}
 *   <li>Timing primitives: {@link #waitWall}, {@link #waitSim}, {@link #waitUntilSim}, {@link #waitTransition}
 * </ul>
 *
 * <p>No camera math or simulation logic lives here — every action is a thin delegation to {@link SimulationCommands}.
 * Name resolution lives in {@link BodyLookupService} (CLAUDE.md constraint).
 *
 * <p><b>Threading:</b> methods are called from the Groovy script thread. {@code SimulationCommands} methods are
 * thread-safe (they post to the transition controller's inbox). Wait primitives block the calling thread.
 *
 * <p><b>Execution semantics</b> (labelled on every public method):
 *
 * <ul>
 *   <li><b>Immediate</b> — takes effect on the calling thread with no JME thread involvement. State mutations are
 *       visible on the next JME frame.
 *   <li><b>Queued</b> — enqueued to the JME thread's transition inbox; returns immediately. The effect occurs when the
 *       JME thread processes the request. Use {@link #waitTransition()} to block until completion.
 *   <li><b>Blocking</b> — blocks the script thread until the operation completes (e.g., screenshot written, scene
 *       rebuilt, wall/sim time elapsed, transition finished).
 *   <li><b>Immediate + Queued</b> — hybrid: some effects (state mutations) are immediate while others (camera
 *       transitions) are queued. Use {@link #waitTransition()} after these calls to ensure the camera arrives.
 * </ul>
 *
 * <h3>Example script</h3>
 *
 * <pre>{@code
 * kepplr.goTo("Earth", 20, 5)
 * kepplr.waitTransition()
 * kepplr.setTimeRate(3600.0)
 * kepplr.waitSim(86400.0)
 * kepplr.goTo("Mars", 20, 5)
 * kepplr.waitTransition()
 * }</pre>
 */
public final class KepplrScript {

    private static final Logger logger = LogManager.getLogger();

    private final SimulationCommands commands;
    private final SimulationState state;
    private final WaitTransition waitTransition;

    /**
     * @param commands simulation commands to delegate to; must not be null
     * @param state simulation state for timing primitives; must not be null
     */
    public KepplrScript(SimulationCommands commands, SimulationState state) {
        this.commands = commands;
        this.state = state;
        this.waitTransition = new WaitTransition(state);
    }

    // ── State access ────────────────────────────────────────────────────────────

    /**
     * Return the live simulation state.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Scripts can read any observable property — current ET, time rate, paused status, focused body ID, camera
     * position, etc.
     *
     * <p>Example:
     *
     * <pre>{@code
     * et = kepplr.getState().currentEtProperty().get()
     * rate = kepplr.getState().timeRateProperty().get()
     * focused = kepplr.getState().focusedBodyIdProperty().get()
     * }</pre>
     *
     * @return the live {@link SimulationState} instance
     */
    public SimulationState getState() {
        return state;
    }

    // ── Configuration access (Step 28) ────────────────────────────────────────────

    /**
     * Return the current KEPPLR configuration.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Acquires the singleton at point-of-use (CLAUDE.md Rule 3). Scripts may call any method on the returned object,
     * including {@code getEphemeris()}.
     *
     * <p>Example:
     *
     * <pre>{@code
     * config = kepplr.getConfiguration()
     * eph = config.getEphemeris()
     * pos = eph.getHeliocentricPositionJ2000(399, config.getTimeConversion().utcStringToTDB("2015 Jul 14 08:00:00"))
     * println pos
     * }</pre>
     *
     * @return the current {@link KEPPLRConfiguration} instance
     */
    public KEPPLRConfiguration getConfiguration() {
        return KEPPLRConfiguration.getInstance();
    }

    // ── Name resolution helper ──────────────────────────────────────────────────

    /**
     * Resolve a body name to a NAIF ID.
     *
     * <p>Example: {@code resolve("Earth")} returns {@code 399}.
     *
     * @param bodyName body name or NAIF ID string; case-insensitive
     * @return the resolved NAIF ID
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    private static int resolve(String bodyName) {
        return BodyLookupService.resolve(bodyName);
    }

    // ── Interaction commands ────────────────────────────────────────────────────

    /**
     * Select a body for HUD display by NAIF ID.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.selectBody(399)}
     *
     * @param naifId NAIF ID of the body to select
     */
    public void selectBody(int naifId) {
        commands.selectBody(naifId);
    }

    /**
     * Select a body for HUD display by name.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.selectBody("Earth")}
     *
     * @param bodyName body name; case-insensitive
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    public void selectBody(String bodyName) {
        commands.selectBody(resolve(bodyName));
    }

    /**
     * Center the interaction state on a body by NAIF ID.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.centerBody(399)}
     *
     * @param naifId NAIF ID of the body to center
     */
    public void centerBody(int naifId) {
        commands.centerBody(naifId);
    }

    /**
     * Center the interaction state on a body by name.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.centerBody("Earth")}
     *
     * @param bodyName body name; case-insensitive
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    public void centerBody(String bodyName) {
        commands.centerBody(resolve(bodyName));
    }

    /**
     * Target a body by NAIF ID.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.targetBody(301)}
     *
     * @param naifId NAIF ID of the body to target
     */
    public void targetBody(int naifId) {
        commands.targetBody(naifId);
    }

    /**
     * Target a body by name.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.targetBody("Moon")}
     *
     * @param bodyName body name; case-insensitive
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    public void targetBody(String bodyName) {
        commands.targetBody(resolve(bodyName));
    }

    // ── Time commands ───────────────────────────────────────────────────────────

    /**
     * Set the simulation time rate as an absolute value.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setTimeRate(3600.0)} — one hour of sim time per wall second.
     *
     * @param simSecondsPerWallSecond simulation seconds per wall-clock second
     */
    public void setTimeRate(double simSecondsPerWallSecond) {
        commands.setTimeRate(simSecondsPerWallSecond);
    }

    /**
     * Pause or unpause the simulation clock.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setPaused(true)}
     *
     * @param paused {@code true} to pause, {@code false} to resume
     */
    public void setPaused(boolean paused) {
        commands.setPaused(paused);
    }

    /**
     * Jump the simulation clock to the specified ET.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setET(4.895232e8)}
     *
     * @param et target ET (TDB seconds past J2000)
     */
    public void setET(double et) {
        commands.setET(et);
    }

    /**
     * Jump the simulation clock to the specified UTC string.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setUTC("2015 Jul 14 07:59:00")}
     *
     * @param utcString UTC time string in Picante format
     */
    public void setUTC(String utcString) {
        commands.setUTC(utcString);
    }

    // ── Camera transition commands ──────────────────────────────────────────────

    /**
     * Point the camera at a body by NAIF ID.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately. Updates
     * selected/targeted state to the same body.
     *
     * <p>Example: {@code kepplr.pointAt(399, 2.0)}
     *
     * @param naifId NAIF ID of the body
     * @param durationSeconds slew duration in seconds
     */
    public void pointAt(int naifId, double durationSeconds) {
        commands.pointAt(naifId, durationSeconds);
    }

    /**
     * Point the camera at a body by name.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately. Updates
     * selected/targeted state to the same body.
     *
     * <p>Example: {@code kepplr.pointAt("Earth", 2.0)}
     *
     * @param bodyName body name; case-insensitive
     * @param durationSeconds slew duration in seconds
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    public void pointAt(String bodyName, double durationSeconds) {
        commands.pointAt(resolve(bodyName), durationSeconds);
    }

    /**
     * Fly the camera to a body by NAIF ID.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately. Updates
     * selected/focused/targeted state to the same body and prepends a default point-at slew.
     *
     * <p>Example: {@code kepplr.goTo(399, 5.0, 3.0)}
     *
     * @param naifId NAIF ID of the body
     * @param apparentRadiusDeg desired apparent radius in degrees
     * @param durationSeconds translation duration in seconds
     */
    public void goTo(int naifId, double apparentRadiusDeg, double durationSeconds) {
        commands.goTo(naifId, apparentRadiusDeg, durationSeconds);
    }

    /**
     * Fly the camera to a body by name.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately. Updates
     * selected/focused/targeted state to the same body and prepends a default point-at slew.
     *
     * <p>Example: {@code kepplr.goTo("Earth", 5.0, 3.0)}
     *
     * @param bodyName body name; case-insensitive
     * @param apparentRadiusDeg desired apparent radius in degrees
     * @param durationSeconds translation duration in seconds
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    public void goTo(String bodyName, double apparentRadiusDeg, double durationSeconds) {
        commands.goTo(resolve(bodyName), apparentRadiusDeg, durationSeconds);
    }

    // ── Camera navigation commands ──────────────────────────────────────────────

    /**
     * Zoom the camera by a multiplicative factor.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>Example: {@code kepplr.zoom(0.5, 1.0)} — halve the distance over one second.
     *
     * @param factor multiplicative distance factor; must be positive
     * @param durationSeconds transition duration in seconds
     */
    public void zoom(double factor, double durationSeconds) {
        commands.zoom(factor, durationSeconds);
    }

    /**
     * Set the camera field of view.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>Example: {@code kepplr.setFov(60.0, 1.0)}
     *
     * @param degrees desired FOV in degrees
     * @param durationSeconds transition duration in seconds
     */
    public void setFov(double degrees, double durationSeconds) {
        commands.setFov(degrees, durationSeconds);
    }

    /**
     * Orbit the camera around the focus body.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>Example: {@code kepplr.orbit(45.0, 0.0, 2.0)} — orbit 45 degrees right over two seconds.
     *
     * @param rightDegrees degrees to orbit around screen-up axis
     * @param upDegrees degrees to orbit around screen-right axis
     * @param durationSeconds transition duration in seconds
     */
    public void orbit(double rightDegrees, double upDegrees, double durationSeconds) {
        commands.orbit(rightDegrees, upDegrees, durationSeconds);
    }

    /**
     * Tilt the camera in place.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>Example: {@code kepplr.tilt(10.0, 0.5)}
     *
     * @param degrees tilt angle in degrees; positive tilts up
     * @param durationSeconds transition duration in seconds
     */
    public void tilt(double degrees, double durationSeconds) {
        commands.tilt(degrees, durationSeconds);
    }

    /**
     * Yaw the camera in place.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>Example: {@code kepplr.yaw(45.0, 1.0)}
     *
     * @param degrees yaw angle in degrees; positive yaws right
     * @param durationSeconds transition duration in seconds
     */
    public void yaw(double degrees, double durationSeconds) {
        commands.yaw(degrees, durationSeconds);
    }

    /**
     * Roll the camera around its look axis.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>Example: {@code kepplr.roll(90.0, 1.0)}
     *
     * @param degrees roll angle in degrees; positive rolls clockwise
     * @param durationSeconds transition duration in seconds
     */
    public void roll(double degrees, double durationSeconds) {
        commands.roll(degrees, durationSeconds);
    }

    /**
     * Set the camera position relative to the current focus body.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>The offset vector is expressed in the current camera frame (INERTIAL = J2000, BODY_FIXED = IAU body frame,
     * SYNODIC = synodic frame axes). Use {@link #setCameraFrame} to change the frame before calling this method.
     *
     * <p>Example: {@code kepplr.setCameraPosition(0, 0, 10000, 2.0)}
     *
     * @param x x offset in km in the current camera frame
     * @param y y offset in km in the current camera frame
     * @param z z offset in km in the current camera frame
     * @param durationSeconds transition duration in seconds
     */
    public void setCameraPosition(double x, double y, double z, double durationSeconds) {
        commands.setCameraPosition(x, y, z, durationSeconds);
    }

    /**
     * Set the camera position relative to an explicit origin body by NAIF ID.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>The offset vector is expressed in the current camera frame (INERTIAL = J2000, BODY_FIXED = IAU body frame,
     * SYNODIC = synodic frame axes). Use {@link #setCameraFrame} to change the frame before calling this method.
     *
     * <p>Example: {@code kepplr.setCameraPosition(0, 0, 50000, 301, 3.0)}
     *
     * @param x x offset in km in the current camera frame
     * @param y y offset in km in the current camera frame
     * @param z z offset in km in the current camera frame
     * @param originNaifId NAIF ID of the origin body
     * @param durationSeconds transition duration in seconds
     */
    public void setCameraPosition(double x, double y, double z, int originNaifId, double durationSeconds) {
        commands.setCameraPosition(x, y, z, originNaifId, durationSeconds);
    }

    /**
     * Set the camera position relative to an explicit origin body by name.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>The offset vector is expressed in the current camera frame (INERTIAL = J2000, BODY_FIXED = IAU body frame,
     * SYNODIC = synodic frame axes). Use {@link #setCameraFrame} to change the frame before calling this method.
     *
     * <p>Example: {@code kepplr.setCameraPosition(0, 0, 50000, "Moon", 3.0)}
     *
     * @param x x offset in km in the current camera frame
     * @param y y offset in km in the current camera frame
     * @param z z offset in km in the current camera frame
     * @param originBodyName origin body name; case-insensitive
     * @param durationSeconds transition duration in seconds
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    public void setCameraPosition(double x, double y, double z, String originBodyName, double durationSeconds) {
        commands.setCameraPosition(x, y, z, resolve(originBodyName), durationSeconds);
    }

    /**
     * Set the camera look direction and up vector.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>Both vectors are expressed in the current camera frame (INERTIAL = J2000, BODY_FIXED = IAU body frame, SYNODIC
     * = synodic frame axes). Use {@link #setCameraFrame} to change the frame before calling this method.
     *
     * <p>Example: {@code kepplr.setCameraOrientation(1, 0, 0, 0, 0, 1, 2.0)}
     *
     * @param lookX x component of look direction in the current camera frame
     * @param lookY y component of look direction in the current camera frame
     * @param lookZ z component of look direction in the current camera frame
     * @param upX x component of up vector in the current camera frame
     * @param upY y component of up vector in the current camera frame
     * @param upZ z component of up vector in the current camera frame
     * @param durationSeconds transition duration in seconds
     */
    public void setCameraOrientation(
            double lookX, double lookY, double lookZ, double upX, double upY, double upZ, double durationSeconds) {
        commands.setCameraOrientation(lookX, lookY, lookZ, upX, upY, upZ, durationSeconds);
    }

    // ── Cinematic camera commands (Step 24) ──────────────────────────────────────

    /**
     * Translate the camera along its screen-right axis.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>Example: {@code kepplr.truck(1000.0, 3.0)} — move 1000 km right over 3 seconds.
     *
     * @param km distance to translate in km; positive = right
     * @param durationSeconds transition duration in seconds
     */
    public void truck(double km, double durationSeconds) {
        commands.truck(km, durationSeconds);
    }

    /**
     * Translate the camera along its screen-right axis using the default cinematic duration.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * @param km distance to translate in km; positive = right
     */
    public void truck(double km) {
        commands.truck(km, KepplrConstants.DEFAULT_CINEMATIC_TRANSITION_DURATION_SECONDS);
    }

    /**
     * Translate the camera along its screen-up axis.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>Example: {@code kepplr.crane(500.0, 2.0)} — move 500 km up over 2 seconds.
     *
     * @param km distance to translate in km; positive = up
     * @param durationSeconds transition duration in seconds
     */
    public void crane(double km, double durationSeconds) {
        commands.crane(km, durationSeconds);
    }

    /**
     * Translate the camera along its screen-up axis using the default cinematic duration.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * @param km distance to translate in km; positive = up
     */
    public void crane(double km) {
        commands.crane(km, KepplrConstants.DEFAULT_CINEMATIC_TRANSITION_DURATION_SECONDS);
    }

    /**
     * Translate the camera along its look direction.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>Example: {@code kepplr.dolly(2000.0, 5.0)} — move 2000 km forward over 5 seconds. Note: dolly is a pure
     * spatial translation — it does not modify apparent radius relative to any body, unlike {@code goTo}.
     *
     * @param km distance to translate in km; positive = forward
     * @param durationSeconds transition duration in seconds
     */
    public void dolly(double km, double durationSeconds) {
        commands.dolly(km, durationSeconds);
    }

    /**
     * Translate the camera along its look direction using the default cinematic duration.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * @param km distance to translate in km; positive = forward
     */
    public void dolly(double km) {
        commands.dolly(km, KepplrConstants.DEFAULT_CINEMATIC_TRANSITION_DURATION_SECONDS);
    }

    /**
     * Switch to the synodic camera frame with explicit body IDs.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setSynodicFrame(399, 301)}
     *
     * @param focusNaifId NAIF ID of the focus body
     * @param targetNaifId NAIF ID of the target body
     */
    public void setSynodicFrame(int focusNaifId, int targetNaifId) {
        commands.setSynodicFrame(focusNaifId, targetNaifId);
    }

    /**
     * Switch to the synodic camera frame with explicit body names.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setSynodicFrame("Earth", "Moon")}
     *
     * @param focusBodyName focus body name; case-insensitive
     * @param targetBodyName target body name; case-insensitive
     * @throws IllegalArgumentException if either name cannot be resolved
     */
    public void setSynodicFrame(String focusBodyName, String targetBodyName) {
        commands.setSynodicFrame(resolve(focusBodyName), resolve(targetBodyName));
    }

    // ── Camera frame ────────────────────────────────────────────────────────────

    /**
     * Switch the active camera frame.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setCameraFrame(CameraFrame.SYNODIC)}
     *
     * @param frame the desired camera frame
     */
    public void setCameraFrame(CameraFrame frame) {
        commands.setCameraFrame(frame);
    }

    // ── Render quality ──────────────────────────────────────────────────────────

    /**
     * Set the render quality preset.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setRenderQuality(RenderQuality.HIGH)}
     *
     * @param quality the desired quality preset
     */
    public void setRenderQuality(RenderQuality quality) {
        commands.setRenderQuality(quality);
    }

    // ── Overlay commands ────────────────────────────────────────────────────────

    /**
     * Toggle label visibility for a body by NAIF ID.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setLabelVisible(399, true)}
     *
     * @param naifId NAIF ID of the body
     * @param visible {@code true} to show, {@code false} to hide
     */
    public void setLabelVisible(int naifId, boolean visible) {
        commands.setLabelVisible(naifId, visible);
    }

    /**
     * Toggle label visibility for a body by name.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setLabelVisible("Earth", true)}
     *
     * @param bodyName body name; case-insensitive
     * @param visible {@code true} to show, {@code false} to hide
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    public void setLabelVisible(String bodyName, boolean visible) {
        commands.setLabelVisible(resolve(bodyName), visible);
    }

    /**
     * Toggle label visibility for ALL bodies and spacecraft.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>When enabling, barycenters (NAIF 1–9) are skipped except Pluto barycenter (9), matching the Overlays menu
     * behavior.
     *
     * <p>Example: {@code kepplr.setAllLabelsVisible(true)}
     *
     * @param visible {@code true} to show all labels, {@code false} to hide all
     */
    public void setAllLabelsVisible(boolean visible) {
        forEachBody((code, show) -> commands.setLabelVisible(code, show), visible);
    }

    /**
     * Toggle the HUD time display.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setHudTimeVisible(true)}
     *
     * @param visible {@code true} to show, {@code false} to hide
     */
    public void setHudTimeVisible(boolean visible) {
        commands.setHudTimeVisible(visible);
    }

    /**
     * Toggle the HUD info display.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setHudInfoVisible(false)}
     *
     * @param visible {@code true} to show, {@code false} to hide
     */
    public void setHudInfoVisible(boolean visible) {
        commands.setHudInfoVisible(visible);
    }

    /**
     * Toggle trail visibility for a body by NAIF ID.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setTrailVisible(399, true)}
     *
     * @param naifId NAIF ID of the body
     * @param visible {@code true} to show, {@code false} to hide
     */
    public void setTrailVisible(int naifId, boolean visible) {
        commands.setTrailVisible(naifId, visible);
    }

    /**
     * Toggle trail visibility for a body by name.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setTrailVisible("Earth", true)}
     *
     * @param bodyName body name; case-insensitive
     * @param visible {@code true} to show, {@code false} to hide
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    public void setTrailVisible(String bodyName, boolean visible) {
        commands.setTrailVisible(resolve(bodyName), visible);
    }

    /**
     * Toggle trail visibility for ALL bodies and spacecraft.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>When enabling, barycenters (NAIF 1–9) are skipped except Pluto barycenter (9), matching the Overlays menu
     * behavior.
     *
     * <p>Example: {@code kepplr.setAllTrailsVisible(true)}
     *
     * @param visible {@code true} to show all trails, {@code false} to hide all
     */
    public void setAllTrailsVisible(boolean visible) {
        forEachBody((code, show) -> commands.setTrailVisible(code, show), visible);
    }

    /**
     * Set the trail duration for a body by NAIF ID.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setTrailDuration(399, 86400.0)} — one day.
     *
     * @param naifId NAIF ID of the body
     * @param seconds trail duration in simulation seconds; {@code -1} for default
     */
    public void setTrailDuration(int naifId, double seconds) {
        commands.setTrailDuration(naifId, seconds);
    }

    /**
     * Set the trail duration for a body by name.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setTrailDuration("Earth", 86400.0)}
     *
     * @param bodyName body name; case-insensitive
     * @param seconds trail duration in simulation seconds; {@code -1} for default
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    public void setTrailDuration(String bodyName, double seconds) {
        commands.setTrailDuration(resolve(bodyName), seconds);
    }

    /**
     * Set the reference body for the trail and velocity vector of a body by NAIF ID.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>. The trail is resampled on the next render frame.
     *
     * <p>Both the orbital trail and the velocity direction arrow for {@code naifId} are drawn relative to
     * {@code referenceBodyId}. Use {@code -1} to restore the default heuristic (natural satellites use their system
     * barycenter; all other bodies use heliocentric coordinates).
     *
     * <p>Example: {@code kepplr.setTrailReferenceBody(-1234, 301)} — show Artemis II's trail relative to the Moon.
     *
     * @param naifId NAIF ID of the body whose trail reference to set
     * @param referenceBodyId NAIF ID of the reference body, or {@code -1} for auto
     */
    public void setTrailReferenceBody(int naifId, int referenceBodyId) {
        commands.setTrailReferenceBody(naifId, referenceBodyId);
    }

    /**
     * Set the reference body for the trail and velocity vector of a body by name.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>. The trail is resampled on the next render frame.
     *
     * <p>Example: {@code kepplr.setTrailReferenceBody("Artemis II", "Moon")}
     *
     * @param bodyName name of the body whose trail reference to set; case-insensitive
     * @param referenceBodyName name of the reference body; case-insensitive
     * @throws IllegalArgumentException if either name cannot be resolved
     */
    public void setTrailReferenceBody(String bodyName, String referenceBodyName) {
        commands.setTrailReferenceBody(resolve(bodyName), resolve(referenceBodyName));
    }

    /**
     * Toggle vector overlay visibility by NAIF ID.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setVectorVisible(399, VectorTypes.velocity(), true)}
     *
     * @param naifId NAIF ID of the body
     * @param type the vector type strategy
     * @param visible {@code true} to show, {@code false} to hide
     */
    public void setVectorVisible(int naifId, VectorType type, boolean visible) {
        commands.setVectorVisible(naifId, type, visible);
    }

    /**
     * Toggle vector overlay visibility by body name.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setVectorVisible("Earth", VectorTypes.velocity(), true)}
     *
     * @param bodyName body name; case-insensitive
     * @param type the vector type strategy
     * @param visible {@code true} to show, {@code false} to hide
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    public void setVectorVisible(String bodyName, VectorType type, boolean visible) {
        commands.setVectorVisible(resolve(bodyName), type, visible);
    }

    /**
     * Toggle body-fixed X, Y, and Z axis overlays by NAIF ID.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Convenience method equivalent to calling {@link #setVectorVisible(int, VectorType, boolean)} three times with
     * {@link VectorTypes#bodyAxisX()}, {@link VectorTypes#bodyAxisY()}, and {@link VectorTypes#bodyAxisZ()}.
     *
     * <p>Example: {@code kepplr.setBodyFixedAxesVisible(399, true)}
     *
     * @param naifId NAIF ID of the body
     * @param visible {@code true} to show all three axes, {@code false} to hide
     */
    public void setBodyFixedAxesVisible(int naifId, boolean visible) {
        commands.setVectorVisible(naifId, VectorTypes.bodyAxisX(), visible);
        commands.setVectorVisible(naifId, VectorTypes.bodyAxisY(), visible);
        commands.setVectorVisible(naifId, VectorTypes.bodyAxisZ(), visible);
    }

    /**
     * Toggle body-fixed X, Y, and Z axis overlays by body name.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Convenience method equivalent to calling {@link #setVectorVisible(String, VectorType, boolean)} three times
     * with {@link VectorTypes#bodyAxisX()}, {@link VectorTypes#bodyAxisY()}, and {@link VectorTypes#bodyAxisZ()}.
     *
     * <p>Example: {@code kepplr.setBodyFixedAxesVisible("Earth", true)}
     *
     * @param bodyName body name; case-insensitive
     * @param visible {@code true} to show all three axes, {@code false} to hide
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    public void setBodyFixedAxesVisible(String bodyName, boolean visible) {
        int id = resolve(bodyName);
        commands.setVectorVisible(id, VectorTypes.bodyAxisX(), visible);
        commands.setVectorVisible(id, VectorTypes.bodyAxisY(), visible);
        commands.setVectorVisible(id, VectorTypes.bodyAxisZ(), visible);
    }

    // ── Instrument frustum overlays (Step 22) ────────────────────────────────────

    /**
     * Toggle instrument frustum overlay visibility by NAIF code.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setFrustumVisible(-98300, true)}
     *
     * @param instrumentNaifCode NAIF code of the instrument
     * @param visible {@code true} to show, {@code false} to hide
     */
    public void setFrustumVisible(int instrumentNaifCode, boolean visible) {
        commands.setFrustumVisible(instrumentNaifCode, visible);
    }

    /**
     * Toggle instrument frustum overlay visibility by instrument name.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setFrustumVisible("NH_LORRI", true)}
     *
     * @param instrumentName instrument name as registered in the kernel pool
     * @param visible {@code true} to show, {@code false} to hide
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    public void setFrustumVisible(String instrumentName, boolean visible) {
        commands.setFrustumVisible(resolve(instrumentName), visible);
    }

    // ── Body visibility (Step 28) ──────────────────────────────────────────────

    /**
     * Show or hide a body in the scene by NAIF ID.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setBodyVisible(9, false)} — hide Pluto barycenter
     *
     * @param naifId NAIF ID of the body
     * @param visible {@code true} to show, {@code false} to hide
     */
    public void setBodyVisible(int naifId, boolean visible) {
        commands.setBodyVisible(naifId, visible);
    }

    /**
     * Show or hide a body in the scene by name.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code kepplr.setBodyVisible("Pluto Barycenter", false)}
     *
     * @param bodyName body name; case-insensitive
     * @param visible {@code true} to show, {@code false} to hide
     * @throws IllegalArgumentException if the name cannot be resolved
     */
    public void setBodyVisible(String bodyName, boolean visible) {
        commands.setBodyVisible(resolve(bodyName), visible);
    }

    // ── Screenshot and capture (Step 25) ────────────────────────────────────────

    /**
     * Capture the current JME framebuffer to a PNG file.
     *
     * <p><b>Execution semantics:</b> <em>Blocking</em> — blocks the script thread until the screenshot is written.
     *
     * <p>Example: {@code kepplr.saveScreenshot("/tmp/screenshot.png")}
     *
     * @param outputPath file system path for the output PNG file
     */
    public void saveScreenshot(String outputPath) {
        commands.saveScreenshot(outputPath);
    }

    /**
     * Capture the current simulation state as a compact, copy-pasteable string (Step 26).
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>Example: {@code def s = kepplr.getStateString()}
     *
     * @return the encoded state string
     */
    public String getStateString() {
        return commands.getStateString();
    }

    /**
     * Restore simulation state from a state string (Step 26).
     *
     * <p><b>Execution semantics:</b> <em>Immediate + Queued</em> — state mutations and clock changes are immediate;
     * camera restore is queued to the JME thread.
     *
     * <p>All fields are applied instantly (no transition animation).
     *
     * <p>Example: {@code kepplr.setStateString("AQA...")}
     *
     * @param stateString the encoded state string
     */
    public void setStateString(String stateString) {
        commands.setStateString(stateString);
    }

    /**
     * Capture a sequence of frames as PNG files (Step 25).
     *
     * <p><b>Execution semantics:</b> <em>Blocking</em> — blocks the script thread until all frames are captured.
     *
     * <p>Sets ET to {@code startET}, pauses the simulation, then loops {@code frameCount} times: captures a screenshot,
     * advances ET by {@code etStep}. After the sequence completes, the simulation remains paused at the final ET.
     *
     * <p>This is a compound operation — it is NOT on {@code SimulationCommands} and is NOT loggable by
     * {@code CommandRecorder}.
     *
     * <p>Example: {@code kepplr.captureSequence("/tmp/frames", 4.895e8, 60, 2.0)}
     *
     * @param outputDir directory for output PNG files (created if it doesn't exist)
     * @param startET starting ET (TDB seconds past J2000)
     * @param frameCount number of frames to capture; must be positive
     * @param etStep ET advance per frame in seconds
     */
    public void captureSequence(String outputDir, double startET, int frameCount, double etStep) {
        kepplr.core.CaptureService.captureSequence(outputDir, startET, frameCount, etStep, commands, state);
    }

    /**
     * Capture a sequence of frames as PNG files, starting from a UTC string (Step 25).
     *
     * <p><b>Execution semantics:</b> <em>Blocking</em> — blocks the script thread until all frames are captured.
     *
     * <p>Converts {@code startUTC} to ET via ephemeris, then delegates to {@link #captureSequence(String, double, int,
     * double)}.
     *
     * <p>Example: {@code kepplr.captureSequence("/tmp/frames", "2015 Jul 14 07:59:00", 60, 2.0)}
     *
     * @param outputDir directory for output PNG files (created if it doesn't exist)
     * @param startUTC UTC time string in Picante format
     * @param frameCount number of frames to capture; must be positive
     * @param etStep ET advance per frame in seconds
     */
    public void captureSequence(String outputDir, String startUTC, int frameCount, double etStep) {
        double startET = KEPPLRConfiguration.getInstance().getTimeConversion().utcStringToTDB(startUTC);
        captureSequence(outputDir, startET, frameCount, etStep);
    }

    // ── Configuration reload (Step 27) ──────────────────────────────────────────

    /**
     * Reload the KEPPLR configuration from the given file path and rebuild the scene.
     *
     * <p><b>Execution semantics:</b> <em>Blocking</em> — blocks the script thread until the JME scene rebuild completes
     * (or the timeout elapses).
     *
     * <p>Any command issued after this call sees the new configuration.
     *
     * <p>Example: {@code kepplr.loadConfiguration("/path/to/config.properties")}
     *
     * @param path file system path to the {@code .properties} configuration file
     */
    public void loadConfiguration(String path) {
        commands.loadConfiguration(path);
    }

    // ── HUD messages (Step 28) ──────────────────────────────────────────────────

    /**
     * Display a message on the JME HUD overlay with the default duration.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>The message appears in the lower-center of the screen for
     * {@value KepplrConstants#SCRIPT_MESSAGE_DEFAULT_DURATION_SEC} seconds, then fades out. Use {@code \n} in the text
     * for line breaks.
     *
     * <p>Example: {@code kepplr.displayMessage("Hello, world!")}
     *
     * @param text message text; may contain {@code \n} for line breaks
     */
    public void displayMessage(String text) {
        commands.displayMessage(text, KepplrConstants.SCRIPT_MESSAGE_DEFAULT_DURATION_SEC);
    }

    /**
     * Display a message on the JME HUD overlay with a specified duration.
     *
     * <p><b>Execution semantics:</b> <em>Immediate</em>.
     *
     * <p>The message appears in the lower-center of the screen for the given duration, then fades out. Only one message
     * is visible at a time — a new message replaces any existing one. Use {@code \n} for line breaks.
     *
     * <p>Example: {@code kepplr.displayMessage("Line 1\nLine 2", 3.0)}
     *
     * @param text message text; may contain {@code \n} for line breaks
     * @param durationSeconds display duration in seconds before fade-out begins
     */
    public void displayMessage(String text, double durationSeconds) {
        commands.displayMessage(text, durationSeconds);
    }

    // ── Window (Step 28) ──────────────────────────────────────────────────────

    /**
     * Resize the JME render window.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>Example: {@code kepplr.setWindowSize(1920, 1080)}
     *
     * @param width window width in pixels
     * @param height window height in pixels
     */
    public void setWindowSize(int width, int height) {
        commands.setWindowSize(width, height);
    }

    // ── Transition control ──────────────────────────────────────────────────────

    /**
     * Cancel the active camera transition.
     *
     * <p><b>Execution semantics:</b> <em>Queued</em> — enqueued to the JME thread; returns immediately.
     *
     * <p>Example: {@code kepplr.cancelTransition()}
     */
    public void cancelTransition() {
        commands.cancelTransition();
    }

    // ── Timing primitives (§11.2) ───────────────────────────────────────────────

    /**
     * Block the script thread until the given number of wall-clock seconds have elapsed.
     *
     * <p><b>Execution semantics:</b> <em>Blocking</em>.
     *
     * <p>Example: {@code kepplr.waitWall(2.5)} — pause the script for 2.5 real seconds.
     *
     * @param seconds wall-clock seconds to wait; values &le; 0 return immediately
     * @throws InterruptedException if the script thread is interrupted
     */
    public void waitWall(double seconds) throws InterruptedException {
        if (seconds <= 0) return;
        Thread.sleep((long) (seconds * 1000));
    }

    /**
     * Block the script thread until simulation time has advanced by the given number of seconds.
     *
     * <p><b>Execution semantics:</b> <em>Blocking</em>.
     *
     * <p>Example: {@code kepplr.waitSim(86400.0)} — wait for one simulation day to pass.
     *
     * <p><b>Warning:</b> this method blocks indefinitely if the simulation is paused or the time rate works against the
     * target direction. Consider using {@code setPaused(false)} before calling. A timeout overload may be added in a
     * future release.
     *
     * @param seconds simulation seconds to wait; values &le; 0 return immediately
     * @throws InterruptedException if the script thread is interrupted
     */
    public void waitSim(double seconds) throws InterruptedException {
        if (seconds == 0) return;
        double startEt = state.currentEtProperty().get();
        double targetEt = startEt + seconds;
        waitUntilSimInternal(targetEt);
    }

    /**
     * Block the script thread until simulation ET reaches the given value.
     *
     * <p><b>Execution semantics:</b> <em>Blocking</em>.
     *
     * <p>Example: {@code kepplr.waitUntilSim(4.895232e8)} — wait until a specific epoch.
     *
     * <p><b>Warning:</b> this method blocks indefinitely if the simulation is paused or the time rate works against the
     * target ET. If the target ET has already been passed in the current time direction, this method returns
     * immediately. A timeout overload may be added in a future release.
     *
     * @param targetEt target ET (TDB seconds past J2000)
     * @throws InterruptedException if the script thread is interrupted
     */
    public void waitUntilSim(double targetEt) throws InterruptedException {
        waitUntilSimInternal(targetEt);
    }

    /**
     * Block the script thread until simulation time reaches the given UTC string.
     *
     * <p><b>Execution semantics:</b> <em>Blocking</em>.
     *
     * <p>Example: {@code kepplr.waitUntilSim("2015 Jul 14 12:00:00")}
     *
     * <p><b>Warning:</b> this method blocks indefinitely if the simulation is paused or the time rate works against the
     * target time. If the target time has already been passed in the current time direction, this method returns
     * immediately. A timeout overload may be added in a future release.
     *
     * @param utcString UTC time string in Picante format
     * @throws InterruptedException if the script thread is interrupted
     */
    public void waitUntilSim(String utcString) throws InterruptedException {
        double targetEt = KEPPLRConfiguration.getInstance().getTimeConversion().utcStringToTDB(utcString);
        waitUntilSimInternal(targetEt);
    }

    /**
     * Block the script thread until the active camera transition completes.
     *
     * <p><b>Execution semantics:</b> <em>Blocking</em>.
     *
     * <p>Example:
     *
     * <pre>{@code
     * kepplr.pointAt("Earth", 5)
     * kepplr.waitTransition()
     * // Camera is now pointing at Earth
     * }</pre>
     *
     * @throws InterruptedException if the script thread is interrupted
     */
    public void waitTransition() throws InterruptedException {
        waitTransition.waitTransition();
    }

    // ── Private ─────────────────────────────────────────────────────────────────

    /**
     * Iterate all known bodies and spacecraft, applying a per-body action. When {@code visible} is true, barycenters
     * (NAIF 1–9 except Pluto barycenter 9) are skipped, matching the Overlays menu convention.
     */
    private void forEachBody(java.util.function.BiConsumer<Integer, Boolean> action, boolean visible) {
        try {
            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
            for (EphemerisID id : eph.getKnownBodies()) {
                eph.getSpiceBundle().getObjectCode(id).ifPresent(code -> {
                    if (visible && code >= 1 && code <= 9 && code != KepplrConstants.PLUTO_BARYCENTER_NAIF_ID) return;
                    action.accept(code, visible);
                });
            }
            for (var sc : eph.getSpacecraft()) {
                action.accept(sc.code(), visible);
            }
        } catch (Exception ex) {
            logger.warn("Failed to apply visibility to all bodies: {}", ex.getMessage());
        }
    }

    private void waitUntilSimInternal(double targetEt) throws InterruptedException {
        double currentEt = state.currentEtProperty().get();
        double timeRate = state.timeRateProperty().get();

        // If already past the target in the current time direction, return immediately
        if (timeRate >= 0 && currentEt >= targetEt) return;
        if (timeRate < 0 && currentEt <= targetEt) return;

        while (true) {
            Thread.sleep(KepplrConstants.SCRIPT_WAIT_POLL_INTERVAL_MS);
            currentEt = state.currentEtProperty().get();
            timeRate = state.timeRateProperty().get();

            if (timeRate >= 0 && currentEt >= targetEt) return;
            if (timeRate < 0 && currentEt <= targetEt) return;
        }
    }
}
