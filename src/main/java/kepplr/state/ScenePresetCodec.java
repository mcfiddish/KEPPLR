package kepplr.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import kepplr.camera.CameraFrame;
import kepplr.render.RenderQuality;

/**
 * Encodes and decodes {@link ScenePreset} as JSON (SCENE-01).
 *
 * <h3>Version 1 JSON format</h3>
 *
 * <pre>
 * {
 *   "version": 1,
 *   "time": { "et": 421348864.184, "timeRate": 1.0, "paused": false },
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
 *     "visibility": {"399": true, "301": true}
 *   },
 *   "render": { "quality": "HIGH" },
 *   "window": { "width": 1920, "height": 1080 },
 *   "overlays": {
 *     "labels": {"399": true, "301": false},
 *     "trails": {"399": true},
 *     "trailDurations": {"399": -1.0},
 *     "trailReferences": {"399": -1},
 *     "vectors": {"399:velocity": true},
 *     "frustums": {"-98300": true},
 *     "hud": {"time": true, "info": true}
 *   },
 *   "unknownFields": {}
 * }
 * </pre>
 *
 * <p>Unknown fields in the input JSON are preserved in the {@code unknownFields} map of the returned ScenePreset,
 * allowing forward compatibility (SCENE-01).
 */
public final class ScenePresetCodec {

    /** Current format version. */
    public static final int CURRENT_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private ScenePresetCodec() {}

    /**
     * Encode a scene preset to a JSON string.
     *
     * @param preset the preset to encode
     * @return JSON string representation
     */
    public static String encode(ScenePreset preset) {
        ObjectNode root = MAPPER.createObjectNode();

        // Version
        root.put("version", preset.version());

        // Time section
        ObjectNode timeNode = root.putObject("time");
        timeNode.put("et", preset.et());
        timeNode.put("timeRate", preset.timeRate());
        timeNode.put("paused", preset.paused());

        // Camera section
        ObjectNode cameraNode = root.putObject("camera");
        cameraNode.put("position", MAPPER.valueToTree(preset.camPosJ2000()));
        cameraNode.put("orientation", MAPPER.valueToTree(preset.camOrientJ2000()));
        cameraNode.put("frame", preset.cameraFrame().name());
        cameraNode.put("fovDeg", preset.fovDeg());

        // Bodies section
        ObjectNode bodiesNode = root.putObject("bodies");
        bodiesNode.put("focused", preset.focusedBodyId());
        bodiesNode.put("targeted", preset.targetedBodyId());
        bodiesNode.put("selected", preset.selectedBodyId());
        ObjectNode bodyVisNode = bodiesNode.putObject("visibility");
        for (var entry : preset.bodyVisibility().entrySet()) {
            bodyVisNode.put(String.valueOf(entry.getKey()), entry.getValue());
        }

        // Render section
        ObjectNode renderNode = root.putObject("render");
        renderNode.put("quality", preset.renderQuality().name());

        // Window section
        ObjectNode windowNode = root.putObject("window");
        windowNode.put("width", preset.windowWidth());
        windowNode.put("height", preset.windowHeight());

        // Overlays section
        ObjectNode overlaysNode = root.putObject("overlays");

        ObjectNode labelsNode = overlaysNode.putObject("labels");
        for (var entry : preset.labelVisibility().entrySet()) {
            labelsNode.put(String.valueOf(entry.getKey()), entry.getValue());
        }

        ObjectNode trailsNode = overlaysNode.putObject("trails");
        for (var entry : preset.trailVisibility().entrySet()) {
            trailsNode.put(String.valueOf(entry.getKey()), entry.getValue());
        }

        ObjectNode trailDurNode = overlaysNode.putObject("trailDurations");
        for (var entry : preset.trailDurations().entrySet()) {
            trailDurNode.put(String.valueOf(entry.getKey()), entry.getValue());
        }

        ObjectNode trailRefNode = overlaysNode.putObject("trailReferences");
        for (var entry : preset.trailReferences().entrySet()) {
            trailRefNode.put(String.valueOf(entry.getKey()), entry.getValue());
        }

        ObjectNode vectorsNode = overlaysNode.putObject("vectors");
        for (var entry : preset.vectorVisibility().entrySet()) {
            vectorsNode.put(entry.getKey(), entry.getValue());
        }

        ObjectNode frustumsNode = overlaysNode.putObject("frustums");
        for (var entry : preset.frustumVisibility().entrySet()) {
            frustumsNode.put(entry.getKey(), entry.getValue());
        }

        ObjectNode hudNode = overlaysNode.putObject("hud");
        hudNode.put("time", preset.hudTimeVisible());
        hudNode.put("info", preset.hudInfoVisible());

        // Unknown fields
        if (preset.unknownFields() != null && !preset.unknownFields().isEmpty()) {
            root.put("unknownFields", MAPPER.valueToTree(preset.unknownFields()));
        }

        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode scene preset", e);
        }
    }

    /**
     * Decode a JSON string to a scene preset.
     *
     * <p>Unknown fields in the JSON are preserved in the returned preset's unknownFields map for forward compatibility.
     *
     * @param json the JSON string
     * @return the decoded ScenePreset
     * @throws IllegalArgumentException if the JSON is malformed or has an unsupported version
     */
    public static ScenePreset decode(String json) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(json);

            // Check version
            int version = root.has("version") ? root.get("version").asInt() : 1;
            if (version != CURRENT_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported scene preset version: " + version + " (expected " + CURRENT_VERSION + ")");
            }

            // Parse time section
            ObjectNode timeNode = (ObjectNode) root.get("time");
            double et = timeNode != null ? timeNode.get("et").asDouble() : 0.0;
            double timeRate = timeNode != null && timeNode.has("timeRate")
                    ? timeNode.get("timeRate").asDouble()
                    : 1.0;
            boolean paused = timeNode != null
                    && timeNode.has("paused")
                    && timeNode.get("paused").asBoolean();

            // Parse camera section
            ObjectNode cameraNode = (ObjectNode) root.get("camera");
            double[] camPos = parseDoubleArray(cameraNode, "position", 3);
            float[] camOrient = parseFloatArray(cameraNode, "orientation", 4);
            CameraFrame frame = CameraFrame.INERTIAL;
            if (cameraNode != null && cameraNode.has("frame")) {
                try {
                    frame = CameraFrame.valueOf(cameraNode.get("frame").asText());
                } catch (IllegalArgumentException e) {
                    // keep default
                }
            }
            double fovDeg = cameraNode != null && cameraNode.has("fovDeg")
                    ? cameraNode.get("fovDeg").asDouble()
                    : 45.0;

            // Parse bodies section
            ObjectNode bodiesNode = (ObjectNode) root.get("bodies");
            int focused = bodiesNode != null && bodiesNode.has("focused")
                    ? bodiesNode.get("focused").asInt()
                    : -1;
            int targeted = bodiesNode != null && bodiesNode.has("targeted")
                    ? bodiesNode.get("targeted").asInt()
                    : -1;
            int selected = bodiesNode != null && bodiesNode.has("selected")
                    ? bodiesNode.get("selected").asInt()
                    : -1;
            Map<Integer, Boolean> bodyVis = parseIntToBooleanMap(bodiesNode, "visibility");

            // Parse render section
            ObjectNode renderNode = (ObjectNode) root.get("render");
            RenderQuality quality = RenderQuality.HIGH;
            if (renderNode != null && renderNode.has("quality")) {
                try {
                    quality = RenderQuality.valueOf(renderNode.get("quality").asText());
                } catch (IllegalArgumentException e) {
                    // keep default
                }
            }

            // Parse window section
            ObjectNode windowNode = (ObjectNode) root.get("window");
            int width = windowNode != null && windowNode.has("width")
                    ? windowNode.get("width").asInt()
                    : 1280;
            int height = windowNode != null && windowNode.has("height")
                    ? windowNode.get("height").asInt()
                    : 720;

            // Parse overlays section
            ObjectNode overlaysNode = (ObjectNode) root.get("overlays");

            Map<Integer, Boolean> labelVis = parseIntToBooleanMap(overlaysNode, "labels");
            Map<Integer, Boolean> trailVis = parseIntToBooleanMap(overlaysNode, "trails");
            Map<Integer, Double> trailDur = parseIntToDoubleMap(overlaysNode, "trailDurations");
            Map<Integer, Integer> trailRef = parseIntToIntegerMap(overlaysNode, "trailReferences");
            Map<String, Boolean> vecVis = parseStringToBooleanMap(overlaysNode, "vectors");
            Map<String, Boolean> frustumVis = parseStringToBooleanMap(overlaysNode, "frustums");

            boolean hudTime = true;
            boolean hudInfo = true;
            if (overlaysNode != null) {
                ObjectNode hudNode = (ObjectNode) overlaysNode.get("hud");
                if (hudNode != null) {
                    if (hudNode.has("time")) {
                        hudTime = hudNode.get("time").asBoolean();
                    }
                    if (hudNode.has("info")) {
                        hudInfo = hudNode.get("info").asBoolean();
                    }
                }
            }

            // Collect unknown fields
            Map<String, Object> unknownFields = new HashMap<>();
            if (root.has("unknownFields")) {
                ObjectNode unknownNode = (ObjectNode) root.get("unknownFields");
                unknownNode
                        .fields()
                        .forEachRemaining(entry ->
                                unknownFields.put(entry.getKey(), MAPPER.convertValue(entry.getValue(), Object.class)));
            }
            // Also capture any top-level fields we don't understand
            String[] knownTopLevel = {
                "version", "time", "camera", "bodies", "render", "window", "overlays", "unknownFields"
            };
            root.fields().forEachRemaining(entry -> {
                boolean known = false;
                for (String knownField : knownTopLevel) {
                    if (knownField.equals(entry.getKey())) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    unknownFields.put(entry.getKey(), MAPPER.convertValue(entry.getValue(), Object.class));
                }
            });

            return new ScenePreset(
                    version,
                    et,
                    timeRate,
                    paused,
                    camPos,
                    camOrient,
                    frame,
                    fovDeg,
                    focused,
                    targeted,
                    selected,
                    bodyVis,
                    labelVis,
                    trailVis,
                    trailDur,
                    trailRef,
                    vecVis,
                    frustumVis,
                    hudTime,
                    hudInfo,
                    quality,
                    width,
                    height,
                    unknownFields);

        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid scene preset JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Save a scene preset to a file.
     *
     * @param preset the preset to save
     * @param path the file path
     * @throws IOException if an I/O error occurs
     */
    public static void saveToFile(ScenePreset preset, Path path) throws IOException {
        String json = encode(preset);
        Files.writeString(path, json);
    }

    /**
     * Load a scene preset from a file.
     *
     * @param path the file path
     * @return the loaded ScenePreset
     * @throws IOException if an I/O error occurs
     * @throws IllegalArgumentException if the file contains an invalid or unsupported scene preset
     */
    public static ScenePreset loadFromFile(Path path) throws IOException {
        String json = Files.readString(path);
        return decode(json);
    }

    // Helper methods for parsing

    private static double[] parseDoubleArray(ObjectNode node, String field, int expectedLength) {
        if (node == null || !node.has(field)) {
            return new double[expectedLength];
        }
        var arr = node.get(field);
        double[] result = new double[expectedLength];
        for (int i = 0; i < Math.min(arr.size(), expectedLength); i++) {
            result[i] = arr.get(i).asDouble();
        }
        return result;
    }

    private static float[] parseFloatArray(ObjectNode node, String field, int expectedLength) {
        if (node == null || !node.has(field)) {
            return new float[expectedLength];
        }
        var arr = node.get(field);
        float[] result = new float[expectedLength];
        for (int i = 0; i < Math.min(arr.size(), expectedLength); i++) {
            result[i] = (float) arr.get(i).asDouble();
        }
        return result;
    }

    private static Map<Integer, Boolean> parseIntToBooleanMap(ObjectNode parent, String field) {
        Map<Integer, Boolean> result = new HashMap<>();
        if (parent == null || !parent.has(field)) {
            return result;
        }
        ObjectNode node = (ObjectNode) parent.get(field);
        node.fields().forEachRemaining(entry -> {
            try {
                result.put(Integer.parseInt(entry.getKey()), entry.getValue().asBoolean());
            } catch (NumberFormatException e) {
                // Skip invalid keys
            }
        });
        return result;
    }

    private static Map<Integer, Double> parseIntToDoubleMap(ObjectNode parent, String field) {
        Map<Integer, Double> result = new HashMap<>();
        if (parent == null || !parent.has(field)) {
            return result;
        }
        ObjectNode node = (ObjectNode) parent.get(field);
        node.fields().forEachRemaining(entry -> {
            try {
                result.put(Integer.parseInt(entry.getKey()), entry.getValue().asDouble());
            } catch (NumberFormatException e) {
                // Skip invalid keys
            }
        });
        return result;
    }

    private static Map<Integer, Integer> parseIntToIntegerMap(ObjectNode parent, String field) {
        Map<Integer, Integer> result = new HashMap<>();
        if (parent == null || !parent.has(field)) {
            return result;
        }
        ObjectNode node = (ObjectNode) parent.get(field);
        node.fields().forEachRemaining(entry -> {
            try {
                result.put(Integer.parseInt(entry.getKey()), entry.getValue().asInt());
            } catch (NumberFormatException e) {
                // Skip invalid keys
            }
        });
        return result;
    }

    private static Map<String, Boolean> parseStringToBooleanMap(ObjectNode parent, String field) {
        Map<String, Boolean> result = new HashMap<>();
        if (parent == null || !parent.has(field)) {
            return result;
        }
        ObjectNode node = (ObjectNode) parent.get(field);
        node.fields()
                .forEachRemaining(
                        entry -> result.put(entry.getKey(), entry.getValue().asBoolean()));
        return result;
    }
}
