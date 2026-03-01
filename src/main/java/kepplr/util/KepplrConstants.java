package kepplr.util;

/**
 * Named constants for thresholds, frustum ranges, and rendering parameters defined in REDESIGN.md.
 *
 * <p>No magic numbers should appear in logic code; reference this class instead (CLAUDE.md Rule 5).
 */
public final class KepplrConstants {

    private KepplrConstants() {}

    // ── Frustum ranges (REDESIGN.md §8.1), in km ──

    /** Near frustum minimum distance (1 meter = 0.001 km). */
    public static final double FRUSTUM_NEAR_MIN_KM = 0.001;

    /** Near frustum maximum distance. */
    public static final double FRUSTUM_NEAR_MAX_KM = 1_000.0;

    /** Mid frustum minimum distance. */
    public static final double FRUSTUM_MID_MIN_KM = 1_000.0;

    /** Mid frustum maximum distance. */
    public static final double FRUSTUM_MID_MAX_KM = 1.0e9;

    /** Far frustum minimum distance. */
    public static final double FRUSTUM_FAR_MIN_KM = 1.0e9;

    /** Far frustum maximum distance. */
    public static final double FRUSTUM_FAR_MAX_KM = 1.0e15;

    // ── Frustum overlap (REDESIGN.md §8.2) ──

    /** Adjacent frustum overlap fraction (hard-coded at 10%). */
    public static final double FRUSTUM_OVERLAP_FRACTION = 0.10;

    // ── Small-body culling (REDESIGN.md §7.3) ──

    /** Apparent-radius threshold in pixels below which a body may be drawn as a point sprite. */
    public static final double POINT_SPRITE_THRESHOLD_PX = 2.0;

    /**
     * Apparent-radius threshold in pixels below which a satellite must not be drawn. Satellites are defined by NAIF ID
     * rules (see §7.3).
     */
    public static final double SATELLITE_CULL_THRESHOLD_PX = 2.0;

    // ── Trail sampling (REDESIGN.md §7.5) ──

    /** Default number of samples per orbital period for trajectory trails. */
    public static final int TRAIL_SAMPLES_PER_PERIOD = 180;

    /** Default trail duration in seconds when orbital period is unknown (30 days). */
    public static final double TRAIL_DEFAULT_DURATION_SEC = 30.0 * 86_400.0;

    // ── Time (REDESIGN.md §2.3) ──

    /** Default time rate: 1 simulation second per wall-clock second. */
    public static final double DEFAULT_TIME_RATE = 1.0;
}
