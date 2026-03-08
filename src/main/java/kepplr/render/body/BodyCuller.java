package kepplr.render.body;

import kepplr.util.KepplrConstants;

/**
 * Small-body culling logic per REDESIGN.md §7.3.
 *
 * <p>Computes apparent angular size in pixels and returns a {@link CullDecision} based on the body's NAIF ID class and
 * the {@link KepplrConstants#POINT_SPRITE_THRESHOLD_PX 2-pixel threshold}.
 *
 * <p>All methods are static; this class is not instantiated.
 */
public final class BodyCuller {

    private BodyCuller() {}

    /**
     * Compute the body's apparent radius in viewport pixels.
     *
     * <p>Uses the small-angle approximation:
     *
     * <pre>
     *   apparentRadiusPx = (bodyRadiusKm / distKm) × (viewportHeight / 2) / tan(fovY / 2)
     * </pre>
     *
     * @param bodyRadiusKm body mean radius (km); must be &gt; 0
     * @param distKm camera-to-body-center distance (km); must be &gt; 0
     * @param viewportHeight viewport height in pixels; must be &gt; 0
     * @param fovYDeg camera vertical field-of-view in degrees; must be &gt; 0
     * @return apparent radius in pixels; 0.0 if any input is invalid
     */
    public static double computeApparentRadiusPx(
            double bodyRadiusKm, double distKm, int viewportHeight, float fovYDeg) {
        if (distKm <= 0.0 || bodyRadiusKm <= 0.0 || viewportHeight <= 0 || fovYDeg <= 0f) {
            return 0.0;
        }
        double tanHalfFov = Math.tan(Math.toRadians(fovYDeg) / 2.0);
        return (bodyRadiusKm / distKm) * (viewportHeight / 2.0) / tanHalfFov;
    }

    /**
     * Determine whether the given NAIF ID represents a natural satellite.
     *
     * <p>Per NAIF conventions (REDESIGN.md §7.3): natural satellites have IDs in the range [100, 999] where {@code id %
     * 100 != 99}. Planet bodies end in 99 (Mercury=199, Venus=299, Earth=399, Mars=499, …, Pluto=999). Barycenters
     * (1–9) and the Sun (10) are excluded by the range.
     *
     * <p>Examples: Moon=301 → satellite; Earth=399 → not satellite; Io=501 → satellite.
     *
     * @param naifId NAIF integer ID
     * @return true if the body is a natural satellite
     */
    public static boolean isSatellite(int naifId) {
        return naifId >= 100 && naifId <= 999 && naifId % 100 != 99;
    }

    /**
     * Decide how to render a body given its apparent size (§7.3).
     *
     * <ul>
     *   <li>Apparent radius &ge; threshold → {@link CullDecision#DRAW_FULL}
     *   <li>Apparent radius &lt; threshold and satellite → {@link CullDecision#CULL}
     *   <li>Apparent radius &lt; threshold and not satellite → {@link CullDecision#DRAW_SPRITE}
     * </ul>
     *
     * @param apparentRadiusPx apparent radius in pixels
     * @param naifId NAIF integer ID of the body
     * @return rendering decision
     */
    public static CullDecision decide(double apparentRadiusPx, int naifId) {
        if (apparentRadiusPx >= KepplrConstants.POINT_SPRITE_THRESHOLD_PX) {
            return CullDecision.DRAW_FULL;
        }
        if (isSatellite(naifId)) {
            return CullDecision.CULL;
        }
        return CullDecision.DRAW_SPRITE;
    }
}
