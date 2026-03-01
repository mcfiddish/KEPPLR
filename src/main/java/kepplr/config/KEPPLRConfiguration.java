package kepplr.config;

import kepplr.ephemeris.KEPPLREphemeris;

/**
 * Singleton configuration and ephemeris access point (REDESIGN.md §3).
 *
 * <p><b>This is a user-owned class.</b> The skeleton below defines the required
 * public API contract. The user will replace this file with their own implementation.
 *
 * <h3>Singleton and threading rule (§3.2)</h3>
 * <ul>
 *   <li>Instantiated once at startup.</li>
 *   <li>Accessed via {@link #getInstance()}.</li>
 *   <li>Holds a {@code ThreadLocal<KEPPLREphemeris>} so each thread gets its own
 *       ephemeris instance.</li>
 * </ul>
 *
 * <h3>No-passing rule (§3.3)</h3>
 * <p>Code must <b>never</b> store or pass references to {@code KEPPLRConfiguration}
 * or {@code KEPPLREphemeris}. Always acquire at point-of-use:
 * <pre>{@code
 *     KEPPLRConfiguration.getInstance().getEphemeris()
 * }</pre>
 */
public class KEPPLRConfiguration {

    // ── Singleton ──

    private static KEPPLRConfiguration instance;

    /**
     * Returns the singleton instance.
     *
     * @return the loaded configuration
     * @throws IllegalStateException if the configuration has not been initialized
     */
    public static KEPPLRConfiguration getInstance() {
        if (instance == null) {
            throw new IllegalStateException("KEPPLRConfiguration has not been initialized");
        }
        return instance;
    }

    /**
     * Returns {@code true} if the singleton has been initialized.
     */
    public static boolean isLoaded() {
        return instance != null;
    }

    // ── ThreadLocal ephemeris ──

    private final ThreadLocal<KEPPLREphemeris> ephemerisThreadLocal = new ThreadLocal<>();

    /**
     * Returns the {@link KEPPLREphemeris} for the calling thread.
     *
     * <p>Each thread receives its own instance to avoid contention on the
     * underlying SPICE provider.
     *
     * @return thread-local ephemeris instance
     * @throws IllegalStateException if no ephemeris has been initialized for this thread
     */
    public KEPPLREphemeris getEphemeris() {
        KEPPLREphemeris eph = ephemerisThreadLocal.get();
        if (eph == null) {
            throw new IllegalStateException("No KEPPLREphemeris initialized for current thread");
        }
        return eph;
    }

    // ── Construction (to be replaced by user implementation) ──

    /**
     * Protected constructor — subclasses and factory methods populate the instance.
     */
    protected KEPPLRConfiguration() {}

    /**
     * Sets the singleton instance. Intended to be called once during startup.
     *
     * @param config the fully-initialized configuration
     * @throws IllegalStateException if already loaded
     */
    protected static void setInstance(KEPPLRConfiguration config) {
        if (instance != null) {
            throw new IllegalStateException("KEPPLRConfiguration is already initialized");
        }
        instance = config;
    }

    /**
     * Resets the singleton — <b>test use only</b>.
     */
    static void resetForTesting() {
        instance = null;
    }

    /**
     * Sets the ephemeris for the calling thread.
     *
     * @param ephemeris the ephemeris to bind to this thread
     */
    protected void setThreadEphemeris(KEPPLREphemeris ephemeris) {
        ephemerisThreadLocal.set(ephemeris);
    }
}
