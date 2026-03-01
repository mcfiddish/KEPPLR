package kepplr.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import kepplr.ephemeris.KEPPLREphemeris;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KEPPLRConfiguration}.
 *
 * <p>Validates the singleton pattern, ThreadLocal ephemeris access,
 * and lifecycle guards described in REDESIGN.md §3.
 */
@DisplayName("KEPPLRConfiguration")
class KEPPLRConfigurationTest {

    @AfterEach
    void tearDown() {
        KEPPLRConfiguration.resetForTesting();
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

        @Test
        @DisplayName("After setInstance(), getInstance() returns the configuration")
        void afterSetInstanceReturnsConfig() {
            KEPPLRConfiguration config = new KEPPLRConfiguration();
            KEPPLRConfiguration.setInstance(config);

            assertTrue(KEPPLRConfiguration.isLoaded());
            assertSame(config, KEPPLRConfiguration.getInstance());
        }

        @Test
        @DisplayName("Double setInstance() throws")
        void doubleSetInstanceThrows() {
            KEPPLRConfiguration config = new KEPPLRConfiguration();
            KEPPLRConfiguration.setInstance(config);

            assertThrows(IllegalStateException.class,
                    () -> KEPPLRConfiguration.setInstance(new KEPPLRConfiguration()));
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
            config = new KEPPLRConfiguration();
            KEPPLRConfiguration.setInstance(config);
        }

        @Test
        @DisplayName("getEphemeris() without initialization throws")
        void getEphemerisWithoutInitThrows() {
            assertThrows(IllegalStateException.class, config::getEphemeris);
        }

        @Test
        @DisplayName("setThreadEphemeris() makes ephemeris available on calling thread")
        void setThreadEphemerisWorks() {
            KEPPLREphemeris mockEph = mock(KEPPLREphemeris.class);
            config.setThreadEphemeris(mockEph);

            assertSame(mockEph, config.getEphemeris());
        }

        @Test
        @DisplayName("Different threads get independent ephemeris instances")
        void threadIsolation() throws InterruptedException {
            KEPPLREphemeris eph1 = mock(KEPPLREphemeris.class);
            KEPPLREphemeris eph2 = mock(KEPPLREphemeris.class);

            config.setThreadEphemeris(eph1);
            assertSame(eph1, config.getEphemeris());

            Thread otherThread = new Thread(() -> {
                config.setThreadEphemeris(eph2);
                assertSame(eph2, config.getEphemeris());
            });
            otherThread.start();
            otherThread.join();

            // Main thread still sees eph1
            assertSame(eph1, config.getEphemeris());
        }
    }
}
