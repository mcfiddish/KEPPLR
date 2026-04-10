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

    /**
     * Apparent-radius threshold in pixels at or above which a body is rendered as a full geometry (DRAW_FULL). Bodies
     * below this threshold are rendered as point sprites. Satellites are not exempt — they also render as sprites (not
     * culled). See §7.3.
     */
    public static final double DRAW_FULL_MIN_APPARENT_RADIUS_PX = 2.0;

    /**
     * Screen-space proximity threshold in pixels for sprite cluster suppression (§7.3).
     *
     * <p>When two sprite-class bodies are within this many pixels of each other on screen, the one with the smaller
     * physical radius is suppressed. Bodies in an active interaction state (selected/focused/targeted/tracked) are
     * exempt from suppression.
     */
    public static final double SPRITE_CLUSTER_PROXIMITY_PX = 2.0;

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
     * diameter regardless of distance. Applied to spacecraft and small bodies drawn below the
     * {@link #DRAW_FULL_MIN_APPARENT_RADIUS_PX} threshold.
     */
    public static final double SPACECRAFT_POINT_SPRITE_SIZE = 2.0;

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
     * Arrow length expressed as a multiple of the focused body's mean radius.
     *
     * <p>Arrow length in km = focused body mean radius × {@code VECTOR_ARROW_FOCUS_BODY_RADIUS_MULTIPLE} ×
     * {@link kepplr.render.vector.VectorDefinition#getScaleFactor()}. A value of 3.0 places the arrow tip at 3× the
     * mean radius from the body centre (i.e., 2× the mean radius above the surface).
     *
     * <p>Vectors are not rendered when no body is focused (focus body ID == −1). When the focused body has no shape
     * data (e.g. a spacecraft), {@link #BODY_DEFAULT_RADIUS_KM} is used as the mean radius.
     */
    public static final double VECTOR_ARROW_FOCUS_BODY_RADIUS_MULTIPLE = 3.0;

    /**
     * Fallback mean radius (km) used when a body has no PCK shape data.
     *
     * <p>Applied by {@link kepplr.render.body.BodyNodeFactory} for ellipsoid geometry and by
     * {@link kepplr.render.vector.VectorRenderer} for vector arrow length when the focused body is a spacecraft or
     * other shape-less object.
     */
    public static final double BODY_DEFAULT_RADIUS_KM = 1.0;

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

    // ── NAIF body IDs ─────────────────────────────────────────────────────────────────────────

    /** NAIF ID of the Sun. */
    public static final int SUN_NAIF_ID = 10;

    /** NAIF ID of Earth. */
    public static final int EARTH_NAIF_ID = 399;

    /** NAIF ID of Pluto. Treated as a satellite of its barycenter (9) for trail purposes. */
    public static final int PLUTO_NAIF_ID = 999;

    /** NAIF ID of the Pluto system barycenter. */
    public static final int PLUTO_BARYCENTER_NAIF_ID = 9;

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
     * <p>Corresponds to the D ring inner edge (~74,490 km actual; this value matches the Bjorn Jonsson ring profile
     * used in the prototype SaturnRingsController).
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
     * <p>2048 segments give a visually smooth circle at close range (near frustum). Matches the prototype's
     * {@code DEFAULT_ANGULAR_SEGMENTS}.
     */
    public static final int SATURN_RING_ANGULAR_SEGMENTS = 2048;

    /**
     * Base ring color tint — red channel (0–1).
     *
     * <p>Warm pinkish-tan tint applied to the brightness texture. Derived from prototype RGB constants (255, 224, 209).
     */
    public static final float SATURN_RING_COLOR_R = 255f / 255f;

    /** Base ring color tint — green channel (0–1). */
    public static final float SATURN_RING_COLOR_G = 224f / 255f;

    /** Base ring color tint — blue channel (0–1). */
    public static final float SATURN_RING_COLOR_B = 209f / 255f;

    // ── Shadows and eclipses (REDESIGN.md §9, Step 16) ────────────────────────────────────────

    /**
     * Maximum number of shadow-casting occluders evaluated per body per frame (absolute cap).
     *
     * <p>Quality-tier-specific limits ({@link #SHADOW_MAX_OCCLUDERS_LOW}, {@link #SHADOW_MAX_OCCLUDERS_MEDIUM},
     * {@link #SHADOW_MAX_OCCLUDERS_HIGH}) must not exceed this value.
     */
    public static final int SHADOW_MAX_OCCLUDERS = 8;

    /**
     * Maximum occluders evaluated per receiver per frame at LOW quality.
     *
     * <p>Reducing the occluder count is the cheapest way to limit shadow computation at low quality — most bodies have
     * at most 1–2 significant casters at any time.
     */
    public static final int SHADOW_MAX_OCCLUDERS_LOW = 2;

    /** Maximum occluders evaluated per receiver per frame at MEDIUM quality. */
    public static final int SHADOW_MAX_OCCLUDERS_MEDIUM = 4;

    /**
     * Maximum occluders evaluated per receiver per frame at HIGH quality.
     *
     * <p>Must equal {@link #SHADOW_MAX_OCCLUDERS} so array sizes remain consistent with the shader.
     */
    public static final int SHADOW_MAX_OCCLUDERS_HIGH = 8;

    /**
     * Shadow model for LOW quality: point-source Sun (binary umbra/penumbra, no extended-source computation).
     *
     * <p>{@code false} means the Sun is treated as a point; shadows are binary (in or out of umbra). This is the
     * cheapest option and disables penumbra gradients entirely. Corresponds to the {@code ExtendedSource} material
     * parameter being {@code false}, selecting the point-source shader variant.
     */
    public static final boolean SHADOW_EXTENDED_SOURCE_LOW = false;

    /**
     * Shadow model for MEDIUM and HIGH quality: extended-source Sun with analytic penumbra.
     *
     * <p>{@code true} enables the full angular-disk eclipse geometry (§9.3 Option C). Penumbra fraction is a continuous
     * value in [0, 1] computed from the angular diameters of the Sun and occluder as seen from each surface point.
     * Corresponds to the {@code ExtendedSource} material parameter being {@code true}.
     */
    public static final boolean SHADOW_EXTENDED_SOURCE_MEDIUM = true;

    /**
     * Smooth-step half-width for the day/night terminator blend (radians, measured in terms of N·L).
     *
     * <p>The terminator transition is applied as {@code smoothstep(−w, +w, NdotL)}, producing a smooth gradient across
     * ±{@code SHADOW_TERMINATOR_WIDTH_RAD} of the geometric terminator (N·L = 0). At ~3° this gives a physically
     * plausible atmospheric scattering transition width at solar-system scales.
     */
    public static final float SHADOW_TERMINATOR_WIDTH_RAD = 0.05f;

    /**
     * Night-side ambient luminance factor for body surface shading (linear space).
     *
     * <p>The minimum base illumination applied to fragments where N·L ≤ 0 (full night side). In linear light space,
     * 0.001 produces approximately 3% perceptual brightness after the linear→sRGB conversion in the shader
     * ({@code pow(0.001, 1/2.2) ≈ 0.03}), keeping night-side geometry barely visible without appearing artificially
     * bright.
     */
    public static final float BODY_AMBIENT_FACTOR = 0.001f;

    /**
     * Wrap lighting factor for the day/night terminator (Step 23).
     *
     * <p>Controls how far illumination extends past the geometric terminator (N·L = 0). The wrap-lighting term is
     * {@code (NdotL + wrap) / (1 + wrap)}, which shifts the terminator slightly onto the night side for a softer, more
     * physically plausible transition. 0.0 = hard Lambertian cutoff; higher values = softer transition.
     */
    public static final float BODY_WRAP_FACTOR = 0.15f;

    /**
     * Minnaert limb darkening exponent for body surface shading (Step 23).
     *
     * <p>Controls the view-angle-dependent darkening near the limb of a body. The Minnaert reflectance model is
     * {@code pow(NdotL, k) × pow(NdotV, k − 1)}. At {@code k = 1.0} this reduces to pure Lambertian; values above 1.0
     * produce progressively darker limbs, appropriate for rough rocky/icy surfaces.
     */
    public static final float BODY_LIMB_DARKENING_K = 1.3f;

    /**
     * Saturn-shadow darkness on the ring surface (Step 16b).
     *
     * <p>Controls how dark the ring becomes when it lies in Saturn's umbra. Applied as a scale factor on the ring light
     * contribution: {@code ringLightFactor *= (1 − shadowFraction × RING_SHADOW_DARKNESS)}. Matches prototype
     * {@code SATURN_SHADOW_DARKNESS}.
     */
    public static final float RING_SHADOW_DARKNESS = 0.9f;

    /**
     * Moon-shadow darkness on the ring surface (Step 16b).
     *
     * <p>Controls how dark the ring becomes when a moon's shadow crosses it. Softer than the planetary shadow because
     * moon shadows are physically smaller and the penumbra zone is proportionally wider. Matches prototype
     * {@code DEFAULT_MOON_SHADOW_DARKNESS}.
     */
    public static final float RING_MOON_SHADOW_DARKNESS = 0.6f;

    /**
     * Beer-Lambert tau scale factor for ring shadow attenuation (Step 16b).
     *
     * <p>Scales the optical depth {@code τ = 1 − transparency} in the exponent of the ring-shadow attenuation function:
     * {@code atten = exp(−TauScale × τ / μ₀)}. 1.0 = physically calibrated. Matches prototype {@code TAU_SCALE}.
     */
    public static final float RING_TAU_SCALE = 1.0f;

    /**
     * Forward-scatter intensity boost for Saturn's rings (Step 23).
     *
     * <p>When the camera and Sun are on opposite sides of the ring plane, ring particles scatter sunlight toward the
     * camera. This constant scales the forward-scatter lobe intensity: {@code 1 + strength × pow(cosAngle, exponent)}.
     * Higher values produce a brighter forward-scatter glow.
     */
    public static final float RING_FORWARD_SCATTER_STRENGTH = 0.8f;

    /**
     * Forward-scatter lobe sharpness exponent for Saturn's rings (Step 23).
     *
     * <p>Controls how narrow the forward-scatter peak is. Higher values concentrate the brightness boost into a smaller
     * angular range around the exact forward-scatter direction. 2.0 = broad lobe, 8.0 = tight peak.
     */
    public static final float RING_FORWARD_SCATTER_EXPONENT = 3.0f;

    /**
     * Ambient brightness for the unlit (night) side of Saturn's rings (Step 23).
     *
     * <p>The side of the ring plane facing away from the Sun receives this fraction of the base ring brightness. Ring
     * particles are not a solid surface, so some light passes through gaps. 0.0 = fully dark, 1.0 = same as lit.
     */
    public static final float RING_UNLIT_SIDE_BRIGHTNESS = 0.2f;

    // ── Quality-preset trail samples (REDESIGN.md §9.4, Step 16b) ─────────────────────────────

    /**
     * Trail samples per orbital period at LOW render quality.
     *
     * <p>Proportionate reduction from the REDESIGN §7.5 baseline (180 samples/period). At LOW quality, trail geometry
     * is coarser to reduce SPICE call frequency.
     */
    public static final int TRAIL_SAMPLES_PER_PERIOD_LOW = 60;

    /**
     * Trail samples per orbital period at MEDIUM render quality.
     *
     * <p>Intermediate density between LOW and the HIGH baseline. At MEDIUM quality, trail geometry is denser than LOW
     * but still below the full HIGH resolution.
     */
    public static final int TRAIL_SAMPLES_PER_PERIOD_MEDIUM = 90;

    // HIGH: use existing TRAIL_SAMPLES_PER_PERIOD (1801) unchanged.

    // ── Quality-preset star magnitude cutoffs (REDESIGN.md §9.4, Step 16b) ────────────────────

    /**
     * Visual magnitude cutoff at LOW render quality — bright stars only (naked-eye limit ≈ 4.5).
     *
     * <p>At this cutoff only the ~900 brightest stars are rendered, roughly halving star vertex count compared to
     * MEDIUM.
     */
    public static final double STAR_MAGNITUDE_CUTOFF_LOW = 4.5;

    /**
     * Visual magnitude cutoff at MEDIUM render quality.
     *
     * <p>Includes stars to magnitude 5.5, giving ~3 000 stars — a good balance between visual quality and vertex count.
     */
    public static final double STAR_MAGNITUDE_CUTOFF_MEDIUM = 5.5;

    // HIGH: use existing STAR_DEFAULT_MAGNITUDE_CUTOFF (6.5) unchanged.

    // ── Sun halo (REDESIGN.md §7.6, Step 17) ──────────────────────────────────────────────────
    //
    // These are visually tuned values derived from the prototype SunHaloController/SunHaloFilter.
    // They are NOT physically derived — adjust after visual confirmation.

    /**
     * Halo billboard radius expressed as a multiple of the Sun's mean radius.
     *
     * <p>The world-space billboard half-size = Sun mean radius × this value. At the prototype value of 2.5, the halo
     * outer edge is at 2.5 Sun radii from the Sun centre (1.5 radii above the limb). Constant across all quality tiers
     * — changing this alters the physical extent of the halo, not rendering cost.
     */
    public static final float SUN_HALO_MAX_RADIUS_MULTIPLIER = 2.5f;

    /**
     * Radial falloff exponent for the Sun halo at LOW render quality.
     *
     * <p>Controls how quickly the corona brightness fades from the limb outward: {@code radial = exp(-rNorm /
     * falloff)}. Smaller value → faster falloff → tighter, subtler halo. Visually tuned.
     */
    public static final float SUN_HALO_FALLOFF_LOW = 0.25f;

    /** Radial falloff exponent at MEDIUM render quality. Visually tuned. */
    public static final float SUN_HALO_FALLOFF_MEDIUM = 0.30f;

    /**
     * Radial falloff exponent at HIGH render quality.
     *
     * <p>Matches the prototype {@code SunHaloController.SUN_HALO_FALLOFF = 0.35f}. Visually tuned.
     */
    public static final float SUN_HALO_FALLOFF_HIGH = 0.35f;

    /**
     * Overall halo brightness scale at LOW render quality.
     *
     * <p>Multiplied against the shader intensity before alpha clamping: a lower value produces a dimmer, less prominent
     * halo. Visually tuned.
     */
    public static final float SUN_HALO_ALPHA_SCALE_LOW = 0.08f;

    /** Overall halo brightness scale at MEDIUM render quality. Visually tuned. */
    public static final float SUN_HALO_ALPHA_SCALE_MEDIUM = 0.10f;

    /**
     * Overall halo brightness scale at HIGH render quality.
     *
     * <p>Matches the prototype {@code SunHaloController.SUN_HALO_ALPHA = 0.12f}. Visually tuned.
     */
    public static final float SUN_HALO_ALPHA_SCALE_HIGH = 0.12f;

    /**
     * Minimum apparent half-angle of the Sun halo in radians (= 0.5°, giving a 1° minimum apparent diameter).
     *
     * <p>When the camera is far enough from the Sun that the physical billboard subtends less than 1°, the billboard is
     * enlarged to this angular floor so the halo remains visible at interplanetary distances (e.g. from Pluto).
     */
    public static final double SUN_HALO_MIN_APPARENT_HALF_ANGLE_RAD = Math.toRadians(0.5);

    // ── Camera transitions (Step 18) ──────────────────────────────────────────────────────────

    /**
     * Default duration for a {@code pointAt} slew (seconds).
     *
     * <p>Used by UI interactions that initiate a default {@code pointAt()} slew with no explicit duration.
     */
    public static final double DEFAULT_SLEW_DURATION_SECONDS = 3.0;

    /**
     * Default duration for a {@code goTo} translation (seconds).
     *
     * <p>Used by UI interactions that initiate a default {@code goTo()} translation with no explicit duration.
     */
    public static final double DEFAULT_GOTO_DURATION_SECONDS = 3.0;

    /**
     * Default apparent radius in degrees for a {@code goTo} command (§4.5).
     *
     * <p>{@code endDistance = bodyRadius / tan(DEFAULT_GOTO_APPARENT_RADIUS_DEG × π/180)}. At 10°, the body subtends
     * approximately 20° of angular diameter — about 44% of the 45° vertical FOV, a dramatic and unambiguous close
     * approach. Confirmed by user.
     */
    public static final double DEFAULT_GOTO_APPARENT_RADIUS_DEG = 10.0;

    /**
     * Duration threshold below which a transition is treated as instantaneous.
     *
     * <p>Any call to {@code pointAt()} or {@code goTo()} with {@code durationSeconds <=
     * CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC} snaps the camera to the end value on the next frame without creating an
     * animated transition.
     */
    public static final double CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC = 0.0;

    /**
     * Dot-product threshold for the "already pointing at target" degenerate case in {@code pointAt()}.
     *
     * <p>If {@code dot(currentLookDir, targetDir) >= 1.0 − CAMERA_POINT_AT_IDENTICAL_DIRECTION_EPSILON}, the camera is
     * already aimed at the target and no slew is started.
     */
    public static final double CAMERA_POINT_AT_IDENTICAL_DIRECTION_EPSILON = 1e-6;

    // ── Camera scripting defaults (Step 19c) ──────────────────────────────────────────────────

    /**
     * Default transition duration for scripted camera navigation commands (seconds).
     *
     * <p>Used by the Groovy wrapper for no-duration overloads and by {@code CameraInputHandler} for keyboard navigation
     * shortcuts. Mouse gestures always use 0 (instant snap).
     */
    public static final double DEFAULT_CAMERA_TRANSITION_DURATION_SECONDS = 1.0;

    /**
     * Default transition duration for cinematic camera commands (truck, crane, dolly) in seconds.
     *
     * <p>Used by the convenience overloads on {@code KepplrScript} when no duration is specified.
     */
    public static final double DEFAULT_CINEMATIC_TRANSITION_DURATION_SECONDS = 2.0;

    /**
     * Whether smoothstep easing is applied to the interpolation parameter {@code t} for all timed transitions.
     *
     * <p>When {@code true}, the raw linear {@code t ∈ [0, 1]} is mapped through {@code t² × (3 − 2t)} before reaching
     * slerp/lerp calls, producing acceleration from rest and deceleration to rest. When {@code false}, raw linear
     * {@code t} is used (useful for testing). Instant transitions ({@code durationSeconds == 0}) are unaffected.
     */
    public static final boolean TRANSITION_EASING_ENABLED = true;

    /**
     * Minimum camera field of view in degrees.
     *
     * <p>Clamping floor for {@code setFov()} commands. Prevents the FOV from becoming so narrow that rendering
     * artifacts or numerical instability appear.
     */
    public static final double FOV_MIN_DEG = 1e-6;

    /**
     * Maximum camera field of view in degrees.
     *
     * <p>Clamping ceiling for {@code setFov()} commands. Prevents extreme wide-angle distortion beyond practical use.
     */
    public static final double FOV_MAX_DEG = 120.0;

    // ── UI keyboard / time-rate controls (Step 19) ────────────────────────────────────────────

    /**
     * Factor by which {@code [} and {@code ]} multiply/divide the current time rate.
     *
     * <p>Pressing {@code ]} sets {@code timeRate *= TIME_RATE_KEYBOARD_FACTOR}; pressing {@code [} divides by it.
     */
    public static final double TIME_RATE_KEYBOARD_FACTOR = 10.0;

    // ── Label decluttering (REDESIGN.md §7.8, Step 19b) ────────────────────────────────────────

    /**
     * Minimum screen-space separation in pixels between labels.
     *
     * <p>A label is drawn only if no other label belonging to a body with larger physical radius is within this many
     * pixels of its screen position. This produces zoom-dependent behavior: at large distances major planets are
     * labeled and satellites are suppressed because they cluster near their primary; as the camera moves closer and
     * satellites separate on screen, their labels appear.
     */
    public static final double LABEL_DECLUTTER_MIN_SEPARATION_PX = 30.0;

    // ── Trail decluttering (REDESIGN.md §7.5, Step 19b) ─────────────────────────────────────

    /**
     * Minimum screen-space separation in pixels between a satellite and its primary for the satellite's trail to be
     * drawn.
     *
     * <p>When a satellite's screen position is within this many pixels of its primary body's screen position, its trail
     * is suppressed. As the camera zooms in and the satellite separates on screen, the trail becomes visible.
     */
    public static final double TRAIL_DECLUTTER_MIN_SEPARATION_PX = 30.0;

    // ── Mouse picking (Step 19) ───────────────────────────────────────────────────────────────

    /**
     * Maximum elapsed nanoseconds between two left-clicks for a double-click to be recognised.
     *
     * <p>400 ms matches common OS defaults.
     */
    public static final long MOUSE_DOUBLE_CLICK_THRESHOLD_NS = 400_000_000L;

    /**
     * Maximum mouse-move distance in pixels between button-down and button-up for the action to be treated as a click
     * rather than a drag.
     */
    public static final double MOUSE_CLICK_DRAG_THRESHOLD_PX = 5.0;

    /**
     * Minimum screen-space pick radius in pixels. Bodies whose apparent radius on screen is smaller than this value are
     * expanded to this radius for mouse-picking purposes. Prevents small/distant bodies from being impossible to click.
     */
    public static final float PICK_MIN_SCREEN_RADIUS_PX = 8.0f;

    // ── Groovy scripting (REDESIGN.md §11, Step 20) ────────────────────────────────────────────

    /** Polling interval in milliseconds for {@code waitSim}, {@code waitUntilSim}, and related scripting primitives. */
    public static final long SCRIPT_WAIT_POLL_INTERVAL_MS = 50L;

    /**
     * Maximum seconds the script thread waits for the JME scene rebuild to complete after {@code loadConfiguration()}.
     * If the latch does not count down within this window a warning is logged and the script continues.
     */
    public static final long CONFIG_RELOAD_TIMEOUT_SEC = 30L;

    /**
     * Coalescing window in milliseconds for the {@code CommandRecorder}: instant camera commands within this window are
     * merged into a single recorded command.
     */
    public static final long RECORDER_COALESCE_WINDOW_MS = 250L;

    /** Default display duration in seconds for {@code displayMessage} when no duration is specified. */
    public static final double SCRIPT_MESSAGE_DEFAULT_DURATION_SEC = 5.0;

    /** Fade-out duration in seconds for HUD messages at the end of their display period. */
    public static final double SCRIPT_MESSAGE_FADE_DURATION_SEC = 1.0;

    // ── Instrument frustum overlays (Step 22) ──────────────────────────────────────────────────

    /**
     * Default extent in km from the frustum apex to its base plane.
     *
     * <p>Each base vertex is placed at {@code apex + normalize(boundVectorJ2000) ×
     * INSTRUMENT_FRUSTUM_DEFAULT_EXTENT_KM}. Body-intersection shortening is out of scope and will be a later
     * refinement.
     */
    public static final double INSTRUMENT_FRUSTUM_DEFAULT_EXTENT_KM = 100_000.0;

    /**
     * Number of polygon sides used to approximate a circular or elliptical instrument FOV.
     *
     * <p>SPICE CIRCLE FOVs have 1 bound vector and ELLIPSE FOVs have 2, which are insufficient to build a closed
     * pyramid. Both are approximated as regular polygons with this many sides.
     */
    public static final int INSTRUMENT_FRUSTUM_CIRCLE_APPROX_SIDES = 32;

    /**
     * Number of subdivisions inserted along each polygonal FOV edge when building the frustum boundary ring.
     *
     * <p>This improves visual fidelity for large footprints and partial body intersections by reducing the coarse
     * corner-to-corner straight-edge approximation used by raw RECTANGLE/POLYGON IK bounds.
     */
    public static final int INSTRUMENT_FRUSTUM_EDGE_SUBDIVISIONS = 32;

    // ── Screenshot and capture (Step 25) ──────────────────────────────────────────────────────

    /**
     * Format string for capture sequence frame filenames.
     *
     * <p>The zero-pad width auto-widens if {@code frameCount >= 10000}. The default 4-digit pad handles up to 9999
     * frames; for larger sequences, compute the required width from the frame count.
     */
    public static final String CAPTURE_FRAME_NAME_FORMAT = "frame_%04d.png";

    /** Sidecar JSON filename written alongside captured frames. Purely informational — no tool depends on it. */
    public static final String CAPTURE_INFO_FILENAME = "capture_info.json";

    // ── Unit conversion ──

    /** One astronomical unit in kilometres (IAU 2012 exact definition). */
    public static final double KM_PER_AU = 1.495_978_707e8;

    // ── Distance display thresholds ──

    /**
     * Camera-to-body distances below this value (in km) are displayed in metres.
     *
     * <p>1 km threshold — spacecraft proximity operations can be sub-kilometre.
     */
    public static final double DISTANCE_DISPLAY_M_THRESHOLD_KM = 1.0;

    /**
     * Camera-to-body distances at or above this value (in AU) are displayed in AU.
     *
     * <p>0.01 AU ≈ 1.5 million km — roughly 4× the Earth–Moon distance.
     */
    public static final double DISTANCE_DISPLAY_AU_THRESHOLD_AU = 0.01;
}
