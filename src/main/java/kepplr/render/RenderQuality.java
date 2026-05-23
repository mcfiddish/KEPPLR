package kepplr.render;

import kepplr.util.KepplrConstants;

/**
 * Render quality presets that jointly configure shadow fidelity, trail density, and star magnitude cutoff (REDESIGN.md
 * §9.4).
 *
 * <h2>Shadow Quality Policy</h2>
 *
 * <p>Each preset controls shadow rendering as follows:
 *
 * <table>
 *   <caption>Shadow behavior per RenderQuality tier</caption>
 *   <tr><th>Tier</th><th>Shadow Model</th><th>Max Occluders</th><th>Occluder Sort</th></tr>
 *   <tr><td>LOW</td><td>Point-source (binary umbra, no penumbra)</td><td>2</td><td>Angular size²</td></tr>
 *   <tr><td>MEDIUM</td><td>Extended-source (analytic penumbra)</td><td>4</td><td>Angular size²</td></tr>
 *   <tr><td>HIGH</td><td>Extended-source (analytic penumbra)</td><td>8</td><td>Angular size²</td></tr>
 * </table>
 *
 * <p><b>Occluder sorting:</b> Casters are sorted by angular radius squared ({@code radius²/distance²}) in descending
 * order. The largest angular radius (most shadow-significant) caster is always kept when the occluder limit is reached.
 * This ensures Titan (largest Saturn moon) is never evicted by smaller, closer inner moons.
 *
 * <p><b>Penumbra computation:</b> Extended-source shadows use the analytic penumbra model in
 * {@link kepplr.render.body.ShadowGeometry}. See {@code ShadowGeometry.computeLitFraction} for the mathematical model.
 *
 * <p>Each preset controls:
 *
 * <ul>
 *   <li><b>Shadow model:</b> extended-source analytic penumbra (MEDIUM/HIGH) or point-source binary (LOW).
 *   <li><b>Max shadow occluders per receiver:</b> 2 / 4 / 8.
 *   <li><b>Trail samples per orbital period:</b> {@link KepplrConstants#TRAIL_SAMPLES_PER_PERIOD_LOW} /
 *       {@link KepplrConstants#TRAIL_SAMPLES_PER_PERIOD_MEDIUM} / {@link KepplrConstants#TRAIL_SAMPLES_PER_PERIOD}.
 *   <li><b>Star magnitude cutoff:</b> {@link KepplrConstants#STAR_MAGNITUDE_CUTOFF_LOW} /
 *       {@link KepplrConstants#STAR_MAGNITUDE_CUTOFF_MEDIUM} / {@link KepplrConstants#STAR_DEFAULT_MAGNITUDE_CUTOFF}.
 *   <li><b>Sun halo alpha scale:</b> {@link KepplrConstants#SUN_HALO_ALPHA_SCALE_LOW} /
 *       {@link KepplrConstants#SUN_HALO_ALPHA_SCALE_MEDIUM} / {@link KepplrConstants#SUN_HALO_ALPHA_SCALE_HIGH}.
 *   <li><b>Sun halo falloff:</b> {@link KepplrConstants#SUN_HALO_FALLOFF_LOW} /
 *       {@link KepplrConstants#SUN_HALO_FALLOFF_MEDIUM} / {@link KepplrConstants#SUN_HALO_FALLOFF_HIGH}.
 * </ul>
 *
 * <p>All enum methods are pure accessors over {@link KepplrConstants}. No logic beyond constant lookup.
 */
public enum RenderQuality {

    /**
     * Low quality — maximum performance.
     *
     * <p>Point-source shadow model (binary umbra only, no penumbra gradient). Minimum occluder count, coarser trails,
     * fewer stars.
     */
    LOW,

    /**
     * Medium quality — balanced performance and visual fidelity.
     *
     * <p>Extended-source analytic penumbra. Intermediate occluder count, moderate trail density and star count.
     */
    MEDIUM,

    /**
     * High quality — full visual fidelity (default).
     *
     * <p>Extended-source analytic penumbra. Maximum occluder count matching the Step 12 and Step 14 baselines exactly.
     */
    HIGH;

    /**
     * Whether the shadow model uses an extended (disk) Sun source for analytic penumbra computation.
     *
     * <p>{@code false} at LOW (point-source, binary shadow); {@code true} at MEDIUM and HIGH (extended-source, smooth
     * penumbra gradient).
     */
    public boolean extendedSource() {
        return switch (this) {
            case LOW -> KepplrConstants.SHADOW_EXTENDED_SOURCE_LOW;
            case MEDIUM, HIGH -> KepplrConstants.SHADOW_EXTENDED_SOURCE_MEDIUM;
        };
    }

    /**
     * Maximum number of shadow-casting occluders evaluated per receiver per frame.
     *
     * <p>Values: LOW = {@link KepplrConstants#SHADOW_MAX_OCCLUDERS_LOW}, MEDIUM =
     * {@link KepplrConstants#SHADOW_MAX_OCCLUDERS_MEDIUM}, HIGH = {@link KepplrConstants#SHADOW_MAX_OCCLUDERS_HIGH}.
     */
    public int maxOccluders() {
        return switch (this) {
            case LOW -> KepplrConstants.SHADOW_MAX_OCCLUDERS_LOW;
            case MEDIUM -> KepplrConstants.SHADOW_MAX_OCCLUDERS_MEDIUM;
            case HIGH -> KepplrConstants.SHADOW_MAX_OCCLUDERS_HIGH;
        };
    }

    /**
     * Trail sample cap per orbital period.
     *
     * <p>Values: LOW = {@link KepplrConstants#TRAIL_SAMPLES_PER_PERIOD_LOW}, MEDIUM =
     * {@link KepplrConstants#TRAIL_SAMPLES_PER_PERIOD_MEDIUM}, HIGH = {@link KepplrConstants#TRAIL_SAMPLES_PER_PERIOD}.
     */
    public int trailSamplesPerPeriod() {
        return switch (this) {
            case LOW -> KepplrConstants.TRAIL_SAMPLES_PER_PERIOD_LOW;
            case MEDIUM -> KepplrConstants.TRAIL_SAMPLES_PER_PERIOD_MEDIUM;
            case HIGH -> KepplrConstants.TRAIL_SAMPLES_PER_PERIOD;
        };
    }

    /**
     * Visual magnitude cutoff for the star field.
     *
     * <p>Values: LOW = {@link KepplrConstants#STAR_MAGNITUDE_CUTOFF_LOW}, MEDIUM =
     * {@link KepplrConstants#STAR_MAGNITUDE_CUTOFF_MEDIUM}, HIGH =
     * {@link KepplrConstants#STAR_DEFAULT_MAGNITUDE_CUTOFF}.
     */
    public double starMagnitudeCutoff() {
        return switch (this) {
            case LOW -> KepplrConstants.STAR_MAGNITUDE_CUTOFF_LOW;
            case MEDIUM -> KepplrConstants.STAR_MAGNITUDE_CUTOFF_MEDIUM;
            case HIGH -> KepplrConstants.STAR_DEFAULT_MAGNITUDE_CUTOFF;
        };
    }

    /**
     * Overall Sun halo brightness scale factor.
     *
     * <p>Values: LOW = {@link KepplrConstants#SUN_HALO_ALPHA_SCALE_LOW}, MEDIUM =
     * {@link KepplrConstants#SUN_HALO_ALPHA_SCALE_MEDIUM}, HIGH = {@link KepplrConstants#SUN_HALO_ALPHA_SCALE_HIGH}.
     * Visually tuned — adjust after observing the halo in-engine.
     */
    public float sunHaloAlphaScale() {
        return switch (this) {
            case LOW -> KepplrConstants.SUN_HALO_ALPHA_SCALE_LOW;
            case MEDIUM -> KepplrConstants.SUN_HALO_ALPHA_SCALE_MEDIUM;
            case HIGH -> KepplrConstants.SUN_HALO_ALPHA_SCALE_HIGH;
        };
    }

    /**
     * Sun halo radial falloff exponent.
     *
     * <p>Values: LOW = {@link KepplrConstants#SUN_HALO_FALLOFF_LOW}, MEDIUM =
     * {@link KepplrConstants#SUN_HALO_FALLOFF_MEDIUM}, HIGH = {@link KepplrConstants#SUN_HALO_FALLOFF_HIGH}. Smaller
     * value → faster falloff → tighter halo. Visually tuned.
     */
    public float sunHaloFalloff() {
        return switch (this) {
            case LOW -> KepplrConstants.SUN_HALO_FALLOFF_LOW;
            case MEDIUM -> KepplrConstants.SUN_HALO_FALLOFF_MEDIUM;
            case HIGH -> KepplrConstants.SUN_HALO_FALLOFF_HIGH;
        };
    }
}
