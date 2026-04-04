package kepplr.camera;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import java.util.concurrent.ConcurrentLinkedQueue;
import kepplr.config.KEPPLRConfiguration;
import kepplr.config.SpacecraftBlock;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.render.body.BodySceneManager;
import kepplr.state.DefaultSimulationState;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.RotationMatrixIJK;
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
 * <p>All Step 19c camera commands (zoom, orbit, tilt, yaw, roll, fov, setCameraPosition, setCameraOrientation) cancel
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
                    CameraOrientationRequest,
                    TranslateRequest,
                    CancelRequest,
                    FollowRequest {}

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

    private record CameraOrientationRequest(
            double lookX, double lookY, double lookZ, double upX, double upY, double upZ, double durationSeconds)
            implements PendingRequest {}

    /** Identifies which camera axis a translate request uses. */
    private enum TranslateAxis {
        RIGHT,
        UP,
        FORWARD
    }

    /**
     * Unified request for truck/crane/dolly. The translation axis is resolved from the camera's orientation when the
     * request is handled on the JME thread, then held fixed for the duration. All three cinematic commands share the
     * same handler — only the axis differs.
     */
    private record TranslateRequest(TranslateAxis axis, double km, double durationSeconds) implements PendingRequest {}

    private record CancelRequest() implements PendingRequest {}

    /** Request to establish body-following at a fixed distance, posted from the commands thread. */
    private record FollowRequest(int naifId, double distKm) implements PendingRequest {}

    private final ConcurrentLinkedQueue<PendingRequest> inbox = new ConcurrentLinkedQueue<>();

    // ── JME-thread-only state ─────────────────────────────────────────────────

    private final DefaultSimulationState state;

    /**
     * Scene-graph radius provider; set by {@code KepplrApp} after the {@link BodySceneManager} is created. May be
     * {@code null} if not yet wired (e.g., during unit tests). When non-null, effective body radii observed during
     * rendering are preferred over config-derived estimates in {@link #getBodyMinDist} and {@link #startGoToNow}.
     */
    private BodySceneManager bodySceneManager;

    /** Active in-progress transition, or {@code null} if none. */
    private CameraTransition active = null;

    /**
     * A {@code goTo} waiting for an active {@code pointAt} to complete before starting. Replaced if a new {@code goTo}
     * arrives while another is already pending. {@code null} if none.
     */
    private GoToRequest pendingGoTo = null;

    /**
     * NAIF ID of the body the camera is following after a {@code goTo} transition, or {@code -1} if not following.
     * Body-following maintains a fixed radial distance from the body's heliocentric J2000 position each frame so that
     * the body remains at a constant apparent size as time advances. Cleared by any new transition request or explicit
     * cancel. Set by {@code requestFollow} and automatically when a {@code goTo} transition completes.
     */
    private int followBodyId = -1;

    /** Distance to maintain from the followed body in km. Valid only when {@code followBodyId != -1}. */
    private double followDistKm = 0.0;

    // ── Constructor ───────────────────────────────────────────────────────────

    /** @param state mutable simulation state; updated by this controller each frame with transition active/progress */
    public TransitionController(DefaultSimulationState state) {
        this.state = state;
    }

    /**
     * Wire the {@link BodySceneManager} so that effective rendered radii are used for goTo end-distance and zoom
     * clamping. Must be called on the JME render thread after {@code BodySceneManager} is created (and again if it is
     * recreated on config reload).
     */
    public void setBodySceneManager(BodySceneManager bsm) {
        this.bodySceneManager = bsm;
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

    /** Request a camera orientation transition (Step 19c). Thread-safe. */
    public void requestCameraOrientation(
            double lookX, double lookY, double lookZ, double upX, double upY, double upZ, double durationSeconds) {
        inbox.add(new CameraOrientationRequest(lookX, lookY, lookZ, upX, upY, upZ, durationSeconds));
    }

    /** Request a truck (screen-right) translation (Step 24). Thread-safe. */
    public void requestTruck(double km, double durationSeconds) {
        inbox.add(new TranslateRequest(TranslateAxis.RIGHT, km, durationSeconds));
    }

    /** Request a crane (screen-up) translation (Step 24). Thread-safe. */
    public void requestCrane(double km, double durationSeconds) {
        inbox.add(new TranslateRequest(TranslateAxis.UP, km, durationSeconds));
    }

    /** Request a dolly (look-direction) translation (Step 24). Thread-safe. */
    public void requestDolly(double km, double durationSeconds) {
        inbox.add(new TranslateRequest(TranslateAxis.FORWARD, km, durationSeconds));
    }

    /**
     * Request cancellation of the active transition and any pending requests (Step 20).
     *
     * <p>Thread-safe. The cancel is enqueued and processed on the next JME render frame. This is the thread-safe
     * alternative to {@link #cancel()} which must be called from the JME thread.
     */
    public void requestCancel() {
        inbox.add(new CancelRequest());
    }

    /**
     * Request that the camera begin following a body at the given distance.
     *
     * <p>Thread-safe. The request is enqueued and processed on the next JME render frame. Enqueue this <em>after</em>
     * any {@link #requestCancel} in the same command sequence so that the follow state is established after
     * cancellation is processed.
     *
     * @param naifId NAIF ID of the body to follow
     * @param distKm distance to maintain from the body in km
     */
    public void requestFollow(int naifId, double distKm) {
        inbox.add(new FollowRequest(naifId, distKm));
    }

    // ── JME-thread methods ────────────────────────────────────────────────────

    /**
     * Cancel the active animated transition and any pending {@code goTo}.
     *
     * <p>Must be called on the JME render thread. Called by {@code KepplrApp.simpleUpdate()} when manual navigation
     * input is detected. Does <b>not</b> clear the inbox, because manual navigation actions (mouse drag, keyboard) now
     * post instant requests to the inbox in the same frame (Step 19c). Those requests must survive the cancel and be
     * processed by the subsequent {@link #update} call.
     */
    public void cancel() {
        active = null;
        pendingGoTo = null;
        followBodyId = -1;
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
                case CameraOrientationRequest r -> handleCameraOrientationRequest(r, cam);
                case TranslateRequest r -> handleTranslateRequest(r, cam, cameraHelioJ2000);
                case CancelRequest r -> {
                    active = null;
                    pendingGoTo = null;
                    followBodyId = -1;
                }
                case FollowRequest r -> {
                    followBodyId = r.naifId();
                    followDistKm = r.distKm();
                }
            }
        }

        // If a pending goTo is ready to start (no active pointAt), start it now
        if (active == null && pendingGoTo != null) {
            GoToRequest r = pendingGoTo;
            pendingGoTo = null;
            startGoToNow(r, cameraHelioJ2000);
        }

        // Body-following: after a goTo completes, maintain a fixed radial distance from the body so
        // the body stays at a constant apparent size as simulation time advances.
        if (active == null && followBodyId != -1) {
            double[] bodyPos = getBodyPos(followBodyId);
            if (bodyPos != null) {
                double dx = cameraHelioJ2000[0] - bodyPos[0];
                double dy = cameraHelioJ2000[1] - bodyPos[1];
                double dz = cameraHelioJ2000[2] - bodyPos[2];
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist > 0) {
                    double scale = followDistKm / dist;
                    cameraHelioJ2000[0] = bodyPos[0] + dx * scale;
                    cameraHelioJ2000[1] = bodyPos[1] + dy * scale;
                    cameraHelioJ2000[2] = bodyPos[2] + dz * scale;
                }
            }
        }

        if (active == null) {
            updateStateProperties();
            return false;
        }

        // Advance elapsed time and compute interpolation parameter
        active.advanceElapsed(tpf);
        double t = active.getT();

        // Apply smoothstep easing: t² × (3 − 2t). Produces ease-in-out (acceleration from rest,
        // deceleration to rest). Instant transitions (t == 1.0 immediately) are unaffected.
        if (KepplrConstants.TRANSITION_EASING_ENABLED) {
            t = smoothstep(t);
        }

        boolean earlyComplete = false;
        switch (active.getType()) {
            case POINT_AT, TILT, YAW, ROLL, CAMERA_LOOK_DIRECTION -> applyOrientationTransition(active, t, cam);
            case GO_TO -> earlyComplete = !applyGoTo(active, t, cameraHelioJ2000);
            case ZOOM -> earlyComplete = !applyZoomTransition(active, t, cameraHelioJ2000);
            case FOV -> applyFovTransition(active, t, cam);
            case ORBIT -> earlyComplete = !applyOrbitTransition(active, t, cam, cameraHelioJ2000);
            case CAMERA_POSITION -> earlyComplete = !applyCameraPositionTransition(active, t, cameraHelioJ2000);
            case TRANSLATE -> applyTranslateTransition(active, t, cameraHelioJ2000);
        }

        if (t >= 1.0 || earlyComplete) {
            CameraTransition.Type completedType = active.getType();
            int completedNaifId = active.getTargetNaifId();
            double completedEndDist = active.getEndDistanceKm();
            active = null;

            // If a pointAt just completed and a goTo is waiting, start the goTo immediately
            if (completedType == CameraTransition.Type.POINT_AT && pendingGoTo != null) {
                GoToRequest r = pendingGoTo;
                pendingGoTo = null;
                startGoToNow(r, cameraHelioJ2000);
            }

            // A completed goTo (normal or early) establishes body-following so the camera tracks
            // the body as simulation time advances.
            if (completedType == CameraTransition.Type.GO_TO && !earlyComplete) {
                followBodyId = completedNaifId;
                followDistKm = completedEndDist;
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
        followBodyId = -1;

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
        // Transform the requested offset from the active camera frame to J2000
        VectorIJK endJ2000 = frameToJ2000(new VectorIJK(r.x(), r.y(), r.z()));
        double[] endOff = {endJ2000.getI(), endJ2000.getJ(), endJ2000.getK()};

        if (r.durationSeconds() <= KepplrConstants.CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC) {
            cameraHelioJ2000[0] = originPos[0] + endOff[0];
            cameraHelioJ2000[1] = originPos[1] + endOff[1];
            cameraHelioJ2000[2] = originPos[2] + endOff[2];
            state.setCameraPositionJ2000(cameraHelioJ2000);
            return;
        }

        active = CameraTransition.cameraPosition(originId, startOff, endOff, r.durationSeconds());
    }

    private void handleCameraOrientationRequest(CameraOrientationRequest r, Camera cam) {
        cancelForNewRequest();

        // Transform look and up vectors from the active camera frame to J2000
        VectorIJK lookJ2000 = frameToJ2000(new VectorIJK(r.lookX(), r.lookY(), r.lookZ()));
        VectorIJK upJ2000 = frameToJ2000(new VectorIJK(r.upX(), r.upY(), r.upZ()));

        Vector3f lookDir = new Vector3f((float) lookJ2000.getI(), (float) lookJ2000.getJ(), (float) lookJ2000.getK());
        Vector3f upDir = new Vector3f((float) upJ2000.getI(), (float) upJ2000.getJ(), (float) upJ2000.getK());
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

    private void handleTranslateRequest(TranslateRequest r, Camera cam, double[] cameraHelioJ2000) {
        cancelForNewRequest();

        // Resolve the translation axis from the camera's current orientation (captured here on the JME thread)
        Vector3f axis =
                switch (r.axis()) {
                    case RIGHT -> cam.getLeft().negate();
                    case UP -> cam.getUp().clone();
                    case FORWARD -> cam.getDirection().normalize();
                };

        double[] startPos = cameraHelioJ2000.clone();
        double[] endPos = {
            cameraHelioJ2000[0] + axis.x * r.km(),
            cameraHelioJ2000[1] + axis.y * r.km(),
            cameraHelioJ2000[2] + axis.z * r.km()
        };

        if (r.durationSeconds() <= KepplrConstants.CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC) {
            cameraHelioJ2000[0] = endPos[0];
            cameraHelioJ2000[1] = endPos[1];
            cameraHelioJ2000[2] = endPos[2];
            state.setCameraPositionJ2000(cameraHelioJ2000);
            return;
        }

        active = CameraTransition.translate(startPos, endPos, r.durationSeconds());
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

    /** Cancel active transition, pending goTo, and body-following for a new request. */
    private void cancelForNewRequest() {
        active = null;
        pendingGoTo = null;
        followBodyId = -1;
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
        double meanRadius;
        if (shape != null) {
            meanRadius = (shape.getA() + shape.getB() + shape.getC()) / 3.0;
        } else {
            // Spacecraft: prefer the GLB bounding-sphere radius observed during rendering (C2).
            double sceneRadius = bodySceneManager != null ? bodySceneManager.getEffectiveBodyRadiusKm(r.naifId()) : 0.0;
            if (sceneRadius > 0.0) {
                meanRadius = sceneRadius;
            } else {
                SpacecraftBlock block = KEPPLRConfiguration.getInstance().spacecraftBlock(r.naifId());
                meanRadius = block != null ? block.scale() * 0.001 : KepplrConstants.BODY_DEFAULT_RADIUS_KM;
            }
        }
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
            followBodyId = r.naifId();
            followDistKm = endDistKm;
            return;
        }

        active = CameraTransition.goTo(r.naifId(), startDistKm, endDistKm, r.durationSeconds());
    }

    /**
     * Transform a vector from the active camera frame to J2000.
     *
     * <p>For INERTIAL, vectors pass through unchanged. For SYNODIC, the vector is expressed as a linear combination of
     * the synodic basis vectors (which are in J2000). For BODY_FIXED, the transpose of the J2000→body-fixed rotation
     * matrix is applied via {@link RotationMatrixIJK#mtxv}.
     *
     * @param v vector in frame-local coordinates
     * @return the vector in J2000, or the original vector unchanged if the frame cannot be resolved (fallback to
     *     inertial)
     */
    private VectorIJK frameToJ2000(VectorIJK v) {
        CameraFrame frame = state.cameraFrameProperty().get();
        double et = state.currentEtProperty().get();

        if (frame == CameraFrame.SYNODIC) {
            int synodicFocus = state.synodicFrameFocusIdProperty().get();
            int synodicSelected = state.synodicFrameSelectedIdProperty().get();
            int focusId = (synodicFocus != -1)
                    ? synodicFocus
                    : state.focusedBodyIdProperty().get();
            int selectedId = (synodicSelected != -1)
                    ? synodicSelected
                    : state.selectedBodyIdProperty().get();
            SynodicFrame.Basis basis = SynodicFrame.compute(focusId, selectedId, et);
            if (basis != null) {
                RotationMatrixIJK synodicToJ2000 = new RotationMatrixIJK(basis.xAxis(), basis.yAxis(), basis.zAxis());
                VectorIJK result = new VectorIJK();
                synodicToJ2000.mxv(v, result);
                return result;
            }
        } else if (frame == CameraFrame.BODY_FIXED) {
            int focusId = state.focusedBodyIdProperty().get();
            if (focusId != -1) {
                RotationMatrixIJK rot =
                        KEPPLRConfiguration.getInstance().getEphemeris().getJ2000ToBodyFixedRotation(focusId, et);
                if (rot != null) {
                    VectorIJK result = new VectorIJK();
                    rot.mtxv(v, result);
                    return result;
                }
            }
        }

        // INERTIAL or fallback
        return v;
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
        if (id == null) {
            return KepplrConstants.CAMERA_ZOOM_FALLBACK_MIN_KM;
        }
        Ellipsoid shape = eph.getShape(id);
        double meanRadius;
        if (shape != null) {
            meanRadius = (shape.getA() + shape.getB() + shape.getC()) / 3.0;
        } else {
            // Spacecraft or shape-less body: prefer the GLB bounding-sphere radius observed during rendering (C2).
            double sceneRadius = bodySceneManager != null ? bodySceneManager.getEffectiveBodyRadiusKm(focusId) : 0.0;
            if (sceneRadius > 0.0) {
                meanRadius = sceneRadius;
            } else {
                SpacecraftBlock block = KEPPLRConfiguration.getInstance().spacecraftBlock(focusId);
                meanRadius = block != null ? block.scale() * 0.001 : KepplrConstants.BODY_DEFAULT_RADIUS_KM;
            }
        }
        return meanRadius * KepplrConstants.CAMERA_ZOOM_BODY_RADIUS_FACTOR;
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

    /** Apply translate transition: lerp camera position between start and end (truck/crane/dolly). */
    private static void applyTranslateTransition(CameraTransition transition, double t, double[] cameraHelioJ2000) {
        double[] startOff = transition.getStartOffset();
        double[] endOff = transition.getEndOffset();
        cameraHelioJ2000[0] = lerp(startOff[0], endOff[0], t);
        cameraHelioJ2000[1] = lerp(startOff[1], endOff[1], t);
        cameraHelioJ2000[2] = lerp(startOff[2], endOff[2], t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Smoothstep easing: {@code t² × (3 − 2t)}. Maps [0,1] → [0,1] with zero derivative at both endpoints. */
    static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }
}
