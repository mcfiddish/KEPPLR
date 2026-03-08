package kepplr.render.frustum;

import kepplr.util.KepplrConstants;

/**
 * Frustum layer assignment for multi-frustum rendering (REDESIGN.md §8).
 *
 * <p>Each layer corresponds to a camera with near/far planes that overlap adjacent layers by
 * {@link KepplrConstants#FRUSTUM_OVERLAP_FRACTION} (§8.2). The overlap is applied symmetrically: the far plane of a
 * layer extends 10% beyond the base boundary, and the near plane of the next layer begins 10% before it.
 *
 * <p>Layers are ordered from most-local (NEAR) to most-distant (FAR). Assignment tries NEAR first so bodies near the
 * camera are always rendered in the most precise frustum available.
 */
public enum FrustumLayer {

    /** Near frustum: 1 m to 1000 km, far plane extended 10% to allow overlap with MID. */
    NEAR(
            KepplrConstants.FRUSTUM_NEAR_MIN_KM,
            KepplrConstants.FRUSTUM_NEAR_MAX_KM * (1.0 + KepplrConstants.FRUSTUM_OVERLAP_FRACTION)),

    /** Mid frustum: 1000 km to 1e9 km, both planes extended 10% to overlap with NEAR and FAR. */
    MID(
            KepplrConstants.FRUSTUM_MID_MIN_KM * (1.0 - KepplrConstants.FRUSTUM_OVERLAP_FRACTION),
            KepplrConstants.FRUSTUM_MID_MAX_KM * (1.0 + KepplrConstants.FRUSTUM_OVERLAP_FRACTION)),

    /** Far frustum: 1e9 km to 1e15 km, near plane extended 10% to overlap with MID. */
    FAR(
            KepplrConstants.FRUSTUM_FAR_MIN_KM * (1.0 - KepplrConstants.FRUSTUM_OVERLAP_FRACTION),
            KepplrConstants.FRUSTUM_FAR_MAX_KM);

    /** Camera near-plane distance for this layer (km). */
    public final double nearKm;

    /** Camera far-plane distance for this layer (km). */
    public final double farKm;

    FrustumLayer(double nearKm, double farKm) {
        this.nearKm = nearKm;
        this.farKm = farKm;
    }

    /**
     * Assign a body to the nearest frustum layer that fully contains its bounding volume.
     *
     * <p>Per §8.3: tries NEAR first, then MID, then FAR. If no single layer fully contains the bounding volume (e.g., a
     * very large body), returns the layer with the greatest containment fraction.
     *
     * @param distKm camera-to-body-center distance (km); must be &ge; 0
     * @param bodyRadiusKm body bounding radius (km); use 0 for a point
     * @return assigned FrustumLayer; never null (defaults to FAR if nothing else fits)
     */
    public static FrustumLayer assign(double distKm, double bodyRadiusKm) {
        double lo = Math.max(0.0, distKm - bodyRadiusKm);
        double hi = distKm + bodyRadiusKm;

        // Try nearest-first: NEAR → MID → FAR
        for (FrustumLayer layer : values()) {
            if (lo >= layer.nearKm && hi <= layer.farKm) {
                return layer;
            }
        }

        // No layer fully contains the bounding volume: pick the one with the greatest overlap
        FrustumLayer best = FAR;
        double bestOverlapFraction = -1.0;
        double boundingSpan = hi - lo;

        for (FrustumLayer layer : values()) {
            double overlapLo = Math.max(lo, layer.nearKm);
            double overlapHi = Math.min(hi, layer.farKm);
            if (overlapHi > overlapLo) {
                double fraction = (overlapHi - overlapLo) / (boundingSpan > 0 ? boundingSpan : 1.0);
                if (fraction > bestOverlapFraction) {
                    bestOverlapFraction = fraction;
                    best = layer;
                }
            }
        }

        return best;
    }
}
