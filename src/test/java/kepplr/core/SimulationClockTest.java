package kepplr.core;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.config.KEPPLRConfiguration;
import kepplr.state.DefaultSimulationState;
import kepplr.testsupport.TestHarness;
import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SimulationClock} (REDESIGN.md §1.2, §2.3).
 *
 * <p>All tests use an injectable wall-clock supplier so wall time is fully controlled — no real sleeping, no dependency
 * on system time. The SPICE kernel is only loaded for {@code setUTC} tests that require UTC→ET conversion.
 */
@DisplayName("SimulationClock")
class SimulationClockTest {

    /** Controllable wall clock: a simple mutable double that tests can advance manually. */
    static class FakeClock {
        double wall = 0.0;

        double get() {
            return wall;
        }
    }

    private static final double START_ET = 100.0;
    private static final double TOLERANCE = 1e-9;

    private FakeClock fakeClock;
    private DefaultSimulationState state;
    private SimulationClock clock;

    @BeforeEach
    void setUp() {
        fakeClock = new FakeClock();
        state = new DefaultSimulationState();
        clock = new SimulationClock(state, START_ET, fakeClock::get);
    }

    // ─────────────────────────────────────────────────────────────────
    // Initial state
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Initial state after construction")
    class InitialState {

        @Test
        @DisplayName("currentEt is startET")
        void initialET() {
            assertEquals(START_ET, state.currentEtProperty().get(), TOLERANCE);
        }

        @Test
        @DisplayName("timeRate is DEFAULT_TIME_RATE")
        void initialRate() {
            assertEquals(
                    KepplrConstants.DEFAULT_TIME_RATE, state.timeRateProperty().get(), TOLERANCE);
        }

        @Test
        @DisplayName("not paused")
        void initialNotPaused() {
            assertFalse(state.pausedProperty().get());
        }

        @Test
        @DisplayName("deltaSimSeconds is 0.0")
        void initialDelta() {
            assertEquals(0.0, state.deltaSimSecondsProperty().get(), TOLERANCE);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ET advancement
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ET advancement (§1.2)")
    class Advancement {

        @Test
        @DisplayName("1x rate: ET advances by wall Δt after one advance() call")
        void advanceAt1x() {
            fakeClock.wall = 1.0; // advance wall by 1 second
            clock.advance();
            assertEquals(START_ET + 1.0, state.currentEtProperty().get(), TOLERANCE);
        }

        @Test
        @DisplayName("5x rate: ET advances 5× wall Δt")
        void advanceAt5x() {
            clock.setTimeRate(5.0);
            fakeClock.wall = 2.0;
            clock.advance();
            assertEquals(START_ET + 10.0, state.currentEtProperty().get(), TOLERANCE);
        }

        @Test
        @DisplayName("negative rate: ET decreases as wall time advances")
        void advanceNegativeRate() {
            clock.setTimeRate(-3.0);
            fakeClock.wall = 4.0;
            clock.advance();
            assertEquals(START_ET - 12.0, state.currentEtProperty().get(), TOLERANCE);
        }

        @Test
        @DisplayName("deltaSimSeconds equals ET change this frame at 1x")
        void deltaAt1x() {
            fakeClock.wall = 1.0;
            clock.advance();
            assertEquals(1.0, state.deltaSimSecondsProperty().get(), TOLERANCE);
        }

        @Test
        @DisplayName("deltaSimSeconds is negative at negative rate")
        void deltaAtNegativeRate() {
            clock.setTimeRate(-2.0);
            fakeClock.wall = 3.0;
            clock.advance();
            assertEquals(-6.0, state.deltaSimSecondsProperty().get(), TOLERANCE);
        }

        @Test
        @DisplayName("deltaSimSeconds accumulates correctly across multiple frames")
        void deltaAcrossFrames() {
            // Frame 1: wall 0→1
            fakeClock.wall = 1.0;
            clock.advance();
            assertEquals(1.0, state.deltaSimSecondsProperty().get(), TOLERANCE);

            // Frame 2: wall 1→2
            fakeClock.wall = 2.0;
            clock.advance();
            assertEquals(1.0, state.deltaSimSecondsProperty().get(), TOLERANCE);
            assertEquals(START_ET + 2.0, state.currentEtProperty().get(), TOLERANCE);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Pause / resume
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Pause / resume (§1.2)")
    class PauseResume {

        @Test
        @DisplayName("paused state is reflected in SimulationState")
        void pausedStateReflected() {
            clock.setPaused(true);
            assertTrue(state.pausedProperty().get());
            clock.setPaused(false);
            assertFalse(state.pausedProperty().get());
        }

        @Test
        @DisplayName("ET freezes when paused")
        void etFreezesDuringPause() {
            // Run for 1 second, then pause
            fakeClock.wall = 1.0;
            clock.advance();
            double etAtPause = state.currentEtProperty().get();

            clock.setPaused(true);

            // Advance wall clock while paused — ET must not change
            fakeClock.wall = 5.0;
            clock.advance();
            assertEquals(etAtPause, state.currentEtProperty().get(), TOLERANCE, "ET must not change while paused");
        }

        @Test
        @DisplayName("no ET jump on resume")
        void noJumpOnResume() {
            // Let 2 seconds pass, pause, let 10 more seconds pass on wall, resume
            fakeClock.wall = 2.0;
            clock.advance();
            double etBeforePause = state.currentEtProperty().get(); // START_ET + 2

            clock.setPaused(true);
            fakeClock.wall = 12.0; // 10 seconds pass on wall while paused
            clock.advance(); // ET should still be etBeforePause

            clock.setPaused(false); // resume; anchor reset at (etBeforePause, wall=2)
            // Immediately advance by 1 more wall second
            fakeClock.wall = 13.0;
            clock.advance();

            // Expected ET = etBeforePause + 1.0 (only the 1 post-resume second counted)
            assertEquals(
                    etBeforePause + 1.0,
                    state.currentEtProperty().get(),
                    TOLERANCE,
                    "Only time elapsed after resume should count");
        }

        @Test
        @DisplayName("calling setPaused(true) twice does not double-clamp wall time")
        void doublePauseIsIdempotent() {
            fakeClock.wall = 1.0;
            clock.setPaused(true); // clamp at wall=1

            fakeClock.wall = 5.0;
            clock.setPaused(true); // second call — wall clamp should still be 1, not 5
            clock.advance();

            // ET = START_ET + rate*(1 - 0) = START_ET + 1
            assertEquals(START_ET + 1.0, state.currentEtProperty().get(), TOLERANCE);
        }

        @Test
        @DisplayName("calling setPaused(false) when already running has no effect")
        void resumeWhenRunningIsNoOp() {
            fakeClock.wall = 1.0;
            clock.advance();
            double et1 = state.currentEtProperty().get();

            clock.setPaused(false); // already running — should be a no-op

            fakeClock.wall = 2.0;
            clock.advance();
            assertEquals(et1 + 1.0, state.currentEtProperty().get(), TOLERANCE);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Rate change mid-run
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setTimeRate — no-jump guarantee (§2.3)")
    class RateChange {

        @Test
        @DisplayName("rate change at wall=0 produces no jump")
        void rateChangeAtStartNoJump() {
            clock.setTimeRate(100.0);
            clock.advance();
            // wall still 0 — ET must still be exactly START_ET
            assertEquals(START_ET, state.currentEtProperty().get(), TOLERANCE);
        }

        @Test
        @DisplayName("no ET jump when rate changes mid-run")
        void noJumpOnRateChange() {
            // 1x rate for 3 wall-seconds
            fakeClock.wall = 3.0;
            clock.advance();
            double etBeforeChange = state.currentEtProperty().get(); // START_ET + 3

            // Switch to 1000x — anchor is replaced at wall=3
            clock.setTimeRate(1000.0);

            // Immediately call advance() at the same wall time: delta = 0
            clock.advance();
            assertEquals(
                    etBeforeChange, state.currentEtProperty().get(), TOLERANCE, "No ET jump at rate change boundary");
        }

        @Test
        @DisplayName("after rate change, ET advances at new rate")
        void advancesAtNewRate() {
            fakeClock.wall = 1.0;
            clock.advance();
            clock.setTimeRate(10.0);

            fakeClock.wall = 2.0;
            clock.advance();
            // ET at rate-change: START_ET + 1. Then 1 wall-second at 10x → +10
            assertEquals(START_ET + 11.0, state.currentEtProperty().get(), TOLERANCE);
        }

        @Test
        @DisplayName("timeRate property updated after setTimeRate")
        void timeRatePropertyUpdated() {
            clock.setTimeRate(86400.0);
            assertEquals(86400.0, state.timeRateProperty().get(), TOLERANCE);
        }

        @Test
        @DisplayName("rate change while paused does not change ET")
        void rateChangeWhilePausedDoesNotChangeET() {
            fakeClock.wall = 1.0;
            clock.advance();
            double etBeforePause = state.currentEtProperty().get();

            clock.setPaused(true);
            clock.setTimeRate(500.0);
            clock.advance(); // still paused, wall still 1

            assertEquals(
                    etBeforePause,
                    state.currentEtProperty().get(),
                    TOLERANCE,
                    "Rate change while paused must not change ET");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // setET
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setET (§1.2)")
    class SetET {

        @Test
        @DisplayName("setET updates currentEt in state after advance()")
        void setETUpdatesState() {
            double target = 999_999.0;
            clock.setET(target);
            clock.advance();
            assertEquals(target, state.currentEtProperty().get(), TOLERANCE);
        }

        @Test
        @DisplayName("after setET, advance() continues from new ET at current rate")
        void advancesFromNewET() {
            clock.setET(500.0);
            fakeClock.wall = 1.0;
            clock.advance();
            // 1x rate, 1 wall-second → ET = 501
            assertEquals(501.0, state.currentEtProperty().get(), TOLERANCE);
        }

        @Test
        @DisplayName("setET preserves current time rate")
        void setETPreservesRate() {
            clock.setTimeRate(60.0);
            clock.setET(0.0);
            assertEquals(60.0, state.timeRateProperty().get(), TOLERANCE);
        }

        @Test
        @DisplayName("setET while paused keeps ET frozen at the new value")
        void setETWhilePaused() {
            clock.setPaused(true);
            clock.setET(42.0);

            fakeClock.wall = 100.0;
            clock.advance(); // still paused

            assertEquals(
                    42.0, state.currentEtProperty().get(), TOLERANCE, "ET must remain at setET value while paused");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // setUTC (requires SPICE kernel)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setUTC (§1.2) — requires SPICE kernel")
    class SetUTC {

        @BeforeEach
        void loadKernel() {
            TestHarness.resetSingleton();
            KEPPLRConfiguration.getTestTemplate();
        }

        @Test
        @DisplayName("setUTC converts UTC string to expected ET after advance()")
        void setUTCConvertsCorrectly() {
            // Known epoch: 2015 Jul 14 07:59:00 UTC (New Horizons Pluto flyby)
            double expectedET = TestHarness.getTestEpoch();

            clock.setUTC("2015 Jul 14 07:59:00");
            clock.advance();

            assertEquals(
                    expectedET,
                    state.currentEtProperty().get(),
                    1e-3,
                    "setUTC must produce the same ET as utcStringToTDB for the same string");
        }

        @Test
        @DisplayName("after setUTC, advance() continues from the converted ET")
        void advancesFromConvertedET() {
            double expectedET = TestHarness.getTestEpoch();
            clock.setUTC("2015 Jul 14 07:59:00");

            fakeClock.wall = 1.0;
            clock.advance();

            assertEquals(
                    expectedET + KepplrConstants.DEFAULT_TIME_RATE,
                    state.currentEtProperty().get(),
                    1e-3,
                    "One wall-second at 1x should advance ET by 1");
        }
    }
}
