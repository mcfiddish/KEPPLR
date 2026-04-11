package kepplr.state;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import kepplr.camera.CameraFrame;
import kepplr.render.vector.VectorTypes;
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
        @DisplayName("currentEt starts at 0.0")
        void currentEtInitial() {
            assertEquals(0.0, state.currentEtProperty().get());
        }

        @Test
        @DisplayName("timeRate starts at DEFAULT_TIME_RATE")
        void timeRateInitial() {
            assertEquals(
                    KepplrConstants.DEFAULT_TIME_RATE, state.timeRateProperty().get());
        }

        @Test
        @DisplayName("paused starts at false")
        void pausedInitial() {
            assertFalse(state.pausedProperty().get());
        }

        @Test
        @DisplayName("cameraFrame starts at INERTIAL")
        void cameraFrameInitial() {
            assertEquals(CameraFrame.INERTIAL, state.cameraFrameProperty().get());
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
        @DisplayName("deltaSimSeconds starts at 0.0")
        void deltaSimSecondsInitial() {
            assertEquals(0.0, state.deltaSimSecondsProperty().get());
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
            state.setCameraFrame(CameraFrame.SYNODIC);
            assertEquals(CameraFrame.SYNODIC, state.cameraFrameProperty().get());
        }

        @Test
        @DisplayName("setCameraPositionJ2000 is reflected in cameraPositionJ2000Property")
        void setCameraPositionJ2000() {
            double[] pos = {1.0e8, 2.0e7, -3.0e6};
            state.setCameraPositionJ2000(pos);
            assertArrayEquals(pos, state.cameraPositionJ2000Property().get());
        }

        @Test
        @DisplayName("setDeltaSimSeconds is reflected in deltaSimSecondsProperty")
        void setDeltaSimSeconds() {
            state.setDeltaSimSeconds(42.5);
            assertEquals(42.5, state.deltaSimSecondsProperty().get());
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
    }

    // ─────────────────────────────────────────────────────────────────
    // Overlay properties (Step 19b)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Overlay properties (Step 19b)")
    class OverlayProperties {

        @Test
        @DisplayName("labelVisibleProperty defaults to false")
        void labelVisibleDefault() {
            assertFalse(state.labelVisibleProperty(EARTH).get(), "Label should default to invisible");
        }

        @Test
        @DisplayName("setLabelVisible(true) makes label visible")
        void setLabelVisibleTrue() {
            state.setLabelVisible(EARTH, true);
            assertTrue(state.labelVisibleProperty(EARTH).get());
        }

        @Test
        @DisplayName("hudTimeVisibleProperty defaults to true")
        void hudTimeDefault() {
            assertTrue(state.hudTimeVisibleProperty().get(), "HUD time should default to visible");
        }

        @Test
        @DisplayName("setHudTimeVisible(false) hides HUD time")
        void setHudTimeVisibleFalse() {
            state.setHudTimeVisible(false);
            assertFalse(state.hudTimeVisibleProperty().get());
        }

        @Test
        @DisplayName("hudInfoVisibleProperty defaults to true")
        void hudInfoDefault() {
            assertTrue(state.hudInfoVisibleProperty().get(), "HUD info should default to visible");
        }

        @Test
        @DisplayName("setHudInfoVisible(false) hides HUD info")
        void setHudInfoVisibleFalse() {
            state.setHudInfoVisible(false);
            assertFalse(state.hudInfoVisibleProperty().get());
        }

        @Test
        @DisplayName("trailVisibleProperty defaults to false")
        void trailVisibleDefault() {
            assertFalse(state.trailVisibleProperty(EARTH).get(), "Trail should default to invisible");
        }

        @Test
        @DisplayName("setTrailVisible(true) makes trail visible")
        void setTrailVisibleTrue() {
            state.setTrailVisible(EARTH, true);
            assertTrue(state.trailVisibleProperty(EARTH).get());
        }

        @Test
        @DisplayName("trailDurationProperty defaults to -1.0")
        void trailDurationDefault() {
            assertEquals(-1.0, state.trailDurationProperty(EARTH).get(), 0.001);
        }

        @Test
        @DisplayName("setTrailDuration changes the duration")
        void setTrailDuration() {
            state.setTrailDuration(EARTH, 86400.0);
            assertEquals(86400.0, state.trailDurationProperty(EARTH).get(), 0.001);
        }

        @Test
        @DisplayName("trailReferenceBodyProperty defaults to -1")
        void trailReferenceBodyDefault() {
            assertEquals(-1, state.trailReferenceBodyProperty(EARTH).get());
        }

        @Test
        @DisplayName("setTrailReferenceBody persists the reference body")
        void setTrailReferenceBody() {
            state.setTrailReferenceBody(EARTH, MOON);
            assertEquals(MOON, state.trailReferenceBodyProperty(EARTH).get());
        }

        @Test
        @DisplayName("setTrailReferenceBody is independent per body")
        void trailReferenceBodyIndependent() {
            state.setTrailReferenceBody(EARTH, MOON);
            assertEquals(
                    -1,
                    state.trailReferenceBodyProperty(MOON).get(),
                    "Moon's reference body should still be -1 (auto)");
        }

        @Test
        @DisplayName("clearOverlayState resets trail reference body to default")
        void clearOverlayStateClearsReferenceBody() {
            state.setTrailReferenceBody(EARTH, MOON);
            state.clearOverlayState();
            assertEquals(-1, state.trailReferenceBodyProperty(EARTH).get());
        }

        @Test
        @DisplayName("vectorVisibleProperty defaults to false")
        void vectorVisibleDefault() {
            assertFalse(
                    state.vectorVisibleProperty(EARTH, VectorTypes.velocity()).get());
        }

        @Test
        @DisplayName("setVectorVisible(true) makes vector visible")
        void setVectorVisibleTrue() {
            state.setVectorVisible(EARTH, VectorTypes.velocity(), true);
            assertTrue(
                    state.vectorVisibleProperty(EARTH, VectorTypes.velocity()).get());
        }

        @Test
        @DisplayName("different VectorType keys are independent")
        void differentVectorTypesIndependent() {
            state.setVectorVisible(EARTH, VectorTypes.velocity(), true);
            assertFalse(
                    state.vectorVisibleProperty(EARTH, VectorTypes.towardBody(10))
                            .get(),
                    "Different VectorType should have independent visibility");
        }

        @Test
        @DisplayName("same body different types are independent")
        void sameBodyDifferentTypesIndependent() {
            state.setVectorVisible(EARTH, VectorTypes.bodyAxisX(), true);
            state.setVectorVisible(EARTH, VectorTypes.bodyAxisY(), false);
            assertTrue(
                    state.vectorVisibleProperty(EARTH, VectorTypes.bodyAxisX()).get());
            assertFalse(
                    state.vectorVisibleProperty(EARTH, VectorTypes.bodyAxisY()).get());
        }

        @Test
        @DisplayName("label visibility for different bodies is independent")
        void labelVisibilityIndependentPerBody() {
            state.setLabelVisible(EARTH, true);
            state.setLabelVisible(MOON, false);
            assertTrue(state.labelVisibleProperty(EARTH).get());
            assertFalse(state.labelVisibleProperty(MOON).get());
        }

        @Test
        @DisplayName("frustumVisibleProperty defaults to false")
        void frustumVisibleDefault() {
            assertFalse(state.frustumVisibleProperty(-98300).get());
        }

        @Test
        @DisplayName("setFrustumVisible(true) makes frustum visible")
        void setFrustumVisibleTrue() {
            state.setFrustumVisible(-98300, true);
            assertTrue(state.frustumVisibleProperty(-98300).get());
        }

        @Test
        @DisplayName("setFrustumVisible(false) hides frustum")
        void setFrustumVisibleFalse() {
            state.setFrustumVisible(-98300, true);
            state.setFrustumVisible(-98300, false);
            assertFalse(state.frustumVisibleProperty(-98300).get());
        }

        @Test
        @DisplayName("frustum visibility is independent per instrument code")
        void frustumVisibilityIndependentPerCode() {
            state.setFrustumVisible(-98300, true);
            state.setFrustumVisible(-98301, false);
            assertTrue(state.frustumVisibleProperty(-98300).get());
            assertFalse(state.frustumVisibleProperty(-98301).get());
        }

        @Test
        @DisplayName("getFrustumVisibilityMap returns live entries")
        void frustumVisibilityMap() {
            state.setFrustumVisible(-98300, true);
            var map = state.getFrustumVisibilityMap();
            assertTrue(map.containsKey(-98300));
            assertTrue(map.get(-98300).get());
        }

        @Test
        @DisplayName("frustumPersistenceEnabledProperty defaults to false")
        void frustumPersistenceEnabledDefault() {
            assertFalse(state.frustumPersistenceEnabledProperty(-98300).get());
        }

        @Test
        @DisplayName("setFrustumPersistenceEnabled(true) enables recording")
        void setFrustumPersistenceEnabledTrue() {
            state.setFrustumPersistenceEnabled(-98300, true);
            assertTrue(state.frustumPersistenceEnabledProperty(-98300).get());
        }

        @Test
        @DisplayName("requestClearFrustumFootprints queues instrument-specific clear")
        void requestClearFrustumFootprintsQueuesRequest() {
            state.requestClearFrustumFootprints(-98300);
            assertEquals(-98300, state.pollPendingFrustumFootprintClear());
        }

        @Test
        @DisplayName("requestClearAllFrustumFootprints queues all-clear sentinel")
        void requestClearAllFrustumFootprintsQueuesRequest() {
            state.requestClearAllFrustumFootprints();
            assertEquals(DefaultSimulationState.CLEAR_ALL_FRUSTUM_FOOTPRINTS, state.pollPendingFrustumFootprintClear());
        }

        @Test
        @DisplayName("pending frustum footprint clears are queued in request order")
        void pendingFrustumFootprintClearsPreserveOrder() {
            state.requestClearFrustumFootprints(-98300);
            state.requestClearAllFrustumFootprints();
            state.requestClearFrustumFootprints(-98301);

            assertEquals(-98300, state.pollPendingFrustumFootprintClear());
            assertEquals(DefaultSimulationState.CLEAR_ALL_FRUSTUM_FOOTPRINTS, state.pollPendingFrustumFootprintClear());
            assertEquals(-98301, state.pollPendingFrustumFootprintClear());
            assertNull(state.pollPendingFrustumFootprintClear());
        }
    }

    @Nested
    @DisplayName("FrustumColor")
    class FrustumColorTests {

        @Test
        @DisplayName("hex accepts RRGGBB and #RRGGBB")
        void hexAcceptsHashAndNoHash() {
            assertEquals(new FrustumColor(255, 80, 20), FrustumColor.hex("#ff5014"));
            assertEquals(new FrustumColor(255, 80, 20), FrustumColor.hex("ff5014"));
        }

        @Test
        @DisplayName("hex rejects invalid forms")
        void hexRejectsInvalidForms() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> FrustumColor.hex(null)),
                    () -> assertThrows(IllegalArgumentException.class, () -> FrustumColor.hex("#fff")),
                    () -> assertThrows(IllegalArgumentException.class, () -> FrustumColor.hex("#xyzxyz")),
                    () -> assertThrows(IllegalArgumentException.class, () -> FrustumColor.hex("#ff501400")));
        }

        @Test
        @DisplayName("rgb rejects out-of-range components")
        void rgbRejectsOutOfRangeComponents() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> FrustumColor.rgb(-1, 0, 0)),
                    () -> assertThrows(IllegalArgumentException.class, () -> FrustumColor.rgb(0, 256, 0)),
                    () -> assertThrows(IllegalArgumentException.class, () -> FrustumColor.rgb(0, 0, 300)));
        }
    }
}
