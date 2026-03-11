package kepplr.render;

import kepplr.util.KepplrConstants;

/**
 * Render quality presets that jointly configure shadow fidelity, trail density, and star magnitude cutoff (REDESIGN.md
 * §9.4).
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
 *   <li><b>Sun halo quality:</b> stub — Step 17.
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
}
