package kepplr.state;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultSimulationState}.
 *
 * <p>No ephemeris required. All tests operate on pure JavaFX properties.
 */
@DisplayName("DefaultSimulationState")
class DefaultSimulationStateTest {

    static final int EARTH = 399;
    static final int MOON = 301;

    private DefaultSimulationState state;

    @BeforeEach
    void setUp() {
        state = new DefaultSimulationState();
    }

    // ─────────────────────────────────────────────────────────────────
    // Initial values
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Initial property values")
    class InitialValues {

        @Test
        @DisplayName("selectedBodyId starts at -1")
        void selectedBodyIdInitial() {
            assertEquals(-1, state.selectedBodyIdProperty().get());
        }

        @Test
        @DisplayName("focusedBodyId starts at -1")
        void focusedBodyIdInitial() {
            assertEquals(-1, state.focusedBodyIdProperty().get());
        }

        @Test
        @DisplayName("targetedBodyId starts at -1")
        void targetedBodyIdInitial() {
            assertEquals(-1, state.targetedBodyIdProperty().get());
        }

        @Test
        @DisplayName("trackedBodyId starts at -1")
        void trackedBodyIdInitial() {
            assertEquals(-1, state.trackedBodyIdProperty().get());
        }

        @Test
        @DisplayName("currentEt starts at 0.0")
        void currentEtInitial() {
            assertEquals(0.0, state.currentEtProperty().get());
        }

        @Test
        @DisplayName("timeRate starts at DEFAULT_TIME_RATE")
        void timeRateInitial() {
            assertEquals(KepplrConstants.DEFAULT_TIME_RATE, state.timeRateProperty().get());
        }

        @Test
        @DisplayName("paused starts at false")
        void pausedInitial() {
            assertFalse(state.pausedProperty().get());
        }

        @Test
        @DisplayName("cameraFrame starts at J2000")
        void cameraFrameInitial() {
            assertEquals("J2000", state.cameraFrameProperty().get());
        }

        @Test
        @DisplayName("cameraPositionJ2000 starts at {0, 0, 0}")
        void cameraPositionJ2000Initial() {
            double[] pos = state.cameraPositionJ2000Property().get();
            assertNotNull(pos);
            assertEquals(3, pos.length);
            assertEquals(0.0, pos[0]);
            assertEquals(0.0, pos[1]);
            assertEquals(0.0, pos[2]);
        }

        @Test
        @DisplayName("trackingAnchor starts at null (no tracking)")
        void trackingAnchorInitial() {
            assertNull(state.trackingAnchorProperty().get());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Setter round-trips
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Setter updates are reflected in read-only properties")
    class SetterRoundTrips {

        @Test
        @DisplayName("setSelectedBodyId is reflected in selectedBodyIdProperty")
        void setSelectedBodyId() {
            state.setSelectedBodyId(EARTH);
            assertEquals(EARTH, state.selectedBodyIdProperty().get());
        }

        @Test
        @DisplayName("setFocusedBodyId is reflected in focusedBodyIdProperty")
        void setFocusedBodyId() {
            state.setFocusedBodyId(EARTH);
            assertEquals(EARTH, state.focusedBodyIdProperty().get());
        }

        @Test
        @DisplayName("setTargetedBodyId is reflected in targetedBodyIdProperty")
        void setTargetedBodyId() {
            state.setTargetedBodyId(MOON);
            assertEquals(MOON, state.targetedBodyIdProperty().get());
        }

        @Test
        @DisplayName("setTrackedBodyId is reflected in trackedBodyIdProperty")
        void setTrackedBodyId() {
            state.setTrackedBodyId(MOON);
            assertEquals(MOON, state.trackedBodyIdProperty().get());
        }

        @Test
        @DisplayName("setCurrentEt is reflected in currentEtProperty")
        void setCurrentEt() {
            double et = 489297600.0;
            state.setCurrentEt(et);
            assertEquals(et, state.currentEtProperty().get());
        }

        @Test
        @DisplayName("setTimeRate is reflected in timeRateProperty")
        void setTimeRate() {
            state.setTimeRate(3600.0);
            assertEquals(3600.0, state.timeRateProperty().get());
        }

        @Test
        @DisplayName("setPaused is reflected in pausedProperty")
        void setPaused() {
            state.setPaused(true);
            assertTrue(state.pausedProperty().get());
            state.setPaused(false);
            assertFalse(state.pausedProperty().get());
        }

        @Test
        @DisplayName("setCameraFrame is reflected in cameraFrameProperty")
        void setCameraFrame() {
            state.setCameraFrame("BODY_FIXED");
            assertEquals("BODY_FIXED", state.cameraFrameProperty().get());
        }

        @Test
        @DisplayName("setCameraPositionJ2000 is reflected in cameraPositionJ2000Property")
        void setCameraPositionJ2000() {
            double[] pos = {1.0e8, 2.0e7, -3.0e6};
            state.setCameraPositionJ2000(pos);
            assertArrayEquals(pos, state.cameraPositionJ2000Property().get());
        }

        @Test
        @DisplayName("setTrackingAnchor is reflected in trackingAnchorProperty")
        void setTrackingAnchor() {
            double[] anchor = {0.25, -0.5};
            state.setTrackingAnchor(anchor);
            assertArrayEquals(anchor, state.trackingAnchorProperty().get());
        }

        @Test
        @DisplayName("setTrackingAnchor(null) clears the anchor")
        void setTrackingAnchorNull() {
            state.setTrackingAnchor(new double[] {0.1, 0.2});
            state.setTrackingAnchor(null);
            assertNull(state.trackingAnchorProperty().get());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Observable change listeners
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Observable properties fire change listeners")
    class ChangeListeners {

        @Test
        @DisplayName("selectedBodyIdProperty fires listener on change")
        void selectedBodyIdFiresListener() {
            AtomicBoolean fired = new AtomicBoolean(false);
            state.selectedBodyIdProperty().addListener((obs, oldVal, newVal) -> fired.set(true));
            state.setSelectedBodyId(EARTH);
            assertTrue(fired.get(), "Change listener should fire when selectedBodyId changes");
        }

        @Test
        @DisplayName("timeRateProperty fires listener on change")
        void timeRateFiresListener() {
            AtomicBoolean fired = new AtomicBoolean(false);
            state.timeRateProperty().addListener((obs, oldVal, newVal) -> fired.set(true));
            state.setTimeRate(86400.0);
            assertTrue(fired.get(), "Change listener should fire when timeRate changes");
        }

        @Test
        @DisplayName("pausedProperty fires listener on change")
        void pausedFiresListener() {
            AtomicInteger count = new AtomicInteger(0);
            state.pausedProperty().addListener((obs, oldVal, newVal) -> count.incrementAndGet());
            state.setPaused(true);
            state.setPaused(false);
            assertEquals(2, count.get(), "Change listener should fire for each paused state change");
        }

        @Test
        @DisplayName("cameraPositionJ2000Property fires listener on change")
        void cameraPositionFiresListener() {
            AtomicBoolean fired = new AtomicBoolean(false);
            state.cameraPositionJ2000Property().addListener((obs, oldVal, newVal) -> fired.set(true));
            state.setCameraPositionJ2000(new double[] {1.0, 2.0, 3.0});
            assertTrue(fired.get(), "Change listener should fire when cameraPositionJ2000 changes");
        }

        @Test
        @DisplayName("trackingAnchorProperty fires listener when anchor is set")
        void trackingAnchorFiresListenerOnSet() {
            AtomicBoolean fired = new AtomicBoolean(false);
            state.trackingAnchorProperty().addListener((obs, oldVal, newVal) -> fired.set(true));
            state.setTrackingAnchor(new double[] {0.3, 0.7});
            assertTrue(fired.get(), "Change listener should fire when tracking anchor is set");
        }

        @Test
        @DisplayName("trackingAnchorProperty fires listener when anchor is cleared")
        void trackingAnchorFiresListenerOnClear() {
            state.setTrackingAnchor(new double[] {0.3, 0.7});
            AtomicBoolean fired = new AtomicBoolean(false);
            state.trackingAnchorProperty().addListener((obs, oldVal, newVal) -> fired.set(true));
            state.setTrackingAnchor(null);
            assertTrue(fired.get(), "Change listener should fire when tracking anchor is cleared");
        }
    }
}
