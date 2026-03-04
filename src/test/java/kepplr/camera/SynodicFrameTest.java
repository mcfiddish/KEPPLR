package kepplr.camera;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.config.KEPPLRConfiguration;
import kepplr.testsupport.TestHarness;
import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.VectorIJK;

/**
 * Unit tests for {@link SynodicFrame}.
 *
 * <p>Real-SPICE tests use a real {@link KEPPLRConfiguration} with the test metakernel. Epoch is 2015 Jul 14 07:59:00
 * UTC (New Horizons Pluto flyby). Degenerate-case and axis-math tests exercise
 * {@link SynodicFrame#computeFromXAxis(VectorIJK)} directly to avoid needing a contrived ephemeris setup.
 */
@DisplayName("SynodicFrame")
class SynodicFrameTest {

    static final int EARTH = 399;
    static final int MOON = 301;

    /** Tolerance for unit-vector length checks. */
    static final double UNIT_TOLERANCE = 1e-10;

    /** Tolerance for orthogonality (dot product near zero). */
    static final double ORTHO_TOLERANCE = 1e-10;

    /** Tolerance for right-handedness check (determinant near +1). */
    static final double DET_TOLERANCE = 1e-10;

    private double testEt;

    @BeforeEach
    void setUp() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTestTemplate();
        testEt = TestHarness.getTestEpoch();
    }

    // ─────────────────────────────────────────────────────────────────
    // Normal case with real SPICE data
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Normal case — Earth focus, Moon target (real SPICE)")
    class NormalCase {

        @Test
        @DisplayName("compute(Earth, Moon, et) returns non-null Basis")
        void returnsNonNull() {
            assertNotNull(SynodicFrame.compute(EARTH, MOON, testEt));
        }

        @Test
        @DisplayName("+X axis is a unit vector")
        void xAxisIsUnit() {
            SynodicFrame.Basis b = SynodicFrame.compute(EARTH, MOON, testEt);
            assertNotNull(b);
            assertEquals(1.0, b.xAxis().getLength(), UNIT_TOLERANCE, "+X should be a unit vector");
        }

        @Test
        @DisplayName("+Y axis is a unit vector")
        void yAxisIsUnit() {
            SynodicFrame.Basis b = SynodicFrame.compute(EARTH, MOON, testEt);
            assertNotNull(b);
            assertEquals(1.0, b.yAxis().getLength(), UNIT_TOLERANCE, "+Y should be a unit vector");
        }

        @Test
        @DisplayName("+Z axis is a unit vector")
        void zAxisIsUnit() {
            SynodicFrame.Basis b = SynodicFrame.compute(EARTH, MOON, testEt);
            assertNotNull(b);
            assertEquals(1.0, b.zAxis().getLength(), UNIT_TOLERANCE, "+Z should be a unit vector");
        }

        @Test
        @DisplayName("Axes are mutually orthogonal")
        void axesAreOrthogonal() {
            SynodicFrame.Basis b = SynodicFrame.compute(EARTH, MOON, testEt);
            assertNotNull(b);
            assertEquals(0.0, dot(b.xAxis(), b.yAxis()), ORTHO_TOLERANCE, "X·Y should be 0");
            assertEquals(0.0, dot(b.xAxis(), b.zAxis()), ORTHO_TOLERANCE, "X·Z should be 0");
            assertEquals(0.0, dot(b.yAxis(), b.zAxis()), ORTHO_TOLERANCE, "Y·Z should be 0");
        }

        @Test
        @DisplayName("Basis is right-handed: X × Y = Z (determinant = +1)")
        void rightHanded() {
            SynodicFrame.Basis b = SynodicFrame.compute(EARTH, MOON, testEt);
            assertNotNull(b);
            VectorIJK xCrossY = cross(b.xAxis(), b.yAxis());
            assertEquals(b.zAxis().getI(), xCrossY.getI(), ORTHO_TOLERANCE, "X×Y should equal Z (I)");
            assertEquals(b.zAxis().getJ(), xCrossY.getJ(), ORTHO_TOLERANCE, "X×Y should equal Z (J)");
            assertEquals(b.zAxis().getK(), xCrossY.getK(), ORTHO_TOLERANCE, "X×Y should equal Z (K)");
        }

        @Test
        @DisplayName("+X axis is parallel to the geometric Earth→Moon direction from ephemeris")
        void xAxisAlignedWithEarthToMoon() {
            SynodicFrame.Basis b = SynodicFrame.compute(EARTH, MOON, testEt);
            assertNotNull(b);

            // Get geometric Earth→Moon from ephemeris for independent comparison
            kepplr.ephemeris.KEPPLREphemeris eph =
                    KEPPLRConfiguration.getInstance().getEphemeris();
            picante.math.vectorspace.VectorIJK raw = eph.getObserverToTargetJ2000(
                    EARTH, MOON, testEt, picante.mechanics.providers.aberrated.AberrationCorrection.NONE);
            assertNotNull(raw);
            double len = raw.getLength();
            VectorIJK expected = new VectorIJK(raw.getI() / len, raw.getJ() / len, raw.getK() / len);

            assertEquals(expected.getI(), b.xAxis().getI(), 1e-10, "+X I component should match Earth→Moon direction");
            assertEquals(expected.getJ(), b.xAxis().getJ(), 1e-10, "+X J component should match Earth→Moon direction");
            assertEquals(expected.getK(), b.xAxis().getK(), 1e-10, "+X K component should match Earth→Moon direction");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Degenerate case: +X near-parallel to J2000 +Z
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Degenerate case — +X near-parallel to J2000 +Z (§5.2)")
    class DegenerateCase {

        /**
         * A unit vector very close to J2000 +Z: only a tiny X/Y component. Angle from +Z is atan2(sqrt(ε²+ε²), 1) ≈ ε√2
         * << SYNODIC_DEGENERATE_THRESHOLD_RAD (1e-3).
         */
        private VectorIJK nearPolarXAxis() {
            double eps = 1e-5;
            double z = Math.sqrt(1.0 - 2 * eps * eps);
            return new VectorIJK(eps, eps, z);
        }

        @Test
        @DisplayName("Near-polar +X triggers degenerate case: result is still non-null")
        void degenerateStillReturnsResult() {
            assertNotNull(SynodicFrame.computeFromXAxis(nearPolarXAxis()));
        }

        @Test
        @DisplayName("Degenerate result has valid unit axes")
        void degenerateResultHasUnitAxes() {
            SynodicFrame.Basis b = SynodicFrame.computeFromXAxis(nearPolarXAxis());
            assertNotNull(b);
            assertEquals(1.0, b.xAxis().getLength(), UNIT_TOLERANCE, "+X should be unit");
            assertEquals(1.0, b.yAxis().getLength(), UNIT_TOLERANCE, "+Y should be unit");
            assertEquals(1.0, b.zAxis().getLength(), UNIT_TOLERANCE, "+Z should be unit");
        }

        @Test
        @DisplayName("Degenerate result is orthogonal")
        void degenerateResultIsOrthogonal() {
            SynodicFrame.Basis b = SynodicFrame.computeFromXAxis(nearPolarXAxis());
            assertNotNull(b);
            assertEquals(0.0, dot(b.xAxis(), b.yAxis()), ORTHO_TOLERANCE, "X·Y should be 0");
            assertEquals(0.0, dot(b.xAxis(), b.zAxis()), ORTHO_TOLERANCE, "X·Z should be 0");
            assertEquals(0.0, dot(b.yAxis(), b.zAxis()), ORTHO_TOLERANCE, "Y·Z should be 0");
        }

        @Test
        @DisplayName("Degenerate result is right-handed: X × Y = Z")
        void degenerateResultIsRightHanded() {
            SynodicFrame.Basis b = SynodicFrame.computeFromXAxis(nearPolarXAxis());
            assertNotNull(b);
            VectorIJK xCrossY = cross(b.xAxis(), b.yAxis());
            assertEquals(b.zAxis().getI(), xCrossY.getI(), ORTHO_TOLERANCE, "X×Y should equal Z (I)");
            assertEquals(b.zAxis().getJ(), xCrossY.getJ(), ORTHO_TOLERANCE, "X×Y should equal Z (J)");
            assertEquals(b.zAxis().getK(), xCrossY.getK(), ORTHO_TOLERANCE, "X×Y should equal Z (K)");
        }

        @Test
        @DisplayName("Verify degenerate threshold constant applied: near-polar input magnitude < threshold")
        void nearPolarInputIsBelowThreshold() {
            VectorIJK xAxis = nearPolarXAxis();
            VectorIJK zJ2000 = new VectorIJK(0.0, 0.0, 1.0);
            VectorIJK xCrossZ = cross(xAxis, zJ2000);
            assertTrue(
                    xCrossZ.getLength() < KepplrConstants.SYNODIC_DEGENERATE_THRESHOLD_RAD,
                    "Test vector should be within degenerate threshold; got |X×Z|=" + xCrossZ.getLength());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Normal axis-math: non-degenerate input via computeFromXAxis
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Axis math — non-degenerate synthetic input")
    class AxisMath {

        @Test
        @DisplayName("X along +Y of J2000 produces valid orthonormal right-handed basis")
        void xAlongJ2000Y() {
            // +X = J2000 +Y; not parallel to J2000 +Z, so non-degenerate
            SynodicFrame.Basis b = SynodicFrame.computeFromXAxis(new VectorIJK(0.0, 1.0, 0.0));
            assertNotNull(b);
            assertEquals(1.0, b.xAxis().getLength(), UNIT_TOLERANCE);
            assertEquals(1.0, b.yAxis().getLength(), UNIT_TOLERANCE);
            assertEquals(1.0, b.zAxis().getLength(), UNIT_TOLERANCE);
            assertEquals(0.0, dot(b.xAxis(), b.yAxis()), ORTHO_TOLERANCE);
            assertEquals(0.0, dot(b.xAxis(), b.zAxis()), ORTHO_TOLERANCE);
            assertEquals(0.0, dot(b.yAxis(), b.zAxis()), ORTHO_TOLERANCE);
            VectorIJK xCrossY = cross(b.xAxis(), b.yAxis());
            assertEquals(b.zAxis().getI(), xCrossY.getI(), ORTHO_TOLERANCE);
            assertEquals(b.zAxis().getJ(), xCrossY.getJ(), ORTHO_TOLERANCE);
            assertEquals(b.zAxis().getK(), xCrossY.getK(), ORTHO_TOLERANCE);
        }

        @Test
        @DisplayName("ECLIPTIC_J2000_Z constant is a unit vector")
        void eclipticJ2000ZIsUnit() {
            assertEquals(
                    1.0,
                    SynodicFrame.ECLIPTIC_J2000_Z.getLength(),
                    UNIT_TOLERANCE,
                    "ECLIPTIC_J2000_Z should be a unit vector");
        }

        @Test
        @DisplayName("ECLIPTIC_J2000_Z has zero X component and is tilted from J2000 +Z by obliquity")
        void eclipticJ2000ZDirection() {
            VectorIJK ez = SynodicFrame.ECLIPTIC_J2000_Z;
            assertEquals(0.0, ez.getI(), 1e-15, "Ecliptic +Z X component should be 0");
            // J component = -sin(ε) < 0
            assertTrue(ez.getJ() < 0.0, "Ecliptic +Z J component should be negative");
            // Z component = cos(ε) > 0
            assertTrue(ez.getK() > 0.0, "Ecliptic +Z K component should be positive");
            // Angle from J2000 +Z equals obliquity
            double dotWithJ2000Z = ez.getK(); // (0,0,1)·(0,-sinε,cosε) = cosε
            double angle = Math.acos(dotWithJ2000Z);
            assertEquals(
                    KepplrConstants.ECLIPTIC_J2000_OBLIQUITY_RAD,
                    angle,
                    1e-10,
                    "Ecliptic +Z should be tilted from J2000 +Z by the obliquity angle");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Fallback: absent focus or target
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Fallback — absent focus or target body")
    class FallbackTests {

        @Test
        @DisplayName("compute(-1, MOON, et) returns null — no focus body")
        void noFocusReturnsNull() {
            assertNull(SynodicFrame.compute(-1, MOON, testEt), "Should return null when focus body is absent");
        }

        @Test
        @DisplayName("compute(EARTH, -1, et) returns null — no target body")
        void noTargetReturnsNull() {
            assertNull(SynodicFrame.compute(EARTH, -1, testEt), "Should return null when target body is absent");
        }

        @Test
        @DisplayName("compute(-1, -1, et) returns null — both absent")
        void bothAbsentReturnsNull() {
            assertNull(
                    SynodicFrame.compute(-1, -1, testEt), "Should return null when both focus and target are absent");
        }
    }

    // ── Private helpers ──────────────────────────────────────────────

    private static double dot(VectorIJK a, VectorIJK b) {
        return a.getI() * b.getI() + a.getJ() * b.getJ() + a.getK() * b.getK();
    }

    private static VectorIJK cross(VectorIJK a, VectorIJK b) {
        return new VectorIJK(
                a.getJ() * b.getK() - a.getK() * b.getJ(),
                a.getK() * b.getI() - a.getI() * b.getK(),
                a.getI() * b.getJ() - a.getJ() * b.getI());
    }
}
