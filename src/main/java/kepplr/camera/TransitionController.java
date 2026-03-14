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
 * Owns and advances all in-progress camera transitions (Step 18).
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

    private sealed interface PendingRequest permits PointAtRequest, GoToRequest {}

    private record PointAtRequest(int naifId, double durationSeconds) implements PendingRequest {}

    private record GoToRequest(int naifId, double apparentRadiusDeg, double durationSeconds)
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
     * @param cam JME camera; orientation mutated for {@link CameraTransition.Type#POINT_AT} transitions
     * @param cameraHelioJ2000 heliocentric J2000 camera position in km (length-3); mutated for
     *     {@link CameraTransition.Type#GO_TO} transitions
     * @return {@code true} if a transition completed this frame
     */
    public boolean update(float tpf, Camera cam, double[] cameraHelioJ2000) {
        // Drain the thread-safe inbox on the JME thread
        PendingRequest req;
        while ((req = inbox.poll()) != null) {
            if (req instanceof PointAtRequest r) {
                handlePointAtRequest(r, cam, cameraHelioJ2000);
            } else if (req instanceof GoToRequest r) {
                handleGoToRequest(r, cameraHelioJ2000);
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
        if (active.getType() == CameraTransition.Type.POINT_AT) {
            applyPointAt(active, t, cam);
        } else {
            earlyComplete = !applyGoTo(active, t, cameraHelioJ2000);
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

    // ── Private JME-thread helpers ─────────────────────────────────────────────

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

    /** Apply spherical linear interpolation to the camera orientation for a POINT_AT transition. */
    private static void applyPointAt(CameraTransition transition, double t, Camera cam) {
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

        double et = state.currentEtProperty().get();
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        VectorIJK bodyPos = eph.getHeliocentricPositionJ2000(transition.getTargetNaifId(), et);
        if (bodyPos == null) {
            logger.warn("GO_TO: NAIF {} temporarily unavailable; completing early", transition.getTargetNaifId());
            return false;
        }

        double dx = cameraHelioJ2000[0] - bodyPos.getI();
        double dy = cameraHelioJ2000[1] - bodyPos.getJ();
        double dz = cameraHelioJ2000[2] - bodyPos.getK();
        double currentDist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (currentDist > 0) {
            double scale = targetDist / currentDist;
            cameraHelioJ2000[0] = bodyPos.getI() + dx * scale;
            cameraHelioJ2000[1] = bodyPos.getJ() + dy * scale;
            cameraHelioJ2000[2] = bodyPos.getK() + dz * scale;
        }
        return true;
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
