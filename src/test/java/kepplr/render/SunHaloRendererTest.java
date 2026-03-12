package kepplr.render;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SunHaloRenderer} — pure-math static helpers only (no JME scene graph required). */
class SunHaloRendererTest {

    // ── computeBillboardRadiusKm ─────────────────────────────────────────────────────────────

    @Test
    void computeBillboardRadiusKm_sunMeanRadius_producesRadiusTimesMultiplier() {
        double sunMeanRadiusKm = 695_700.0; // IAU mean solar radius
        double expected = sunMeanRadiusKm * KepplrConstants.SUN_HALO_MAX_RADIUS_MULTIPLIER;
        assertEquals(expected, SunHaloRenderer.computeBillboardRadiusKm(sunMeanRadiusKm), 1e-6);
    }

    @Test
    void computeBillboardRadiusKm_doubleRadius_doublesResult() {
        double r1 = 100_000.0;
        double r2 = 200_000.0;
        assertEquals(
                2.0 * SunHaloRenderer.computeBillboardRadiusKm(r1), SunHaloRenderer.computeBillboardRadiusKm(r2), 1e-6);
    }

    @Test
    void computeBillboardRadiusKm_zeroRadius_returnsZero() {
        assertEquals(0.0, SunHaloRenderer.computeBillboardRadiusKm(0.0), 1e-12);
    }

    @Test
    void computeBillboardRadiusKm_multiplierMatchesConstant() {
        // Verify the multiplier used inside the method is SUN_HALO_MAX_RADIUS_MULTIPLIER
        double r = 1.0;
        assertEquals(KepplrConstants.SUN_HALO_MAX_RADIUS_MULTIPLIER, SunHaloRenderer.computeBillboardRadiusKm(r), 1e-9);
    }

    // ── effectiveBillboardRadiusKm ───────────────────────────────────────────────────────────

    private static final double SUN_MEAN_RADIUS_KM = 695_700.0; // IAU mean solar radius

    @Test
    void effectiveBillboardRadiusKm_nearSun_usesPhysicalRadius() {
        // At 1 AU (~149.6e6 km) the physical radius is much larger than the 0.5° minimum.
        double distKm = 149_597_870.7; // 1 AU
        double physical = SunHaloRenderer.computeBillboardRadiusKm(SUN_MEAN_RADIUS_KM);
        double effective = SunHaloRenderer.effectiveBillboardRadiusKm(SUN_MEAN_RADIUS_KM, distKm);
        assertEquals(physical, effective, 1.0, "Physical radius should dominate at 1 AU");
    }

    @Test
    void effectiveBillboardRadiusKm_farFromSun_enforcesMinimum() {
        // At 100 AU the physical billboard subtends ~0.011° half-angle, well below 0.5°.
        double distKm = 100 * 149_597_870.7;
        double minimum = distKm * Math.tan(KepplrConstants.SUN_HALO_MIN_APPARENT_HALF_ANGLE_RAD);
        double effective = SunHaloRenderer.effectiveBillboardRadiusKm(SUN_MEAN_RADIUS_KM, distKm);
        assertEquals(minimum, effective, 1.0, "Minimum angular floor should dominate at 100 AU");
    }

    @Test
    void effectiveBillboardRadiusKm_atCrossoverDistance_isExact() {
        // Find the crossover distance where physical == minimum, and verify effective == both.
        double physical = SunHaloRenderer.computeBillboardRadiusKm(SUN_MEAN_RADIUS_KM);
        double tanHalf = Math.tan(KepplrConstants.SUN_HALO_MIN_APPARENT_HALF_ANGLE_RAD);
        double crossoverDistKm = physical / tanHalf;
        double effective = SunHaloRenderer.effectiveBillboardRadiusKm(SUN_MEAN_RADIUS_KM, crossoverDistKm);
        assertEquals(physical, effective, 1.0);
    }

    @Test
    void effectiveBillboardRadiusKm_isNeverLessThanPhysical() {
        double[] distances = {1e4, 1e6, 1e8, 1e10, 1e12};
        for (double d : distances) {
            double physical = SunHaloRenderer.computeBillboardRadiusKm(SUN_MEAN_RADIUS_KM);
            double effective = SunHaloRenderer.effectiveBillboardRadiusKm(SUN_MEAN_RADIUS_KM, d);
            assertTrue(effective >= physical - 1e-9, "Effective radius must be >= physical at dist=" + d);
        }
    }

    // ── RenderQuality halo constants monotonicity ────────────────────────────────────────────

    @Test
    void sunHaloFalloff_monotonicallyIncreasing() {
        assertTrue(RenderQuality.LOW.sunHaloFalloff() <= RenderQuality.MEDIUM.sunHaloFalloff());
        assertTrue(RenderQuality.MEDIUM.sunHaloFalloff() <= RenderQuality.HIGH.sunHaloFalloff());
    }

    @Test
    void sunHaloAlphaScale_monotonicallyIncreasing() {
        assertTrue(RenderQuality.LOW.sunHaloAlphaScale() <= RenderQuality.MEDIUM.sunHaloAlphaScale());
        assertTrue(RenderQuality.MEDIUM.sunHaloAlphaScale() <= RenderQuality.HIGH.sunHaloAlphaScale());
    }

    @Test
    void sunHaloFalloff_low_matchesConstant() {
        assertEquals(KepplrConstants.SUN_HALO_FALLOFF_LOW, RenderQuality.LOW.sunHaloFalloff(), 1e-9f);
    }

    @Test
    void sunHaloFalloff_medium_matchesConstant() {
        assertEquals(KepplrConstants.SUN_HALO_FALLOFF_MEDIUM, RenderQuality.MEDIUM.sunHaloFalloff(), 1e-9f);
    }

    @Test
    void sunHaloFalloff_high_matchesConstant() {
        assertEquals(KepplrConstants.SUN_HALO_FALLOFF_HIGH, RenderQuality.HIGH.sunHaloFalloff(), 1e-9f);
    }

    @Test
    void sunHaloAlphaScale_low_matchesConstant() {
        assertEquals(KepplrConstants.SUN_HALO_ALPHA_SCALE_LOW, RenderQuality.LOW.sunHaloAlphaScale(), 1e-9f);
    }

    @Test
    void sunHaloAlphaScale_medium_matchesConstant() {
        assertEquals(KepplrConstants.SUN_HALO_ALPHA_SCALE_MEDIUM, RenderQuality.MEDIUM.sunHaloAlphaScale(), 1e-9f);
    }

    @Test
    void sunHaloAlphaScale_high_matchesConstant() {
        assertEquals(KepplrConstants.SUN_HALO_ALPHA_SCALE_HIGH, RenderQuality.HIGH.sunHaloAlphaScale(), 1e-9f);
    }

    // ── Constant sanity checks ───────────────────────────────────────────────────────────────

    @Test
    void maxRadiusMultiplier_isGreaterThanOne() {
        // Must be > 1 so the billboard extends beyond the Sun's limb
        assertTrue(KepplrConstants.SUN_HALO_MAX_RADIUS_MULTIPLIER > 1.0f);
    }

    @Test
    void allFalloffConstants_arePositive() {
        assertTrue(KepplrConstants.SUN_HALO_FALLOFF_LOW > 0.0f);
        assertTrue(KepplrConstants.SUN_HALO_FALLOFF_MEDIUM > 0.0f);
        assertTrue(KepplrConstants.SUN_HALO_FALLOFF_HIGH > 0.0f);
    }

    @Test
    void allAlphaScaleConstants_areInUnitRange() {
        assertTrue(KepplrConstants.SUN_HALO_ALPHA_SCALE_LOW > 0.0f);
        assertTrue(KepplrConstants.SUN_HALO_ALPHA_SCALE_LOW <= 1.0f);
        assertTrue(KepplrConstants.SUN_HALO_ALPHA_SCALE_HIGH <= 1.0f);
    }
}
