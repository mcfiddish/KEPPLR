package kepplr.camera;

import com.jme3.math.Quaternion;
import kepplr.util.KepplrConstants;

/**
 * Represents an in-progress camera transition initiated by {@code pointAt()} or {@code goTo()}.
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
        GO_TO
    }

    private final Type type;
    private final int targetNaifId;

    // ── POINT_AT fields ──
    private final Quaternion startOrientation;
    private final Quaternion endOrientation;

    // ── GO_TO fields ──
    private final double startDistanceKm;
    private final double endDistanceKm;

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
        return new CameraTransition(Type.POINT_AT, naifId, start.clone(), end.clone(), 0.0, 0.0, durationSeconds);
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
        return new CameraTransition(Type.GO_TO, naifId, null, null, startDistKm, endDistKm, durationSeconds);
    }

    private CameraTransition(
            Type type,
            int targetNaifId,
            Quaternion startOrientation,
            Quaternion endOrientation,
            double startDistanceKm,
            double endDistanceKm,
            double durationSeconds) {
        this.type = type;
        this.targetNaifId = targetNaifId;
        this.startOrientation = startOrientation;
        this.endOrientation = endOrientation;
        this.startDistanceKm = startDistanceKm;
        this.endDistanceKm = endDistanceKm;
        this.durationSeconds = durationSeconds;
        this.elapsedSeconds = 0.0;
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    /** The kind of camera movement this transition performs. */
    public Type getType() {
        return type;
    }

    /** NAIF ID of the body this transition is targeting. */
    public int getTargetNaifId() {
        return targetNaifId;
    }

    /** Camera orientation at the start of this transition (POINT_AT only). */
    public Quaternion getStartOrientation() {
        return startOrientation;
    }

    /** Desired camera orientation at the end of this transition (POINT_AT only). */
    public Quaternion getEndOrientation() {
        return endOrientation;
    }

    /** Camera distance from the target body at the start of this transition (GO_TO only, km). */
    public double getStartDistanceKm() {
        return startDistanceKm;
    }

    /** Desired camera distance from the target body at the end of this transition (GO_TO only, km). */
    public double getEndDistanceKm() {
        return endDistanceKm;
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
