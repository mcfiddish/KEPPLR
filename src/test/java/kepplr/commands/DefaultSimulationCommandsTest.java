package kepplr.commands;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.state.DefaultSimulationState;
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
        commands = new DefaultSimulationCommands(state);
    }

    // ─────────────────────────────────────────────────────────────────
    // selectBody (§4.3)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("selectBody (§4.3)")
    class SelectBodyTests {

        @Test
        @DisplayName("selectBody sets only selectedBodyId; focused, targeted, tracked remain -1")
        void selectBodyOnlyAffectsSelected() {
            commands.selectBody(EARTH);
            assertEquals(EARTH, state.selectedBodyIdProperty().get(), "selected should be EARTH");
            assertEquals(-1, state.focusedBodyIdProperty().get(), "focused should be unchanged");
            assertEquals(-1, state.targetedBodyIdProperty().get(), "targeted should be unchanged");
            assertEquals(-1, state.trackedBodyIdProperty().get(), "tracked should be unchanged");
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
        @DisplayName("focusBody clears tracked (§4.6)")
        void focusBodyClearsTracked() {
            commands.trackBody(MOON);
            commands.focusBody(EARTH);
            assertEquals(-1, state.trackedBodyIdProperty().get(), "tracked should be cleared by focusBody");
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

        @Test
        @DisplayName("targetBody clears tracked (§4.6)")
        void targetBodyClearsTracked() {
            commands.trackBody(MOON);
            commands.targetBody(EARTH);
            assertEquals(-1, state.trackedBodyIdProperty().get(), "tracked should be cleared by targetBody");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // trackBody / stopTracking (§4.6)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("trackBody and stopTracking (§4.6)")
    class TrackingTests {

        @Test
        @DisplayName("trackBody sets trackedBodyId")
        void trackBodySetsTracked() {
            commands.trackBody(MOON);
            assertEquals(MOON, state.trackedBodyIdProperty().get(), "tracked should be MOON");
        }

        @Test
        @DisplayName("stopTracking clears trackedBodyId")
        void stopTrackingClearsTracked() {
            commands.trackBody(MOON);
            commands.stopTracking();
            assertEquals(-1, state.trackedBodyIdProperty().get(), "tracked should be -1 after stopTracking");
        }

        @Test
        @DisplayName("trackBody then targetBody disables tracking (§4.6)")
        void trackThenTargetDisablesTracking() {
            commands.trackBody(MOON);
            commands.targetBody(EARTH);
            assertEquals(-1, state.trackedBodyIdProperty().get(), "targetBody should disable tracking");
        }

        @Test
        @DisplayName("trackBody then focusBody disables tracking (§4.6)")
        void trackThenFocusDisablesTracking() {
            commands.trackBody(MOON);
            commands.focusBody(EARTH);
            assertEquals(-1, state.trackedBodyIdProperty().get(), "focusBody should disable tracking");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Tracking anchor (§4.6)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tracking anchor (§4.6)")
    class TrackingAnchorTests {

        @Test
        @DisplayName("trackBody resets trackingAnchor to null (render loop sets it on next frame)")
        void trackBodyResetsAnchor() {
            // Simulate: a previous tracking session left an anchor behind
            state.setTrackingAnchor(new double[] {0.5, 0.5});
            commands.trackBody(MOON);
            assertNull(state.trackingAnchorProperty().get(), "trackBody should reset anchor to null");
            assertEquals(MOON, state.trackedBodyIdProperty().get());
        }

        @Test
        @DisplayName("anchor persists in state once set by render thread")
        void anchorPersistsAfterSet() {
            commands.trackBody(MOON);
            // Simulate render thread computing screen position for first frame
            state.setTrackingAnchor(new double[] {0.12, -0.34});
            assertArrayEquals(new double[] {0.12, -0.34}, state.trackingAnchorProperty().get(),
                    "Anchor should persist until explicitly changed");
        }

        @Test
        @DisplayName("stopTracking clears both trackedBodyId and trackingAnchor")
        void stopTrackingClearsBothFields() {
            commands.trackBody(MOON);
            state.setTrackingAnchor(new double[] {0.1, 0.2});
            commands.stopTracking();
            assertEquals(-1, state.trackedBodyIdProperty().get(), "trackedBodyId should be -1");
            assertNull(state.trackingAnchorProperty().get(), "trackingAnchor should be null");
        }

        @Test
        @DisplayName("targetBody clears tracked body and anchor (§4.6)")
        void targetBodyClearsAnchor() {
            commands.trackBody(MOON);
            state.setTrackingAnchor(new double[] {0.3, 0.4});
            commands.targetBody(EARTH);
            assertEquals(-1, state.trackedBodyIdProperty().get(), "trackedBodyId should be -1");
            assertNull(state.trackingAnchorProperty().get(), "trackingAnchor should be null");
        }

        @Test
        @DisplayName("focusBody clears tracked body and anchor (§4.6)")
        void focusBodyClearsAnchor() {
            commands.trackBody(MOON);
            state.setTrackingAnchor(new double[] {0.6, -0.2});
            commands.focusBody(EARTH);
            assertEquals(-1, state.trackedBodyIdProperty().get(), "trackedBodyId should be -1");
            assertNull(state.trackingAnchorProperty().get(), "trackingAnchor should be null");
        }

        @Test
        @DisplayName("selectBody does not affect tracking anchor")
        void selectBodyDoesNotAffectAnchor() {
            commands.trackBody(MOON);
            state.setTrackingAnchor(new double[] {0.0, 0.5});
            commands.selectBody(EARTH);
            assertEquals(MOON, state.trackedBodyIdProperty().get(), "trackedBodyId unchanged after selectBody");
            assertArrayEquals(new double[] {0.0, 0.5}, state.trackingAnchorProperty().get(),
                    "trackingAnchor unchanged after selectBody");
        }

        @Test
        @DisplayName("trackBody on a different body while already tracking resets anchor to null")
        void retrackDifferentBodyResetsAnchor() {
            commands.trackBody(MOON);
            state.setTrackingAnchor(new double[] {0.1, 0.1});
            commands.trackBody(EARTH);
            assertEquals(EARTH, state.trackedBodyIdProperty().get(), "trackedBodyId should switch to EARTH");
            assertNull(state.trackingAnchorProperty().get(), "anchor should reset when switching tracked body");
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
    }
}
