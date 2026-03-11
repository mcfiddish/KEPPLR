package kepplr.render.body;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ShadowGeometry}.
 *
 * <p>Pure math — no SPICE kernel, no JME context required.
 * All positions in km; distances chosen to give easily-verifiable angular geometry.
 */
class ShadowGeometryTest {

    // ── Geometry helpers ─────────────────────────────────────────────────────────────────────

    /** Simple 3-vector at (x, 0, 0). */
    private static double[] v(double x, double y, double z) {
        return new double[] {x, y, z};
    }

    // ── canCastShadow ────────────────────────────────────────────────────────────────────────

    @Test
    void canCastShadow_casterInSolarHemisphere_returnsTrue() {
        // receiver at origin, Sun at +X, caster also at +X (same hemisphere)
        assertTrue(ShadowGeometry.canCastShadow(v(0, 0, 0), v(1e8, 0, 0), v(5e7, 0, 0)));
    }

    @Test
    void canCastShadow_casterBehindReceiver_returnsFalse() {
        // receiver at origin, Sun at +X, caster at -X (opposite hemisphere)
        assertFalse(ShadowGeometry.canCastShadow(v(0, 0, 0), v(1e8, 0, 0), v(-5e7, 0, 0)));
    }

    @Test
    void canCastShadow_casterPerpendicularToSun_returnsFalse() {
        // dot product is exactly 0 — caster is 90° from sun direction
        assertFalse(ShadowGeometry.canCastShadow(v(0, 0, 0), v(1e8, 0, 0), v(0, 5e7, 0)));
    }

    // ── computeLitFraction — point source ────────────────────────────────────────────────────

    @Test
    void pointSource_fullyLit_returns1() {
        // Sun at 1e8 km along +X; receiver at origin; caster at +Y (off-axis, no shadow)
        double lit = ShadowGeometry.computeLitFraction(
                v(0, 0, 0), v(1e8, 0, 0), 695700, v(0, 1e6, 0), 1000, false);
        assertEquals(1.0, lit, 1e-9);
    }

    @Test
    void pointSource_fullUmbra_returns0() {
        // Caster (r=1000 km) at 2000 km directly between receiver and Sun (at 1e8 km)
        // Angular radius of caster from receiver = asin(1000/2000) ≈ 30° >> angular radius of sun direction offset = 0
        // Receiver is directly behind the caster → full umbra
        double lit = ShadowGeometry.computeLitFraction(
                v(0, 0, 0), v(1e8, 0, 0), 695700, v(2000, 0, 0), 1000, false);
        assertEquals(0.0, lit, 1e-9);
    }

    // ── computeLitFraction — extended source ─────────────────────────────────────────────────

    @Test
    void extendedSource_fullyLit_returns1() {
        // Caster is far off-axis; no overlap with solar disk
        double lit = ShadowGeometry.computeLitFraction(
                v(0, 0, 0), v(1.5e9, 0, 0), 695700, v(0, 1e8, 0), 1000, true);
        assertEquals(1.0, lit, 1e-9);
    }

    @Test
    void extendedSource_fullUmbra_returns0() {
        // Moon (r=1737 km) at 384000 km; Sun at 1.5e8 km.
        // αOcc = asin(1737/384000) ≈ 0.00452 rad; αSun = asin(695700/1.5e8) ≈ 0.00464 rad
        // umbraLimit = αOcc - αSun < 0 → no true umbra with these values.
        // Use a large caster that guarantees umbra: caster r=5000 km at dist=10000 km → αOcc ≈ 30°.
        // Sun at 1e8 km, r=695700 km → αSun ≈ asin(695700/1e8) ≈ 0.4°.
        // umbraLimit = 30° − 0.4° > 0; receiver at origin, θ = 0 < umbraLimit → full umbra.
        double lit = ShadowGeometry.computeLitFraction(
                v(0, 0, 0), v(1e8, 0, 0), 695700, v(10000, 0, 0), 5000, true);
        assertEquals(0.0, lit, 1e-9);
    }

    @Test
    void extendedSource_penumbra_strictlyBetween0And1() {
        // Caster (r=10000 km) at 50001 km from receiver.
        // αOcc ≈ asin(10000/50001) ≈ 0.2013 rad; αSun ≈ asin(695700/1e8) ≈ 0.0070 rad
        // umbraLimit ≈ 0.1944 rad, penumbraEnd ≈ 0.2083 rad.
        // Caster position (49004, 9935, 0) gives θ ≈ 0.2000 rad — squarely in the penumbra band.
        double lit = ShadowGeometry.computeLitFraction(
                v(0, 0, 0), v(1e8, 0, 0), 695700, v(49004, 9935, 0), 10000, true);
        assertTrue(lit > 0.0 && lit < 1.0,
                "Expected penumbra lit fraction in (0,1) but got " + lit);
    }

    @Test
    void extendedSource_casterBehindReceiver_fullyLit() {
        // Caster is on the far side of receiver from the Sun (canCastShadow would return false,
        // but computeLitFraction should also return 1.0 gracefully).
        double lit = ShadowGeometry.computeLitFraction(
                v(0, 0, 0), v(1e8, 0, 0), 695700, v(-5e7, 0, 0), 1000, true);
        assertEquals(1.0, lit, 1e-9);
    }

    // ── computeCombinedLitFraction ────────────────────────────────────────────────────────────

    @Test
    void combined_noCasters_fullyLit() {
        double lit = ShadowGeometry.computeCombinedLitFraction(
                v(0, 0, 0), v(1e8, 0, 0), 695700, new double[0][], new double[0], 0, true);
        assertEquals(1.0, lit, 1e-9);
    }

    @Test
    void combined_oneCasterFullUmbra_returns0() {
        double lit = ShadowGeometry.computeCombinedLitFraction(
                v(0, 0, 0), v(1e8, 0, 0), 695700,
                new double[][] {v(10000, 0, 0)}, new double[] {5000}, 1, true);
        assertEquals(0.0, lit, 1e-9);
    }

    @Test
    void combined_twoCastersWorstCaseWins() {
        // Caster A is fully lit; Caster B is in full umbra — combined should be 0.
        double lit = ShadowGeometry.computeCombinedLitFraction(
                v(0, 0, 0), v(1e8, 0, 0), 695700,
                new double[][] {v(0, 1e8, 0), v(10000, 0, 0)},
                new double[] {1000, 5000},
                2, true);
        assertEquals(0.0, lit, 1e-9);
    }

    // ── Degenerate inputs ─────────────────────────────────────────────────────────────────────

    @Test
    void degenerate_zeroCasterRadius_fullyLit() {
        double lit = ShadowGeometry.computeLitFraction(
                v(0, 0, 0), v(1e8, 0, 0), 695700, v(5e7, 0, 0), 0.0, true);
        assertEquals(1.0, lit, 1e-9);
    }

    @Test
    void degenerate_zeroSunDistance_fullyLit() {
        // Sun at receiver position — degenerate; should return 1.0 gracefully
        double lit = ShadowGeometry.computeLitFraction(
                v(0, 0, 0), v(0, 0, 0), 695700, v(5e7, 0, 0), 1000, true);
        assertEquals(1.0, lit, 1e-9);
    }
}
