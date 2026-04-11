package kepplr.camera;

import com.jme3.math.Quaternion;
import kepplr.util.KepplrConstants;

/**
 * Represents an in-progress camera transition initiated by a {@link TransitionController} request.
 *
 * <p>Start and end values, target body, and duration are immutable after construction. Elapsed time is mutable and
 * advanced each frame by {@link TransitionController} on the JME render thread.
 *
 * <p>This class is a package-private value type; the public API is {@link TransitionController}.
 */
public final class CameraTransition {

    /** Identifies the kind of camera movement this transition performs. */
    public enum Type {
        /** Slew the camera orientation to point at a target body (position unchanged). */
        POINT_AT,
        /** Translate the camera along its look direction to reach a target apparent radius. */
        GO_TO,
        /** Multiplicative zoom: lerp distance from focus body (Step 19c). */
        ZOOM,
        /** Field-of-view transition: lerp FOV in degrees (Step 19c). */
        FOV,
        /** Orbit around focus body: slerp orientation + lerp position offset (Step 19c). */
        ORBIT,
        /** Tilt in place (pitch around screen-right): slerp orientation (Step 19c). */
        TILT,
        /** Yaw in place (around screen-up): slerp orientation (Step 19c). */
        YAW,
        /** Roll in place (around look axis): slerp orientation (Step 19c). */
        ROLL,
        /** Translate to explicit position offset relative to an origin body (Step 19c). */
        CAMERA_POSITION,
        /** Set look direction and up vector: slerp orientation (Step 19c). */
        CAMERA_LOOK_DIRECTION,
        /** Set explicit position offset and look/up vectors together (Step 19c). */
        CAMERA_POSE,
        /** Pure spatial translation along a fixed axis: lerp position (Step 24 — truck/crane/dolly). */
        TRANSLATE
    }

    private final Type type;
    private final int targetNaifId;

    // ── Orientation fields (POINT_AT, ORBIT, TILT, YAW, ROLL, CAMERA_LOOK_DIRECTION, CAMERA_POSE) ──
    private final Quaternion startOrientation;
    private final Quaternion endOrientation;

    // ── Distance fields (GO_TO, ZOOM) ──
    private final double startDistanceKm;
    private final double endDistanceKm;

    // ── FOV fields ──
    private final double startFov;
    private final double endFov;

    // ── Position offset fields (ORBIT, CAMERA_POSITION, CAMERA_POSE) ──
    private final double[] startOffset;
    private final double[] endOffset;

    private final double durationSeconds;
    private double elapsedSeconds;

    // ── Factory methods ────────────────────────────────────────────────────────

    /**
     * Create a {@link Type#POINT_AT} transition.
     *
     * @param naifId NAIF ID of the target body
     * @param start camera orientation at transition start; cloned for safety
     * @param end desired camera orientation at transition end; cloned for safety
     * @param durationSeconds total slew duration in seconds
     */
    public static CameraTransition pointAt(int naifId, Quaternion start, Quaternion end, double durationSeconds) {
        return new CameraTransition(
                Type.POINT_AT, naifId, start.clone(), end.clone(), 0, 0, 0, 0, null, null, durationSeconds);
    }

    /**
     * Create a {@link Type#GO_TO} transition.
     *
     * @param naifId NAIF ID of the target body (used to look up position each frame)
     * @param startDistKm camera distance from target body at transition start (km)
     * @param endDistKm desired camera distance from target body at transition end (km)
     * @param durationSeconds total translation duration in seconds
     */
    public static CameraTransition goTo(int naifId, double startDistKm, double endDistKm, double durationSeconds) {
        return new CameraTransition(
                Type.GO_TO, naifId, null, null, startDistKm, endDistKm, 0, 0, null, null, durationSeconds);
    }

    /**
     * Create a {@link Type#ZOOM} transition.
     *
     * @param focusNaifId NAIF ID of the focus body
     * @param startDistKm current camera distance from focus body (km)
     * @param endDistKm target camera distance from focus body (km)
     * @param durationSeconds total duration in seconds
     */
    public static CameraTransition zoom(int focusNaifId, double startDistKm, double endDistKm, double durationSeconds) {
        return new CameraTransition(
                Type.ZOOM, focusNaifId, null, null, startDistKm, endDistKm, 0, 0, null, null, durationSeconds);
    }

    /**
     * Create a {@link Type#FOV} transition.
     *
     * @param startFov current FOV in degrees
     * @param endFov target FOV in degrees
     * @param durationSeconds total duration in seconds
     */
    public static CameraTransition fov(double startFov, double endFov, double durationSeconds) {
        return new CameraTransition(Type.FOV, -1, null, null, 0, 0, startFov, endFov, null, null, durationSeconds);
    }

    /**
     * Create a {@link Type#ORBIT} transition.
     *
     * @param focusNaifId NAIF ID of the focus body
     * @param startQ camera orientation at transition start; cloned for safety
     * @param endQ camera orientation at transition end; cloned for safety
     * @param startOff camera offset from focus body at start (length 3, km); cloned
     * @param endOff camera offset from focus body at end (length 3, km); cloned
     * @param durationSeconds total duration in seconds
     */
    public static CameraTransition orbit(
            int focusNaifId,
            Quaternion startQ,
            Quaternion endQ,
            double[] startOff,
            double[] endOff,
            double durationSeconds) {
        return new CameraTransition(
                Type.ORBIT,
                focusNaifId,
                startQ.clone(),
                endQ.clone(),
                0,
                0,
                0,
                0,
                startOff.clone(),
                endOff.clone(),
                durationSeconds);
    }

    /**
     * Create an orientation-only transition (TILT, YAW, ROLL, or CAMERA_LOOK_DIRECTION).
     *
     * @param type one of TILT, YAW, ROLL, CAMERA_LOOK_DIRECTION
     * @param startQ camera orientation at transition start; cloned for safety
     * @param endQ camera orientation at transition end; cloned for safety
     * @param durationSeconds total duration in seconds
     */
    public static CameraTransition orientation(Type type, Quaternion startQ, Quaternion endQ, double durationSeconds) {
        return new CameraTransition(type, -1, startQ.clone(), endQ.clone(), 0, 0, 0, 0, null, null, durationSeconds);
    }

    /**
     * Create a {@link Type#CAMERA_POSITION} transition.
     *
     * @param originNaifId NAIF ID of the origin body
     * @param startOff camera offset from origin at start (length 3, km); cloned
     * @param endOff camera offset from origin at end (length 3, km); cloned
     * @param durationSeconds total duration in seconds
     */
    public static CameraTransition cameraPosition(
            int originNaifId, double[] startOff, double[] endOff, double durationSeconds) {
        return new CameraTransition(
                Type.CAMERA_POSITION,
                originNaifId,
                null,
                null,
                0,
                0,
                0,
                0,
                startOff.clone(),
                endOff.clone(),
                durationSeconds);
    }

    /**
     * Create a {@link Type#CAMERA_POSE} transition.
     *
     * @param originNaifId NAIF ID of the origin body
     * @param startOff camera offset from origin at start (length 3, km); cloned
     * @param endOff camera offset from origin at end (length 3, km); cloned
     * @param startQ camera orientation at transition start; cloned
     * @param endQ camera orientation at transition end; cloned
     * @param durationSeconds total duration in seconds
     */
    public static CameraTransition cameraPose(
            int originNaifId,
            double[] startOff,
            double[] endOff,
            Quaternion startQ,
            Quaternion endQ,
            double durationSeconds) {
        return new CameraTransition(
                Type.CAMERA_POSE,
                originNaifId,
                startQ.clone(),
                endQ.clone(),
                0,
                0,
                0,
                0,
                startOff.clone(),
                endOff.clone(),
                durationSeconds);
    }

    /**
     * Create a {@link Type#TRANSLATE} transition (truck, crane, or dolly).
     *
     * @param startPos camera heliocentric J2000 position at transition start (length 3, km); cloned
     * @param endPos camera heliocentric J2000 position at transition end (length 3, km); cloned
     * @param durationSeconds total duration in seconds
     */
    public static CameraTransition translate(double[] startPos, double[] endPos, double durationSeconds) {
        return new CameraTransition(
                Type.TRANSLATE, -1, null, null, 0, 0, 0, 0, startPos.clone(), endPos.clone(), durationSeconds);
    }

    private CameraTransition(
            Type type,
            int targetNaifId,
            Quaternion startOrientation,
            Quaternion endOrientation,
            double startDistanceKm,
            double endDistanceKm,
            double startFov,
            double endFov,
            double[] startOffset,
            double[] endOffset,
            double durationSeconds) {
        this.type = type;
        this.targetNaifId = targetNaifId;
        this.startOrientation = startOrientation;
        this.endOrientation = endOrientation;
        this.startDistanceKm = startDistanceKm;
        this.endDistanceKm = endDistanceKm;
        this.startFov = startFov;
        this.endFov = endFov;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.durationSeconds = durationSeconds;
        this.elapsedSeconds = 0.0;
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    /** The kind of camera movement this transition performs. */
    public Type getType() {
        return type;
    }

    /** NAIF ID of the body this transition is targeting or orbiting. */
    public int getTargetNaifId() {
        return targetNaifId;
    }

    /** Camera orientation at the start of this transition (orientation-based types). */
    public Quaternion getStartOrientation() {
        return startOrientation;
    }

    /** Desired camera orientation at the end of this transition (orientation-based types). */
    public Quaternion getEndOrientation() {
        return endOrientation;
    }

    /** Camera distance from the target body at the start of this transition (GO_TO, ZOOM; km). */
    public double getStartDistanceKm() {
        return startDistanceKm;
    }

    /** Desired camera distance from the target body at the end of this transition (GO_TO, ZOOM; km). */
    public double getEndDistanceKm() {
        return endDistanceKm;
    }

    /** Start FOV in degrees (FOV type only). */
    public double getStartFov() {
        return startFov;
    }

    /** End FOV in degrees (FOV type only). */
    public double getEndFov() {
        return endFov;
    }

    /** Start position offset from origin body (ORBIT, CAMERA_POSITION, CAMERA_POSE; length 3, km). */
    public double[] getStartOffset() {
        return startOffset;
    }

    /** End position offset from origin body (ORBIT, CAMERA_POSITION, CAMERA_POSE; length 3, km). */
    public double[] getEndOffset() {
        return endOffset;
    }

    /** Total duration of this transition in seconds. */
    public double getDurationSeconds() {
        return durationSeconds;
    }

    /** Elapsed simulation time for this transition in seconds. Set to zero at construction. */
    public double getElapsedSeconds() {
        return elapsedSeconds;
    }

    /**
     * Interpolation parameter {@code t ∈ [0.0, 1.0]}.
     *
     * <p>Returns {@code 1.0} immediately for zero-duration (instant) transitions. Clamped so it never exceeds 1.0.
     */
    public double getT() {
        if (durationSeconds <= KepplrConstants.CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC) {
            return 1.0;
        }
        return Math.min(1.0, elapsedSeconds / durationSeconds);
    }

    // ── Package-private mutation (TransitionController only) ──────────────────

    /**
     * Advance elapsed time by the given number of seconds.
     *
     * <p>Must only be called by {@link TransitionController} on the JME render thread.
     */
    void advanceElapsed(double seconds) {
        elapsedSeconds += seconds;
    }
}
