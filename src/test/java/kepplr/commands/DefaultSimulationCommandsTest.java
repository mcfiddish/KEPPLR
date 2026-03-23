package kepplr.commands;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.camera.CameraFrame;
import kepplr.camera.TransitionController;
import kepplr.config.KEPPLRConfiguration;
import kepplr.core.SimulationClock;
import kepplr.render.vector.VectorTypes;
import kepplr.state.DefaultSimulationState;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultSimulationCommands}.
 *
 * <p>Uses a real {@link DefaultSimulationState} — no mocks needed; these are pure state-transition tests (REDESIGN.md
 * §4.3–§4.6).
 */
@DisplayName("DefaultSimulationCommands")
class DefaultSimulationCommandsTest {

    static final int SUN = 10;
    static final int EARTH = 399;
    static final int MOON = 301;
    static final int NEW_HORIZONS = -98;

    private DefaultSimulationState state;
    private DefaultSimulationCommands commands;

    @BeforeEach
    void setUp() {
        state = new DefaultSimulationState();
        SimulationClock clock = new SimulationClock(state, 0.0);
        TransitionController tc = new TransitionController(state);
        commands = new DefaultSimulationCommands(state, clock, tc);
    }

    // ─────────────────────────────────────────────────────────────────
    // selectBody (§4.3)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("selectBody (§4.3)")
    class SelectBodyTests {

        @Test
        @DisplayName("selectBody sets only selectedBodyId; focused and targeted remain -1")
        void selectBodyOnlyAffectsSelected() {
            commands.selectBody(EARTH);
            assertEquals(EARTH, state.selectedBodyIdProperty().get(), "selected should be EARTH");
            assertEquals(-1, state.focusedBodyIdProperty().get(), "focused should be unchanged");
            assertEquals(-1, state.targetedBodyIdProperty().get(), "targeted should be unchanged");
        }

        @Test
        @DisplayName("selectBody after focusBody does not change focused or targeted")
        void selectAfterFocusLeavesOtherFieldsUnchanged() {
            commands.focusBody(EARTH);
            commands.selectBody(MOON);

            assertEquals(MOON, state.selectedBodyIdProperty().get(), "selected should be MOON");
            assertEquals(EARTH, state.focusedBodyIdProperty().get(), "focused should still be EARTH");
            assertEquals(EARTH, state.targetedBodyIdProperty().get(), "targeted should still be EARTH");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // focusBody (§4.5, §4.6)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("focusBody (§4.5, §4.6)")
    class FocusBodyTests {

        @Test
        @DisplayName("focusBody sets selected, focused, and targeted to same body")
        void focusBodySetsThreeFields() {
            commands.focusBody(EARTH);
            assertEquals(EARTH, state.selectedBodyIdProperty().get(), "selected should be EARTH");
            assertEquals(EARTH, state.focusedBodyIdProperty().get(), "focused should be EARTH");
            assertEquals(EARTH, state.targetedBodyIdProperty().get(), "targeted should be EARTH");
        }

        @Test
        @DisplayName("focusBody then targetBody: selected=target, focused=focus, targeted=target")
        void focusThenTarget() {
            commands.focusBody(EARTH);
            commands.targetBody(MOON);

            assertEquals(MOON, state.selectedBodyIdProperty().get(), "selected should be MOON");
            assertEquals(EARTH, state.focusedBodyIdProperty().get(), "focused should still be EARTH");
            assertEquals(MOON, state.targetedBodyIdProperty().get(), "targeted should be MOON");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // targetBody (§4.4, §4.6)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("targetBody (§4.4, §4.6)")
    class TargetBodyTests {

        @Test
        @DisplayName("targetBody sets selected and targeted; focused unchanged")
        void targetBodySetsSelectedAndTargeted() {
            commands.focusBody(EARTH);
            commands.targetBody(MOON);

            assertEquals(MOON, state.selectedBodyIdProperty().get(), "selected should be MOON");
            assertEquals(EARTH, state.focusedBodyIdProperty().get(), "focused should still be EARTH");
            assertEquals(MOON, state.targetedBodyIdProperty().get(), "targeted should be MOON");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // setCameraFrame (§1.5)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setCameraFrame (§1.5)")
    class SetCameraFrameTests {

        @Test
        @DisplayName("setCameraFrame(INERTIAL) updates state to INERTIAL")
        void setInertial() {
            commands.setCameraFrame(CameraFrame.INERTIAL);
            assertEquals(CameraFrame.INERTIAL, state.cameraFrameProperty().get());
        }

        @Test
        @DisplayName("setCameraFrame(SYNODIC) updates state to SYNODIC")
        void setSynodic() {
            commands.setCameraFrame(CameraFrame.SYNODIC);
            assertEquals(CameraFrame.SYNODIC, state.cameraFrameProperty().get());
        }

        @Test
        @DisplayName("setCameraFrame(BODY_FIXED) updates state to BODY_FIXED (§1.5)")
        void setBodyFixed() {
            commands.setCameraFrame(CameraFrame.BODY_FIXED);
            assertEquals(CameraFrame.BODY_FIXED, state.cameraFrameProperty().get());
        }

        @Test
        @DisplayName("setCameraFrame(BODY_FIXED) after SYNODIC updates state to BODY_FIXED")
        void setBodyFixedAfterSynodic() {
            commands.setCameraFrame(CameraFrame.SYNODIC);
            commands.setCameraFrame(CameraFrame.BODY_FIXED);
            assertEquals(CameraFrame.BODY_FIXED, state.cameraFrameProperty().get());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Time commands (§1.2, §2.3)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Time commands (§1.2, §2.3)")
    class TimeCommandTests {

        @Test
        @DisplayName("setTimeRate is an absolute assignment — not multiplicative (§2.3)")
        void setTimeRateAbsolute() {
            commands.setTimeRate(3600.0);
            assertEquals(3600.0, state.timeRateProperty().get(), "timeRate should be 3600.0 (absolute)");
        }

        @Test
        @DisplayName("setTimeRate called twice sets the final value")
        void setTimeRateTwice() {
            commands.setTimeRate(1.0);
            commands.setTimeRate(86400.0);
            assertEquals(86400.0, state.timeRateProperty().get(), "timeRate should be final absolute value");
        }

        @Test
        @DisplayName("setPaused(true) then setPaused(false) round-trip")
        void setPausedRoundTrip() {
            commands.setPaused(true);
            assertTrue(state.pausedProperty().get(), "should be paused after setPaused(true)");
            commands.setPaused(false);
            assertFalse(state.pausedProperty().get(), "should be unpaused after setPaused(false)");
        }

        @Test
        @DisplayName("setET updates currentEt in state")
        void setETUpdatesState() {
            commands.setET(489297600.0);
            assertEquals(489297600.0, state.currentEtProperty().get(), "currentEt should reflect setET value");
        }

        @Test
        @DisplayName("setUTC converts known UTC string and updates currentEt (requires SPICE kernel)")
        void setUTCUpdatesState() {
            TestHarness.resetSingleton();
            KEPPLRConfiguration.getTestTemplate();

            double expectedET = TestHarness.getTestEpoch();
            commands.setUTC("2015 Jul 14 07:59:00");

            assertEquals(
                    expectedET,
                    state.currentEtProperty().get(),
                    1e-3,
                    "setUTC must set currentEt to the ET matching the UTC string");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Camera navigation commands (Step 19c)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Camera navigation commands (Step 19c)")
    class CameraNavigationCommands {

        @Test
        @DisplayName("zoom delegates to TransitionController (does not throw)")
        void zoomDelegates() {
            assertDoesNotThrow(() -> commands.zoom(2.0, 0));
        }

        @Test
        @DisplayName("setFov delegates to TransitionController (does not throw)")
        void setFovDelegates() {
            assertDoesNotThrow(() -> commands.setFov(60.0, 0));
        }

        @Test
        @DisplayName("orbit delegates to TransitionController (does not throw)")
        void orbitDelegates() {
            assertDoesNotThrow(() -> commands.orbit(45.0, 0.0, 0));
        }

        @Test
        @DisplayName("tilt delegates to TransitionController (does not throw)")
        void tiltDelegates() {
            assertDoesNotThrow(() -> commands.tilt(10.0, 0));
        }

        @Test
        @DisplayName("yaw delegates to TransitionController (does not throw)")
        void yawDelegates() {
            assertDoesNotThrow(() -> commands.yaw(10.0, 0));
        }

        @Test
        @DisplayName("roll delegates to TransitionController (does not throw)")
        void rollDelegates() {
            assertDoesNotThrow(() -> commands.roll(90.0, 0));
        }

        @Test
        @DisplayName("setCameraPosition (focus-relative) delegates to TransitionController")
        void setCameraPositionFocusRelative() {
            assertDoesNotThrow(() -> commands.setCameraPosition(0, 0, 10000, 0));
        }

        @Test
        @DisplayName("setCameraPosition (explicit origin) delegates to TransitionController")
        void setCameraPositionExplicitOrigin() {
            assertDoesNotThrow(() -> commands.setCameraPosition(0, 0, 50000, MOON, 0));
        }

        @Test
        @DisplayName("setCameraOrientation delegates to TransitionController")
        void setCameraOrientationDelegates() {
            assertDoesNotThrow(() -> commands.setCameraOrientation(1, 0, 0, 0, 0, 1, 0));
        }

        @Test
        @DisplayName("setSynodicFrame sets override IDs and frame without changing interaction state")
        void setSynodicFrameSetsOverrides() {
            commands.focusBody(EARTH);
            commands.targetBody(MOON);
            commands.selectBody(SUN);

            commands.setSynodicFrame(EARTH, MOON);

            assertEquals(EARTH, state.synodicFrameFocusIdProperty().get(), "synodic focus override");
            assertEquals(MOON, state.synodicFrameTargetIdProperty().get(), "synodic target override");
            assertEquals(CameraFrame.SYNODIC, state.cameraFrameProperty().get(), "frame should be SYNODIC");
            // Interaction state untouched
            assertEquals(SUN, state.selectedBodyIdProperty().get(), "selected should not change");
            assertEquals(EARTH, state.focusedBodyIdProperty().get(), "focused should not change");
            assertEquals(MOON, state.targetedBodyIdProperty().get(), "targeted should not change");
        }

        @Test
        @DisplayName("setCameraFrame clears synodic override IDs")
        void setCameraFrameClearsOverrides() {
            commands.setSynodicFrame(EARTH, MOON);
            assertEquals(EARTH, state.synodicFrameFocusIdProperty().get());

            commands.setCameraFrame(CameraFrame.INERTIAL);

            assertEquals(-1, state.synodicFrameFocusIdProperty().get(), "override focus should be cleared");
            assertEquals(-1, state.synodicFrameTargetIdProperty().get(), "override target should be cleared");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Overlay commands (Step 19b)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Overlay commands (Step 19b)")
    class OverlayCommands {

        @Test
        @DisplayName("setLabelVisible delegates to state")
        void setLabelVisible() {
            commands.setLabelVisible(EARTH, true);
            assertTrue(state.labelVisibleProperty(EARTH).get());
        }

        @Test
        @DisplayName("setHudTimeVisible delegates to state")
        void setHudTimeVisible() {
            commands.setHudTimeVisible(false);
            assertFalse(state.hudTimeVisibleProperty().get());
        }

        @Test
        @DisplayName("setHudInfoVisible delegates to state")
        void setHudInfoVisible() {
            commands.setHudInfoVisible(false);
            assertFalse(state.hudInfoVisibleProperty().get());
        }

        @Test
        @DisplayName("setTrailVisible delegates to state")
        void setTrailVisible() {
            commands.setTrailVisible(EARTH, true);
            assertTrue(state.trailVisibleProperty(EARTH).get());
        }

        @Test
        @DisplayName("setTrailDuration delegates to state")
        void setTrailDuration() {
            commands.setTrailDuration(EARTH, 86400.0);
            assertEquals(86400.0, state.trailDurationProperty(EARTH).get(), 0.001);
        }

        @Test
        @DisplayName("setVectorVisible delegates to state")
        void setVectorVisible() {
            commands.setVectorVisible(EARTH, VectorTypes.velocity(), true);
            assertTrue(
                    state.vectorVisibleProperty(EARTH, VectorTypes.velocity()).get());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Transition control (Step 20)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Transition control (Step 20)")
    class TransitionControlTests {

        @Test
        @DisplayName("cancelTransition delegates to TransitionController without throwing")
        void cancelTransitionDelegates() {
            assertDoesNotThrow(() -> commands.cancelTransition());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Instrument frustum overlays (Step 22)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Instrument frustum overlays (Step 22)")
    class FrustumOverlayTests {

        @Test
        @DisplayName("setFrustumVisible(int, true) updates state")
        void setFrustumVisibleInt() {
            commands.setFrustumVisible(-98300, true);
            assertTrue(state.frustumVisibleProperty(-98300).get());
        }

        @Test
        @DisplayName("setFrustumVisible(int, false) updates state")
        void setFrustumVisibleIntFalse() {
            commands.setFrustumVisible(-98300, true);
            commands.setFrustumVisible(-98300, false);
            assertFalse(state.frustumVisibleProperty(-98300).get());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // loadConfiguration (Step 27)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("loadConfiguration (Step 27)")
    class LoadConfigurationTests {

        @Test
        @DisplayName("invalid path does not throw from the caller thread")
        void invalidPathDoesNotThrow() {
            // No sceneRebuildCallback set — commands has none in this test harness.
            // The call must return normally even if KEPPLRConfiguration.reload() fails.
            assertDoesNotThrow(() -> commands.loadConfiguration("/nonexistent/path/config.properties"));
        }

        @Test
        @DisplayName("invalid path leaves sceneRebuildCallback uncalled")
        void invalidPathSkipsRebuild() {
            boolean[] called = {false};
            commands.setSceneRebuildCallback(latch -> {
                called[0] = true;
                latch.countDown();
            });
            commands.loadConfiguration("/nonexistent/path/config.properties");
            assertFalse(called[0], "sceneRebuildCallback must not be called after a failed reload");
        }
    }
}
