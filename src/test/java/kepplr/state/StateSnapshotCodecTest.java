package kepplr.state;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.camera.CameraFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link StateSnapshotCodec} (Step 26). */
@DisplayName("StateSnapshotCodec")
class StateSnapshotCodecTest {

    private static StateSnapshot sample() {
        return new StateSnapshot(
                4.895e8, // ET: ~2015 Jul 14
                1.0, // 1x time rate
                false,
                new double[] {1.234e8, -5.678e7, 9.012e6},
                new float[] {0.0f, 0.0f, 0.0f, 1.0f},
                CameraFrame.INERTIAL,
                399, // Earth focused
                301, // Moon targeted
                10, // Sun selected
                45.0,
                false); // absolute J2000
    }

    @Nested
    @DisplayName("Round-trip encode/decode")
    class RoundTrip {

        @Test
        @DisplayName("all fields survive round-trip")
        void allFieldsSurvive() {
            StateSnapshot original = sample();
            String encoded = StateSnapshotCodec.encode(original);
            StateSnapshot decoded = StateSnapshotCodec.decode(encoded);

            assertEquals(original.et(), decoded.et());
            assertEquals(original.timeRate(), decoded.timeRate());
            assertEquals(original.paused(), decoded.paused());
            assertArrayEquals(original.camPosJ2000(), decoded.camPosJ2000());
            assertArrayEquals(original.camOrientJ2000(), decoded.camOrientJ2000());
            assertEquals(original.cameraFrame(), decoded.cameraFrame());
            assertEquals(original.focusedBodyId(), decoded.focusedBodyId());
            assertEquals(original.targetedBodyId(), decoded.targetedBodyId());
            assertEquals(original.selectedBodyId(), decoded.selectedBodyId());
            assertEquals(original.fovDeg(), decoded.fovDeg());
            assertEquals(original.camPosRelativeToFocus(), decoded.camPosRelativeToFocus());
        }

        @Test
        @DisplayName("paused flag encodes correctly")
        void pausedFlag() {
            StateSnapshot paused = new StateSnapshot(
                    0, 1, true, new double[3], new float[] {0, 0, 0, 1}, CameraFrame.INERTIAL, -1, -1, -1, 45, false);
            String encoded = StateSnapshotCodec.encode(paused);
            assertTrue(StateSnapshotCodec.decode(encoded).paused());
        }

        @Test
        @DisplayName("camPosRelativeToFocus flag encodes correctly")
        void camPosRelativeToFocusFlag() {
            double[] offset = {12345.6, -7890.1, 2345.6};
            StateSnapshot relative = new StateSnapshot(
                    0, 1, false, offset, new float[] {0, 0, 0, 1}, CameraFrame.INERTIAL, 399, -1, -1, 45, true);
            String encoded = StateSnapshotCodec.encode(relative);
            StateSnapshot decoded = StateSnapshotCodec.decode(encoded);
            assertTrue(decoded.camPosRelativeToFocus());
            assertArrayEquals(offset, decoded.camPosJ2000());
        }

        @Test
        @DisplayName("paused and camPosRelativeToFocus both set encode independently")
        void bothFlagsSet() {
            StateSnapshot snap = new StateSnapshot(
                    0, 1, true, new double[3], new float[] {0, 0, 0, 1}, CameraFrame.INERTIAL, 10, -1, -1, 45, true);
            String encoded = StateSnapshotCodec.encode(snap);
            StateSnapshot decoded = StateSnapshotCodec.decode(encoded);
            assertTrue(decoded.paused());
            assertTrue(decoded.camPosRelativeToFocus());
        }

        @Test
        @DisplayName("all camera frames round-trip")
        void allCameraFrames() {
            for (CameraFrame frame : CameraFrame.values()) {
                StateSnapshot snap = new StateSnapshot(
                        0, 1, false, new double[3], new float[] {0, 0, 0, 1}, frame, -1, -1, -1, 45, false);
                String encoded = StateSnapshotCodec.encode(snap);
                assertEquals(frame, StateSnapshotCodec.decode(encoded).cameraFrame());
            }
        }

        @Test
        @DisplayName("negative NAIF IDs round-trip")
        void negativeNaifIds() {
            StateSnapshot snap = new StateSnapshot(
                    0, 1, false, new double[3], new float[] {0, 0, 0, 1}, CameraFrame.INERTIAL, -98, -1, -1, 45, false);
            String encoded = StateSnapshotCodec.encode(snap);
            assertEquals(-98, StateSnapshotCodec.decode(encoded).focusedBodyId());
        }

        @Test
        @DisplayName("non-identity quaternion round-trips")
        void nonIdentityQuaternion() {
            float[] q = {0.5f, -0.5f, 0.5f, 0.5f};
            StateSnapshot snap =
                    new StateSnapshot(0, 1, false, new double[3], q, CameraFrame.SYNODIC, -1, -1, -1, 45, false);
            String encoded = StateSnapshotCodec.encode(snap);
            assertArrayEquals(q, StateSnapshotCodec.decode(encoded).camOrientJ2000());
        }

        @Test
        @DisplayName("extreme ET values round-trip")
        void extremeEt() {
            double et = -1.5e10; // far past
            StateSnapshot snap = new StateSnapshot(
                    et,
                    -100,
                    false,
                    new double[] {1e15, -1e15, 0},
                    new float[] {0, 0, 0, 1},
                    CameraFrame.BODY_FIXED,
                    10,
                    399,
                    301,
                    120,
                    false);
            String encoded = StateSnapshotCodec.encode(snap);
            StateSnapshot decoded = StateSnapshotCodec.decode(encoded);
            assertEquals(et, decoded.et());
            assertEquals(-100, decoded.timeRate());
            assertEquals(120, decoded.fovDeg());
        }
    }

    @Nested
    @DisplayName("Encoded string properties")
    class EncodedProperties {

        @Test
        @DisplayName("encoded string is URL-safe Base64 without padding")
        void urlSafeNoPadding() {
            String encoded = StateSnapshotCodec.encode(sample());
            assertFalse(encoded.contains("+"), "should not contain + (not URL-safe)");
            assertFalse(encoded.contains("/"), "should not contain / (not URL-safe)");
            assertFalse(encoded.contains("="), "should not contain = (padding)");
            // Should only contain [A-Za-z0-9_-]
            assertTrue(encoded.matches("[A-Za-z0-9_-]+"), "should be URL-safe Base64");
        }

        @Test
        @DisplayName("version byte is 1")
        void versionByte() {
            assertEquals(1, StateSnapshotCodec.VERSION);
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("invalid Base64 throws IllegalArgumentException")
        void invalidBase64() {
            assertThrows(IllegalArgumentException.class, () -> StateSnapshotCodec.decode("!!!not-base64!!!"));
        }

        @Test
        @DisplayName("empty string throws IllegalArgumentException")
        void emptyString() {
            assertThrows(IllegalArgumentException.class, () -> StateSnapshotCodec.decode(""));
        }

        @Test
        @DisplayName("truncated payload throws IllegalArgumentException")
        void truncatedPayload() {
            // Encode one byte (just the version) — too short for the full payload
            String truncated = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] {1, 0, 0});
            assertThrows(IllegalArgumentException.class, () -> StateSnapshotCodec.decode(truncated));
        }

        @Test
        @DisplayName("unsupported version throws IllegalArgumentException")
        void unsupportedVersion() {
            // Version 99 does not exist
            byte[] payload = new byte[75];
            payload[0] = 99;
            String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> StateSnapshotCodec.decode(encoded));
            assertTrue(ex.getMessage().contains("version"));
        }
    }

    @Nested
    @DisplayName("StateSnapshot.capture")
    class Capture {

        @Test
        @DisplayName("captures all fields from DefaultSimulationState")
        void capturesAllFields() {
            DefaultSimulationState state = new DefaultSimulationState();
            state.setCurrentEt(1000.0);
            state.setTimeRate(10.0);
            state.setPaused(true);
            state.setCameraPositionJ2000(new double[] {1, 2, 3});
            state.setCameraOrientationJ2000(new float[] {0.1f, 0.2f, 0.3f, 0.9f});
            state.setCameraFrame(CameraFrame.SYNODIC);
            state.setFocusedBodyId(399);
            state.setTargetedBodyId(301);
            state.setSelectedBodyId(10);
            state.setFovDeg(60.0);

            StateSnapshot snap = StateSnapshot.capture(state);
            assertEquals(1000.0, snap.et());
            assertEquals(10.0, snap.timeRate());
            assertTrue(snap.paused());
            assertArrayEquals(new double[] {1, 2, 3}, snap.camPosJ2000());
            assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f, 0.9f}, snap.camOrientJ2000());
            assertEquals(CameraFrame.SYNODIC, snap.cameraFrame());
            assertEquals(399, snap.focusedBodyId());
            assertEquals(301, snap.targetedBodyId());
            assertEquals(10, snap.selectedBodyId());
            assertEquals(60.0, snap.fovDeg());
            assertFalse(snap.camPosRelativeToFocus(), "capture() always stores absolute J2000");
        }

        @Test
        @DisplayName("capture clones arrays (mutation safety)")
        void capturedArraysAreClones() {
            DefaultSimulationState state = new DefaultSimulationState();
            double[] pos = {1, 2, 3};
            float[] orient = {0, 0, 0, 1};
            state.setCameraPositionJ2000(pos);
            state.setCameraOrientationJ2000(orient);

            StateSnapshot snap = StateSnapshot.capture(state);
            pos[0] = 999;
            orient[3] = 0;

            assertEquals(1, snap.camPosJ2000()[0], "should be cloned from original");
            assertEquals(1, snap.camOrientJ2000()[3], "should be cloned from original");
        }
    }
}
