package kepplr.camera;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.providers.aberrated.AberrationCorrection;

/**
 * Unit tests for {@link CameraPointing}.
 *
 * <p>Uses real Picante SPICE kernels via {@link KEPPLRConfiguration#getTestTemplate()}. Test epoch is 2015 Jul 14
 * 07:59:00 UTC (New Horizons Pluto flyby).
 */
@DisplayName("CameraPointing")
class CameraPointingTest {

    static final int EARTH = 399;
    static final int MOON = 301;
    static final int NEW_HORIZONS = -98;

    /** 10 arcseconds in radians. Earth-Moon light-time is ~1.3 s, so LT+S aberration is small but detectable. */
    static final double TEN_ARCSECONDS_RAD = 10.0 / 3600.0 * Math.PI / 180.0;

    private double testEt;

    @BeforeEach
    void setUp() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTestTemplate();
        testEt = TestHarness.getTestEpoch();
    }

    // ─────────────────────────────────────────────────────────────────
    // With focus body (LT+S correction)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("With focus body (LT+S correction)")
    class WithFocusBody {

        @Test
        @DisplayName("Earth→Moon with focus returns non-null unit vector")
        void earthToMoonReturnsUnitVector() {
            VectorIJK dir = CameraPointing.computePointAtDirection(EARTH, MOON, testEt);
            assertNotNull(dir, "Result should not be null for valid Earth→Moon query");
            assertEquals(1.0, dir.getLength(), 1e-10, "Result should be a unit vector");
        }

        @Test
        @DisplayName("LT+S direction differs from geometric direction (regression, §6.2)")
        void ltsDiffersFromGeometric() {
            // LT+S apparent direction from Earth to Moon
            VectorIJK ltsDir = CameraPointing.computePointAtDirection(EARTH, MOON, testEt);
            assertNotNull(ltsDir);

            // Geometric direction from Earth to Moon (no correction)
            KEPPLREphemeris ephemeris = KEPPLRConfiguration.getInstance().getEphemeris();
            VectorIJK geoRaw = ephemeris.getObserverToTargetJ2000(EARTH, MOON, testEt, AberrationCorrection.NONE);
            assertNotNull(geoRaw);
            double geoLen = geoRaw.getLength();
            VectorIJK geoDir = new VectorIJK(geoRaw.getI() / geoLen, geoRaw.getJ() / geoLen, geoRaw.getK() / geoLen);

            // Angle between LT+S and geometric directions
            double dot = ltsDir.getI() * geoDir.getI() + ltsDir.getJ() * geoDir.getJ() + ltsDir.getK() * geoDir.getK();
            dot = Math.min(1.0, Math.max(-1.0, dot));
            double angleRad = Math.acos(dot);
            double angleSec = Math.toDegrees(angleRad) * 3600.0;

            assertTrue(angleRad > 0, "LT+S direction should differ from geometric direction");
            assertTrue(
                    angleRad < TEN_ARCSECONDS_RAD,
                    "Aberration angle should be < 10 arcseconds for Earth-Moon, got " + angleSec + " arcsec");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Without focus body (geometric heliocentric)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Without focus body (geometric heliocentric)")
    class WithoutFocusBody {

        @Test
        @DisplayName("No-focus query returns non-null unit vector pointing from Sun to Moon")
        void noFocusReturnsSunToMoonDirection() {
            VectorIJK dir = CameraPointing.computePointAtDirection(-1, MOON, testEt);
            assertNotNull(dir, "Result should not be null for Sun→Moon heliocentric query");
            assertEquals(1.0, dir.getLength(), 1e-10, "Result should be a unit vector");
        }

        @Test
        @DisplayName("No-focus direction matches heliocentric direction from ephemeris")
        void noFocusMatchesHeliocentric() {
            VectorIJK dir = CameraPointing.computePointAtDirection(-1, MOON, testEt);
            assertNotNull(dir);

            KEPPLREphemeris ephemeris = KEPPLRConfiguration.getInstance().getEphemeris();
            VectorIJK helioRaw = ephemeris.getHeliocentricPositionJ2000(MOON, testEt);
            assertNotNull(helioRaw);
            double len = helioRaw.getLength();
            VectorIJK expected = new VectorIJK(helioRaw.getI() / len, helioRaw.getJ() / len, helioRaw.getK() / len);

            assertEquals(expected.getI(), dir.getI(), 1e-10, "I component should match heliocentric unit direction");
            assertEquals(expected.getJ(), dir.getJ(), 1e-10, "J component should match heliocentric unit direction");
            assertEquals(expected.getK(), dir.getK(), 1e-10, "K component should match heliocentric unit direction");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Degenerate case: focus == target
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Degenerate case: focus == target")
    class DegenerateCase {

        @Test
        @DisplayName("computePointAtDirection(EARTH, EARTH, et) returns null (zero-length vector)")
        void sameFocusAndTargetReturnsNull() {
            VectorIJK dir = CameraPointing.computePointAtDirection(EARTH, EARTH, testEt);
            assertNull(dir, "Pointing from a body to itself should return null (zero-length vector)");
        }
    }
}
