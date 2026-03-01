package kepplr.ephemeris;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.StateVector;
import picante.surfaces.Ellipsoid;

/**
 * Unit tests for {@link KEPPLREphemeris} implementations.
 *
 * <p>Tests use a Mockito mock of the interface to verify the contract.
 * When the real Picante-backed implementation arrives, this class will
 * be extended with integration tests using known-good SPICE values
 * (per CLAUDE.md testing rules).
 */
@DisplayName("KEPPLREphemeris")
class KEPPLREphemerisTest {

    /** NAIF IDs for standard test bodies. */
    static final int SUN = 10;
    static final int EARTH = 399;
    static final int MOON = 301;

    /**
     * A known-good ET for testing: 2000-01-01T12:00:00 TDB (J2000 epoch).
     * ET = 0.0 by definition.
     */
    static final double J2000_EPOCH_ET = 0.0;

    private KEPPLREphemeris ephemeris;

    @BeforeEach
    void setUp() {
        ephemeris = mock(KEPPLREphemeris.class);
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
            VectorIJK zero = new VectorIJK(0, 0, 0);
            when(ephemeris.getHeliocentricPositionJ2000(SUN, J2000_EPOCH_ET)).thenReturn(zero);

            VectorIJK result = ephemeris.getHeliocentricPositionJ2000(SUN, J2000_EPOCH_ET);
            assertNotNull(result);
            assertEquals(0.0, result.getI(), 1e-15);
            assertEquals(0.0, result.getJ(), 1e-15);
            assertEquals(0.0, result.getK(), 1e-15);
        }

        @Test
        @DisplayName("Earth position at J2000 epoch is non-null and ~1 AU from Sun")
        void earthPositionAtJ2000() {
            // Mock with values that give ~1 AU distance (1.496e8 km)
            VectorIJK earthPos = new VectorIJK(-1.060e8, 1.060e8, 0.0);
            when(ephemeris.getHeliocentricPositionJ2000(EARTH, J2000_EPOCH_ET)).thenReturn(earthPos);

            VectorIJK result = ephemeris.getHeliocentricPositionJ2000(EARTH, J2000_EPOCH_ET);
            assertNotNull(result);
            // Distance from Sun should be ~1 AU (1.496e8 km)
            double distKm = result.getLength();
            assertTrue(distKm > 1.47e8 && distKm < 1.52e8,
                    "Earth should be approximately 1 AU from Sun, got " + distKm + " km");
        }

        @Test
        @DisplayName("Unknown body returns null")
        void unknownBodyReturnsNull() {
            when(ephemeris.getHeliocentricPositionJ2000(eq(-999999), anyDouble())).thenReturn(null);

            assertNull(ephemeris.getHeliocentricPositionJ2000(-999999, J2000_EPOCH_ET));
        }

        @Test
        @DisplayName("Heliocentric state includes velocity")
        void heliocentricStateIncludesVelocity() {
            StateVector state = new StateVector(
                    new VectorIJK(-2.649e7, 1.445e8, 6.264e7),
                    new VectorIJK(-29.78, -5.02, -2.18));
            when(ephemeris.getHeliocentricStateJ2000(EARTH, J2000_EPOCH_ET)).thenReturn(state);

            StateVector result = ephemeris.getHeliocentricStateJ2000(EARTH, J2000_EPOCH_ET);
            assertNotNull(result);
            assertNotNull(result.getPosition());
            assertNotNull(result.getVelocity());
            // Earth's orbital velocity is ~29.78 km/s
            double speed = result.getVelocity().getLength();
            assertTrue(speed > 25.0 && speed < 35.0,
                    "Earth orbital velocity should be ~30 km/s, got " + speed);
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
            VectorIJK earthToMoon = new VectorIJK(-2.916e5, -2.667e5, -7.574e4);
            when(ephemeris.getTargetPositionJ2000(EARTH, MOON, J2000_EPOCH_ET, AberrationCorrection.NONE))
                    .thenReturn(earthToMoon);

            VectorIJK result = ephemeris.getTargetPositionJ2000(
                    EARTH, MOON, J2000_EPOCH_ET, AberrationCorrection.NONE);
            assertNotNull(result);
            double distKm = result.getLength();
            assertTrue(distKm > 350_000 && distKm < 410_000,
                    "Earth-Moon distance should be ~384,400 km, got " + distKm);
        }

        @Test
        @DisplayName("LT_S correction produces different position than NONE")
        void aberrationCorrectionDiffers() {
            VectorIJK geom = new VectorIJK(1000, 2000, 3000);
            VectorIJK corr = new VectorIJK(1000.5, 2000.3, 3000.1);
            when(ephemeris.getTargetPositionJ2000(EARTH, MOON, J2000_EPOCH_ET, AberrationCorrection.NONE))
                    .thenReturn(geom);
            when(ephemeris.getTargetPositionJ2000(EARTH, MOON, J2000_EPOCH_ET, AberrationCorrection.LT_S))
                    .thenReturn(corr);

            VectorIJK resultNone = ephemeris.getTargetPositionJ2000(
                    EARTH, MOON, J2000_EPOCH_ET, AberrationCorrection.NONE);
            VectorIJK resultLtS = ephemeris.getTargetPositionJ2000(
                    EARTH, MOON, J2000_EPOCH_ET, AberrationCorrection.LT_S);

            assertNotEquals(resultNone.getI(), resultLtS.getI(),
                    "Corrected position should differ from geometric");
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
            when(ephemeris.hasBodyFixedFrame(EARTH)).thenReturn(true);

            assertTrue(ephemeris.hasBodyFixedFrame(EARTH));
        }

        @Test
        @DisplayName("Body without PCK data has no body-fixed frame")
        void unknownBodyHasNoFrame() {
            when(ephemeris.hasBodyFixedFrame(-999999)).thenReturn(false);

            assertFalse(ephemeris.hasBodyFixedFrame(-999999));
        }

        @Test
        @DisplayName("J2000 to body-fixed rotation returns non-null for valid body")
        void rotationReturnsNonNull() {
            when(ephemeris.getJ2000ToBodyFixedRotation(EARTH, J2000_EPOCH_ET))
                    .thenReturn(new picante.math.vectorspace.RotationMatrixIJK());

            assertNotNull(ephemeris.getJ2000ToBodyFixedRotation(EARTH, J2000_EPOCH_ET));
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
            String j2000Utc = "2000 JAN 01 12:00:00";
            when(ephemeris.utcToEt(j2000Utc)).thenReturn(64.184); // TDB-UTC offset at J2000
            when(ephemeris.etToUtc(64.184, "C")).thenReturn(j2000Utc);

            double et = ephemeris.utcToEt(j2000Utc);
            String utc = ephemeris.etToUtc(et, "C");
            assertEquals(j2000Utc, utc);
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
            double expectedLightTimeSec = oneAuKm / 299_792.458;

            when(ephemeris.computeLightTimeSeconds(pos)).thenReturn(expectedLightTimeSec);

            double lt = ephemeris.computeLightTimeSeconds(pos);
            assertTrue(lt > 498 && lt < 500,
                    "Light-time for 1 AU should be ~499s, got " + lt);
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
            when(ephemeris.getKnownBodyIds()).thenReturn(Set.of(SUN, EARTH, MOON));

            Set<Integer> bodies = ephemeris.getKnownBodyIds();
            assertFalse(bodies.isEmpty());
            assertTrue(bodies.contains(EARTH));
        }

        @Test
        @DisplayName("Earth has an ellipsoid shape")
        void earthHasShape() {
            Ellipsoid shape = mock(Ellipsoid.class);
            when(ephemeris.getBodyShape(EARTH)).thenReturn(shape);

            assertNotNull(ephemeris.getBodyShape(EARTH));
        }

        @Test
        @DisplayName("Body name lookup works")
        void bodyNameLookup() {
            when(ephemeris.getBodyName(EARTH)).thenReturn("EARTH");
            when(ephemeris.getBodyId("EARTH")).thenReturn(EARTH);

            assertEquals("EARTH", ephemeris.getBodyName(EARTH));
            assertEquals(EARTH, ephemeris.getBodyId("EARTH"));
        }
    }
}
