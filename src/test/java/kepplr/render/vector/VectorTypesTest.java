package kepplr.render.vector;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.RotationMatrixIJK;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.StateVector;

/**
 * Unit tests for {@link VectorTypes} strategy implementations.
 *
 * <p>Uses real SPICE kernels via the test metakernel. All direction checks use known-good SPICE state vectors derived
 * from the New Horizons Pluto flyby epoch (2015 Jul 14 07:59:00 UTC = {@link TestHarness#getTestEpoch()}).
 */
@DisplayName("VectorTypes")
class VectorTypesTest {

    /** Earth NAIF ID. */
    private static final int EARTH = 399;

    /** Moon NAIF ID. */
    private static final int MOON = 301;

    /** Sun NAIF ID. */
    private static final int SUN = 10;

    /** Tolerance for unit-vector length checks. */
    private static final double UNIT_LENGTH_TOL = 1e-12;

    /** Tolerance for direction alignment checks (cosine of angle ≥ 1 − TOL means nearly identical direction). */
    private static final double DIRECTION_TOL = 1e-9;

    private double et;

    @BeforeEach
    void setUp() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTestTemplate();
        et = TestHarness.getTestEpoch();
    }

    // ─────────────────────────────────────────────────────────────────
    // velocity()
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("velocity()")
    class VelocityTests {

        @Test
        @DisplayName("result is a unit vector")
        void resultIsUnitVector() {
            VectorIJK dir = VectorTypes.velocity().computeDirection(EARTH, et);
            assertNotNull(dir, "velocity() must return non-null for Earth at the test epoch");
            assertEquals(1.0, dir.getLength(), UNIT_LENGTH_TOL, "velocity direction must be a unit vector");
        }

        @Test
        @DisplayName("direction matches the normalized heliocentric velocity from SPICE")
        void matchesSpiceVelocity() {
            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
            StateVector state = eph.getHeliocentricStateJ2000(EARTH, et);
            assertNotNull(state, "SPICE must provide a heliocentric state for Earth");
            VectorIJK vel = state.getVelocity();
            double len = vel.getLength();
            // Expected unit velocity direction, computed manually from SPICE.
            double ex = vel.getI() / len;
            double ey = vel.getJ() / len;
            double ez = vel.getK() / len;

            VectorIJK dir = VectorTypes.velocity().computeDirection(EARTH, et);
            assertNotNull(dir);

            // Dot product of two unit vectors = cos(angle); should be ~1.0 (same direction).
            double dot = dir.getI() * ex + dir.getJ() * ey + dir.getK() * ez;
            assertEquals(1.0, dot, DIRECTION_TOL, "velocity direction must match SPICE velocity direction");
        }

        @Test
        @DisplayName("satellite velocity is relative to barycenter, not heliocentric")
        void satelliteVelocityIsRelativeToBarycenter() {
            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();

            // Moon (301) is a satellite — barycenter is 3 (Earth-Moon system)
            StateVector moonState = eph.getHeliocentricStateJ2000(MOON, et);
            StateVector baryState = eph.getHeliocentricStateJ2000(MOON / 100, et);
            assertNotNull(moonState);
            assertNotNull(baryState);

            // Expected: barycenter-relative velocity, normalized
            VectorIJK moonVel = moonState.getVelocity();
            VectorIJK baryVel = baryState.getVelocity();
            double rx = moonVel.getI() - baryVel.getI();
            double ry = moonVel.getJ() - baryVel.getJ();
            double rz = moonVel.getK() - baryVel.getK();
            double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
            double ex = rx / len;
            double ey = ry / len;
            double ez = rz / len;

            VectorIJK dir = VectorTypes.velocity().computeDirection(MOON, et);
            assertNotNull(dir, "velocity() must return non-null for Moon at the test epoch");
            assertEquals(1.0, dir.getLength(), UNIT_LENGTH_TOL, "velocity direction must be a unit vector");

            double dot = dir.getI() * ex + dir.getJ() * ey + dir.getK() * ez;
            assertEquals(
                    1.0,
                    dot,
                    DIRECTION_TOL,
                    "Moon velocity direction must match barycenter-relative velocity, not heliocentric");
        }

        @Test
        @DisplayName("returns null for a body not in the SPK")
        void returnsNullForUnknownBody() {
            VectorIJK dir = VectorTypes.velocity().computeDirection(-999999, et);
            assertNull(dir, "velocity() must return null when no ephemeris is available");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // towardBody(SUN) — direction from Earth toward the Sun
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("towardBody(SUN) from Earth")
    class TowardSunTests {

        @Test
        @DisplayName("result is a unit vector")
        void resultIsUnitVector() {
            VectorIJK dir = VectorTypes.towardBody(SUN).computeDirection(EARTH, et);
            assertNotNull(dir, "towardBody(SUN) must return non-null for Earth at the test epoch");
            assertEquals(1.0, dir.getLength(), UNIT_LENGTH_TOL, "toward-Sun direction must be a unit vector");
        }

        @Test
        @DisplayName("direction points from Earth toward Sun (antiparallel to Earth heliocentric position)")
        void pointsTowardSun() {
            // Sun is at the heliocentric origin, so the toward-Sun direction from Earth is
            // the negated, normalized Earth heliocentric position.
            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
            VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, et);
            assertNotNull(earthPos);
            double earthLen = earthPos.getLength();
            // Unit vector from Sun toward Earth.
            double sunToEarthX = earthPos.getI() / earthLen;
            double sunToEarthY = earthPos.getJ() / earthLen;
            double sunToEarthZ = earthPos.getK() / earthLen;

            VectorIJK dir = VectorTypes.towardBody(SUN).computeDirection(EARTH, et);
            assertNotNull(dir);

            // Earth→Sun is antiparallel to Sun→Earth: dot product must be ≈ -1.0.
            double dot = dir.getI() * sunToEarthX + dir.getJ() * sunToEarthY + dir.getK() * sunToEarthZ;
            assertEquals(-1.0, dot, DIRECTION_TOL, "toward-Sun must be antiparallel to heliocentric Earth position");
        }

        @Test
        @DisplayName("determinism: two calls with the same arguments produce identical results")
        void deterministic() {
            VectorType type = VectorTypes.towardBody(SUN);
            VectorIJK dir1 = type.computeDirection(EARTH, et);
            VectorIJK dir2 = type.computeDirection(EARTH, et);
            assertNotNull(dir1);
            assertNotNull(dir2);
            assertEquals(dir1.getI(), dir2.getI(), 0.0, "determinism: I component must be identical");
            assertEquals(dir1.getJ(), dir2.getJ(), 0.0, "determinism: J component must be identical");
            assertEquals(dir1.getK(), dir2.getK(), 0.0, "determinism: K component must be identical");
        }

        @Test
        @DisplayName("two separate VectorTypes.towardBody(SUN) instances produce the same result")
        void separateInstancesDeterministic() {
            VectorIJK dir1 = VectorTypes.towardBody(SUN).computeDirection(EARTH, et);
            VectorIJK dir2 = VectorTypes.towardBody(SUN).computeDirection(EARTH, et);
            assertNotNull(dir1);
            assertNotNull(dir2);
            assertEquals(dir1.getI(), dir2.getI(), 0.0, "separate instances must produce identical I");
            assertEquals(dir1.getJ(), dir2.getJ(), 0.0, "separate instances must produce identical J");
            assertEquals(dir1.getK(), dir2.getK(), 0.0, "separate instances must produce identical K");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // towardBody(EARTH) — direction from Moon toward Earth
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("towardBody(EARTH) from Moon")
    class TowardEarthTests {

        @Test
        @DisplayName("result is a unit vector")
        void resultIsUnitVector() {
            VectorIJK dir = VectorTypes.towardBody(EARTH).computeDirection(MOON, et);
            assertNotNull(dir, "towardBody(EARTH) must return non-null for Moon at the test epoch");
            assertEquals(1.0, dir.getLength(), UNIT_LENGTH_TOL, "toward-Earth direction must be a unit vector");
        }

        @Test
        @DisplayName("direction matches SPICE-computed Moon-to-Earth vector")
        void matchesSpiceVector() {
            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
            VectorIJK moonPos = eph.getHeliocentricPositionJ2000(MOON, et);
            VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, et);
            assertNotNull(moonPos);
            assertNotNull(earthPos);

            // Expected direction: normalize(Earth - Moon).
            double dx = earthPos.getI() - moonPos.getI();
            double dy = earthPos.getJ() - moonPos.getJ();
            double dz = earthPos.getK() - moonPos.getK();
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double ex = dx / len;
            double ey = dy / len;
            double ez = dz / len;

            VectorIJK dir = VectorTypes.towardBody(EARTH).computeDirection(MOON, et);
            assertNotNull(dir);

            double dot = dir.getI() * ex + dir.getJ() * ey + dir.getK() * ez;
            assertEquals(
                    1.0,
                    dot,
                    DIRECTION_TOL,
                    "toward-Earth from Moon must match normalized (Earth helio pos − Moon helio pos)");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // bodyAxisX/Y/Z() — body-fixed frame axes
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("bodyAxisX/Y/Z() for Earth")
    class BodyAxisTests {

        @Test
        @DisplayName("each axis is a unit vector")
        void eachAxisIsUnitVector() {
            VectorIJK x = VectorTypes.bodyAxisX().computeDirection(EARTH, et);
            VectorIJK y = VectorTypes.bodyAxisY().computeDirection(EARTH, et);
            VectorIJK z = VectorTypes.bodyAxisZ().computeDirection(EARTH, et);
            assertNotNull(x, "bodyAxisX must be non-null for Earth");
            assertNotNull(y, "bodyAxisY must be non-null for Earth");
            assertNotNull(z, "bodyAxisZ must be non-null for Earth");
            assertEquals(1.0, x.getLength(), UNIT_LENGTH_TOL, "bodyAxisX must be a unit vector");
            assertEquals(1.0, y.getLength(), UNIT_LENGTH_TOL, "bodyAxisY must be a unit vector");
            assertEquals(1.0, z.getLength(), UNIT_LENGTH_TOL, "bodyAxisZ must be a unit vector");
        }

        @Test
        @DisplayName("axes are mutually orthogonal (orthonormal basis)")
        void axesAreOrthogonal() {
            VectorIJK x = VectorTypes.bodyAxisX().computeDirection(EARTH, et);
            VectorIJK y = VectorTypes.bodyAxisY().computeDirection(EARTH, et);
            VectorIJK z = VectorTypes.bodyAxisZ().computeDirection(EARTH, et);
            assertNotNull(x);
            assertNotNull(y);
            assertNotNull(z);

            double dotXY = x.getI() * y.getI() + x.getJ() * y.getJ() + x.getK() * y.getK();
            double dotXZ = x.getI() * z.getI() + x.getJ() * z.getJ() + x.getK() * z.getK();
            double dotYZ = y.getI() * z.getI() + y.getJ() * z.getJ() + y.getK() * z.getK();

            assertEquals(0.0, dotXY, UNIT_LENGTH_TOL, "bodyAxisX and bodyAxisY must be orthogonal (dot product = 0)");
            assertEquals(0.0, dotXZ, UNIT_LENGTH_TOL, "bodyAxisX and bodyAxisZ must be orthogonal (dot product = 0)");
            assertEquals(0.0, dotYZ, UNIT_LENGTH_TOL, "bodyAxisY and bodyAxisZ must be orthogonal (dot product = 0)");
        }

        @Test
        @DisplayName("axes match rows of J2000-to-body-fixed rotation matrix")
        void matchRotationMatrixRows() {
            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
            RotationMatrixIJK rot = eph.getJ2000ToBodyFixedRotation(EARTH, et);
            assertNotNull(rot, "Earth must have a body-fixed rotation matrix");

            VectorIJK x = VectorTypes.bodyAxisX().computeDirection(EARTH, et);
            VectorIJK y = VectorTypes.bodyAxisY().computeDirection(EARTH, et);
            VectorIJK z = VectorTypes.bodyAxisZ().computeDirection(EARTH, et);
            assertNotNull(x);
            assertNotNull(y);
            assertNotNull(z);

            // Body-fixed +X in J2000 = R^T * e1 = row 0 of R = [R.get(0,0), R.get(0,1), R.get(0,2)].
            assertEquals(rot.get(0, 0), x.getI(), DIRECTION_TOL, "bodyAxisX I-component must equal R.get(0,0)");
            assertEquals(rot.get(0, 1), x.getJ(), DIRECTION_TOL, "bodyAxisX J-component must equal R.get(0,1)");
            assertEquals(rot.get(0, 2), x.getK(), DIRECTION_TOL, "bodyAxisX K-component must equal R.get(0,2)");

            assertEquals(rot.get(1, 0), y.getI(), DIRECTION_TOL, "bodyAxisY I-component must equal R.get(1,0)");
            assertEquals(rot.get(1, 1), y.getJ(), DIRECTION_TOL, "bodyAxisY J-component must equal R.get(1,1)");
            assertEquals(rot.get(1, 2), y.getK(), DIRECTION_TOL, "bodyAxisY K-component must equal R.get(1,2)");

            assertEquals(rot.get(2, 0), z.getI(), DIRECTION_TOL, "bodyAxisZ I-component must equal R.get(2,0)");
            assertEquals(rot.get(2, 1), z.getJ(), DIRECTION_TOL, "bodyAxisZ J-component must equal R.get(2,1)");
            assertEquals(rot.get(2, 2), z.getK(), DIRECTION_TOL, "bodyAxisZ K-component must equal R.get(2,2)");
        }

        @Test
        @DisplayName("bodyAxisX returns null for a body with no orientation data")
        void returnsNullForNoOrientation() {
            // NAIF -999 is not in any kernel and not configured as a spacecraft,
            // so hasBodyFixedFrame returns false and bodyAxisX returns null.
            VectorIJK dir = VectorTypes.bodyAxisX().computeDirection(-999, et);
            assertNull(dir, "bodyAxisX must return null when no body-fixed frame is available");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // equals/hashCode/toString (Step 19b)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("equals/hashCode/toString (Step 19b)")
    class EqualityTests {

        @Test
        @DisplayName("velocity() instances are equal")
        void velocityEquality() {
            VectorType a = VectorTypes.velocity();
            VectorType b = VectorTypes.velocity();
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("velocity() toString")
        void velocityToString() {
            assertEquals("velocity", VectorTypes.velocity().toString());
        }

        @Test
        @DisplayName("bodyAxisX() instances are equal")
        void bodyAxisXEquality() {
            assertEquals(VectorTypes.bodyAxisX(), VectorTypes.bodyAxisX());
        }

        @Test
        @DisplayName("bodyAxisX and bodyAxisY are not equal")
        void bodyAxisXNotEqualY() {
            assertNotEquals(VectorTypes.bodyAxisX(), VectorTypes.bodyAxisY());
        }

        @Test
        @DisplayName("bodyAxisZ toString")
        void bodyAxisZToString() {
            assertEquals("bodyAxisZ", VectorTypes.bodyAxisZ().toString());
        }

        @Test
        @DisplayName("towardBody(10) instances are equal")
        void towardBodyEquality() {
            assertEquals(VectorTypes.towardBody(10), VectorTypes.towardBody(10));
            assertEquals(
                    VectorTypes.towardBody(10).hashCode(),
                    VectorTypes.towardBody(10).hashCode());
        }

        @Test
        @DisplayName("towardBody(10) and towardBody(399) are not equal")
        void towardBodyDifferentTargets() {
            assertNotEquals(VectorTypes.towardBody(10), VectorTypes.towardBody(399));
        }

        @Test
        @DisplayName("towardBody toString includes target NAIF ID")
        void towardBodyToString() {
            assertEquals("towardBody:10", VectorTypes.towardBody(10).toString());
        }

        @Test
        @DisplayName("different type categories are not equal")
        void crossTypeInequality() {
            assertNotEquals(VectorTypes.velocity(), VectorTypes.bodyAxisX());
            assertNotEquals(VectorTypes.velocity(), VectorTypes.towardBody(10));
            assertNotEquals(VectorTypes.bodyAxisX(), VectorTypes.towardBody(10));
        }
    }
}
