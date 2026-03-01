package kepplr.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KEPPLRConfiguration}.
 *
 * <p>Validates the singleton pattern, ThreadLocal ephemeris access, and lifecycle guards described in REDESIGN.md §3.
 */
@DisplayName("KEPPLRConfiguration")
class KEPPLRConfigurationTest {

    @AfterEach
    void tearDown() {
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
}
