package kepplr.render.trail;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.config.KEPPLRConfiguration;
import kepplr.testsupport.TestHarness;
import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

@DisplayName("TrailSampler")
public class TrailSamplerTest {

    /** Phobos NAIF ID. */
    private static final int PHOBOS = 401;

    /**
     * Phobos orbital period in seconds at the test epoch (Dec 25, 2007), derived from
     * OsculatingElementsTest — the authoritative cross-check for this implementation.
     * Tolerance of 1 s applied in assertions.
     */
    private static final double PHOBOS_PERIOD_SEC = 27_577.09;

    @BeforeEach
    void setup() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTestTemplate();
    }

    @Test
    @DisplayName("sample() produces 181 positions for Phobos over one full orbital period")
    void testPhobos180Samples() {
        // The test SPK covers Phobos w.r.t. Mars Barycenter, Mars Barycenter w.r.t. SSB, and
        // Sun w.r.t. SSB for the full day 2015 JUL 14 00:01–JUL 15 00:01. Centering at the
        // test epoch (07:59:00) with one Phobos period (≈7.66 h) spans [04:09, 11:49] — entirely
        // within the 24-hour heliocentric coverage window, so all 181 samples resolve directly.
        double et = TestHarness.getTestEpoch(); // 2015 Jul 14 07:59:00 UTC

        List<double[]> samples = TrailSampler.sample(PHOBOS, et, PHOBOS_PERIOD_SEC, "J2000");

        assertEquals(KepplrConstants.TRAIL_SAMPLES_PER_PERIOD, samples.size(),
                "Expected exactly 181 samples over one Phobos orbital period");

        // Every position must be a non-null double[3] within a physically plausible range.
        // Samples are anchored at Mars barycenter's heliocentric position at centerEt (≈1.5 AU
        // ≈ 2.25e8 km). Phobos orbits at ~9400 km from Mars, so each sample is displaced by at
        // most ~9400 km from Mars's position — still well within [1e7, 1e9] km from the Sun.
        for (double[] pos : samples) {
            assertNotNull(pos);
            assertEquals(3, pos.length);
            double distFromSun = Math.sqrt(pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2]);
            assertTrue(distFromSun > 1e7 && distFromSun < 1e9,
                    "Distance from Sun out of range: " + distFromSun);
        }
    }

    @Test
    @DisplayName("computeTrailDurationSec() returns Phobos orbital period via barycenter-relative state")
    void testPhobosOrbitalPeriod() {
        // OsculatingElementsTest shows the Phobos period at Dec 25, 2007 is ≈ 27,577.09 s.
        // This test verifies that TrailSampler independently arrives at the same value using
        // the barycenter-relative path (Phobos relative to Mars Barycenter, BODY4_GM).
        double et = KEPPLRConfiguration.getInstance().getTimeConversion().utcStringToTDB("Dec 25, 2007");

        double duration = TrailSampler.computeTrailDurationSec(PHOBOS, et);

        assertEquals(PHOBOS_PERIOD_SEC, duration, 1.0,
                "Phobos trail duration should match its orbital period (~27,577 s)");
    }

    @Test
    @DisplayName("computeTrailDurationSec() returns 30-day fallback for a body with no ephemeris")
    void testFallback30Days() {
        // NAIF ID -999999 is not in any loaded SPK; both the satellite path and the heliocentric
        // path will fail gracefully, and the method must return the 30-day default.
        double et = TestHarness.getTestEpoch();

        double duration = TrailSampler.computeTrailDurationSec(-999999, et);

        assertEquals(KepplrConstants.TRAIL_DEFAULT_DURATION_SEC, duration,
                "Should fall back to 30-day default for a body not in the SPK");
    }
}
