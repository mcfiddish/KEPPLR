package kepplr.camera;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import java.util.concurrent.ConcurrentLinkedQueue;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.state.DefaultSimulationState;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.EphemerisID;
import picante.surfaces.Ellipsoid;

/**
 * Owns and advances all in-progress camera transitions (Step 18, Step 19c).
 *
 * <p>Called from {@code KepplrApp.simpleUpdate()} on the JME render thread each frame. Transition requests are posted
 * from any thread (e.g., the JavaFX UI thread via {@link kepplr.commands.DefaultSimulationCommands}) through a
 * thread-safe {@link ConcurrentLinkedQueue}; the JME thread drains the queue at the start of each frame.
 *
 * <h3>Sequencing rule</h3>
 *
 * <p>A {@code goTo} posted while a {@code pointAt} is active is held as a single pending slot and starts automatically
 * when the {@code pointAt} completes. If a new {@code pointAt} arrives while both an active transition and a pending
 * {@code goTo} exist, the pending {@code goTo} is discarded and the new {@code pointAt} takes over. Queue depth is
 * therefore at most 1: one active + one pending {@code goTo}.
 *
 * <p>All Step 19c camera commands (zoom, orbit, tilt, yaw, roll, fov, setCameraPosition, setCameraLookDirection) cancel
 * any active transition when they arrive, same as a new {@code pointAt}.
 *
 * <h3>Manual navigation cancellation</h3>
 *
 * <p>When the user moves the mouse or presses a navigation key during a transition, {@code KepplrApp.simpleUpdate()}
 * calls {@link #cancel()} before calling {@link #update}, cancelling both the active transition and any pending
 * requests.
 *
 * <h3>Ephemeris access</h3>
 *
 * <p>All ephemeris queries follow Architecture Rule 3: acquired at point-of-use via
 * {@link KEPPLRConfiguration#getInstance()}.
 */
public final class TransitionController {

    private static final Logger logger = LogManager.getLogger();

    // ── Thread-safe inbox (written from commands thread, drained on JME thread) ──

    private sealed interface PendingRequest
            permits PointAtRequest,
                    GoToRequest,
                    ZoomRequest,
                    FovRequest,
                    OrbitRequest,
                    TiltRequest,
                    YawRequest,
                    RollRequest,
                    CameraPositionRequest,
                    CameraLookDirectionRequest {}

    private record PointAtRequest(int naifId, double durationSeconds) implements PendingRequest {}

    private record GoToRequest(int naifId, double apparentRadiusDeg, double durationSeconds)
            implements PendingRequest {}

    private record ZoomRequest(double factor, double durationSeconds) implements PendingRequest {}

    private record FovRequest(double degrees, double durationSeconds) implements PendingRequest {}

    private record OrbitRequest(double rightDegrees, double upDegrees, double durationSeconds)
            implements PendingRequest {}

    private record TiltRequest(double degrees, double durationSeconds) implements PendingRequest {}

    private record YawRequest(double degrees, double durationSeconds) implements PendingRequest {}

    private record RollRequest(double degrees, double durationSeconds) implements PendingRequest {}

    private record CameraPositionRequest(double x, double y, double z, int originNaifId, double durationSeconds)
            implements PendingRequest {}

    private record CameraLookDirectionRequest(
            double lookX, double lookY, double lookZ, double upX, double upY, double upZ, double durationSeconds)
            implements PendingRequest {}

    private final ConcurrentLinkedQueue<PendingRequest> inbox = new ConcurrentLinkedQueue<>();

    // ── JME-thread-only state ─────────────────────────────────────────────────

    private final DefaultSimulationState state;

    /** Active in-progress transition, or {@code null} if none. */
    private CameraTransition active = null;

    /**
     * A {@code goTo} waiting for an active {@code pointAt} to complete before starting. Replaced if a new {@code goTo}
     * arrives while another is already pending. {@code null} if none.
     */
    private GoToRequest pendingGoTo = null;

    // ── Constructor ───────────────────────────────────────────────────────────

    /** @param state mutable simulation state; updated by this controller each frame with transition active/progress */
    public TransitionController(DefaultSimulationState state) {
        this.state = state;
    }

    // ── Thread-safe request methods (called from any thread) ──────────────────

    /**
     * Request a {@code pointAt} slew transition.
     *
     * <p>Thread-safe. The request is enqueued and picked up by the JME render thread on the next {@link #update} call.
     * Any active transition is cancelled when the request is processed; a pending {@code goTo} is also discarded.
     *
     * @param naifId NAIF ID of the body to point at
     * @param durationSeconds slew duration; ≤ 0 snaps instantly on the next frame
     */
    public void requestPointAt(int naifId, double durationSeconds) {
        inbox.add(new PointAtRequest(naifId, durationSeconds));
    }

    /**
     * Request a {@code goTo} translation transition.
     *
     * <p>Thread-safe. If a {@code pointAt} is already active (or arrives in the same inbox drain), the {@code goTo} is
     * held as pending and starts after the {@code pointAt} completes. Otherwise it starts immediately on the next
     * {@link #update} call.
     *
     * @param naifId NAIF ID of the body to approach
     * @param apparentRadiusDeg desired apparent radius in degrees at end of translation
     * @param durationSeconds translation duration; ≤ 0 snaps instantly
     */
    public void requestGoTo(int naifId, double apparentRadiusDeg, double durationSeconds) {
        inbox.add(new GoToRequest(naifId, apparentRadiusDeg, durationSeconds));
    }

    /** Request a zoom transition (Step 19c). Thread-safe. */
    public void requestZoom(double factor, double durationSeconds) {
        inbox.add(new ZoomRequest(factor, durationSeconds));
    }

    /** Request a FOV transition (Step 19c). Thread-safe. */
    public void requestFov(double degrees, double durationSeconds) {
        inbox.add(new FovRequest(degrees, durationSeconds));
    }

    /** Request an orbit transition (Step 19c). Thread-safe. */
    public void requestOrbit(double rightDegrees, double upDegrees, double durationSeconds) {
        inbox.add(new OrbitRequest(rightDegrees, upDegrees, durationSeconds));
    }

    /** Request a tilt transition (Step 19c). Thread-safe. */
    public void requestTilt(double degrees, double durationSeconds) {
        inbox.add(new TiltRequest(degrees, durationSeconds));
    }

    /** Request a yaw transition (Step 19c). Thread-safe. */
    public void requestYaw(double degrees, double durationSeconds) {
        inbox.add(new YawRequest(degrees, durationSeconds));
    }

    /** Request a roll transition (Step 19c). Thread-safe. */
    public void requestRoll(double degrees, double durationSeconds) {
        inbox.add(new RollRequest(degrees, durationSeconds));
    }

    /** Request a camera position transition (Step 19c). Thread-safe. */
    public void requestCameraPosition(double x, double y, double z, int originNaifId, double durationSeconds) {
        inbox.add(new CameraPositionRequest(x, y, z, originNaifId, durationSeconds));
    }

    /** Request a camera look direction transition (Step 19c). Thread-safe. */
    public void requestCameraLookDirection(
            double lookX, double lookY, double lookZ, double upX, double upY, double upZ, double durationSeconds) {
        inbox.add(new CameraLookDirectionRequest(lookX, lookY, lookZ, upX, upY, upZ, durationSeconds));
    }

    // ── JME-thread methods ────────────────────────────────────────────────────

    /**
     * Cancel the active transition and discard all pending requests.
     *
     * <p>Must be called on the JME render thread. Called by {@code KepplrApp.simpleUpdate()} when manual navigation
     * input is detected.
     */
    public void cancel() {
        active = null;
        pendingGoTo = null;
        inbox.clear();
        updateStateProperties();
    }

    /**
     * {@code true} if any transition is active or pending (including requests not yet drained from the inbox).
     *
     * <p>May be called from any thread, but the inbox check introduces a minor race: a request enqueued between this
     * call and the next {@link #update} may not be reflected until the next frame.
     */
    public boolean isActive() {
        return active != null || pendingGoTo != null || !inbox.isEmpty();
    }

    /**
     * Progress of the active transition as {@code t ∈ [0.0, 1.0]}, or {@code 0.0} if none.
     *
     * <p>Must be called on the JME render thread.
     */
    public double getProgress() {
        return active == null ? 0.0 : active.getT();
    }

    /**
     * Advance the transition one frame.
     *
     * <p>Must be called on the JME render thread from {@code KepplrApp.simpleUpdate()}, after
     * {@code CameraInputHandler.update()} and any manual-navigation cancellation.
     *
     * @param tpf time per frame in seconds (wall-clock delta, never negative)
     * @param cam JME camera; orientation mutated for orientation-based transitions
     * @param cameraHelioJ2000 heliocentric J2000 camera position in km (length-3); mutated for position-based
     *     transitions
     * @return {@code true} if a transition completed this frame
     */
    public boolean update(float tpf, Camera cam, double[] cameraHelioJ2000) {
        // Drain the thread-safe inbox on the JME thread
        PendingRequest req;
        while ((req = inbox.poll()) != null) {
            switch (req) {
                case PointAtRequest r -> handlePointAtRequest(r, cam, cameraHelioJ2000);
                case GoToRequest r -> handleGoToRequest(r, cameraHelioJ2000);
                case ZoomRequest r -> handleZoomRequest(r, cam, cameraHelioJ2000);
                case FovRequest r -> handleFovRequest(r, cam);
                case OrbitRequest r -> handleOrbitRequest(r, cam, cameraHelioJ2000);
                case TiltRequest r -> handleTiltRequest(r, cam);
                case YawRequest r -> handleYawRequest(r, cam);
                case RollRequest r -> handleRollRequest(r, cam);
                case CameraPositionRequest r -> handleCameraPositionRequest(r, cam, cameraHelioJ2000);
                case CameraLookDirectionRequest r -> handleCameraLookDirectionRequest(r, cam);
            }
        }

        // If a pending goTo is ready to start (no active pointAt), start it now
        if (active == null && pendingGoTo != null) {
            GoToRequest r = pendingGoTo;
            pendingGoTo = null;
            startGoToNow(r, cameraHelioJ2000);
        }

        if (active == null) {
            updateStateProperties();
            return false;
        }

        // Advance elapsed time and compute interpolation parameter
        active.advanceElapsed(tpf);
        double t = active.getT();

        boolean earlyComplete = false;
        switch (active.getType()) {
            case POINT_AT, TILT, YAW, ROLL, CAMERA_LOOK_DIRECTION -> applyOrientationTransition(active, t, cam);
            case GO_TO -> earlyComplete = !applyGoTo(active, t, cameraHelioJ2000);
            case ZOOM -> earlyComplete = !applyZoomTransition(active, t, cameraHelioJ2000);
            case FOV -> applyFovTransition(active, t, cam);
            case ORBIT -> earlyComplete = !applyOrbitTransition(active, t, cam, cameraHelioJ2000);
            case CAMERA_POSITION -> earlyComplete = !applyCameraPositionTransition(active, t, cameraHelioJ2000);
        }

        if (t >= 1.0 || earlyComplete) {
            CameraTransition.Type completedType = active.getType();
            active = null;

            // If a pointAt just completed and a goTo is waiting, start the goTo immediately
            if (completedType == CameraTransition.Type.POINT_AT && pendingGoTo != null) {
                GoToRequest r = pendingGoTo;
                pendingGoTo = null;
                startGoToNow(r, cameraHelioJ2000);
            }

            updateStateProperties();
            return true;
        }

        updateStateProperties();
        return false;
    }

    // ── Private: inbox handlers ──────────────────────────────────────────────────

    private void handlePointAtRequest(PointAtRequest r, Camera cam, double[] cameraHelioJ2000) {
        // A new pointAt always cancels whatever is active and discards any pending goTo
        active = null;
        pendingGoTo = null;

        double et = state.currentEtProperty().get();
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        VectorIJK targetPos = eph.getHeliocentricPositionJ2000(r.naifId(), et);
        if (targetPos == null) {
            logger.warn("POINT_AT: NAIF {} has no ephemeris at ET={}; skipping transition", r.naifId(), et);
            return;
        }

        // Direction from camera to target (float precision is sufficient for the scene-space vector)
        float dx = (float) (targetPos.getI() - cameraHelioJ2000[0]);
        float dy = (float) (targetPos.getJ() - cameraHelioJ2000[1]);
        float dz = (float) (targetPos.getK() - cameraHelioJ2000[2]);
        Vector3f dir = new Vector3f(dx, dy, dz);
        if (dir.lengthSquared() < 1e-20f) {
            logger.warn("POINT_AT: camera is at target body NAIF {}; skipping transition", r.naifId());
            return;
        }
        dir.normalizeLocal();

        // Degenerate: camera already pointing at target
        float dot = cam.getDirection().dot(dir);
        if (dot >= 1.0f - (float) KepplrConstants.CAMERA_POINT_AT_IDENTICAL_DIRECTION_EPSILON) {
            logger.debug("POINT_AT: already pointing at NAIF {}; no slew needed", r.naifId());
            return;
        }

        Quaternion startQ = cam.getRotation().clone();
        Quaternion endQ = buildLookAtQuaternion(dir, cam.getUp());

        if (r.durationSeconds() <= KepplrConstants.CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC) {
            // Instant snap — no animation
            cam.setAxes(endQ);
            return;
        }

        active = CameraTransition.pointAt(r.naifId(), startQ, endQ, r.durationSeconds());
    }

    private void handleGoToRequest(GoToRequest r, double[] cameraHelioJ2000) {
        if (active != null && active.getType() == CameraTransition.Type.POINT_AT) {
            // Queue behind the active pointAt (replace any previously queued goTo)
            pendingGoTo = r;
        } else {
            // No active pointAt — start immediately (cancel any active goTo if present)
            active = null;
            startGoToNow(r, cameraHelioJ2000);
        }
    }

    private void handleZoomRequest(ZoomRequest r, Camera cam, double[] cameraHelioJ2000) {
        cancelForNewRequest();

        int focusId = state.focusedBodyIdProperty().get();
        if (focusId == -1) return;

        double[] focusPos = getBodyPos(focusId);
        if (focusPos == null) return;

        double dx = cameraHelioJ2000[0] - focusPos[0];
        double dy = cameraHelioJ2000[1] - focusPos[1];
        double dz = cameraHelioJ2000[2] - focusPos[2];
        double startDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double endDist = clampZoomDistance(focusId, startDist * r.factor());

        if (r.durationSeconds() <= KepplrConstants.CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC) {
            applyDistanceChange(focusPos, startDist, endDist, cameraHelioJ2000);
            return;
        }

        active = CameraTransition.zoom(focusId, startDist, endDist, r.durationSeconds());
    }

    private void handleFovRequest(FovRequest r, Camera cam) {
        cancelForNewRequest();

        double startFov = cam.getFov();
        double endFov = Math.max(KepplrConstants.FOV_MIN_DEG, Math.min(KepplrConstants.FOV_MAX_DEG, r.degrees()));

        if (r.durationSeconds() <= KepplrConstants.CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC) {
            cam.setFov((float) endFov);
            return;
        }

        active = CameraTransition.fov(startFov, endFov, r.durationSeconds());
    }

    private void handleOrbitRequest(OrbitRequest r, Camera cam, double[] cameraHelioJ2000) {
        cancelForNewRequest();

        int focusId = state.focusedBodyIdProperty().get();
        if (focusId == -1) return;

        double[] focusPos = getBodyPos(focusId);
        if (focusPos == null) return;

        // Compute end state by applying the full orbit angle
        float deltaRight = (float) Math.toRadians(r.upDegrees());
        float deltaUp = (float) Math.toRadians(r.rightDegrees());
        Vector3f screenRight = cam.getLeft().negate();
        Vector3f screenUp = cam.getUp();

        CameraNavigator.OrbitResult result = CameraNavigator.orbit(
                cameraHelioJ2000, cam.getRotation(), focusPos, screenRight, screenUp, deltaRight, deltaUp);

        if (r.durationSeconds() <= KepplrConstants.CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC) {
            cameraHelioJ2000[0] = result.position()[0];
            cameraHelioJ2000[1] = result.position()[1];
            cameraHelioJ2000[2] = result.position()[2];
            cam.setAxes(result.orientation());
            state.setCameraPositionJ2000(cameraHelioJ2000);
            return;
        }

        // Compute offsets from focus body
        double[] startOff = {
            cameraHelioJ2000[0] - focusPos[0], cameraHelioJ2000[1] - focusPos[1], cameraHelioJ2000[2] - focusPos[2]
        };
        double[] endOff = {
            result.position()[0] - focusPos[0], result.position()[1] - focusPos[1], result.position()[2] - focusPos[2]
        };

        active = CameraTransition.orbit(
                focusId, cam.getRotation(), result.orientation(), startOff, endOff, r.durationSeconds());
    }

    private void handleTiltRequest(TiltRequest r, Camera cam) {
        cancelForNewRequest();

        float angleRad = (float) Math.toRadians(r.degrees());
        Vector3f screenRight = cam.getLeft().negate();
        Quaternion startQ = cam.getRotation().clone();
        Quaternion endQ = CameraNavigator.rotateInPlace(cam.getRotation(), screenRight, cam.getUp(), angleRad, 0f);

        if (r.durationSeconds() <= KepplrConstants.CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC) {
            cam.setAxes(endQ);
            return;
        }

        active = CameraTransition.orientation(CameraTransition.Type.TILT, startQ, endQ, r.durationSeconds());
    }

    private void handleYawRequest(YawRequest r, Camera cam) {
        cancelForNewRequest();

        float angleRad = (float) Math.toRadians(r.degrees());
        Vector3f screenRight = cam.getLeft().negate();
        Quaternion startQ = cam.getRotation().clone();
        Quaternion endQ = CameraNavigator.rotateInPlace(cam.getRotation(), screenRight, cam.getUp(), 0f, angleRad);

        if (r.durationSeconds() <= KepplrConstants.CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC) {
            cam.setAxes(endQ);
            return;
        }

        active = CameraTransition.orientation(CameraTransition.Type.YAW, startQ, endQ, r.durationSeconds());
    }

    private void handleRollRequest(RollRequest r, Camera cam) {
        cancelForNewRequest();

        float angleRad = (float) Math.toRadians(r.degrees());
        Vector3f lookDir = cam.getDirection().normalize();
        Quaternion qRoll = new Quaternion().fromAngleNormalAxis(angleRad, lookDir);
        Quaternion startQ = cam.getRotation().clone();
        Quaternion endQ = qRoll.mult(cam.getRotation()).normalizeLocal();

        if (r.durationSeconds() <= KepplrConstants.CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC) {
            cam.setAxes(endQ);
            return;
        }

        active = CameraTransition.orientation(CameraTransition.Type.ROLL, startQ, endQ, r.durationSeconds());
    }

    private void handleCameraPositionRequest(CameraPositionRequest r, Camera cam, double[] cameraHelioJ2000) {
        cancelForNewRequest();

        int originId = r.originNaifId();
        if (originId == -1) {
            // Use current focus body as origin
            originId = state.focusedBodyIdProperty().get();
        }
        if (originId == -1) return;

        double[] originPos = getBodyPos(originId);
        if (originPos == null) return;

        double[] startOff = {
            cameraHelioJ2000[0] - originPos[0], cameraHelioJ2000[1] - originPos[1], cameraHelioJ2000[2] - originPos[2]
        };
        double[] endOff = {r.x(), r.y(), r.z()};

        if (r.durationSeconds() <= KepplrConstants.CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC) {
            cameraHelioJ2000[0] = originPos[0] + endOff[0];
            cameraHelioJ2000[1] = originPos[1] + endOff[1];
            cameraHelioJ2000[2] = originPos[2] + endOff[2];
            state.setCameraPositionJ2000(cameraHelioJ2000);
            return;
        }

        active = CameraTransition.cameraPosition(originId, startOff, endOff, r.durationSeconds());
    }

    private void handleCameraLookDirectionRequest(CameraLookDirectionRequest r, Camera cam) {
        cancelForNewRequest();

        Vector3f lookDir = new Vector3f((float) r.lookX(), (float) r.lookY(), (float) r.lookZ());
        Vector3f upDir = new Vector3f((float) r.upX(), (float) r.upY(), (float) r.upZ());
        if (lookDir.lengthSquared() < 1e-20f) return;
        lookDir.normalizeLocal();

        Quaternion startQ = cam.getRotation().clone();
        Quaternion endQ = buildLookAtQuaternion(lookDir, upDir);

        if (r.durationSeconds() <= KepplrConstants.CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC) {
            cam.setAxes(endQ);
            return;
        }

        active = CameraTransition.orientation(
                CameraTransition.Type.CAMERA_LOOK_DIRECTION, startQ, endQ, r.durationSeconds());
    }

    // ── Private: apply methods for animated transitions ─────────────────────────

    /** Apply spherical linear interpolation to the camera orientation for orientation-based transitions. */
    private static void applyOrientationTransition(CameraTransition transition, double t, Camera cam) {
        Quaternion slerped = new Quaternion();
        slerped.slerp(transition.getStartOrientation(), transition.getEndOrientation(), (float) t);
        cam.setAxes(slerped.normalizeLocal());
    }

    /**
     * Reposition the camera along its current focus-to-camera direction for a GO_TO transition.
     *
     * @return {@code true} if the body position is available and the camera was repositioned; {@code false} if the body
     *     has no ephemeris (signals early completion)
     */
    private boolean applyGoTo(CameraTransition transition, double t, double[] cameraHelioJ2000) {
        double targetDist = lerp(transition.getStartDistanceKm(), transition.getEndDistanceKm(), t);

        double[] bodyPos = getBodyPos(transition.getTargetNaifId());
        if (bodyPos == null) {
            logger.warn("GO_TO: NAIF {} temporarily unavailable; completing early", transition.getTargetNaifId());
            return false;
        }

        double dx = cameraHelioJ2000[0] - bodyPos[0];
        double dy = cameraHelioJ2000[1] - bodyPos[1];
        double dz = cameraHelioJ2000[2] - bodyPos[2];
        double currentDist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (currentDist > 0) {
            double scale = targetDist / currentDist;
            cameraHelioJ2000[0] = bodyPos[0] + dx * scale;
            cameraHelioJ2000[1] = bodyPos[1] + dy * scale;
            cameraHelioJ2000[2] = bodyPos[2] + dz * scale;
        }
        return true;
    }

    /**
     * Apply zoom transition: lerp distance from focus body.
     *
     * @return {@code false} if focus body position unavailable (early completion)
     */
    private boolean applyZoomTransition(CameraTransition transition, double t, double[] cameraHelioJ2000) {
        double targetDist = lerp(transition.getStartDistanceKm(), transition.getEndDistanceKm(), t);

        double[] focusPos = getBodyPos(transition.getTargetNaifId());
        if (focusPos == null) {
            logger.warn("ZOOM: NAIF {} temporarily unavailable; completing early", transition.getTargetNaifId());
            return false;
        }

        applyDistanceChange(focusPos, -1, targetDist, cameraHelioJ2000);
        return true;
    }

    /** Apply FOV transition: lerp between start and end FOV. */
    private static void applyFovTransition(CameraTransition transition, double t, Camera cam) {
        double fov = lerp(transition.getStartFov(), transition.getEndFov(), t);
        cam.setFov((float) fov);
    }

    /**
     * Apply orbit transition: slerp orientation + lerp position offset relative to focus body.
     *
     * @return {@code false} if focus body position unavailable (early completion)
     */
    private boolean applyOrbitTransition(CameraTransition transition, double t, Camera cam, double[] cameraHelioJ2000) {
        double[] focusPos = getBodyPos(transition.getTargetNaifId());
        if (focusPos == null) {
            logger.warn("ORBIT: NAIF {} temporarily unavailable; completing early", transition.getTargetNaifId());
            return false;
        }

        // Slerp orientation
        Quaternion slerped = new Quaternion();
        slerped.slerp(transition.getStartOrientation(), transition.getEndOrientation(), (float) t);
        cam.setAxes(slerped.normalizeLocal());

        // Lerp position offset
        double[] startOff = transition.getStartOffset();
        double[] endOff = transition.getEndOffset();
        cameraHelioJ2000[0] = focusPos[0] + lerp(startOff[0], endOff[0], t);
        cameraHelioJ2000[1] = focusPos[1] + lerp(startOff[1], endOff[1], t);
        cameraHelioJ2000[2] = focusPos[2] + lerp(startOff[2], endOff[2], t);
        return true;
    }

    /**
     * Apply camera position transition: lerp offset relative to origin body.
     *
     * @return {@code false} if origin body position unavailable (early completion)
     */
    private boolean applyCameraPositionTransition(CameraTransition transition, double t, double[] cameraHelioJ2000) {
        double[] originPos = getBodyPos(transition.getTargetNaifId());
        if (originPos == null) {
            logger.warn(
                    "CAMERA_POSITION: NAIF {} temporarily unavailable; completing early", transition.getTargetNaifId());
            return false;
        }

        double[] startOff = transition.getStartOffset();
        double[] endOff = transition.getEndOffset();
        cameraHelioJ2000[0] = originPos[0] + lerp(startOff[0], endOff[0], t);
        cameraHelioJ2000[1] = originPos[1] + lerp(startOff[1], endOff[1], t);
        cameraHelioJ2000[2] = originPos[2] + lerp(startOff[2], endOff[2], t);
        return true;
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    /** Cancel active transition and pending goTo for a new Step 19c request. */
    private void cancelForNewRequest() {
        active = null;
        pendingGoTo = null;
    }

    private void startGoToNow(GoToRequest r, double[] cameraHelioJ2000) {
        double et = state.currentEtProperty().get();
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();

        // Resolve target body shape for end-distance computation
        EphemerisID id = eph.getSpiceBundle().getObject(r.naifId());
        if (id == null) {
            logger.warn("GO_TO: NAIF {} not in kernel; skipping transition", r.naifId());
            return;
        }
        Ellipsoid shape = eph.getShape(id);
        if (shape == null) {
            logger.warn("GO_TO: no shape data for NAIF {}; skipping transition", r.naifId());
            return;
        }
        double meanRadius = (shape.getA() + shape.getB() + shape.getC()) / 3.0;
        // endDistance = bodyRadius / tan(apparentRadiusDeg) (REDESIGN.md §4.5)
        double endDistKm = meanRadius / Math.tan(Math.toRadians(r.apparentRadiusDeg()));

        // Current heliocentric position of the target body
        VectorIJK bodyPos = eph.getHeliocentricPositionJ2000(r.naifId(), et);
        if (bodyPos == null) {
            logger.warn("GO_TO: NAIF {} has no ephemeris at ET={}; skipping transition", r.naifId(), et);
            return;
        }

        double dx = cameraHelioJ2000[0] - bodyPos.getI();
        double dy = cameraHelioJ2000[1] - bodyPos.getJ();
        double dz = cameraHelioJ2000[2] - bodyPos.getK();
        double startDistKm = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (r.durationSeconds() <= KepplrConstants.CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC) {
            // Instant snap
            if (startDistKm > 0) {
                double scale = endDistKm / startDistKm;
                cameraHelioJ2000[0] = bodyPos.getI() + dx * scale;
                cameraHelioJ2000[1] = bodyPos.getJ() + dy * scale;
                cameraHelioJ2000[2] = bodyPos.getK() + dz * scale;
            }
            return;
        }

        active = CameraTransition.goTo(r.naifId(), startDistKm, endDistKm, r.durationSeconds());
    }

    /** Get heliocentric J2000 position of a body at the current ET. Returns null if unavailable. */
    private double[] getBodyPos(int naifId) {
        double et = state.currentEtProperty().get();
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        VectorIJK pos = eph.getHeliocentricPositionJ2000(naifId, et);
        if (pos == null) return null;
        return new double[] {pos.getI(), pos.getJ(), pos.getK()};
    }

    /** Clamp zoom distance to valid range for the given focus body. */
    private double clampZoomDistance(int focusId, double distKm) {
        double minDist = getBodyMinDist(focusId);
        return Math.max(minDist, Math.min(KepplrConstants.FRUSTUM_FAR_MAX_KM, distKm));
    }

    /** Get minimum zoom distance for a body (1.1× mean radius or fallback). */
    private double getBodyMinDist(int focusId) {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        EphemerisID id = eph.getSpiceBundle().getObject(focusId);
        if (id != null) {
            Ellipsoid shape = eph.getShape(id);
            if (shape != null) {
                double meanRadius = (shape.getA() + shape.getB() + shape.getC()) / 3.0;
                return meanRadius * KepplrConstants.CAMERA_ZOOM_BODY_RADIUS_FACTOR;
            }
        }
        return KepplrConstants.CAMERA_ZOOM_FALLBACK_MIN_KM;
    }

    /** Apply a distance change along the camera-to-focus direction. If currentDist is -1, it is recomputed. */
    private static void applyDistanceChange(
            double[] focusPos, double currentDist, double targetDist, double[] cameraHelioJ2000) {
        double dx = cameraHelioJ2000[0] - focusPos[0];
        double dy = cameraHelioJ2000[1] - focusPos[1];
        double dz = cameraHelioJ2000[2] - focusPos[2];
        double dist = currentDist > 0 ? currentDist : Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > 0) {
            double scale = targetDist / dist;
            cameraHelioJ2000[0] = focusPos[0] + dx * scale;
            cameraHelioJ2000[1] = focusPos[1] + dy * scale;
            cameraHelioJ2000[2] = focusPos[2] + dz * scale;
        }
    }

    /**
     * Push transition active/progress into {@link DefaultSimulationState} so the JavaFX bridge can propagate them.
     *
     * <p>Called at the end of every {@link #update} path and after {@link #cancel()}.
     */
    private void updateStateProperties() {
        boolean anyActive = active != null || pendingGoTo != null;
        state.setTransitionActive(anyActive);
        state.setTransitionProgress(getProgress());
    }

    /**
     * Build a look-at quaternion such that after {@code cam.setAxes(q)}, {@code cam.getDirection()} equals {@code dir}.
     *
     * <p>In JME 3.8, {@link com.jme3.renderer.Camera#setAxes(Quaternion)} stores the quaternion directly as
     * {@code this.rotation}, and {@link com.jme3.renderer.Camera#getDirection()} returns
     * {@code rotation.getRotationColumn(2)} — i.e., column 2 of the rotation matrix. {@link Quaternion#lookAt} stores
     * its {@code direction} argument as column 2 of the resulting rotation matrix. Therefore {@code q.lookAt(dir, up)}
     * → column 2 = {@code dir} → {@code cam.getDirection() = dir}, which is exactly what callers expect.
     *
     * @param dir normalised look direction in world space
     * @param refUp reference up vector (e.g., the camera's current up)
     * @return normalised quaternion encoding the desired camera orientation
     */
    static Quaternion buildLookAtQuaternion(Vector3f dir, Vector3f refUp) {
        Quaternion q = new Quaternion();
        q.lookAt(dir, refUp);
        return q.normalizeLocal();
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
