package kepplr.state;

import java.util.ArrayList;
import java.util.List;
import kepplr.camera.CameraFrame;
import kepplr.render.RenderQuality;

/**
 * Validates a {@link ScenePreset} and returns field-specific validation errors (SCENE-02).
 *
 * <p>Validation ensures that invalid scene files do not leave the application in a partially applied state. All
 * validation errors are collected before any state is applied — the load is atomic (all-or-nothing).
 */
public final class ScenePresetValidator {

    /** Severity level for validation errors. */
    public enum Severity {
        /** Error that must be fixed for the scene to be valid. */
        ERROR,
        /** Warning that does not prevent the scene from being loaded. */
        WARNING
    }

    /**
     * A validation error with field name, message, and severity.
     *
     * @param field the field that failed validation (JSON path style, e.g., "time.et")
     * @param message human-readable error message
     * @param severity error or warning
     */
    public record ValidationError(String field, String message, Severity severity) {}

    private static final double MIN_ET = 0.0;
    private static final double MAX_ET = 1e12; // Way beyond any reasonable solar system time
    private static final double MIN_TIME_RATE = 0.0;
    private static final double MAX_TIME_RATE = 1e9;
    private static final double MIN_FOV_DEG = 1.0;
    private static final double MAX_FOV_DEG = 180.0;
    private static final int MIN_WINDOW_SIZE = 100;
    private static final int MAX_WINDOW_SIZE = 10000;

    private ScenePresetValidator() {}

    /**
     * Validate a scene preset.
     *
     * @param preset the preset to validate
     * @return list of validation errors (empty if valid)
     */
    public static List<ValidationError> validate(ScenePreset preset) {
        List<ValidationError> errors = new ArrayList<>();

        if (preset == null) {
            errors.add(new ValidationError("", "Scene preset is null", Severity.ERROR));
            return errors;
        }

        // Version check
        if (preset.version() != ScenePreset.CURRENT_VERSION) {
            errors.add(new ValidationError(
                    "version",
                    "Unsupported version: " + preset.version() + " (expected " + ScenePreset.CURRENT_VERSION + ")",
                    Severity.ERROR));
        }

        // Time validation
        validateTime(preset, errors);

        // Camera validation
        validateCamera(preset, errors);

        // Bodies validation
        validateBodies(preset, errors);

        // Render validation
        validateRender(preset, errors);

        // Window validation
        validateWindow(preset, errors);

        // Overlay validation
        validateOverlays(preset, errors);

        return errors;
    }

    private static void validateTime(ScenePreset preset, List<ValidationError> errors) {
        // ET validation
        double et = preset.et();
        if (et < MIN_ET || et > MAX_ET) {
            errors.add(new ValidationError(
                    "time.et", "ET must be in range [" + MIN_ET + ", " + MAX_ET + "], got: " + et, Severity.ERROR));
        }

        // Time rate validation
        double timeRate = preset.timeRate();
        if (timeRate < MIN_TIME_RATE || timeRate > MAX_TIME_RATE) {
            errors.add(new ValidationError(
                    "time.timeRate",
                    "Time rate must be in range [" + MIN_TIME_RATE + ", " + MAX_TIME_RATE + "], got: " + timeRate,
                    Severity.ERROR));
        }
    }

    private static void validateCamera(ScenePreset preset, List<ValidationError> errors) {
        // Camera position validation
        double[] pos = preset.camPosJ2000();
        if (pos == null || pos.length != 3) {
            errors.add(new ValidationError(
                    "camera.position", "Camera position must be a 3-element array", Severity.ERROR));
        } else {
            if (Double.isNaN(pos[0]) || Double.isNaN(pos[1]) || Double.isNaN(pos[2])) {
                errors.add(
                        new ValidationError("camera.position", "Camera position contains NaN values", Severity.ERROR));
            }
            if (Double.isInfinite(pos[0]) || Double.isInfinite(pos[1]) || Double.isInfinite(pos[2])) {
                errors.add(new ValidationError(
                        "camera.position", "Camera position contains infinite values", Severity.ERROR));
            }
        }

        // Camera orientation validation
        float[] orient = preset.camOrientJ2000();
        if (orient == null || orient.length != 4) {
            errors.add(new ValidationError(
                    "camera.orientation", "Camera orientation must be a 4-element array", Severity.ERROR));
        } else {
            // Check for NaN/Inf
            for (int i = 0; i < 4; i++) {
                if (Float.isNaN(orient[i]) || Float.isInfinite(orient[i])) {
                    errors.add(new ValidationError(
                            "camera.orientation",
                            "Camera orientation contains invalid value at index " + i,
                            Severity.ERROR));
                }
            }
            // Check quaternion normalization (warning)
            float mag = orient[0] * orient[0] + orient[1] * orient[1] + orient[2] * orient[2] + orient[3] * orient[3];
            if (Math.abs(mag - 1.0f) > 0.01f) {
                errors.add(new ValidationError(
                        "camera.orientation",
                        "Camera orientation quaternion is not normalized (magnitude: " + mag + ")",
                        Severity.WARNING));
            }
        }

        // Camera frame validation
        CameraFrame frame = preset.cameraFrame();
        if (frame == null) {
            errors.add(new ValidationError("camera.frame", "Camera frame is null", Severity.ERROR));
        }

        // FOV validation
        double fov = preset.fovDeg();
        if (fov < MIN_FOV_DEG || fov > MAX_FOV_DEG) {
            errors.add(new ValidationError(
                    "camera.fovDeg",
                    "FOV must be in range [" + MIN_FOV_DEG + ", " + MAX_FOV_DEG + "], got: " + fov,
                    Severity.ERROR));
        }
    }

    private static void validateBodies(ScenePreset preset, List<ValidationError> errors) {
        // Validate body IDs are reasonable NAIF IDs
        int[] bodyIds = {preset.focusedBodyId(), preset.targetedBodyId(), preset.selectedBodyId()};
        String[] fieldNames = {"bodies.focused", "bodies.targeted", "bodies.selected"};

        for (int i = 0; i < bodyIds.length; i++) {
            int id = bodyIds[i];
            if (id != -1 && (id < -1000000000 || id > 1000000000)) {
                // Warning for unusual NAIF IDs, but not an error
                errors.add(new ValidationError(fieldNames[i], "Unusual NAIF ID: " + id, Severity.WARNING));
            }
        }

        // Validate body visibility map keys
        if (preset.bodyVisibility() != null) {
            for (var entry : preset.bodyVisibility().entrySet()) {
                int naifId = entry.getKey();
                if (naifId != -1 && (naifId < -1000000000 || naifId > 1000000000)) {
                    errors.add(new ValidationError(
                            "bodies.visibility." + naifId, "Unusual NAIF ID in body visibility map", Severity.WARNING));
                }
            }
        }
    }

    private static void validateRender(ScenePreset preset, List<ValidationError> errors) {
        RenderQuality quality = preset.renderQuality();
        if (quality == null) {
            errors.add(new ValidationError("render.quality", "Render quality is null", Severity.ERROR));
        }
    }

    private static void validateWindow(ScenePreset preset, List<ValidationError> errors) {
        int width = preset.windowWidth();
        int height = preset.windowHeight();

        if (width < MIN_WINDOW_SIZE || width > MAX_WINDOW_SIZE) {
            errors.add(new ValidationError(
                    "window.width",
                    "Window width must be in range [" + MIN_WINDOW_SIZE + ", " + MAX_WINDOW_SIZE + "], got: " + width,
                    Severity.ERROR));
        }

        if (height < MIN_WINDOW_SIZE || height > MAX_WINDOW_SIZE) {
            errors.add(new ValidationError(
                    "window.height",
                    "Window height must be in range [" + MIN_WINDOW_SIZE + ", " + MAX_WINDOW_SIZE + "], got: " + height,
                    Severity.ERROR));
        }
    }

    private static void validateOverlays(ScenePreset preset, List<ValidationError> errors) {
        // Validate trail durations are reasonable
        if (preset.trailDurations() != null) {
            for (var entry : preset.trailDurations().entrySet()) {
                double duration = entry.getValue();
                // -1 is allowed (means default)
                if (duration != -1 && duration < 0) {
                    errors.add(new ValidationError(
                            "overlays.trailDurations." + entry.getKey(),
                            "Trail duration must be >= 0 or -1 (default), got: " + duration,
                            Severity.ERROR));
                }
                if (duration > 1e9) {
                    errors.add(new ValidationError(
                            "overlays.trailDurations." + entry.getKey(),
                            "Trail duration exceeds maximum reasonable value: " + duration,
                            Severity.WARNING));
                }
            }
        }

        // Validate trail reference body IDs
        if (preset.trailReferences() != null) {
            for (var entry : preset.trailReferences().entrySet()) {
                int refBody = entry.getValue();
                // -1 is allowed (means auto)
                if (refBody != -1 && (refBody < -1000000000 || refBody > 1000000000)) {
                    errors.add(new ValidationError(
                            "overlays.trailReferences." + entry.getKey(),
                            "Unusual reference body NAIF ID: " + refBody,
                            Severity.WARNING));
                }
            }
        }
    }
}
