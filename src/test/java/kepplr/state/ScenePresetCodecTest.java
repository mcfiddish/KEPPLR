package kepplr.state;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import kepplr.camera.CameraFrame;
import kepplr.render.RenderQuality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link ScenePresetCodec} (SCENE-01). */
@DisplayName("ScenePresetCodec")
class ScenePresetCodecTest {

    private static ScenePreset sample() {
        Map<Integer, Boolean> bodyVis = new HashMap<>();
        bodyVis.put(399, true); // Earth
        bodyVis.put(301, true); // Moon

        Map<Integer, Boolean> labelVis = new HashMap<>();
        labelVis.put(399, true);

        Map<Integer, Boolean> trailVis = new HashMap<>();
        trailVis.put(399, true);

        Map<Integer, Double> trailDur = new HashMap<>();
        trailDur.put(399, -1.0);

        Map<Integer, Integer> trailRef = new HashMap<>();
        trailRef.put(399, -1);

        Map<String, Boolean> vecVis = new HashMap<>();
        vecVis.put("399:velocity", true);

        Map<String, Boolean> frustumVis = new HashMap<>();
        frustumVis.put("-98300", true); // NH_LORRI

        return new ScenePreset(
                1,
                4.21348864184e8, // ET: ~2015 Jul 14
                1.0, // 1x time rate
                false, // not paused
                new double[] {1.234e8, -5.678e7, 9.012e6},
                new float[] {0.0f, 0.0f, 0.0f, 1.0f},
                CameraFrame.INERTIAL,
                45.0,
                399, // Earth focused
                301, // Moon targeted
                399, // Earth selected
                bodyVis,
                labelVis,
                trailVis,
                trailDur,
                trailRef,
                vecVis,
                frustumVis,
                true, // HUD time visible
                true, // HUD info visible
                RenderQuality.HIGH,
                1920,
                1080,
                Collections.emptyMap());
    }

    @Nested
    @DisplayName("Round-trip encode/decode")
    class RoundTrip {

        @Test
        @DisplayName("all fields survive round-trip")
        void allFieldsSurvive() {
            ScenePreset original = sample();
            String encoded = ScenePresetCodec.encode(original);
            ScenePreset decoded = ScenePresetCodec.decode(encoded);

            assertEquals(original.version(), decoded.version());
            assertEquals(original.et(), decoded.et(), 1e-10);
            assertEquals(original.timeRate(), decoded.timeRate(), 1e-10);
            assertEquals(original.paused(), decoded.paused());
            assertArrayEquals(original.camPosJ2000(), decoded.camPosJ2000());
            assertEquals(original.camOrientJ2000()[0], decoded.camOrientJ2000()[0], 1e-6);
            assertEquals(original.camOrientJ2000()[1], decoded.camOrientJ2000()[1], 1e-6);
            assertEquals(original.camOrientJ2000()[2], decoded.camOrientJ2000()[2], 1e-6);
            assertEquals(original.camOrientJ2000()[3], decoded.camOrientJ2000()[3], 1e-6);
            assertEquals(original.cameraFrame(), decoded.cameraFrame());
            assertEquals(original.fovDeg(), decoded.fovDeg(), 1e-10);
            assertEquals(original.focusedBodyId(), decoded.focusedBodyId());
            assertEquals(original.targetedBodyId(), decoded.targetedBodyId());
            assertEquals(original.selectedBodyId(), decoded.selectedBodyId());
            assertEquals(original.renderQuality(), decoded.renderQuality());
            assertEquals(original.windowWidth(), decoded.windowWidth());
            assertEquals(original.windowHeight(), decoded.windowHeight());
            assertEquals(original.hudTimeVisible(), decoded.hudTimeVisible());
            assertEquals(original.hudInfoVisible(), decoded.hudInfoVisible());
        }

        @Test
        @DisplayName("body visibility map survives round-trip")
        void bodyVisibilityMap() {
            ScenePreset original = sample();
            String encoded = ScenePresetCodec.encode(original);
            ScenePreset decoded = ScenePresetCodec.decode(encoded);

            assertEquals(original.bodyVisibility(), decoded.bodyVisibility());
        }

        @Test
        @DisplayName("overlay visibility maps survive round-trip")
        void overlayVisibilityMaps() {
            ScenePreset original = sample();
            String encoded = ScenePresetCodec.encode(original);
            ScenePreset decoded = ScenePresetCodec.decode(encoded);

            assertEquals(original.labelVisibility(), decoded.labelVisibility());
            assertEquals(original.trailVisibility(), decoded.trailVisibility());
            assertEquals(original.trailDurations(), decoded.trailDurations());
            assertEquals(original.trailReferences(), decoded.trailReferences());
            assertEquals(original.vectorVisibility(), decoded.vectorVisibility());
            assertEquals(original.frustumVisibility(), decoded.frustumVisibility());
        }
    }

    @Nested
    @DisplayName("File save/load")
    class FileSaveLoad {

        @Test
        @DisplayName("file save and load preserves all fields")
        void fileSaveLoad(@TempDir Path tempDir) throws Exception {
            ScenePreset original = sample();
            Path file = tempDir.resolve("test.kepplrscene");

            ScenePresetCodec.saveToFile(original, file);
            ScenePreset loaded = ScenePresetCodec.loadFromFile(file);

            assertEquals(original.version(), loaded.version());
            assertEquals(original.et(), loaded.et(), 1e-10);
            assertEquals(original.windowWidth(), loaded.windowWidth());
        }

        @Test
        @DisplayName("loaded file has correct content")
        void loadedFileContent(@TempDir Path tempDir) throws Exception {
            Path file = tempDir.resolve("scene.kepplrscene");
            Files.writeString(
                    file,
                    "{\"version\":1,\"time\":{\"et\":421348864.184,\"timeRate\":1.0,\"paused\":false},"
                            + "\"camera\":{\"position\":[1e8,0,0],\"orientation\":[0,0,0,1],\"frame\":\"INERTIAL\",\"fovDeg\":45},"
                            + "\"bodies\":{\"focused\":399,\"targeted\":-1,\"selected\":399,\"visibility\":{}},"
                            + "\"render\":{\"quality\":\"HIGH\"},\"window\":{\"width\":1280,\"height\":720},"
                            + "\"overlays\":{\"labels\":{},\"trails\":{},\"trailDurations\":{},\"trailReferences\":{},"
                            + "\"vectors\":{},\"frustums\":{},\"hud\":{\"time\":true,\"info\":true}}}");

            ScenePreset loaded = ScenePresetCodec.loadFromFile(file);

            assertEquals(1, loaded.version());
            assertEquals(421348864.184, loaded.et(), 1e-6);
            assertEquals(1.0, loaded.timeRate(), 1e-6);
            assertFalse(loaded.paused());
            assertEquals(399, loaded.focusedBodyId());
            assertEquals(-1, loaded.targetedBodyId());
            assertEquals(399, loaded.selectedBodyId());
            assertEquals(RenderQuality.HIGH, loaded.renderQuality());
            assertEquals(1280, loaded.windowWidth());
            assertEquals(720, loaded.windowHeight());
        }
    }

    @Nested
    @DisplayName("Unknown field handling")
    class UnknownFields {

        @Test
        @DisplayName("unknown fields are preserved in round-trip")
        void unknownFieldsPreserved() {
            String json = "{\"version\":1,\"time\":{\"et\":0,\"timeRate\":1,\"paused\":false},"
                    + "\"camera\":{\"position\":[0,0,0],\"orientation\":[0,0,0,1],\"frame\":\"INERTIAL\",\"fovDeg\":45},"
                    + "\"bodies\":{\"focused\":-1,\"targeted\":-1,\"selected\":-1,\"visibility\":{}},"
                    + "\"render\":{\"quality\":\"HIGH\"},\"window\":{\"width\":1280,\"height\":720},"
                    + "\"overlays\":{\"labels\":{},\"trails\":{},\"trailDurations\":{},\"trailReferences\":{},"
                    + "\"vectors\":{},\"frustums\":{},\"hud\":{\"time\":true,\"info\":true}},"
                    + "\"futureField\":\"value123\",\"futureNumber\":42}";

            ScenePreset decoded = ScenePresetCodec.decode(json);

            assertTrue(decoded.unknownFields().containsKey("futureField"));
            assertEquals("value123", decoded.unknownFields().get("futureField"));
            assertTrue(decoded.unknownFields().containsKey("futureNumber"));
            assertEquals(42, ((Number) decoded.unknownFields().get("futureNumber")).intValue());
        }

        @Test
        @DisplayName("version field is required")
        void versionRequired() {
            String json = "{\"time\":{\"et\":0,\"timeRate\":1,\"paused\":false},"
                    + "\"camera\":{\"position\":[0,0,0],\"orientation\":[0,0,0,1],\"frame\":\"INERTIAL\",\"fovDeg\":45},"
                    + "\"bodies\":{\"focused\":-1,\"targeted\":-1,\"selected\":-1,\"visibility\":{}},"
                    + "\"render\":{\"quality\":\"HIGH\"},\"window\":{\"width\":1280,\"height\":720},"
                    + "\"overlays\":{\"labels\":{},\"trails\":{},\"trailDurations\":{},\"trailReferences\":{},"
                    + "\"vectors\":{},\"frustums\":{},\"hud\":{\"time\":true,\"info\":true}}}";

            // Should default to version 1
            ScenePreset decoded = ScenePresetCodec.decode(json);
            assertEquals(1, decoded.version());
        }
    }

    @Nested
    @DisplayName("Version handling")
    class VersionHandling {

        @Test
        @DisplayName("unsupported version throws")
        void unsupportedVersionThrows() {
            String json = "{\"version\":99,\"time\":{\"et\":0,\"timeRate\":1,\"paused\":false},"
                    + "\"camera\":{\"position\":[0,0,0],\"orientation\":[0,0,0,1],\"frame\":\"INERTIAL\",\"fovDeg\":45},"
                    + "\"bodies\":{\"focused\":-1,\"targeted\":-1,\"selected\":-1,\"visibility\":{}},"
                    + "\"render\":{\"quality\":\"HIGH\"},\"window\":{\"width\":1280,\"height\":720},"
                    + "\"overlays\":{\"labels\":{},\"trails\":{},\"trailDurations\":{},\"trailReferences\":{},"
                    + "\"vectors\":{},\"frustums\":{},\"hud\":{\"time\":true,\"info\":true}}}";

            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> ScenePresetCodec.decode(json));
            assertTrue(ex.getMessage().contains("Unsupported"));
        }
    }
}
