package kepplr.render.body;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kepplr.util.KepplrConstants;

/**
 * Small-body culling logic per REDESIGN.md §7.3.
 *
 * <p>Computes apparent angular size in pixels and returns a {@link CullDecision} based on the
 * {@link KepplrConstants#DRAW_FULL_MIN_APPARENT_RADIUS_PX 2-pixel threshold}. All bodies below the threshold render as
 * sprites — satellites are not exempt.
 *
 * <p>A second pass ({@link #computeClusterCulls}) suppresses the smaller-radius body in any pair of sprites within
 * {@link KepplrConstants#SPRITE_CLUSTER_PROXIMITY_PX} pixels of each other on screen. Bodies in an active interaction
 * state (selected/focused/targeted/tracked) are exempt from suppression.
 *
 * <p>All methods are static; this class is not instantiated.
 */
public final class BodyCuller {

    private BodyCuller() {}

    // ── Per-body classification ────────────────────────────────────────────────────────────────

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
     *   <li>Apparent radius &ge; {@link KepplrConstants#DRAW_FULL_MIN_APPARENT_RADIUS_PX} →
     *       {@link CullDecision#DRAW_FULL}
     *   <li>Apparent radius &lt; threshold → {@link CullDecision#DRAW_SPRITE} (satellites included)
     * </ul>
     *
     * @param apparentRadiusPx apparent radius in pixels
     * @return rendering decision
     */
    public static CullDecision decide(double apparentRadiusPx) {
        if (apparentRadiusPx >= KepplrConstants.DRAW_FULL_MIN_APPARENT_RADIUS_PX) {
            return CullDecision.DRAW_FULL;
        }
        return CullDecision.DRAW_SPRITE;
    }

    // ── Cluster suppression ────────────────────────────────────────────────────────────────────

    /**
     * Lightweight descriptor for a body classified as {@link CullDecision#DRAW_SPRITE}, carrying the screen-space
     * position needed for cluster suppression.
     *
     * @param naifId NAIF integer ID
     * @param screenX screen X coordinate in pixels
     * @param screenY screen Y coordinate in pixels
     * @param physicalRadiusKm body mean physical radius in km (used to pick which body to suppress)
     * @param exempt true if the body is in an active interaction state and must never be suppressed
     */
    public record SpriteCandidate(
            int naifId, double screenX, double screenY, double physicalRadiusKm, boolean exempt) {}

    /**
     * Compute the set of sprite NAIF IDs that should be suppressed due to screen-space clustering (§7.3).
     *
     * <p>For each pair of candidates within {@code proximityPx} pixels, the one with the smaller physical radius is
     * added to the suppression set — unless it is marked {@code exempt}, in which case the larger body is never
     * suppressed on its behalf and the smaller body survives. When both bodies have equal physical radius, the one with
     * the lower NAIF ID is suppressed (deterministic tie-break).
     *
     * <p>Suppression is not transitive: if A suppresses B and B would suppress C, C is evaluated independently against
     * the remaining non-suppressed candidates.
     *
     * @param candidates list of sprite candidates with screen positions; may be empty
     * @param proximityPx pixel distance threshold; pairs closer than this are considered clustered
     * @return set of NAIF IDs to suppress; never null, may be empty
     */
    public static Set<Integer> computeClusterCulls(List<SpriteCandidate> candidates, double proximityPx) {
        Set<Integer> culled = new HashSet<>();
        int n = candidates.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                SpriteCandidate a = candidates.get(i);
                SpriteCandidate b = candidates.get(j);
                if (culled.contains(a.naifId()) || culled.contains(b.naifId())) continue;

                double dx = a.screenX() - b.screenX();
                double dy = a.screenY() - b.screenY();
                if (Math.sqrt(dx * dx + dy * dy) >= proximityPx) continue;

                // Pick the smaller body; tie-break by lower NAIF ID
                SpriteCandidate smaller;
                if (a.physicalRadiusKm() < b.physicalRadiusKm()) {
                    smaller = a;
                } else if (b.physicalRadiusKm() < a.physicalRadiusKm()) {
                    smaller = b;
                } else {
                    smaller = a.naifId() <= b.naifId() ? a : b;
                }

                if (!smaller.exempt()) {
                    culled.add(smaller.naifId());
                }
            }
        }
        return culled;
    }
}
