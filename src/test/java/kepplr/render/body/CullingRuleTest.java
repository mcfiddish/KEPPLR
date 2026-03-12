package kepplr.render.body;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the full culling pipeline (REDESIGN.md §7.3).
 *
 * <p>Tests 1–3 verify the per-body {@link BodyCuller#decide} classification. Tests 4–5 verify the cluster-suppression
 * pass {@link BodyCuller#computeClusterCulls}.
 */
class CullingRuleTest {

    private static final double ABOVE = KepplrConstants.DRAW_FULL_MIN_APPARENT_RADIUS_PX + 1.0;
    private static final double BELOW = KepplrConstants.DRAW_FULL_MIN_APPARENT_RADIUS_PX - 0.5;
    private static final double PROX = KepplrConstants.SPRITE_CLUSTER_PROXIMITY_PX;

    /** Case 1: apparent radius >= 2 px → DRAW_FULL. */
    @Test
    void case1_aboveThreshold_drawFull() {
        assertEquals(CullDecision.DRAW_FULL, BodyCuller.decide(ABOVE));
    }

    /** Case 2: apparent radius < 2 px, satellite → DRAW_SPRITE (not culled). */
    @Test
    void case2_belowThreshold_satellite_drawSprite() {
        // Moon NAIF ID 301 is a satellite; it must render as a sprite, not be culled.
        assertEquals(CullDecision.DRAW_SPRITE, BodyCuller.decide(BELOW));
        assertTrue(BodyCuller.isSatellite(301), "sanity: 301 is a satellite");
    }

    /** Case 3: apparent radius < 2 px, non-satellite → DRAW_SPRITE. */
    @Test
    void case3_belowThreshold_nonSatellite_drawSprite() {
        // Earth NAIF ID 399 is not a satellite.
        assertEquals(CullDecision.DRAW_SPRITE, BodyCuller.decide(BELOW));
        assertFalse(BodyCuller.isSatellite(399), "sanity: 399 is not a satellite");
    }

    /**
     * Case 4: two sprites within proximity threshold → smaller physical-radius body is suppressed.
     *
     * <p>Moon (301, r=1737 km) and Deimos (402, r=6 km) at the same screen position. Deimos (smaller radius) must be in
     * the culled set; Moon must not be culled.
     */
    @Test
    void case4_clusteredSprites_smallerIsSupressed() {
        List<BodyCuller.SpriteCandidate> candidates = List.of(
                new BodyCuller.SpriteCandidate(301, 100.0, 200.0, 1737.0, false), // Moon
                new BodyCuller.SpriteCandidate(402, 100.5, 200.5, 6.0, false) // Deimos — within 2 px
                );
        double dist = Math.sqrt(0.5 * 0.5 + 0.5 * 0.5); // ≈ 0.707 px — inside PROX=2
        assertTrue(dist < PROX, "sanity: candidates are within proximity threshold");

        Set<Integer> culled = BodyCuller.computeClusterCulls(candidates, PROX);

        assertTrue(culled.contains(402), "Deimos (smaller radius) must be suppressed");
        assertFalse(culled.contains(301), "Moon (larger radius) must not be suppressed");
    }

    /**
     * Case 5: two sprites within proximity threshold, smaller body is exempt → neither suppressed.
     *
     * <p>Deimos (402) is the focused body (exempt). Even though it has the smaller radius, it must not be culled. The
     * Moon (larger radius) is also not culled — the rule only removes the smaller, and since the smaller is exempt,
     * neither is removed.
     */
    @Test
    void case5_clusteredSprites_smallerIsExempt_neitherSuppressed() {
        List<BodyCuller.SpriteCandidate> candidates = List.of(
                new BodyCuller.SpriteCandidate(301, 100.0, 200.0, 1737.0, false), // Moon
                new BodyCuller.SpriteCandidate(402, 100.5, 200.5, 6.0, true) // Deimos — exempt
                );

        Set<Integer> culled = BodyCuller.computeClusterCulls(candidates, PROX);

        assertFalse(culled.contains(402), "Exempt body (Deimos) must not be suppressed");
        assertFalse(culled.contains(301), "Larger body (Moon) must not be suppressed when smaller is exempt");
    }
}
