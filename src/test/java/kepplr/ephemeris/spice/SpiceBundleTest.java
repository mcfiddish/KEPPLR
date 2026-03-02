package kepplr.ephemeris.spice;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Optional;
import kepplr.config.KEPPLRConfiguration;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picante.mechanics.CelestialBodies;
import picante.mechanics.CelestialFrames;
import picante.mechanics.EphemerisID;
import picante.mechanics.FrameID;

/**
 * Unit tests for {@link SpiceBundle}.
 *
 * <p>Tests exercise the object/frame lookup maps, reverse bindings, kernel list, and core provider accessors built from
 * the test metakernel (New Horizons Pluto encounter, 2015 Jul 14).
 */
@DisplayName("SpiceBundle")
class SpiceBundleTest {

    static final int EARTH = 399;
    static final int MOON = 301;
    static final int NEW_HORIZONS = -98;

    private static SpiceBundle bundle;

    @BeforeAll
    static void setUpOnce() {
        TestHarness.resetSingleton();
        bundle = KEPPLRConfiguration.getTestTemplate().getEphemeris().getSpiceBundle();
    }

    // ─────────────────────────────────────────────────────────────────
    // Object lookup by NAIF code
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Object lookup by NAIF code")
    class ObjectByCodeTests {

        @Test
        @DisplayName("getObject(399) returns Earth")
        void earthByCode() {
            EphemerisID earth = bundle.getObject(EARTH);
            assertNotNull(earth);
            assertEquals("EARTH", earth.getName());
        }

        @Test
        @DisplayName("getObject(301) returns Moon")
        void moonByCode() {
            EphemerisID moon = bundle.getObject(MOON);
            assertNotNull(moon);
            assertEquals("MOON", moon.getName());
        }

        @Test
        @DisplayName("getObject(-98) returns New Horizons")
        void newHorizonsByCode() {
            EphemerisID nh = bundle.getObject(NEW_HORIZONS);
            assertNotNull(nh, "New Horizons should be bound from FK");
        }

        @Test
        @DisplayName("Unknown NAIF code returns null")
        void unknownCodeReturnsNull() {
            assertNull(bundle.getObject(-999999));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Object lookup by name
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Object lookup by name")
    class ObjectByNameTests {

        @Test
        @DisplayName("getObject(\"EARTH\") returns Earth")
        void earthByUpperCase() {
            EphemerisID earth = bundle.getObject("EARTH");
            assertNotNull(earth);
            assertEquals("EARTH", earth.getName());
        }

        @Test
        @DisplayName("getObject(\"earth\") is case-insensitive")
        void earthByLowerCase() {
            EphemerisID earth = bundle.getObject("earth");
            assertNotNull(earth);
            assertEquals("EARTH", earth.getName());
        }

        @Test
        @DisplayName("getObject(\"Earth\") is case-insensitive (mixed)")
        void earthByMixedCase() {
            EphemerisID earth = bundle.getObject("Earth");
            assertNotNull(earth);
        }

        @Test
        @DisplayName("Unknown name returns null")
        void unknownNameReturnsNull() {
            assertNull(bundle.getObject("NONEXISTENT_BODY"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Reverse bindings
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Reverse bindings")
    class ReverseBindingTests {

        @Test
        @DisplayName("getObjectCode for Earth returns 399")
        void earthObjectCode() {
            Optional<Integer> code = bundle.getObjectCode(CelestialBodies.EARTH);
            assertTrue(code.isPresent());
            assertEquals(EARTH, code.get());
        }

        @Test
        @DisplayName("getObjectName for Earth returns EARTH")
        void earthObjectName() {
            Optional<String> name = bundle.getObjectName(CelestialBodies.EARTH);
            assertTrue(name.isPresent());
            assertEquals("EARTH", name.get());
        }

        @Test
        @DisplayName("getObjectCode for unknown body returns empty")
        void unknownBodyCodeIsEmpty() {
            // Create a throwaway EphemerisID that is not in the bindings
            EphemerisID unknown = new EphemerisID() {
                @Override
                public String getName() {
                    return "FAKE_BODY";
                }
            };
            assertTrue(bundle.getObjectCode(unknown).isEmpty());
        }

        @Test
        @DisplayName("getObjectName for unknown body returns empty")
        void unknownBodyNameIsEmpty() {
            EphemerisID unknown = new EphemerisID() {
                @Override
                public String getName() {
                    return "FAKE_BODY";
                }
            };
            assertTrue(bundle.getObjectName(unknown).isEmpty());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Frame lookup
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Frame lookup")
    class FrameLookupTests {

        @Test
        @DisplayName("getFrame(\"J2000\") returns the J2000 built-in frame")
        void j2000ByName() {
            FrameID j2000 = bundle.getFrame("J2000");
            assertNotNull(j2000);
            assertEquals(CelestialFrames.J2000, j2000);
        }

        @Test
        @DisplayName("getFrame(\"NH_SPACECRAFT\") returns the FK-defined frame")
        void nhSpacecraftByName() {
            FrameID nhFrame = bundle.getFrame("NH_SPACECRAFT");
            assertNotNull(nhFrame, "NH_SPACECRAFT should be defined in test FK");
        }

        @Test
        @DisplayName("getFrame(1) returns the J2000 frame by code")
        void j2000ByCode() {
            FrameID j2000 = bundle.getFrame(1);
            assertNotNull(j2000);
        }

        @Test
        @DisplayName("Unknown frame name returns null")
        void unknownFrameNameReturnsNull() {
            assertNull(bundle.getFrame("NONEXISTENT_FRAME"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Body-fixed frame resolution
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Body-fixed frame resolution")
    class BodyFixedFrameTests {

        @Test
        @DisplayName("Earth body-fixed frame is IAU_EARTH")
        void earthBodyFixedFrame() {
            EphemerisID earth = bundle.getObject(EARTH);
            FrameID bodyFixed = bundle.getBodyFixedFrame(earth);
            assertNotNull(bodyFixed, "Earth should have a body-fixed frame");
            assertEquals("IAU_EARTH", bodyFixed.getName());
        }

        @Test
        @DisplayName("Moon body-fixed frame is IAU_MOON")
        void moonBodyFixedFrame() {
            EphemerisID moon = bundle.getObject(MOON);
            FrameID bodyFixed = bundle.getBodyFixedFrame(moon);
            assertNotNull(bodyFixed, "Moon should have a body-fixed frame");
            assertEquals("IAU_MOON", bodyFixed.getName());
        }

        @Test
        @DisplayName("Unknown body has no body-fixed frame")
        void unknownBodyHasNoFrame() {
            assertNull(bundle.getBodyFixedFrame(null));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Kernel list and search
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Kernel list and search")
    class KernelListTests {

        @Test
        @DisplayName("Loaded kernel list is non-empty")
        void kernelListNonEmpty() {
            assertFalse(bundle.getKernels().isEmpty());
        }

        @Test
        @DisplayName("findKernel matches LSK by regex")
        void findKernelMatchesLsk() {
            File lsk = bundle.findKernel(".*naif0012.*");
            assertNotNull(lsk, "Should find naif0012 LSK in loaded kernels");
        }

        @Test
        @DisplayName("findKernel matches SPK by regex")
        void findKernelMatchesSpk() {
            File spk = bundle.findKernel(".*kepplr_test\\.bsp");
            assertNotNull(spk, "Should find test SPK in loaded kernels");
        }

        @Test
        @DisplayName("findKernel returns null for non-matching regex")
        void findKernelNoMatch() {
            assertNull(bundle.findKernel(".*nonexistent_kernel.*"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Core providers and time conversion
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Core providers and time conversion")
    class CoreProviderTests {

        @Test
        @DisplayName("AberratedEphemerisProvider is non-null")
        void abProviderNonNull() {
            assertNotNull(bundle.getAbProvider());
        }

        @Test
        @DisplayName("KernelPool is non-null")
        void kernelPoolNonNull() {
            assertNotNull(bundle.getKernelPool());
        }

        @Test
        @DisplayName("SpiceEnvironment is non-null")
        void spiceEnvNonNull() {
            assertNotNull(bundle.getSpiceEnv());
        }

        @Test
        @DisplayName("TimeConversion is non-null and functional")
        void timeConversionWorks() {
            assertNotNull(bundle.getTimeConversion());
            // Verify it can convert a known epoch
            String utc = bundle.getTimeConversion().tdbToUTCString(64.184, "C");
            assertEquals("2000 JAN 01 12:00:00.000", utc);
        }
    }
}
