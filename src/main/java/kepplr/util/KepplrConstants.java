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
    public static final int TRAIL_SAMPLES_PER_PERIOD = 181;

    /** Default trail duration in seconds when orbital period is unknown (30 days). */
    public static final double TRAIL_DEFAULT_DURATION_SEC = 30.0 * 86_400.0;

    /**
     * Maximum simulation-time drift (seconds) before a cached trail is considered stale and must be
     * resampled. Trails are rebuilt when {@code |currentEt − sampledEt| > } this value.
     */
    public static final double TRAIL_STALENESS_THRESHOLD_SEC = 1_800.0; // 30 simulation minutes

    /**
     * Minimum wall-clock interval (seconds) between SPICE trail resample passes.
     *
     * <p>Prevents saturating the render thread with ephemeris calls when many trails are active and
     * simulation time is advancing rapidly. Geometry is still rebuilt from cached samples every frame.
     */
    public static final double TRAIL_RESAMPLE_WALL_INTERVAL_SEC = 5.0;

    // ── Time (REDESIGN.md §2.3) ──

    /** Default time rate: 1 simulation second per wall-clock second. */
    public static final double DEFAULT_TIME_RATE = 1.0;

    // ── World-space units (REDESIGN.md §2.1) ──

    /**
     * World-space units are kilometers. Ephemeris data from KEPPLREphemeris is in km; scene graph coordinates are in
     * km. Do not rescale to avoid precision issues — use floating origin instead.
     */
    public static final String WORLD_SPACE_UNIT = "km";

    // ── Body tessellation ──

    /** Sphere tessellation level (zSamples and radialSamples) for planet-class bodies. */
    public static final int BODY_SPHERE_TESSELLATION = 64;

    // ── Synodic frame (REDESIGN.md §5.2) ──

    /**
     * Degenerate-case threshold for the synodic frame (§5.2).
     *
     * <p>If the magnitude of +X × +Z_candidate is less than this value (in radians, equivalent to |sin θ| for unit
     * vectors), +X is considered too close to the secondary axis and Ecliptic J2000 +Z is used instead.
     */
    public static final double SYNODIC_DEGENERATE_THRESHOLD_RAD = 1e-3;

    /**
     * Obliquity of the ecliptic at the J2000 epoch (IAU 2000), in radians.
     *
     * <p>Used to derive the Ecliptic J2000 +Z axis = (0, −sin ε, cos ε) in J2000 coordinates, which is the fallback
     * secondary axis for the synodic frame degenerate case (§5.2).
     */
    public static final double ECLIPTIC_J2000_OBLIQUITY_RAD = Math.toRadians(23.439291111);

    // ── Camera navigation (Step 10) ──

    /**
     * Fractional distance change per scroll or keyboard zoom step.
     *
     * <p>New distance = current distance × {@code CAMERA_ZOOM_FACTOR_PER_STEP}^steps. Values less than 1.0 shrink
     * distance (zoom in) for a positive step count.
     */
    public static final double CAMERA_ZOOM_FACTOR_PER_STEP = 0.85;

    /** Keyboard orbit angular rate while key is held (radians per second). */
    public static final double CAMERA_KEYBOARD_ORBIT_RATE_RAD_PER_SEC = Math.toRadians(60.0);

    /** Keyboard tilt/roll angular rate while key is held (radians per second). */
    public static final double CAMERA_KEYBOARD_ROTATE_RATE_RAD_PER_SEC = Math.toRadians(90.0);

    /** Keyboard zoom rate while PgUp/PgDn is held (zoom steps per second). */
    public static final double CAMERA_KEYBOARD_ZOOM_RATE_STEPS_PER_SEC = 5.0;

    /**
     * Minimum zoom distance expressed as a multiple of the focus body's mean radius.
     *
     * <p>Camera cannot zoom closer than {@code bodyRadius × CAMERA_ZOOM_BODY_RADIUS_FACTOR}.
     */
    public static final double CAMERA_ZOOM_BODY_RADIUS_FACTOR = 1.1;

    /** Fallback minimum zoom distance in km, used when the focus body has no shape data. */
    public static final double CAMERA_ZOOM_FALLBACK_MIN_KM = 100.0;

    /** Mouse rotate sensitivity in radians per pixel. */
    public static final double CAMERA_MOUSE_ROTATE_SENSITIVITY = 0.001;

    /** Mouse orbit sensitivity in radians per pixel. */
    public static final double CAMERA_MOUSE_ORBIT_SENSITIVITY = 0.005;

    // ── Body rendering ──

    /**
     * Vertical field-of-view for all frustum cameras (degrees).
     *
     * <p>Stored here so {@link kepplr.render.body.BodyCuller} and {@link kepplr.render.body.BodySceneManager} can
     * compute apparent pixel radii without access to the JME camera object.
     */
    public static final float CAMERA_FOV_Y_DEG = 45f;

    /**
     * Cosine threshold used to filter the "bodies in view" status list (§10.2).
     *
     * <p>A body's scene-space unit vector must satisfy {@code cam.direction · bodyDir ≥ threshold} to be included.
     * {@code cos(45°) ≈ 0.707} means within ~45° of the camera centre, which covers the full diagonal FOV for a 45°
     * vertical FOV at 16:9 aspect with a small margin.
     */
    public static final float BODIES_IN_VIEW_COS_THRESHOLD = 0.707f;

    /**
     * Visual size of a point-sprite in screen pixels.
     *
     * <p>Used by {@link kepplr.render.body.BodySceneNode} to scale sprite geometry so it appears as this many pixels in
     * diameter regardless of distance. Applied to spacecraft and small non-satellite bodies drawn below the
     * {@link #POINT_SPRITE_THRESHOLD_PX} threshold.
     */
    public static final double SPACECRAFT_POINT_SPRITE_SIZE = 4.0;
}
