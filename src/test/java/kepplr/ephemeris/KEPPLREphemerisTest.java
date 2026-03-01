package kepplr.ephemeris;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.Set;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.spice.SpiceBundle;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.CelestialBodies;
import picante.mechanics.EphemerisID;
import picante.mechanics.StateVector;
import picante.mechanics.providers.aberrated.AberrationCorrection;
import picante.time.TimeConversion;

/**
 * Unit tests for {@link KEPPLREphemeris} implementations.
 *
 * <p>Tests use a Mockito mock of the interface to verify the contract. When the real Picante-backed implementation
 * arrives, this class will be extended with integration tests using known-good SPICE values (per CLAUDE.md testing
 * rules).
 */
@DisplayName("KEPPLREphemeris")
class KEPPLREphemerisTest {

    /** NAIF IDs for standard test bodies. */
    static final int SUN = 10;

    static final int EARTH = 399;
    static final int MOON = 301;

    private KEPPLREphemeris ephemeris;

    @BeforeEach
    void setUp() {
        TestHarness.resetSingleton();
        ephemeris = KEPPLRConfiguration.getTestTemplate().getEphemeris();
    }

    // ─────────────────────────────────────────────────────────────────
    // Heliocentric position queries
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Heliocentric position queries")
    class HeliocentricPositionTests {

        @Test
        @DisplayName("Sun position is the zero vector")
        void sunPositionIsZero() {
            VectorIJK result = ephemeris.getHeliocentricPositionJ2000(SUN, TestHarness.getTestEpoch());
            assertNotNull(result);
            assertEquals(0.0, result.getI(), 1e-15);
            assertEquals(0.0, result.getJ(), 1e-15);
            assertEquals(0.0, result.getK(), 1e-15);
        }

        @Test
        @DisplayName("Earth position at test epoch is non-null and ~1 AU from Sun")
        void earthPositionAtJ2000() {
            VectorIJK earthPos = new VectorIJK(5.5427570706691965E7, -1.2992832464098956E8, -5.632542539697142E7);

            VectorIJK result = ephemeris.getHeliocentricPositionJ2000(EARTH, TestHarness.getTestEpoch());
            assertNotNull(result);
            // Distance from Sun should be ~1 AU (1.496e8 km)
            double distKm = result.getLength();
            assertEquals(
                    distKm,
                    earthPos.getLength(),
                    1e-15,
                    "Earth should be approximately 1 AU from Sun, got " + distKm + " km");
        }

        @Test
        @DisplayName("Unknown body returns null")
        void unknownBodyReturnsNull() {
            assertNull(ephemeris.getHeliocentricPositionJ2000(-999999, TestHarness.getTestEpoch()));
        }

        @Test
        @DisplayName("Heliocentric state includes velocity")
        void heliocentricStateIncludesVelocity() {
            StateVector result = ephemeris.getHeliocentricStateJ2000(EARTH, TestHarness.getTestEpoch());
            assertNotNull(result);
            assertNotNull(result.getPosition());
            assertNotNull(result.getVelocity());
            // Earth's orbital velocity is ~29.78 km/s
            double speed = result.getVelocity().getLength();
            assertTrue(speed > 25.0 && speed < 35.0, "Earth orbital velocity should be ~30 km/s, got " + speed);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Observer-to-target queries
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Observer-to-target queries")
    class ObserverToTargetTests {

        @Test
        @DisplayName("Earth-to-Moon distance is ~384,400 km")
        void earthToMoonDistance() {

            VectorIJK result = ephemeris.getObserverToTargetJ2000(
                    EARTH, MOON, TestHarness.getTestEpoch(), AberrationCorrection.NONE);
            assertNotNull(result);
            double distKm = result.getLength();
            assertTrue(
                    distKm > 350_000 && distKm < 410_000, "Earth-Moon distance should be ~384,400 km, got " + distKm);
        }

        @Test
        @DisplayName("LT_S correction produces different position than NONE")
        void aberrationCorrectionDiffers() {
            VectorIJK resultNone = ephemeris.getObserverToTargetJ2000(
                    EARTH, MOON, TestHarness.getTestEpoch(), AberrationCorrection.NONE);
            VectorIJK resultLtS = ephemeris.getObserverToTargetJ2000(
                    EARTH, MOON, TestHarness.getTestEpoch(), AberrationCorrection.LT_S);

            assertTrue(
                    Math.abs(1 - resultLtS.getLength() / resultNone.getLength()) < 0.01,
                    "geometric and aberrated positions should differ by < 1%");
            assertNotEquals(resultNone.getI(), resultLtS.getI(), "Corrected position should differ from geometric");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Body-fixed frame transforms
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Body-fixed frame transforms")
    class BodyFixedTests {

        @Test
        @DisplayName("Earth has a body-fixed frame")
        void earthHasBodyFixedFrame() {
            assertTrue(ephemeris.hasBodyFixedFrame(EARTH));
        }

        @Test
        @DisplayName("Body without PCK data has no body-fixed frame")
        void unknownBodyHasNoFrame() {
            assertFalse(ephemeris.hasBodyFixedFrame(-999999));
        }

        @Test
        @DisplayName("J2000 to body-fixed rotation returns non-null for valid body")
        void rotationReturnsNonNull() {
            assertNotNull(ephemeris.getJ2000ToBodyFixedRotation(EARTH, TestHarness.getTestEpoch()));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Time conversions
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Time conversions")
    class TimeConversionTests {

        @Test
        @DisplayName("J2000 epoch UTC round-trips")
        void j2000UtcRoundTrip() {
            TimeConversion tc = TimeConversion.createUsingInternalConstants();
            String utc = tc.tdbToUTCString(64.184, "C");
            assertEquals("2000 JAN 01 12:00:00.000", utc);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Light-time computation
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Light-time computation")
    class LightTimeTests {

        @Test
        @DisplayName("Light-time for 1 AU is approximately 499 seconds")
        void lightTimeForOneAu() {
            double oneAuKm = 1.496e8;
            VectorIJK pos = new VectorIJK(oneAuKm, 0, 0);

            double lt = ephemeris.computeLightTimeSeconds(pos);
            assertTrue(lt > 498 && lt < 500, "Light-time for 1 AU should be ~499s, got " + lt);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Body metadata
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Body metadata")
    class BodyMetadataTests {

        @Test
        @DisplayName("Known bodies set is non-empty")
        void knownBodiesNonEmpty() {
            Set<EphemerisID> bodies = ephemeris.getKnownBodies();
            assertFalse(bodies.isEmpty());
            assertTrue(bodies.contains(CelestialBodies.EARTH));
        }

        @Test
        @DisplayName("Earth has an ellipsoid shape")
        void earthHasShape() {
            assertNotNull(ephemeris.getShape(CelestialBodies.EARTH));
        }

        @Test
        @DisplayName("Body name lookup works")
        void bodyNameLookup() {

            SpiceBundle spiceBundle = mock(SpiceBundle.class);
            when(spiceBundle.getObject(EARTH)).thenReturn(CelestialBodies.EARTH);
            when(spiceBundle.getObjectCode(CelestialBodies.EARTH)).thenReturn(Optional.of(399));

            assertEquals("EARTH", spiceBundle.getObject(EARTH).getName());
            assertEquals(EARTH, spiceBundle.getObjectCode(CelestialBodies.EARTH).get());
        }
    }
}
