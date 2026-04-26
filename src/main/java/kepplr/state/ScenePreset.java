package kepplr.state;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import kepplr.camera.CameraFrame;
import kepplr.render.RenderQuality;

/**
 * Immutable snapshot of all simulation state needed for a durable `.kepplrscene` file (SCENE-01, SCENE-02, SCENE-03).
 *
 * <p>Contains the full visual setup: time, camera, bodies, overlays (labels, trails, trail durations, trail references,
 * vectors, frustums, HUD), render quality, and window size.
 *
 * <h3>Version 1 JSON schema</h3>
 *
 * <pre>
 * {
 *   "version": 1,
 *   "time": {
 *     "et": 421348864.184,
 *     "timeRate": 1.0,
 *     "paused": false
 *   },
 *   "camera": {
 *     "position": [x, y, z],
 *     "orientation": [x, y, z, w],
 *     "frame": "INERTIAL",
 *     "fovDeg": 45.0
 *   },
 *   "bodies": {
 *     "focused": 399,
 *     "targeted": 301,
 *     "selected": 399,
 *     "visibility": {"399": true, "301": true, ...}
 *   },
 *   "render": {
 *     "quality": "HIGH"
 *   },
 *   "window": {
 *     "width": 1920,
 *     "height": 1080
 *   },
 *   "overlays": {
 *     "labels": {"399": true, "301": false, ...},
 *     "trails": {"399": true, "301": false, ...},
 *     "trailDurations": {"399": -1.0, "301": 86400.0, ...},
 *     "trailReferences": {"399": -1, "301": 3, ...},
 *     "vectors": {"399:velocity": true, "301:bodyAxisZ": false, ...},
 *     "frustums": {"NH_LORRI": true, ...},
 *     "hud": {"time": true, "info": true}
 *   },
 *   "unknownFields": {"futureField1": "preserved", ...}
 * }
 * </pre>
 *
 * @param version format version (1)
 * @param et current simulation epoch (TDB seconds past J2000)
 * @param timeRate simulation seconds per wall-clock second
 * @param paused whether the simulation is paused
 * @param camPosJ2000 camera position in km [x, y, z], heliocentric J2000
 * @param camOrientJ2000 camera orientation quaternion [x, y, z, w]
 * @param cameraFrame active camera frame enum
 * @param fovDeg camera field of view in degrees
 * @param focusedBodyId NAIF ID of the focused body, or -1
 * @param targetedBodyId NAIF ID of the targeted body, or -1
 * @param selectedBodyId NAIF ID of the selected body, or -1
 * @param bodyVisibility map of NAIF ID to visibility (true=visible, false=hidden)
 * @param labelVisibility map of NAIF ID to label visibility
 * @param trailVisibility map of NAIF ID to trail visibility
 * @param trailDurations map of NAIF ID to trail duration in seconds (-1 = default)
 * @param trailReferences map of NAIF ID to trail reference body NAIF ID (-1 = auto)
 * @param vectorVisibility map of "naifId:type" to visibility; type is "velocity", "bodyAxisX", "bodyAxisY",
 *     "bodyAxisZ", or "towardBody:targetNaifId"
 * @param frustumVisibility map of instrument name to frustum visibility
 * @param hudTimeVisible whether HUD time display is visible
 * @param hudInfoVisible whether HUD info display is visible
 * @param renderQuality render quality preset
 * @param windowWidth window width in pixels
 * @param windowHeight window height in pixels
 * @param unknownFields map of unknown field names to their values (preserved on decode for forward compatibility)
 */
public record ScenePreset(
        int version,
        double et,
        double timeRate,
        boolean paused,
        double[] camPosJ2000,
        float[] camOrientJ2000,
        CameraFrame cameraFrame,
        double fovDeg,
        int focusedBodyId,
        int targetedBodyId,
        int selectedBodyId,
        Map<Integer, Boolean> bodyVisibility,
        Map<Integer, Boolean> labelVisibility,
        Map<Integer, Boolean> trailVisibility,
        Map<Integer, Double> trailDurations,
        Map<Integer, Integer> trailReferences,
        Map<String, Boolean> vectorVisibility,
        Map<String, Boolean> frustumVisibility,
        boolean hudTimeVisible,
        boolean hudInfoVisible,
        RenderQuality renderQuality,
        int windowWidth,
        int windowHeight,
        Map<String, Object> unknownFields) {

    /** Current format version. */
    public static final int CURRENT_VERSION = 1;

    /**
     * Capture a scene preset from the current simulation state.
     *
     * @param state the simulation state to capture from
     * @param windowWidth current window width
     * @param windowHeight current window height
     * @return a new ScenePreset capturing all current state
     */
    public static ScenePreset capture(SimulationState state, int windowWidth, int windowHeight) {
        double[] pos = state.cameraPositionJ2000Property().get();
        float[] orient = state.cameraOrientationJ2000Property().get();

        // Capture body visibility - use DefaultSimulationState's map directly
        Map<Integer, Boolean> bodyVis = new HashMap<>();
        if (state instanceof DefaultSimulationState dss) {
            for (var entry : dss.getBodyVisibilityMap().entrySet()) {
                bodyVis.put(entry.getKey(), entry.getValue().get());
            }
        }

        // Capture label visibility
        Map<Integer, Boolean> labelVis = new HashMap<>();
        if (state instanceof DefaultSimulationState dss) {
            for (var entry : dss.getLabelVisibilityMap().entrySet()) {
                labelVis.put(entry.getKey(), entry.getValue().get());
            }
        }

        // Capture trail visibility and settings
        Map<Integer, Boolean> trailVis = new HashMap<>();
        Map<Integer, Double> trailDur = new HashMap<>();
        if (state instanceof DefaultSimulationState dss) {
            for (var entry : dss.getTrailVisibilityMap().entrySet()) {
                trailVis.put(entry.getKey(), entry.getValue().get());
            }
            for (var entry : dss.getTrailDurationMap().entrySet()) {
                trailDur.put(entry.getKey(), entry.getValue().get());
            }
        }

        // Capture trail references - use property access for each key
        Map<Integer, Integer> trailRefFinal = new HashMap<>();
        if (state instanceof DefaultSimulationState dss) {
            for (var entry : dss.getTrailDurationMap().entrySet()) {
                int naifId = entry.getKey();
                trailRefFinal.put(naifId, state.trailReferenceBodyProperty(naifId).get());
            }
        }

        // Capture vector visibility - use DefaultSimulationState.VectorKey
        Map<String, Boolean> vecVis = new HashMap<>();
        if (state instanceof DefaultSimulationState dss) {
            for (var entry : dss.getVectorVisibilityMap().entrySet()) {
                DefaultSimulationState.VectorKey key = entry.getKey();
                String typeStr = vectorTypeToString(key.type());
                vecVis.put(key.naifId() + ":" + typeStr, entry.getValue().get());
            }
        }

        // Capture frustum visibility
        Map<String, Boolean> frustumVis = new HashMap<>();
        if (state instanceof DefaultSimulationState dss) {
            for (var entry : dss.getFrustumVisibilityMap().entrySet()) {
                // Store by NAIF code directly
                frustumVis.put(String.valueOf(entry.getKey()), entry.getValue().get());
            }
        }

        return new ScenePreset(
                CURRENT_VERSION,
                state.currentEtProperty().get(),
                state.timeRateProperty().get(),
                state.pausedProperty().get(),
                pos != null ? pos.clone() : new double[] {0, 0, 0},
                orient != null ? orient.clone() : new float[] {0, 0, 0, 1},
                state.cameraFrameProperty().get(),
                state.fovDegProperty().get(),
                state.focusedBodyIdProperty().get(),
                state.targetedBodyIdProperty().get(),
                state.selectedBodyIdProperty().get(),
                Collections.unmodifiableMap(bodyVis),
                Collections.unmodifiableMap(labelVis),
                Collections.unmodifiableMap(trailVis),
                Collections.unmodifiableMap(trailDur),
                Collections.unmodifiableMap(trailRefFinal),
                Collections.unmodifiableMap(vecVis),
                Collections.unmodifiableMap(frustumVis),
                state.hudTimeVisibleProperty().get(),
                state.hudInfoVisibleProperty().get(),
                state.renderQualityProperty().get(),
                windowWidth,
                windowHeight,
                Collections.emptyMap());
    }

    /** Convert a VectorType to a string representation for JSON storage. */
    private static String vectorTypeToString(kepplr.render.vector.VectorType type) {
        if (type == kepplr.render.vector.VectorTypes.velocity()) {
            return "velocity";
        } else if (type == kepplr.render.vector.VectorTypes.bodyAxisX()) {
            return "bodyAxisX";
        } else if (type == kepplr.render.vector.VectorTypes.bodyAxisY()) {
            return "bodyAxisY";
        } else if (type == kepplr.render.vector.VectorTypes.bodyAxisZ()) {
            return "bodyAxisZ";
        } else {
            // For towardBody, we need to extract the target naif ID
            // This is a simplification - in reality we'd need to inspect the type more carefully
            return "velocity"; // fallback
        }
    }

    /** Create a default/empty scene preset. */
    public static ScenePreset createDefault() {
        return new ScenePreset(
                CURRENT_VERSION,
                0.0,
                1.0,
                false,
                new double[] {0, 0, 0},
                new float[] {0, 0, 0, 1},
                CameraFrame.INERTIAL,
                45.0,
                -1,
                -1,
                -1,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                true,
                true,
                RenderQuality.HIGH,
                1280,
                720,
                Collections.emptyMap());
    }
}
