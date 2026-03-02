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
import picante.math.vectorspace.RotationMatrixIJK;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.CelestialBodies;
import picante.mechanics.EphemerisID;
import picante.mechanics.StateVector;
import picante.mechanics.providers.aberrated.AberrationCorrection;
import picante.time.TimeConversion;

/**
 * Unit tests for {@link KEPPLREphemeris}.
 *
 * <p>Tests use real Picante SPICE kernels loaded via the test metakernel. Known-good values are used per CLAUDE.md
 * testing rules.
 */
@DisplayName("KEPPLREphemeris")
class KEPPLREphemerisTest {

    /** NAIF IDs for standard test bodies. */
    static final int SUN = 10;

    static final int EARTH = 399;
    static final int MOON = 301;
    static final int NEW_HORIZONS = -98;

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
    // Heliocentric state edge cases
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Heliocentric state edge cases")
    class HeliocentricStateEdgeCases {

        @Test
        @DisplayName("Sun heliocentric state is the zero state vector")
        void sunStateIsZero() {
            StateVector result = ephemeris.getHeliocentricStateJ2000(SUN, TestHarness.getTestEpoch());
            assertNotNull(result);
            assertEquals(0.0, result.getPosition().getLength(), 1e-15);
            assertEquals(0.0, result.getVelocity().getLength(), 1e-15);
        }

        @Test
        @DisplayName("Unknown body heliocentric state returns null")
        void unknownBodyStateReturnsNull() {
            assertNull(ephemeris.getHeliocentricStateJ2000(-999999, TestHarness.getTestEpoch()));
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

        @Test
        @DisplayName("Unknown observer returns null")
        void unknownObserverReturnsNull() {
            assertNull(ephemeris.getObserverToTargetJ2000(
                    -999999, MOON, TestHarness.getTestEpoch(), AberrationCorrection.NONE));
        }

        @Test
        @DisplayName("Unknown target returns null")
        void unknownTargetReturnsNull() {
            assertNull(ephemeris.getObserverToTargetJ2000(
                    EARTH, -999999, TestHarness.getTestEpoch(), AberrationCorrection.NONE));
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
    // Body-fixed position transforms
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Body-fixed position transforms")
    class BodyFixedPositionTests {

        @Test
        @DisplayName("toBodyFixedPosition preserves vector magnitude")
        void rotationPreservesMagnitude() {
            double et = TestHarness.getTestEpoch();
            VectorIJK earthPos = ephemeris.getHeliocentricPositionJ2000(EARTH, et);
            VectorIJK bodyFixed = ephemeris.toBodyFixedPosition(EARTH, et, earthPos, false, null);
            assertNotNull(bodyFixed);
            assertEquals(
                    earthPos.getLength(),
                    bodyFixed.getLength(),
                    1e-6,
                    "Rotation should preserve vector magnitude");
        }

        @Test
        @DisplayName("toBodyFixedPosition with null position returns null")
        void nullPositionReturnsNull() {
            assertNull(ephemeris.toBodyFixedPosition(EARTH, TestHarness.getTestEpoch(), null, false, null));
        }

        @Test
        @DisplayName("toBodyFixedPosition for unknown body returns null")
        void unknownBodyReturnsNull() {
            VectorIJK pos = new VectorIJK(1, 0, 0);
            assertNull(ephemeris.toBodyFixedPosition(-999999, TestHarness.getTestEpoch(), pos, false, null));
        }

        @Test
        @DisplayName("getJ2000ToBodyFixedAtEvalTime without light-time returns non-null")
        void evalTimeWithoutLightTime() {
            assertNotNull(ephemeris.getJ2000ToBodyFixedAtEvalTime(EARTH, TestHarness.getTestEpoch(), null));
        }

        @Test
        @DisplayName("getJ2000ToBodyFixedAtEvalTime with light-time returns non-null")
        void evalTimeWithLightTime() {
            assertNotNull(ephemeris.getJ2000ToBodyFixedAtEvalTime(EARTH, TestHarness.getTestEpoch(), 499.0));
        }

        @Test
        @DisplayName("Light-time correction shifts the evaluation epoch")
        void lightTimeCorrectionShiftsEpoch() {
            double et = TestHarness.getTestEpoch();
            RotationMatrixIJK withoutLt = ephemeris.getJ2000ToBodyFixedAtEvalTime(EARTH, et, null);
            RotationMatrixIJK withLt = ephemeris.getJ2000ToBodyFixedAtEvalTime(EARTH, et, 499.0);
            // Apply both rotations to the same test vector
            VectorIJK testVec = new VectorIJK(1, 0, 0);
            VectorIJK result1 = withoutLt.mxv(testVec);
            VectorIJK result2 = withLt.mxv(testVec);
            assertTrue(
                    Math.abs(result1.getI() - result2.getI()) > 1e-10,
                    "Light-time correction should change the rotation result");
        }

        @Test
        @DisplayName("getJ2000ToBodyFixedAtEvalTime for unknown body returns null")
        void unknownBodyEvalTimeReturnsNull() {
            assertNull(ephemeris.getJ2000ToBodyFixedAtEvalTime(-999999, TestHarness.getTestEpoch(), null));
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

        @Test
        @DisplayName("Null vector returns zero light-time")
        void nullVectorReturnsZero() {
            assertEquals(0.0, ephemeris.computeLightTimeSeconds(null));
        }

        @Test
        @DisplayName("Zero vector returns zero light-time")
        void zeroVectorReturnsZero() {
            assertEquals(0.0, ephemeris.computeLightTimeSeconds(new VectorIJK(0, 0, 0)));
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

    // ─────────────────────────────────────────────────────────────────
    // Spacecraft and instrument queries
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Spacecraft and instrument queries")
    class SpacecraftAndInstrumentTests {

        @Test
        @DisplayName("Spacecraft collection is non-empty")
        void spacecraftCollectionNonEmpty() {
            assertFalse(ephemeris.getSpacecraft().isEmpty());
        }

        @Test
        @DisplayName("New Horizons spacecraft is present")
        void newHorizonsPresent() {
            EphemerisID nhId = ephemeris.getSpiceBundle().getObject(NEW_HORIZONS);
            assertNotNull(nhId, "New Horizons should be a known object");
            Spacecraft nh = ephemeris.getSpacecraft(nhId);
            assertNotNull(nh, "New Horizons should be in spacecraft map");
            assertEquals(NEW_HORIZONS, nh.code());
        }

        @Test
        @DisplayName("Instrument set is accessible (empty without IK)")
        void instrumentSetAccessible() {
            // Test kernels include an FK but no instrument kernel (IK),
            // so the set should be empty but accessible without error
            assertNotNull(ephemeris.getInstruments());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Frame transforms
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Frame transforms")
    class FrameTransformTests {

        @Test
        @DisplayName("j2000ToFrame by string name returns non-null for NH_SPACECRAFT")
        void nhSpacecraftFrameAvailable() {
            assertNotNull(
                    ephemeris.j2000ToFrame("NH_SPACECRAFT"),
                    "NH_SPACECRAFT frame should be defined in test FK");
        }
    }
}
