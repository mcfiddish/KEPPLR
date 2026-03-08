package kepplr.ui;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.camera.CameraFrame;
import kepplr.config.KEPPLRConfiguration;
import kepplr.state.DefaultSimulationState;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SimulationStateFxBridge}.
 *
 * <p>Uses a synchronous dispatcher ({@code Runnable::run}) so all property updates happen immediately without requiring
 * a running JavaFX toolkit.
 */
@DisplayName("SimulationStateFxBridge")
class SimulationStateFxBridgeTest {

    private DefaultSimulationState state;
    private SimulationStateFxBridge bridge;

    @BeforeEach
    void setUp() {
        state = new DefaultSimulationState();
        bridge = new SimulationStateFxBridge(state, Runnable::run);
    }

    // ─────────────────────────────────────────────────────────────────
    // Static formatters
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("formatBodyId")
    class FormatBodyId {

        @Test
        @DisplayName("-1 → em-dash (no body)")
        void noBody() {
            assertEquals("—", SimulationStateFxBridge.formatBodyId(-1));
        }

        @Test
        @DisplayName("399 → \"NAIF 399\"")
        void earth() {
            assertEquals("NAIF 399", SimulationStateFxBridge.formatBodyId(399));
        }

        @Test
        @DisplayName("negative NAIF ID (spacecraft) → \"NAIF -98\"")
        void spacecraft() {
            assertEquals("NAIF -98", SimulationStateFxBridge.formatBodyId(-98));
        }
    }

    @Nested
    @DisplayName("formatTracked")
    class FormatTracked {

        @Test
        @DisplayName("-1 → \"Not tracking\"")
        void notTracking() {
            assertEquals("Not tracking", SimulationStateFxBridge.formatTracked(-1));
        }

        @Test
        @DisplayName("301 → \"Tracking NAIF 301\"")
        void tracking() {
            assertEquals("Tracking NAIF 301", SimulationStateFxBridge.formatTracked(301));
        }
    }

    @Nested
    @DisplayName("formatTimeRate")
    class FormatTimeRate {

        @Test
        @DisplayName("1.0 → \"1.00×\"")
        void realTime() {
            assertEquals("1.00×", SimulationStateFxBridge.formatTimeRate(1.0));
        }

        @Test
        @DisplayName("86400.0 → \"86400.00×\"")
        void oneDay() {
            assertEquals("86400.00×", SimulationStateFxBridge.formatTimeRate(86400.0));
        }

        @Test
        @DisplayName("-3600.0 → \"-3600.00×\"")
        void negativeRate() {
            assertEquals("-3600.00×", SimulationStateFxBridge.formatTimeRate(-3600.0));
        }

        @Test
        @DisplayName("1e7 → scientific notation")
        void largeRate() {
            String s = SimulationStateFxBridge.formatTimeRate(1e7);
            assertTrue(s.contains("e") || s.contains("E"), "Large rates should use scientific notation, got: " + s);
        }

        @Test
        @DisplayName("0.001 → \"0.00×\" (boundary: not scientific)")
        void smallBoundary() {
            String s = SimulationStateFxBridge.formatTimeRate(0.001);
            assertFalse(s.contains("e") || s.contains("E"), "0.001 should not use scientific notation, got: " + s);
        }
    }

    @Nested
    @DisplayName("formatCameraFrame")
    class FormatCameraFrame {

        @Test
        @DisplayName("INERTIAL → \"INERTIAL\"")
        void inertial() {
            assertEquals("INERTIAL", SimulationStateFxBridge.formatCameraFrame(CameraFrame.INERTIAL));
        }

        @Test
        @DisplayName("SYNODIC → \"SYNODIC\"")
        void synodic() {
            assertEquals("SYNODIC", SimulationStateFxBridge.formatCameraFrame(CameraFrame.SYNODIC));
        }

        @Test
        @DisplayName("null → \"—\"")
        void nullFrame() {
            assertEquals("—", SimulationStateFxBridge.formatCameraFrame(null));
        }
    }

    @Nested
    @DisplayName("formatCameraPosition")
    class FormatCameraPositionTests {

        @Test
        @DisplayName("null → \"—\"")
        void nullPos() {
            assertEquals("—", SimulationStateFxBridge.formatCameraPosition(null));
        }

        @Test
        @DisplayName("zero vector formatted correctly")
        void zeroVector() {
            String s = SimulationStateFxBridge.formatCameraPosition(new double[] {0.0, 0.0, 0.0});
            assertTrue(s.contains("km"), "Should contain unit: " + s);
            assertTrue(s.startsWith("["), "Should start with '[': " + s);
        }

        @Test
        @DisplayName("non-zero vector contains all three components")
        void nonZeroVector() {
            String s = SimulationStateFxBridge.formatCameraPosition(new double[] {1.23e8, -4.56e7, 7.89e4});
            assertTrue(s.contains("1.230e+08") || s.contains("1.230e+008"), "Expected scientific notation for x: " + s);
        }
    }

    @Nested
    @DisplayName("formatPaused")
    class FormatPausedTests {

        @Test
        @DisplayName("true → \"Paused\"")
        void paused() {
            assertEquals("Paused", SimulationStateFxBridge.formatPaused(true));
        }

        @Test
        @DisplayName("false → \"Running\"")
        void running() {
            assertEquals("Running", SimulationStateFxBridge.formatPaused(false));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Listener-driven property updates
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Bridge properties update when state changes")
    class ListenerUpdates {

        @Test
        @DisplayName("selectedBodyTextProperty updates when selectedBodyId changes")
        void selectedBodyUpdates() {
            state.setSelectedBodyId(399);
            assertEquals("NAIF 399", bridge.selectedBodyTextProperty().get());
        }

        @Test
        @DisplayName("focusedBodyTextProperty updates when focusedBodyId changes")
        void focusedBodyUpdates() {
            state.setFocusedBodyId(301);
            assertEquals("NAIF 301", bridge.focusedBodyTextProperty().get());
        }

        @Test
        @DisplayName("targetedBodyTextProperty updates when targetedBodyId changes")
        void targetedBodyUpdates() {
            state.setTargetedBodyId(10);
            assertEquals("NAIF 10", bridge.targetedBodyTextProperty().get());
        }

        @Test
        @DisplayName("trackedTextProperty shows tracking when trackedBodyId set")
        void trackedUpdates() {
            state.setTrackedBodyId(301);
            assertEquals("Tracking NAIF 301", bridge.trackedTextProperty().get());
        }

        @Test
        @DisplayName("trackedTextProperty shows not-tracking when cleared")
        void trackedClearedUpdates() {
            state.setTrackedBodyId(301);
            state.setTrackedBodyId(-1);
            assertEquals("Not tracking", bridge.trackedTextProperty().get());
        }

        @Test
        @DisplayName("timeRateTextProperty updates when timeRate changes")
        void timeRateUpdates() {
            state.setTimeRate(3600.0);
            assertEquals("3600.00×", bridge.timeRateTextProperty().get());
        }

        @Test
        @DisplayName("pausedTextProperty updates when paused changes")
        void pausedUpdates() {
            state.setPaused(true);
            assertEquals("Paused", bridge.pausedTextProperty().get());
            state.setPaused(false);
            assertEquals("Running", bridge.pausedTextProperty().get());
        }

        @Test
        @DisplayName("cameraFrameTextProperty updates when cameraFrame changes")
        void cameraFrameUpdates() {
            // No focus set (focusedBodyId == -1, targetedBodyId == -1) → default Sun target
            state.setCameraFrame(CameraFrame.SYNODIC);
            assertEquals(
                    "SYNODIC [— → NAIF 10 (Sun)]",
                    bridge.cameraFrameTextProperty().get());
        }

        @Test
        @DisplayName("cameraFrameTextProperty shows focus+target when SYNODIC and focus is set")
        void cameraFrameUpdatesSynodicWithFocus() {
            state.setFocusedBodyId(399);
            state.setTargetedBodyId(399); // same as focus → default Sun
            state.setCameraFrame(CameraFrame.SYNODIC);
            assertEquals(
                    "SYNODIC [NAIF 399 → NAIF 10 (Sun)]",
                    bridge.cameraFrameTextProperty().get());
        }

        @Test
        @DisplayName("cameraFrameTextProperty updates when focusedBodyId changes while SYNODIC")
        void cameraFrameUpdatesSynodicOnFocusChange() {
            state.setCameraFrame(CameraFrame.SYNODIC);
            state.setFocusedBodyId(399);
            assertEquals(
                    "SYNODIC [NAIF 399 → NAIF 10 (Sun)]",
                    bridge.cameraFrameTextProperty().get());
        }

        @Test
        @DisplayName("cameraPositionTextProperty updates when cameraPositionJ2000 changes")
        void cameraPositionUpdates() {
            state.setCameraPositionJ2000(new double[] {1.0e8, 0.0, 0.0});
            String s = bridge.cameraPositionTextProperty().get();
            assertTrue(s.contains("km"), "Position should include unit: " + s);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Initial property values
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Bridge initialises with current state values")
    class InitialValues {

        @Test
        @DisplayName("selectedBodyText starts as '—' (default state has -1)")
        void initialSelectedBody() {
            assertEquals("—", bridge.selectedBodyTextProperty().get());
        }

        @Test
        @DisplayName("pausedText starts as 'Running' (default state is unpaused)")
        void initialPaused() {
            assertEquals("Running", bridge.pausedTextProperty().get());
        }

        @Test
        @DisplayName("cameraFrameText starts as 'INERTIAL'")
        void initialCameraFrame() {
            assertEquals("INERTIAL", bridge.cameraFrameTextProperty().get());
        }

        @Test
        @DisplayName("trackedText starts as 'Not tracking'")
        void initialTracked() {
            assertEquals("Not tracking", bridge.trackedTextProperty().get());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // UTC formatting (requires SPICE kernel)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("UTC time formatting (requires SPICE kernel)")
    class UtcFormatting {

        @BeforeEach
        void loadKernel() {
            TestHarness.resetSingleton();
            KEPPLRConfiguration.getTestTemplate();
        }

        @Test
        @DisplayName("utcTimeTextProperty updates when ET changes and produces non-empty string")
        void utcTimeUpdates() {
            double testEt = TestHarness.getTestEpoch();
            state.setCurrentEt(testEt);
            String utc = bridge.utcTimeTextProperty().get();
            assertFalse(utc.isBlank(), "UTC text should not be blank after ET set: " + utc);
            assertNotEquals("—", utc, "UTC text should not be fallback after ET set");
        }

        @Test
        @DisplayName("UTC text contains '2015' for the New Horizons flyby epoch")
        void utcTextContainsExpectedYear() {
            double testEt = TestHarness.getTestEpoch();
            state.setCurrentEt(testEt);
            String utc = bridge.utcTimeTextProperty().get();
            assertTrue(utc.contains("2015"), "UTC for New Horizons flyby should contain '2015', got: " + utc);
        }
    }
}
