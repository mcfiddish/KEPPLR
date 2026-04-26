package kepplr.state;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kepplr.camera.CameraFrame;
import kepplr.render.RenderQuality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import kepplr.state.ScenePresetValidator.Severity;
import kepplr.state.ScenePresetValidator.ValidationError;

/** Unit tests for {@link ScenePresetValidator} (SCENE-02). */
@DisplayName("ScenePresetValidator")
class ScenePresetValidatorTest {

    private ScenePreset createValidPreset() {
        Map<Integer, Boolean> emptyIntBool = new HashMap<>();
        Map<Integer, Double> emptyIntDouble = new HashMap<>();
        Map<Integer, Integer> emptyIntInt = new HashMap<>();
        Map<String, Boolean> emptyStringBool = new HashMap<>();
        Map<String, Object> emptyUnknown = new HashMap<>();

        return new ScenePreset(
                1,
                4.21348864184e8, // Valid ET
                1.0, // Valid time rate
                false,
                new double[] {1e8, 0, 0},
                new float[] {0, 0, 0, 1},
                CameraFrame.INERTIAL,
                45.0, // Valid FOV
                399, // Earth
                -1,
                -1,
                emptyIntBool,
                emptyIntBool,
                emptyIntBool,
                emptyIntDouble,
                emptyIntInt,
                emptyStringBool,
                emptyStringBool,
                true,
                true,
                RenderQuality.HIGH,
                1920,
                1080,
                emptyUnknown);
    }

    @Nested
    @DisplayName("Valid preset")
    class ValidPreset {

        @Test
        @DisplayName("valid preset has no errors")
        void validPresetNoErrors() {
            ScenePreset preset = createValidPreset();
            List<ValidationError> errors = ScenePresetValidator.validate(preset);
            assertTrue(errors.isEmpty(), "Expected no errors, got: " + errors);
        }
    }

    @Nested
    @DisplayName("ET validation")
    class EtValidation {

        @Test
        @DisplayName("negative ET is an error")
        void negativeEtError() {
            Map<Integer, Boolean> emptyIntBool = new HashMap<>();
            Map<Integer, Double> emptyIntDouble = new HashMap<>();
            Map<Integer, Integer> emptyIntInt = new HashMap<>();
            Map<String, Boolean> emptyStringBool = new HashMap<>();
            Map<String, Object> emptyUnknown = new HashMap<>();

            ScenePreset preset = new ScenePreset(
                    1, -100.0, 1.0, false,
                    new double[] {0, 0, 0}, new float[] {0, 0, 0, 1},
                    CameraFrame.INERTIAL, 45.0, -1, -1, -1,
                    emptyIntBool, emptyIntBool, emptyIntBool,
                    emptyIntDouble, emptyIntInt, emptyStringBool,
                    emptyStringBool, true, true, RenderQuality.HIGH,
                    1280, 720, emptyUnknown);

            List<ValidationError> errors = ScenePresetValidator.validate(preset);
            assertTrue(errors.stream().anyMatch(e ->
                    e.field().equals("time.et") && e.severity() == Severity.ERROR));
        }

        @Test
        @DisplayName("ET beyond max is an error")
        void etBeyondMaxError() {
            Map<Integer, Boolean> emptyIntBool = new HashMap<>();
            Map<Integer, Double> emptyIntDouble = new HashMap<>();
            Map<Integer, Integer> emptyIntInt = new HashMap<>();
            Map<String, Boolean> emptyStringBool = new HashMap<>();
            Map<String, Object> emptyUnknown = new HashMap<>();

            ScenePreset preset = new ScenePreset(
                    1, 1e15, 1.0, false,
                    new double[] {0, 0, 0}, new float[] {0, 0, 0, 1},
                    CameraFrame.INERTIAL, 45.0, -1, -1, -1,
                    emptyIntBool, emptyIntBool, emptyIntBool,
                    emptyIntDouble, emptyIntInt, emptyStringBool,
                    emptyStringBool, true, true, RenderQuality.HIGH,
                    1280, 720, emptyUnknown);

            List<ValidationError> errors = ScenePresetValidator.validate(preset);
            assertTrue(errors.stream().anyMatch(e ->
                    e.field().equals("time.et") && e.severity() == Severity.ERROR));
        }
    }

    @Nested
    @DisplayName("Time rate validation")
    class TimeRateValidation {

        @Test
        @DisplayName("negative time rate is an error")
        void negativeTimeRateError() {
            Map<Integer, Boolean> emptyIntBool = new HashMap<>();
            Map<Integer, Double> emptyIntDouble = new HashMap<>();
            Map<Integer, Integer> emptyIntInt = new HashMap<>();
            Map<String, Boolean> emptyStringBool = new HashMap<>();
            Map<String, Object> emptyUnknown = new HashMap<>();

            ScenePreset preset = new ScenePreset(
                    1, 0.0, -5.0, false,
                    new double[] {0, 0, 0}, new float[] {0, 0, 0, 1},
                    CameraFrame.INERTIAL, 45.0, -1, -1, -1,
                    emptyIntBool, emptyIntBool, emptyIntBool,
                    emptyIntDouble, emptyIntInt, emptyStringBool,
                    emptyStringBool, true, true, RenderQuality.HIGH,
                    1280, 720, emptyUnknown);

            List<ValidationError> errors = ScenePresetValidator.validate(preset);
            assertTrue(errors.stream().anyMatch(e ->
                    e.field().equals("time.timeRate") && e.severity() == Severity.ERROR));
        }
    }

    @Nested
    @DisplayName("Window size validation")
    class WindowSizeValidation {

        @Test
        @DisplayName("zero width is an error")
        void zeroWidthError() {
            Map<Integer, Boolean> emptyIntBool = new HashMap<>();
            Map<Integer, Double> emptyIntDouble = new HashMap<>();
            Map<Integer, Integer> emptyIntInt = new HashMap<>();
            Map<String, Boolean> emptyStringBool = new HashMap<>();
            Map<String, Object> emptyUnknown = new HashMap<>();

            ScenePreset preset = new ScenePreset(
                    1, 0.0, 1.0, false,
                    new double[] {0, 0, 0}, new float[] {0, 0, 0, 1},
                    CameraFrame.INERTIAL, 45.0, -1, -1, -1,
                    emptyIntBool, emptyIntBool, emptyIntBool,
                    emptyIntDouble, emptyIntInt, emptyStringBool,
                    emptyStringBool, true, true, RenderQuality.HIGH,
                    0, 720, emptyUnknown);

            List<ValidationError> errors = ScenePresetValidator.validate(preset);
            assertTrue(errors.stream().anyMatch(e ->
                    e.field().equals("window.width") && e.severity() == Severity.ERROR));
        }

        @Test
        @DisplayName("negative height is an error")
        void negativeHeightError() {
            Map<Integer, Boolean> emptyIntBool = new HashMap<>();
            Map<Integer, Double> emptyIntDouble = new HashMap<>();
            Map<Integer, Integer> emptyIntInt = new HashMap<>();
            Map<String, Boolean> emptyStringBool = new HashMap<>();
            Map<String, Object> emptyUnknown = new HashMap<>();

            ScenePreset preset = new ScenePreset(
                    1, 0.0, 1.0, false,
                    new double[] {0, 0, 0}, new float[] {0, 0, 0, 1},
                    CameraFrame.INERTIAL, 45.0, -1, -1, -1,
                    emptyIntBool, emptyIntBool, emptyIntBool,
                    emptyIntDouble, emptyIntInt, emptyStringBool,
                    emptyStringBool, true, true, RenderQuality.HIGH,
                    1280, -100, emptyUnknown);

            List<ValidationError> errors = ScenePresetValidator.validate(preset);
            assertTrue(errors.stream().anyMatch(e ->
                    e.field().equals("window.height") && e.severity() == Severity.ERROR));
        }
    }

    @Nested
    @DisplayName("Camera validation")
    class CameraValidation {

        @Test
        @DisplayName("NaN camera position is an error")
        void nanPositionError() {
            Map<Integer, Boolean> emptyIntBool = new HashMap<>();
            Map<Integer, Double> emptyIntDouble = new HashMap<>();
            Map<Integer, Integer> emptyIntInt = new HashMap<>();
            Map<String, Boolean> emptyStringBool = new HashMap<>();
            Map<String, Object> emptyUnknown = new HashMap<>();

            ScenePreset preset = new ScenePreset(
                    1, 0.0, 1.0, false,
                    new double[] {Double.NaN, 0, 0}, new float[] {0, 0, 0, 1},
                    CameraFrame.INERTIAL, 45.0, -1, -1, -1,
                    emptyIntBool, emptyIntBool, emptyIntBool,
                    emptyIntDouble, emptyIntInt, emptyStringBool,
                    emptyStringBool, true, true, RenderQuality.HIGH,
                    1280, 720, emptyUnknown);

            List<ValidationError> errors = ScenePresetValidator.validate(preset);
            assertTrue(errors.stream().anyMatch(e ->
                    e.field().equals("camera.position") && e.severity() == Severity.ERROR));
        }

        @Test
        @DisplayName("infinite camera position is an error")
        void infinitePositionError() {
            Map<Integer, Boolean> emptyIntBool = new HashMap<>();
            Map<Integer, Double> emptyIntDouble = new HashMap<>();
            Map<Integer, Integer> emptyIntInt = new HashMap<>();
            Map<String, Boolean> emptyStringBool = new HashMap<>();
            Map<String, Object> emptyUnknown = new HashMap<>();

            ScenePreset preset = new ScenePreset(
                    1, 0.0, 1.0, false,
                    new double[] {Double.POSITIVE_INFINITY, 0, 0}, new float[] {0, 0, 0, 1},
                    CameraFrame.INERTIAL, 45.0, -1, -1, -1,
                    emptyIntBool, emptyIntBool, emptyIntBool,
                    emptyIntDouble, emptyIntInt, emptyStringBool,
                    emptyStringBool, true, true, RenderQuality.HIGH,
                    1280, 720, emptyUnknown);

            List<ValidationError> errors = ScenePresetValidator.validate(preset);
            assertTrue(errors.stream().anyMatch(e ->
                    e.field().equals("camera.position") && e.severity() == Severity.ERROR));
        }
    }

    @Nested
    @DisplayName("Version validation")
    class VersionValidation {

        @Test
        @DisplayName("wrong version is an error")
        void wrongVersionError() {
            Map<Integer, Boolean> emptyIntBool = new HashMap<>();
            Map<Integer, Double> emptyIntDouble = new HashMap<>();
            Map<Integer, Integer> emptyIntInt = new HashMap<>();
            Map<String, Boolean> emptyStringBool = new HashMap<>();
            Map<String, Object> emptyUnknown = new HashMap<>();

            ScenePreset preset = new ScenePreset(
                    99, 0.0, 1.0, false,
                    new double[] {0, 0, 0}, new float[] {0, 0, 0, 1},
                    CameraFrame.INERTIAL, 45.0, -1, -1, -1,
                    emptyIntBool, emptyIntBool, emptyIntBool,
                    emptyIntDouble, emptyIntInt, emptyStringBool,
                    emptyStringBool, true, true, RenderQuality.HIGH,
                    1280, 720, emptyUnknown);

            List<ValidationError> errors = ScenePresetValidator.validate(preset);
            assertTrue(errors.stream().anyMatch(e ->
                    e.field().equals("version") && e.severity() == Severity.ERROR));
        }
    }

    @Nested
    @DisplayName("Trail duration validation")
    class TrailDurationValidation {

        @Test
        @DisplayName("negative non-default trail duration is an error")
        void negativeTrailDurationError() {
            Map<Integer, Boolean> emptyIntBool = new HashMap<>();
            Map<Integer, Double> trailDur = new HashMap<>();
            trailDur.put(399, -5.0); // -5 is invalid (only -1 means default)
            Map<Integer, Integer> emptyIntInt = new HashMap<>();
            Map<String, Boolean> emptyStringBool = new HashMap<>();
            Map<String, Object> emptyUnknown = new HashMap<>();

            ScenePreset preset = new ScenePreset(
                    1, 0.0, 1.0, false,
                    new double[] {0, 0, 0}, new float[] {0, 0, 0, 1},
                    CameraFrame.INERTIAL, 45.0, -1, -1, -1,
                    emptyIntBool, emptyIntBool, emptyIntBool,
                    trailDur, emptyIntInt, emptyStringBool,
                    emptyStringBool, true, true, RenderQuality.HIGH,
                    1280, 720, emptyUnknown);

            List<ValidationError> errors = ScenePresetValidator.validate(preset);
            assertTrue(errors.stream().anyMatch(e ->
                    e.field().startsWith("overlays.trailDurations") && e.severity() == Severity.ERROR));
        }

        @Test
        @DisplayName("default trail duration (-1) is valid")
        void defaultTrailDurationValid() {
            Map<Integer, Boolean> emptyIntBool = new HashMap<>();
            Map<Integer, Double> trailDur = new HashMap<>();
            trailDur.put(399, -1.0); // -1 means default, which is valid
            Map<Integer, Integer> emptyIntInt = new HashMap<>();
            Map<String, Boolean> emptyStringBool = new HashMap<>();
            Map<String, Object> emptyUnknown = new HashMap<>();

            ScenePreset preset = new ScenePreset(
                    1, 0.0, 1.0, false,
                    new double[] {0, 0, 0}, new float[] {0, 0, 0, 1},
                    CameraFrame.INERTIAL, 45.0, -1, -1, -1,
                    emptyIntBool, emptyIntBool, emptyIntBool,
                    trailDur, emptyIntInt, emptyStringBool,
                    emptyStringBool, true, true, RenderQuality.HIGH,
                    1280, 720, emptyUnknown);

            List<ValidationError> errors = ScenePresetValidator.validate(preset);
            assertTrue(errors.stream().noneMatch(e -> e.field().startsWith("overlays.trailDurations")));
        }
    }

    @Nested
    @DisplayName("Null preset handling")
    class NullPresetHandling {

        @Test
        @DisplayName("null preset returns error")
        void nullPresetError() {
            List<ValidationError> errors = ScenePresetValidator.validate(null);
            assertFalse(errors.isEmpty());
            assertEquals("", errors.get(0).field());
            assertEquals("Scene preset is null", errors.get(0).message());
            assertEquals(Severity.ERROR, errors.get(0).severity());
        }
    }
}