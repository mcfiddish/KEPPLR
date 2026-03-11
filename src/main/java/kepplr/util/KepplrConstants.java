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

    /**
     * Per-half safety cap on adaptive trail samples.
     *
     * <p>Applied independently to the forward and backward passes in {@code TrailSampler.sample()}, so the total sample
     * count is bounded at {@code 2 × TRAIL_SAMPLES_PER_PERIOD}.
     */
    public static final int TRAIL_SAMPLES_PER_PERIOD = 1801;

    /** Minimum angular arc per trail segment, used near the body's current position (degrees). */
    public static final double TRAIL_MIN_ARC_DEG = 0.01;

    /** Maximum angular arc per trail segment, used at the orbit edges far from the body (degrees). */
    public static final double TRAIL_MAX_ARC_DEG = 2.0;

    /** Default trail duration in seconds when orbital period is unknown (30 days). */
    public static final double TRAIL_DEFAULT_DURATION_SEC = 30.0 * 86_400.0;

    /**
     * Primary staleness criterion: fraction of the orbital period after which a cached trail must be resampled.
     *
     * <p>Using a period fraction means fast-period bodies (e.g., Phobos at ~7.7 h) resample frequently while slow
     * bodies (e.g., Earth at 365 days) resample much less often. At 0.5%, Phobos resamples every ~138 s of simulation
     * time; the fade boundary advances by only ~0.18° per cycle (well below the 2° coarse sample spacing), giving
     * smooth fade behaviour at any time rate.
     */
    public static final double TRAIL_STALENESS_FRACTION = 0.0003;

    /**
     * Upper cap on simulation-time staleness (seconds).
     *
     * <p>Prevents slow-period bodies (orbital period measured in years) from going without a resample for days of
     * wall-clock time at low time rates. The effective staleness threshold is {@code min(TRAIL_STALENESS_THRESHOLD_SEC,
     * durationSec × TRAIL_STALENESS_FRACTION)}.
     */
    public static final double TRAIL_STALENESS_THRESHOLD_SEC = 1_800.0; // 30 simulation minutes

    /**
     * Minimum wall-clock interval (seconds) between SPICE trail resample passes.
     *
     * <p>Prevents saturating the render thread with ephemeris calls when many trails are active and simulation time is
     * advancing rapidly. Geometry is still rebuilt from cached samples every frame.
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

    // ── Vector overlays (Step 13) ──

    /**
     * Simulation-time staleness threshold for vector overlay geometry (seconds).
     *
     * <p>Vector directions are recomputed and geometry rebuilt when {@code |currentEt - lastBuiltEt|} exceeds this
     * value. At 1× real-time, vectors update at most every 60 simulation seconds; at 1000× time rate, this translates
     * to a wall-clock update interval of ~60 ms (≈ 17 Hz), which is responsive enough for interactive use.
     */
    public static final double VECTOR_STALENESS_THRESHOLD_SEC = 60.0;

    /**
     * Minimum projected screen length in pixels for a vector to be rendered.
     *
     * <p>Vectors whose scale, when projected onto the screen, would be shorter than this threshold are silently
     * skipped. This prevents sub-pixel clutter when the camera is very far from the origin body.
     */
    public static final double VECTOR_MIN_VISIBLE_LENGTH_PX = 5.0;

    /**
     * Default visual scale of a vector overlay in km.
     *
     * <p>The rendered line segment extends {@code VECTOR_DEFAULT_SCALE_KM} km from the origin body centre in the
     * computed direction. Callers may override this per-{@code VectorDefinition}. Chosen as roughly twice Earth's mean
     * radius (6371 km) so vectors are legible when focused on an Earth-class body.
     */
    public static final double VECTOR_DEFAULT_SCALE_KM = 15_000.0;

    // ── Star field (Step 14, REDESIGN.md §7.4) ──

    /** Visual magnitude cutoff — naked-eye limit. Stars dimmer than this are not rendered. */
    public static final double STAR_DEFAULT_MAGNITUDE_CUTOFF = 6.5;

    /**
     * Distance from the camera at which all stars are placed (km).
     *
     * <p>Must be inside the FAR frustum (< FRUSTUM_FAR_MAX_KM) so stars are rendered and depth-tested. Set to 90% of
     * FAR far clip to keep stars well inside the frustum.
     */
    public static final double STAR_FIELD_DISTANCE_KM = 9.0e14;

    /** Reference magnitude for flux ratio calculation (naked-eye limit). */
    public static final double STAR_MAG_REF = 6.0;

    /** Exponential coefficient for core brightness saturation: intensity = 1 − exp(−k · flux). */
    public static final double STAR_CORE_K = 0.02;

    /** Normalisation divisor for halo strength: haloStrength = clamp(log2(flux) / scale, 0, 1). */
    public static final double STAR_HALO_SCALE = 5.0;

    /** Base point-sprite radius in pixels at the reference magnitude (flux=1). */
    public static final float STAR_POINT_BASE_PX = 1.5f;

    /** Point-sprite radius growth per log₂ of flux ratio. */
    public static final float STAR_POINT_SLOPE = 0.5f;

    /** Maximum point-sprite radius in pixels (Sirius saturates here). */
    public static final float STAR_POINT_MAX_PX = 8.0f;

    /** Base halo radius in pixels at the reference magnitude. */
    public static final float STAR_HALO_BASE_PX = 0.4f;

    /** Halo radius growth per log₂ of flux ratio. */
    public static final float STAR_HALO_SLOPE = 0.8f;

    /** Maximum halo radius in pixels. */
    public static final float STAR_HALO_MAX_PX = 4.0f;

    // ── Saturn rings (REDESIGN.md §7.2, Step 16) ──────────────────────────────────────────────

    /**
     * NAIF ID of Saturn.
     *
     * <p>Used to guard ring geometry creation — rings are only produced for this body. Guards in
     * {@code SaturnRingManager.appliesToBody(int)} and related tests reference this constant.
     */
    public static final int SATURN_NAIF_ID = 699;

    /**
     * Inner radius of Saturn's ring system (km).
     *
     * <p>Corresponds to the D ring inner edge (~74,490 km actual; this value matches the Bjorn
     * Jonsson ring profile used in the prototype SaturnRingsController).
     */
    public static final double SATURN_RING_INNER_RADIUS_KM = 74_400.0;

    /**
     * Outer radius of Saturn's ring system (km).
     *
     * <p>Corresponds to the F ring outer edge. Matches the prototype SaturnRingsController.
     */
    public static final double SATURN_RING_OUTER_RADIUS_KM = 140_154.0;

    /**
     * Angular tessellation segments for the Saturn ring annulus mesh.
     *
     * <p>2048 segments give a visually smooth circle at close range (near frustum). Matches the
     * prototype's {@code DEFAULT_ANGULAR_SEGMENTS}.
     */
    public static final int SATURN_RING_ANGULAR_SEGMENTS = 2048;

    /**
     * Base ring color tint — red channel (0–1).
     *
     * <p>Warm pinkish-tan tint applied to the brightness texture. Derived from prototype RGB
     * constants (255, 224, 209).
     */
    public static final float SATURN_RING_COLOR_R = 255f / 255f;

    /** Base ring color tint — green channel (0–1). */
    public static final float SATURN_RING_COLOR_G = 224f / 255f;

    /** Base ring color tint — blue channel (0–1). */
    public static final float SATURN_RING_COLOR_B = 209f / 255f;

    // ── Shadows and eclipses (REDESIGN.md §9, Step 16) ────────────────────────────────────────

    /**
     * Maximum number of shadow-casting occluders evaluated per body per frame.
     *
     * <p>Applies to both the body eclipse shader (OccluderPositions/Radii arrays) and moon shadow
     * casters in the ring shader. Matches the prototype's {@code MAX_SHADOW_CASTERS = 8}.
     */
    public static final int SHADOW_MAX_OCCLUDERS = 8;

    /**
     * Saturn-shadow darkness on the ring surface (Step 16b).
     *
     * <p>Controls how dark the ring becomes when it lies in Saturn's shadow. 0 = no shadow effect,
     * 1 = fully dark. Matches prototype {@code SATURN_SHADOW_DARKNESS}.
     */
    public static final float RING_SHADOW_DARKNESS = 0.9f;

    /**
     * Moon-shadow darkness on the ring surface (Step 16b).
     *
     * <p>Controls how dark the ring becomes when a moon's shadow crosses it. Softer than the
     * planetary shadow because moon shadows are smaller. Matches prototype
     * {@code DEFAULT_MOON_SHADOW_DARKNESS}.
     */
    public static final float RING_MOON_SHADOW_DARKNESS = 0.6f;

    /**
     * Beer-Lambert tau scale factor for ring shadow attenuation (Step 16b).
     *
     * <p>Scales the optical depth {@code τ = 1 − transparency} in the exponent of the ring-shadow
     * attenuation function: {@code atten = exp(−TauScale × τ / μ₀)}. 1.0 = physically calibrated.
     * Matches prototype {@code TAU_SCALE}.
     */
    public static final float RING_TAU_SCALE = 1.0f;
}
