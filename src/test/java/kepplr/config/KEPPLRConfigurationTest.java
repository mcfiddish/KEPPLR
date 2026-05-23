package kepplr.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.testsupport.TestHarness;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.junit.jupiter.api.*;
import picante.time.TimeConversion;

/**
 * Unit tests for {@link KEPPLRConfiguration}.
 *
 * <p>Validates the singleton pattern, ThreadLocal ephemeris access, and lifecycle guards described in REDESIGN.md §3.
 */
@DisplayName("KEPPLRConfiguration")
class KEPPLRConfigurationTest {

    @BeforeEach
    void setup() {
        TestHarness.resetSingleton();
    }

    // ─────────────────────────────────────────────────────────────────
    // Singleton lifecycle
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Singleton lifecycle")
    class SingletonTests {

        @Test
        @DisplayName("getInstance() before initialization throws")
        void getInstanceBeforeInitThrows() {
            assertThrows(IllegalStateException.class, KEPPLRConfiguration::getInstance);
        }

        @Test
        @DisplayName("isLoaded() returns false before initialization")
        void isLoadedFalseBeforeInit() {
            assertFalse(KEPPLRConfiguration.isLoaded());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ThreadLocal ephemeris access
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ThreadLocal ephemeris access")
    class ThreadLocalEphemerisTests {

        private KEPPLRConfiguration config;

        @BeforeEach
        void setUp() {
            config = KEPPLRConfiguration.getTemplate();
        }

        @Test
        @DisplayName("getEphemeris() without initialization throws")
        void getEphemerisWithoutInitThrows() {
            TestHarness.resetSingleton();
            assertThrows(IllegalStateException.class, config::getEphemeris);
        }

        @Test
        @DisplayName("Different threads get independent ephemeris instances")
        void threadIsolation() throws InterruptedException {
            KEPPLREphemeris eph1 = config.getEphemeris();

            assertSame(eph1, config.getEphemeris());

            Thread otherThread = new Thread(() -> {
                KEPPLREphemeris eph2 = config.getEphemeris();
                assertSame(eph2, config.getEphemeris());
            });
            otherThread.start();
            otherThread.join();

            // Main thread still sees eph1
            assertSame(eph1, config.getEphemeris());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Test template configuration
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Test template configuration")
    class TestTemplateTests {

        private KEPPLRConfiguration config;

        @BeforeEach
        void setUp() {
            config = KEPPLRConfiguration.getTestTemplate();
        }

        @Test
        @DisplayName("Test template sets metakernel to test path")
        void testMetakernelPath() {
            List<String> metakernels = config.spiceBlock().metakernel();
            assertTrue(
                    metakernels.stream().anyMatch(mk -> mk.contains("kepplr_test.tm")),
                    "Test template should use test metakernel, got: " + metakernels);
        }

        @Test
        @DisplayName("Template has three default bodies")
        void templateHasDefaultBodies() {
            List<String> bodies = config.bodies();
            assertEquals(3, bodies.size(), "Template should have Sun, Earth, and Moon");
            assertTrue(bodies.contains("SUN"));
            assertTrue(bodies.contains("EARTH"));
            assertTrue(bodies.contains("MOON"));
        }

        @Test
        @DisplayName("Template has New Horizons spacecraft")
        void templateHasNewHorizons() {
            List<Integer> spacecraft = config.spacecraft();
            assertTrue(spacecraft.contains(-98), "Template should include New Horizons (-98)");
        }

        @Test
        @DisplayName("bodyBlock returns correct NAIF ID for Earth")
        void earthBodyBlockNaifId() {
            BodyBlock earth = config.bodyBlock("EARTH");
            assertNotNull(earth);
            assertEquals(399, earth.naifID());
        }

        @Test
        @DisplayName("spacecraftBlock returns New Horizons")
        void newHorizonsSpacecraftBlock() {
            SpacecraftBlock nh = config.spacecraftBlock(-98);
            assertNotNull(nh);
            assertEquals("New Horizons", nh.name());
        }

        @Test
        @DisplayName("getTimeConversion() returns usable time conversion")
        void timeConversionWorks() {
            TimeConversion tc = config.getTimeConversion();
            assertNotNull(tc);
            // J2000 epoch: 64.184 TDB seconds = 2000 JAN 01 12:00:00 UTC
            String utc = tc.tdbToUTCString(64.184, "C");
            assertEquals("2000 JAN 01 12:00:00.000", utc);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Configuration property defaults
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Configuration property defaults")
    class ConfigPropertyTests {

        private KEPPLRConfiguration config;

        @BeforeEach
        void setUp() {
            config = KEPPLRConfiguration.getTemplate();
        }

        @Test
        @DisplayName("Default log level is INFO")
        void defaultLogLevel() {
            assertEquals("INFO", config.logLevel());
        }

        @Test
        @DisplayName("Default time format is ISOC")
        void defaultTimeFormat() {
            assertEquals("ISOC", config.timeFormat());
        }

        @Test
        @DisplayName("Resources folder is non-blank")
        void resourcesFolderNonBlank() {
            assertFalse(config.resourcesFolder().isBlank());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Configuration reload
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Configuration reload")
    class ReloadTests {

        @Test
        @DisplayName("reload() picks up changed property")
        void reloadPicksUpChange() {
            KEPPLRConfiguration config = KEPPLRConfiguration.getTestTemplate();
            PropertiesConfiguration pc = config.toPropertiesConfiguration();
            pc.setProperty("timeFormat", "C");
            KEPPLRConfiguration reloaded = KEPPLRConfiguration.reload(pc);
            assertEquals("C", reloaded.timeFormat());
        }

        @Test
        @DisplayName("Singleton replacement clears old state")
        void reloadClearsOldState() {
            KEPPLRConfiguration first = KEPPLRConfiguration.getTemplate();
            assertTrue(KEPPLRConfiguration.isLoaded());

            // Reload with new config
            PropertiesConfiguration pc = first.toPropertiesConfiguration();
            pc.setProperty("logLevel", "DEBUG");
            KEPPLRConfiguration second = KEPPLRConfiguration.reload(pc);

            assertNotSame(first, second, "Reload should create new instance");
            assertEquals("DEBUG", second.logLevel(), "Reload should use new config");
        }

        @Test
        @DisplayName("ThreadLocal ephemeris recreated after reload")
        void reloadRecreatesEphemeris() throws InterruptedException {
            KEPPLRConfiguration config = KEPPLRConfiguration.getTemplate();
            KEPPLREphemeris eph1 = config.getEphemeris();

            // Reload
            KEPPLRConfiguration reloaded = KEPPLRConfiguration.reload(config.toPropertiesConfiguration());

            // Thread that used old config
            Thread oldThread = new Thread(() -> {
                KEPPLREphemeris ephOld = config.getEphemeris();
                assertNotNull(ephOld);
            });
            oldThread.start();
            oldThread.join();

            // New config gets new ephemeris
            KEPPLREphemeris eph2 = reloaded.getEphemeris();
            assertNotSame(eph1, eph2, "Reloaded config should have different ephemeris");
        }
    }
}
