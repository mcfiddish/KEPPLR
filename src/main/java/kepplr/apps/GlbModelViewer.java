package kepplr.apps;

import static kepplr.render.util.GLTFUtils.readModelToBodyFixedQuatFromGlb;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingSphere;
import com.jme3.bounding.BoundingVolume;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.MatParamTexture;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.*;
import com.jme3.scene.debug.Arrow;
import com.jme3.scene.plugins.gltf.GlbLoader;
import com.jme3.scene.plugins.gltf.GltfLoader;
import com.jme3.shader.VarType;
import com.jme3.system.AppSettings;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;
import com.jme3.texture.image.ImageRaster;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import javax.imageio.ImageIO;
import kepplr.templates.KEPPLRTool;
import kepplr.util.Log4j2Configurator;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Standalone viewer for GLB models with simple keyboard rotation.
 *
 * <p><b>Important frame semantics</b>
 *
 * <ul>
 *   <li>glTF is conventionally right-handed with +Y up (-Z forward).
 *   <li>Blender is Z-up, and exporters/importers may apply a fixed basis conversion.
 *   <li>Our conversion pipeline injects a constant quaternion into the GLB JSON at: <code>
 *       asset.extras.kepplr.modelToBodyFixedQuat.value = [x,y,z,w]</code>.
 *   <li>This quaternion is intended to be applied once at runtime to map glTF model-space vectors into the
 *       "intrinsic/body-fixed" model basis used by SPICE frame definitions.
 * </ul>
 *
 * <p>Viewer goal: show axes that reflect the same transforms applied to the model, so orientation debugging is
 * trustworthy.
 */
public class GlbModelViewer implements KEPPLRTool {
    private static final Logger logger = LogManager.getLogger();

    @Override
    public String shortDescription() {
        return "View a GLB model in a standalone JME window.";
    }

    @Override
    public String fullDescription(Options options) {
        String header = "\nRender a single GLB model in a standalone JME window.";
        String footer = """
                Controls:
                  - Rotate model: Arrow keys (up, down, left, right)
                  - Toggle axes: X
                  - Zoom: Page Up / Page Down
                  - Sampler preset (QualityDefault): F6
                  - Sampler preset (NoMipmapsDebug): F7
                  - Sampler preset (NearestDebug): F8
                  - Sampler preset (previous/next): 9 / 0
                  - UV debug mode cycle: U
                  - UV transform mode cycle: G
                  - Atlas tile select U (dec/inc): J / L
                  - Atlas tile select V (inc/dec): I / K
                  - Debug tile tint toggle: T
                  - Unlit albedo debug: Y
                  - Dump materials/textures: P
                  - Cycle isolate geometry: 8
                  - Clear isolation: 7
                  - Dump albedo textures: 6
                  - Dump all PBR textures: 5
                  - Albedo sampling (nearest): ,
                  - Albedo sampling (linear): .
                  - Dump isolated provenance: O
                """;
        return KEPPLRTool.super.fullDescription(options, header, footer);
    }

    private static Options defineOptions() {
        Options options = KEPPLRTool.defineOptions();
        options.addOption(Option.builder("input")
                .hasArg()
                .required()
                .desc("Required. Path to the input GLB file.")
                .get());
        options.addOption(Option.builder()
                .longOpt("uv-verbose")
                .desc("Enable verbose UV/material debug logging.")
                .get());
        return options;
    }

    public static void main(String[] args) {
        System.setProperty("jme3.shader.debug", "true");
        Log4j2Configurator.getInstance().setLevel("com.jme3.shader", Level.INFO);
        Log4j2Configurator.getInstance().setLevel("com.jme3.material", Level.INFO);
        KEPPLRTool tool = new GlbModelViewer();
        Options options = defineOptions();
        CommandLine cl = tool.parseArgs(args, options);

        Map<MessageLabel, String> startupMessages = tool.startupMessages(cl);
        for (MessageLabel ml : startupMessages.keySet()) {
            logger.info("{} {}", ml.label, startupMessages.get(ml));
        }

        Path inputPath = Paths.get(cl.getOptionValue("input")).toAbsolutePath().normalize();
        File inputFile = inputPath.toFile();
        if (!inputFile.isFile()) {
            throw new IllegalArgumentException("Input GLB file does not exist: " + inputPath);
        }

        ViewerApp app = new ViewerApp(inputPath, cl.hasOption("uv-verbose"));
        AppSettings settings = new AppSettings(true);
        settings.setResolution(1024, 768);
        settings.setSamples(8);
        settings.setResizable(true);
        app.setShowSettings(false);
        app.setSettings(settings);
        app.start();
    }

    private static final class ViewerApp extends SimpleApplication implements AnalogListener, ActionListener {
        // JME 3.9.0 adds a protected j.u.l.Logger field to Application; redeclare here
        // so that the Log4j2 logger is used instead of the inherited JUL one.
        private static final Logger logger = LogManager.getLogger();

        private static final String ROTATE_LEFT = "RotateLeft";
        private static final String ROTATE_RIGHT = "RotateRight";
        private static final String ROTATE_UP = "RotateUp";
        private static final String ROTATE_DOWN = "RotateDown";
        private static final String TOGGLE_AXES = "ToggleAxes";
        private static final String ZOOM_IN = "ZoomIn";
        private static final String ZOOM_OUT = "ZoomOut";
        private static final String PRESET_QUALITY = "PresetQuality";
        private static final String PRESET_NOMIP = "PresetNoMip";
        private static final String PRESET_NEAREST = "PresetNearest";
        private static final String PRESET_PREV = "PresetPrev";
        private static final String PRESET_NEXT = "PresetNext";
        private static final String CYCLE_UV_DEBUG = "CycleUvDebug";
        private static final String CYCLE_UV_TRANSFORM = "CycleUvTransform";
        private static final String TILE_U_DEC = "TileUDec";
        private static final String TILE_U_INC = "TileUInc";
        private static final String TILE_V_INC = "TileVInc";
        private static final String TILE_V_DEC = "TileVDec";
        private static final String TOGGLE_UNLIT = "ToggleUnlit";
        private static final String DUMP_MATERIALS = "DumpMaterials";
        private static final String CYCLE_ISOLATE = "CycleIsolate";
        private static final String CLEAR_ISOLATION = "ClearIsolation";
        private static final String DUMP_ALBEDO = "DumpAlbedo";
        private static final String DUMP_PBR = "DumpPbr";
        private static final String SAMPLE_NEAREST = "SampleNearest";
        private static final String SAMPLE_LINEAR = "SampleLinear";
        private static final String DUMP_ISOLATED = "DumpIsolated";
        private static final String TOGGLE_TILE_TINT = "ToggleTileTint";
        private static final float ROTATE_SPEED = 1.25f;
        private static final float ZOOM_SPEED = 12f;
        private static final float UV_EPSILON = 1e-5f;
        private static final double UV_OFFSET_EPSILON = 0.02;
        private static final double UV_OFFSET_THRESHOLD = 0.95;

        private final Path modelPath;
        private final boolean initialUvVerbose;

        /**
         * Wrapper Node that always exists.
         *
         * <p>We apply rotations/transforms to this node so that:
         *
         * <ul>
         *   <li>We can attach axes regardless of whether the loaded model is a Node or Geometry.
         *   <li>Axes always inherit the exact same transform as the model (no manual sync needed).
         * </ul>
         */
        private Node modelRoot;

        private BitmapText sizeText;
        private BitmapText debugHudText;
        private boolean axesVisible = false;
        private float modelRadius = 1f;

        /**
         * Constant quaternion read from GLB metadata: asset.extras.kepplr.modelToBodyFixedQuat.value = [x,y,z,w]
         *
         * <p>This is stored in glTF quaternion order [x,y,z,w]. JME uses (x,y,z,w) too.
         */
        private Quaternion modelToBodyFixedQuat = new Quaternion(0, 0, 0, 1);

        private Node worldAxesNode;

        private float orbitDistance;
        private Quaternion orbitRot = new Quaternion(); // camera orientation around target
        private SamplerPreset samplerPreset = SamplerPreset.QUALITY_DEFAULT;
        private boolean unlitDebugEnabled = false;
        private final Map<Geometry, Material> originalMaterials = new IdentityHashMap<>();
        private final Map<Geometry, Material> renderMaterials = new IdentityHashMap<>();
        private final Map<Geometry, Material> atlasDecodeMaterials = new IdentityHashMap<>();
        private final Map<Texture, Material> unlitMaterialCache = new IdentityHashMap<>();
        private Material unlitFallbackMaterial;
        private Material unlitUvOffsetFallbackMaterial;
        private Material untexturedFallbackMaterial;
        private final Map<Texture, SamplerState> originalSamplerStates = new IdentityHashMap<>();
        private SamplingPreset currentSamplingPreset;
        private final Set<Texture> forcedWrapTextures = Collections.newSetFromMap(new IdentityHashMap<>());
        private UvDebugMode uvDebugMode = UvDebugMode.OFF;
        private Material uvDebugFractMaterial;
        private Material uvDebugCheckerMaterial;
        private Material uvDebugSanityMaterial;
        private Material uvDebugFallbackMaterial;
        private UvTransformMode uvTransformMode = UvTransformMode.ATLAS_DECODE;
        private final List<Geometry> geometryList = new ArrayList<>();
        private final Map<Geometry, String> geometryPaths = new IdentityHashMap<>();
        private int isolateIndex = -1;
        private final Set<String> dumpedTextureNames = new HashSet<>();
        private int selectedTileU = 0;
        private int selectedTileV = 0;
        private UvTransformDescriptor currentUvDescriptor;
        private boolean debugTileTintEnabled = false;
        private boolean uvVerbose = false;
        private final Map<Geometry, Integer> materialIds = new IdentityHashMap<>();
        private final Set<String> missingParamWarnings = new HashSet<>();

        private ViewerApp(Path modelPath, boolean uvVerbose) {
            this.modelPath = modelPath;
            this.initialUvVerbose = uvVerbose;
        }

        @Override
        public void simpleInitApp() {
            String parent =
                    modelPath.getParent() != null ? modelPath.getParent().toString() : ".";
            assetManager.registerLocator(parent, FileLocator.class);
            assetManager.registerLoader(GltfLoader.class, "gltf");
            assetManager.registerLoader(GlbLoader.class, "glb");

            // Read GLB JSON extras before loading the model (no third-party JSON libs required).
            modelToBodyFixedQuat = readModelToBodyFixedQuatFromGlb(modelPath);

            // Load model normally via JME glTF loader.
            /* The model loaded from GLB (may be Node or Geometry, but typed as Spatial). */
            Spatial model = assetManager.loadModel(modelPath.getFileName().toString());
            applySamplerPreset(model, samplerPreset, "initial");
            model.updateModelBound();

            // Wrap the model in a Node so we can attach debug axes and rotate everything together.
            modelRoot = new Node("modelRoot");
            modelRoot.attachChild(model);
            rootNode.attachChild(modelRoot);

            // Apply constant "glTF model-space -> intended body-fixed model basis" rotation.
            // NOTE: In the full KEPPLR pipeline, this will be composed with time-varying SPICE frame rotations.
            modelRoot.setLocalRotation(modelToBodyFixedQuat);

            centerModelRootAtOrigin();
            geometryList.clear();
            geometryPaths.clear();
            collectGeometries(modelRoot, "modelRoot");
            captureOriginalMaterials();
            forcedWrapTextures.clear();
            configureCamera();
            addLights();
            setupInput();
            updateSizeAnnotation();

            uvVerbose = initialUvVerbose;
            applyDebugMaterials();
            logUvStateChange("Startup");
            logModelToBodyDebug();
        }

        private void applySamplerPreset(Spatial spatial, SamplerPreset preset, String source) {
            if (spatial == null || preset == null) {
                return;
            }
            samplerPreset = preset;
            Set<Texture> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Texture> changed = new ArrayList<>();
            applySamplerPresetRecursive(spatial, preset, visited, changed);
            logger.info(
                    "Sampler preset {} ({}) applied: updated {} of {} baseColor textures",
                    preset.label,
                    source,
                    changed.size(),
                    visited.size());
            for (Texture texture : changed) {
                logger.debug("  {}", formatTextureSampler(texture));
            }
        }

        private void cycleSamplerPreset(int direction) {
            SamplerPreset[] presets = SamplerPreset.values();
            int index = 0;
            for (int i = 0; i < presets.length; i++) {
                if (presets[i] == samplerPreset) {
                    index = i;
                    break;
                }
            }
            int next = (index + direction) % presets.length;
            if (next < 0) {
                next += presets.length;
            }
            SamplerPreset chosen = presets[next];
            String source = direction < 0 ? "9" : "0";
            applySamplerPreset(modelRoot, chosen, source);
        }

        private void handlePrevNextKey(int direction) {
            cycleSamplerPreset(direction);
            logger.info("Key {}: Sampling preset -> {}", direction < 0 ? "9" : "0", samplerPreset.label);
        }

        private void applySamplerPresetRecursive(
                Spatial spatial, SamplerPreset preset, Set<Texture> visited, List<Texture> changed) {
            if (spatial instanceof Geometry geometry) {
                Material material = geometry.getMaterial();
                if (material != null) {
                    applySamplerToParam(material, "BaseColorMap", preset, visited, changed);
                    applySamplerToParam(material, "DiffuseMap", preset, visited, changed);
                }
                return;
            }
            if (spatial instanceof Node node) {
                for (Spatial child : node.getChildren()) {
                    applySamplerPresetRecursive(child, preset, visited, changed);
                }
            }
        }

        private void applySamplerToParam(
                Material material,
                String paramName,
                SamplerPreset preset,
                Set<Texture> visited,
                List<Texture> changed) {
            MatParamTexture param = material.getTextureParam(paramName);
            if (param == null) {
                return;
            }
            Texture texture = param.getTextureValue();
            if (texture == null) {
                return;
            }
            if (!visited.add(texture)) {
                return;
            }
            boolean updated = applySamplerPresetToTexture(texture, preset);
            if (updated) {
                changed.add(texture);
            }
        }

        private boolean applySamplerPresetToTexture(Texture texture, SamplerPreset preset) {
            boolean updated = false;
            Texture.WrapMode wrapS = texture.getWrap(Texture.WrapAxis.S);
            Texture.WrapMode wrapT = texture.getWrap(Texture.WrapAxis.T);
            if (wrapS != Texture.WrapMode.Clamp || wrapT != Texture.WrapMode.Clamp) {
                texture.setWrap(Texture.WrapMode.Clamp);
                updated = true;
            }
            if (texture.getMagFilter() != preset.magFilter) {
                texture.setMagFilter(preset.magFilter);
                updated = true;
            }
            if (texture.getMinFilter() != preset.minFilter) {
                texture.setMinFilter(preset.minFilter);
                updated = true;
            }
            if (texture.getAnisotropicFilter() != preset.anisotropy) {
                texture.setAnisotropicFilter(preset.anisotropy);
                updated = true;
            }
            if (texture.getImage() != null && texture.getImage().getColorSpace() != ColorSpace.sRGB) {
                texture.getImage().setColorSpace(ColorSpace.sRGB);
                updated = true;
            }
            return updated;
        }

        private String formatTextureSampler(Texture texture) {
            String name = texture.getName();
            if ((name == null || name.isBlank()) && texture.getKey() != null) {
                name = texture.getKey().toString();
            }
            if (name == null || name.isBlank()) {
                name = "<unnamed>";
            }
            String colorSpace = texture.getImage() != null
                    ? String.valueOf(texture.getImage().getColorSpace())
                    : "unknown";
            return String.format(
                    "Texture[%s] min=%s mag=%s wrapS=%s wrapT=%s aniso=%d colorspace=%s",
                    name,
                    texture.getMinFilter(),
                    texture.getMagFilter(),
                    texture.getWrap(Texture.WrapAxis.S),
                    texture.getWrap(Texture.WrapAxis.T),
                    texture.getAnisotropicFilter(),
                    colorSpace);
        }

        private enum SamplerPreset {
            QUALITY_DEFAULT("QualityDefault", Texture.MinFilter.Trilinear, Texture.MagFilter.Bilinear, 8),
            NO_MIPMAPS_DEBUG("NoMipmapsDebug", Texture.MinFilter.BilinearNoMipMaps, Texture.MagFilter.Bilinear, 0),
            NEAREST_DEBUG("NearestDebug", Texture.MinFilter.NearestNoMipMaps, Texture.MagFilter.Nearest, 0);

            private final String label;
            private final Texture.MinFilter minFilter;
            private final Texture.MagFilter magFilter;
            private final int anisotropy;

            SamplerPreset(String label, Texture.MinFilter minFilter, Texture.MagFilter magFilter, int anisotropy) {
                this.label = label;
                this.minFilter = minFilter;
                this.magFilter = magFilter;
                this.anisotropy = anisotropy;
            }
        }

        private void toggleUnlitDebug() {
            if (modelRoot == null) {
                return;
            }
            if (unlitDebugEnabled) {
                unlitDebugEnabled = false;
                applyDebugMaterials();
                logger.info("Unlit albedo debug disabled");
            } else {
                unlitDebugEnabled = true;
                applyDebugMaterials();
                logger.info("Unlit albedo debug enabled");
            }
        }

        private void applyUnlitAlbedoMaterials() {
            applyUnlitToSpatial(modelRoot);
        }

        private void applyDebugMaterials() {
            if (modelRoot == null) {
                return;
            }
            if (uvDebugMode != UvDebugMode.OFF) {
                applyUvDebugMaterials();
                return;
            }
            if (uvTransformMode == UvTransformMode.ATLAS_DECODE) {
                applyAtlasDecodeMaterials();
                return;
            }
            if (unlitDebugEnabled) {
                applyUnlitAlbedoMaterials();
            } else {
                restoreBaseMaterials();
            }
        }

        private void captureOriginalMaterials() {
            for (Geometry geometry : geometryList) {
                if (!originalMaterials.containsKey(geometry)) {
                    Material material = geometry.getMaterial();
                    if (material != null) {
                        originalMaterials.put(geometry, material);
                    }
                }
            }
        }

        private void applySolarArrayOverrideMaterials() {
            boolean solarArrayOverrideEnabled = false;
            if (!solarArrayOverrideEnabled) {
                renderMaterials.clear();
                return;
            }
            renderMaterials.clear();
            for (Geometry geometry : geometryList) {
                Material original = originalMaterials.get(geometry);
                if (original == null) {
                    continue;
                }
                Material cloned = original.clone();
                UvBounds bounds = computeUvBounds(geometry.getMesh());
                UvOffsetStats stats = computeUvOffsetStats(geometry.getMesh(), bounds);
                MeshStats meshStats = buildMeshStats(geometry.getMesh());
                boolean isArray = isSolarArray(geometry, bounds, stats, meshStats);
                if (!isArray) {
                    clearBaseColorTextures(cloned);
                    ensureBaseColorFactor(cloned);
                }
                geometry.setMaterial(cloned);
                renderMaterials.put(geometry, cloned);
            }
        }

        private void clearBaseColorTextures(Material material) {
            if (material == null) {
                return;
            }
            clearParamIfPresent(material, "BaseColorMap");
            clearParamIfPresent(material, "AlbedoMap");
            clearParamIfPresent(material, "ColorMap");
            clearParamIfPresent(material, "DiffuseMap");
        }

        private Material getUntexturedFallbackMaterial() {
            if (untexturedFallbackMaterial == null) {
                Material fallback = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
                fallback.setColor("Color", new ColorRGBA(0.7f, 0.7f, 0.7f, 1f));
                untexturedFallbackMaterial = fallback;
            }
            return untexturedFallbackMaterial;
        }

        private void clearParamIfPresent(Material material, String name) {
            if (material == null || material.getMaterialDef() == null) {
                return;
            }
            if (material.getMaterialDef().getMaterialParam(name) != null && material.getParam(name) != null) {
                material.clearParam(name);
            }
        }

        private void ensureBaseColorFactor(Material material) {
            if (material == null || material.getMaterialDef() == null) {
                return;
            }
            ColorRGBA baseColor = getColorParam(material, "BaseColor");
            ColorRGBA diffuse = getColorParam(material, "Diffuse");
            if (baseColor != null || diffuse != null) {
                return;
            }
            if (material.getMaterialDef().getMaterialParam("BaseColor") != null) {
                material.setColor("BaseColor", new ColorRGBA(0.7f, 0.7f, 0.7f, 1f));
            } else if (material.getMaterialDef().getMaterialParam("Diffuse") != null) {
                material.setColor("Diffuse", new ColorRGBA(0.7f, 0.7f, 0.7f, 1f));
            }
        }

        private boolean isSolarArray(Geometry geometry, UvBounds bounds, UvOffsetStats stats, MeshStats meshStats) {
            if (geometry == null || bounds == null || stats == null || meshStats == null) {
                return false;
            }
            if (meshStats.uvSets == 0) {
                return false;
            }
            if (!bounds.outOfRange) {
                return false;
            }
            double spanV = bounds.maxV - bounds.minV;
            if (spanV < 0.8 || spanV > 1.2) {
                return false;
            }
            if (stats.percentWithinEpsU < UV_OFFSET_THRESHOLD || stats.percentWithinEpsV < UV_OFFSET_THRESHOLD) {
                return false;
            }
            if (Math.abs(stats.meanOffsetU) > 0.2) {
                return false;
            }
            if (Math.abs(stats.meanOffsetV - 1.0) > 0.2) {
                return false;
            }
            double floorMinV = Math.floor(bounds.minV + UV_EPSILON);
            double ceilMaxV = Math.ceil(bounds.maxV - UV_EPSILON);
            if (!(floorMinV == 1.0 && ceilMaxV == 2.0)) {
                return false;
            }
            String name = geometry.getName() != null ? geometry.getName().toLowerCase(Locale.ROOT) : "";
            if (name.contains("array") || name.contains("solar")) {
                return true;
            }
            return true;
        }

        private void applyUvDebugMaterials() {
            for (Geometry geometry : geometryList) {
                Mesh mesh = geometry.getMesh();
                if (mesh == null || mesh.getBuffer(VertexBuffer.Type.TexCoord) == null) {
                    Material fallback = getUvDebugFallbackMaterial();
                    geometry.setMaterial(fallback);
                    logApplyMaterial(geometry, fallback);
                    logUvVerboseInfo("No UV0; UV debug unavailable for {}", geometry.getName());
                    logUvDebugMaterialAssignment(geometry, fallback, false);
                    continue;
                }
                Material original = getOriginalMaterial(geometry);
                Material debugMaterial = buildUvDebugMaterialForGeometry(geometry, original);
                geometry.setMaterial(debugMaterial);
                logApplyMaterial(geometry, debugMaterial);
                logUvDebugMaterialAssignment(geometry, debugMaterial, true);
                logUvDebugApplied(geometry, debugMaterial);
            }
            applyUvTransformToActiveMaterials();
        }

        private void applyAtlasDecodeMaterials() {
            atlasDecodeMaterials.clear();
            for (Geometry geometry : geometryList) {
                Material original = getBaseRenderMaterial(geometry);
                Mesh mesh = geometry.getMesh();
                UvBounds bounds = computeUvBounds(mesh);
                UvTileStats tiles = computeUvTileStats(mesh);
                float tilesU = tiles != null ? (float) tiles.tilesU : 1f;
                float tilesV = tiles != null ? (float) tiles.tilesV : 1f;
                Texture albedo = original != null ? findAlbedoTexture(original) : null;
                boolean hasMap = albedo != null;
                UvTransformDescriptor descriptor = buildUvTransformDescriptor(geometry);
                boolean hasUv0 = bounds != null;
                boolean baseOffset = Math.abs(descriptor.baseFloor.x) > 0.0f || Math.abs(descriptor.baseFloor.y) > 0.0f;
                boolean outOfRange = bounds != null && bounds.outOfRange;
                boolean tilesSpan = tilesU > 1f || tilesV > 1f;
                boolean eligible = hasMap && hasUv0 && (baseOffset || tilesSpan || outOfRange);
                logUvVerboseInfo(
                        "atlas_decode eligibility: geom={} tiles=({}, {}) baseFloor=({}, {}) outOfRange={} hasMap={} => {}",
                        geometry.getName(),
                        formatInt(tilesU),
                        formatInt(tilesV),
                        formatInt(descriptor.baseFloor.x),
                        formatInt(descriptor.baseFloor.y),
                        outOfRange,
                        hasMap,
                        eligible ? "override" : "keep");

                if (!eligible) {
                    if (original != null) {
                        if (!hasMap) {
                            clearBaseColorTextures(original);
                            ensureBaseColorFactor(original);
                        }
                        geometry.setMaterial(original);
                        logApplyMaterial(geometry, original);
                        logTextureOverride(geometry, original, hasMap, "none");
                    } else if (!hasMap) {
                        Material fallback = getUntexturedFallbackMaterial();
                        geometry.setMaterial(fallback);
                        logApplyMaterial(geometry, fallback);
                        logTextureOverride(geometry, fallback, false, "untextured_fallback");
                    }
                    continue;
                }

                Material material = new Material(assetManager, "assets/MatDefs/Debug/AtlasDecodeUnshaded.j3md");
                if (albedo != null) {
                    material.setTexture("AlbedoMap", albedo);
                }
                material.setColor("BaseColorFactor", resolveBaseColorFactor(original));
                applyUvTransformParams(material, descriptor);
                setMaterialBoolean(material, "DebugTileTint", debugTileTintEnabled);
                if (albedo != null) {
                    albedo.setWrap(Texture.WrapMode.Clamp);
                    forcedWrapTextures.remove(albedo);
                }
                logAtlasDecodeMaterialBinding(geometry, material, descriptor, albedo);
                logUvVerboseInfo(
                        "atlas_decode: overriding material for geom={} tiles=({}, {}) baseColorMap={}",
                        geometry.getName(),
                        formatInt(tilesU),
                        formatInt(tilesV),
                        textureKeyName(albedo));
                if (albedo != null) {
                    logUvVerboseInfo(
                            "atlas_decode: BaseColorMap wrap forced to {} / {}, forcedRepeat disabled",
                            albedo.getWrap(Texture.WrapAxis.S),
                            albedo.getWrap(Texture.WrapAxis.T));
                } else {
                    logUvVerboseInfo("atlas_decode: BaseColorMap wrap forced skipped (no map)");
                }
                copyRenderState(original, material);
                geometry.setMaterial(material);
                logApplyMaterial(geometry, material);
                logTextureOverride(geometry, material, true, "atlas_decode");
                applyAtlasDecodeParamsToGeometry(geometry);
                atlasDecodeMaterials.put(geometry, material);
            }
            applyUvTransformToActiveMaterials();
        }

        private Material buildUvDebugMaterialForGeometry(Geometry geometry, Material original) {
            Material base;
            float modeValue;
            if (uvDebugMode == UvDebugMode.FRACT) {
                if (uvDebugFractMaterial == null) {
                    uvDebugFractMaterial = buildUvDebugMaterial(0f);
                }
                base = uvDebugFractMaterial;
                modeValue = 0f;
            } else if (uvDebugMode == UvDebugMode.CHECKER) {
                if (uvDebugCheckerMaterial == null) {
                    uvDebugCheckerMaterial = buildUvDebugMaterial(1f);
                }
                base = uvDebugCheckerMaterial;
                modeValue = 1f;
            } else if (uvDebugMode == UvDebugMode.SANITY) {
                if (uvDebugSanityMaterial == null) {
                    uvDebugSanityMaterial = buildUvDebugMaterial(2f);
                }
                base = uvDebugSanityMaterial;
                modeValue = 2f;
            } else {
                return getUvDebugFallbackMaterial();
            }
            Material material = base.clone();
            UvTransformDescriptor descriptor = buildUvTransformDescriptor(geometry);
            material.setFloat("Mode", modeValue);
            applyUvTransformParams(material, descriptor);
            material.setFloat("UvOffsetEnabled", descriptor.offsetEnabled ? 1f : 0f);
            material.setVector2("UvOffset", descriptor.offset);
            if (original != null) {
                copyRenderState(original, material);
            }
            logUvDebugAtlasState(geometry, descriptor);
            return material;
        }

        private Material buildUvDebugMaterial(float mode) {
            Material material = new Material(assetManager, "assets/MatDefs/Debug/UvDebug.j3md");
            material.setFloat("Mode", mode);
            material.setInt("UvTransformMode", (int) Math.round(uvTransformMode.shaderValue));
            material.setFloat("Scale", 16f);
            material.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Off);
            return material;
        }

        private void copyRenderState(Material source, Material dest) {
            if (source == null || dest == null) {
                return;
            }
            com.jme3.material.RenderState srcState = source.getAdditionalRenderState();
            com.jme3.material.RenderState dstState = dest.getAdditionalRenderState();
            dstState.setFaceCullMode(srcState.getFaceCullMode());
            dstState.setDepthTest(srcState.isDepthTest());
            dstState.setDepthWrite(srcState.isDepthWrite());
            dstState.setBlendMode(srcState.getBlendMode());
            dstState.setWireframe(srcState.isWireframe());
            dstState.setColorWrite(srcState.isColorWrite());
        }

        private void logUvDebugMaterialAssignment(Geometry geometry, Material material, boolean hasUv0) {
            if (!uvVerbose || geometry == null || material == null) {
                return;
            }
            String defName = material.getMaterialDef() != null
                    ? material.getMaterialDef().getName()
                    : "<unknown>";
            String defPath = material.getMaterialDef() != null
                    ? material.getMaterialDef().getAssetName()
                    : "<unknown>";
            String techName = material.getActiveTechnique() != null
                            && material.getActiveTechnique().getDef() != null
                    ? material.getActiveTechnique().getDef().getName()
                    : "<none>";
            boolean hasMode = material.getParam("Mode") != null;
            Object modeValue = hasMode ? material.getParam("Mode").getValue() : null;
            boolean hasUvTransform = material.getParam("UvTransformMode") != null;
            Object uvTransformValue =
                    hasUvTransform ? material.getParam("UvTransformMode").getValue() : null;
            boolean hasUvOffset = material.getParam("UvOffset") != null;
            boolean hasUvBase = material.getParam("UvBase") != null;
            boolean hasSelectedTile = material.getParam("SelectedTile") != null;
            boolean hasTiles = material.getParam("Tiles") != null;
            String textureParams =
                    formatTextureParamPresence(material, "Texture", "ColorMap", "AlbedoMap", "BaseColorMap");
            logUvVerboseInfo(
                    "UV debug material assigned: geom={} path={} matId={} matDef={} matDefPath={} tech={} uv0={} modeParam={} modeValue={} uvTransformParam={} uvTransformValue={} uvOffsetParam={} uvBaseParam={} selectedTileParam={} tilesParam={} texParams={}",
                    geometry.getName(),
                    geometryPaths.getOrDefault(geometry, "modelRoot"),
                    System.identityHashCode(material),
                    defName,
                    defPath,
                    techName,
                    hasUv0,
                    hasMode,
                    modeValue,
                    hasUvTransform,
                    uvTransformValue,
                    hasUvOffset,
                    hasUvBase,
                    hasSelectedTile,
                    hasTiles,
                    textureParams);
            logUvVerboseInfo("UV debug material textures: setParam=<none> availableParams={}", textureParams);
        }

        private void logUvDebugApplied(Geometry geometry, Material material) {
            if (!uvVerbose || geometry == null || material == null) {
                return;
            }
            Object modeValue = material.getParam("Mode") != null
                    ? material.getParam("Mode").getValue()
                    : null;
            float scale = material.getParam("Scale") != null
                    ? (Float) material.getParam("Scale").getValue()
                    : -1f;
            Object tiles = material.getParam("Tiles") != null
                    ? material.getParam("Tiles").getValue()
                    : null;
            Object selectedTile = material.getParam("SelectedTile") != null
                    ? material.getParam("SelectedTile").getValue()
                    : null;
            logUvVerboseInfo(
                    "UV debug applied: geom={} mode={} modeValue={} scale={} uvTransform={} tiles={} selectedTile={}",
                    geometry.getName(),
                    uvDebugMode.label,
                    modeValue,
                    Float.isFinite(scale) ? String.format("%.2f", scale) : "unknown",
                    uvTransformMode.label,
                    tiles,
                    selectedTile);
        }

        private void logApplyMaterial(Geometry geometry, Material material) {
            if (!uvVerbose || geometry == null || material == null) {
                return;
            }
            Mesh mesh = geometry.getMesh();
            boolean hasTexCoord0 = mesh != null && mesh.getBuffer(VertexBuffer.Type.TexCoord) != null;
            String defName = material.getMaterialDef() != null
                    ? material.getMaterialDef().getName()
                    : "<unknown>";
            List<String> params = new ArrayList<>();
            for (var param : material.getParams()) {
                params.add(param.getName());
            }
            Collections.sort(params);
            logUvVerboseDebug(
                    "APPLY geom={} path={} uvDebug={} uvTransform={} matDef={} params={} texcoord0={}",
                    geometry.getName(),
                    geometryPaths.getOrDefault(geometry, "modelRoot"),
                    uvDebugMode.label,
                    uvTransformMode.label,
                    defName,
                    params,
                    hasTexCoord0);
        }

        private void logUvTransformParams(
                Material material, int mode, Vector2f base, Vector2f tiles, Vector2f selected) {
            if (!uvVerbose || material == null) {
                return;
            }
            boolean hasMode =
                    materialDefHasParam(material, "UvTransformMode") || materialDefHasParam(material, "UvMode");
            boolean hasBase = materialDefHasParam(material, "UvBase");
            boolean hasTiles = materialDefHasParam(material, "Tiles")
                    || materialDefHasParam(material, "TilesU")
                    || materialDefHasParam(material, "TilesV");
            boolean hasSelected = materialDefHasParam(material, "SelectedTile");
            String defName = material.getMaterialDef() != null
                    ? material.getMaterialDef().getName()
                    : "<unknown>";
            List<String> params = new ArrayList<>();
            for (var param : material.getParams()) {
                params.add(param.getName());
            }
            Collections.sort(params);
            logUvVerboseDebug(
                    "UV params set: matId={} def={} has[Mode={} Base={} Tiles={} Sel={}] mode={} base=({}, {}) tiles=({}, {}) sel=({}, {}) params={}",
                    System.identityHashCode(material),
                    defName,
                    hasMode,
                    hasBase,
                    hasTiles,
                    hasSelected,
                    mode,
                    formatInt(base.x),
                    formatInt(base.y),
                    formatInt(tiles.x),
                    formatInt(tiles.y),
                    formatInt(selected.x),
                    formatInt(selected.y),
                    params);
        }

        private void applyUvTransformToActiveMaterials() {
            if (geometryList.isEmpty()) {
                return;
            }
            currentUvDescriptor = resolveActiveUvTransformDescriptor();
            for (Geometry geometry : geometryList) {
                if (uvTransformMode == UvTransformMode.ATLAS_DECODE && uvDebugMode == UvDebugMode.OFF) {
                    ensureAtlasDecodeMaterial(geometry);
                }
                Material material = geometry.getMaterial();
                if (material == null) {
                    continue;
                }
                applyUvTransformToMaterial(geometry, material);
            }
            if (uvTransformMode == UvTransformMode.ATLAS_DECODE) {
                if (uvVerbose) {
                    logSelectedTileMaterialState("Pre-render");
                }
            }
        }

        private void applyUvTransformToMaterial(Geometry geometry, Material material) {
            if (geometry == null || material == null) {
                return;
            }
            UvTransformDescriptor descriptor = buildUvTransformDescriptor(geometry);
            applyUvTransformParams(material, descriptor);
            applyAtlasDecodeParamsToGeometry(geometry);
        }

        private void applyUvTransformParams(Material material, UvTransformDescriptor descriptor) {
            if (material == null || descriptor == null) {
                return;
            }
            int mode = (int) Math.round(descriptor.mode.shaderValue);
            Vector2f tiles = new Vector2f(Math.max(1f, descriptor.tiles.x), Math.max(1f, descriptor.tiles.y));
            Vector2f base = descriptor.baseFloor;
            if (descriptor.mode == UvTransformMode.OFFSET_ONLY) {
                base = descriptor.offset;
            }
            setMaterialInt(material, "UvTransformMode", mode);
            setMaterialFloat(material, "UvMode", descriptor.mode.shaderValue);
            setMaterialVector2(material, "UvBase", base);
            setMaterialVector2(material, "Tiles", tiles);
            setMaterialFloat(material, "TilesU", tiles.x);
            setMaterialFloat(material, "TilesV", tiles.y);
            setMaterialVector2(material, "SelectedTile", descriptor.selectedTile);
            setMaterialBoolean(material, "DebugTileTint", debugTileTintEnabled);
            logUvTransformParams(material, mode, base, tiles, descriptor.selectedTile);
        }

        private void setMaterialInt(Material material, String name, int value) {
            if (!materialDefHasParam(material, name)) {
                logMissingParamOnce(material, name);
                return;
            }
            VarType defType = getDefVarType(material, name);
            if (defType == VarType.Int) {
                material.setInt(name, value);
            } else if (defType == VarType.Float) {
                material.setFloat(name, value);
            }
        }

        private void setMaterialFloat(Material material, String name, float value) {
            if (!materialDefHasParam(material, name)) {
                logMissingParamOnce(material, name);
                return;
            }
            VarType defType = getDefVarType(material, name);
            if (defType == VarType.Float) {
                material.setFloat(name, value);
            } else if (defType == VarType.Int) {
                material.setInt(name, Math.round(value));
            }
        }

        private void setMaterialVector2(Material material, String name, Vector2f value) {
            if (!materialDefHasParam(material, name)) {
                logMissingParamOnce(material, name);
                return;
            }
            if (getDefVarType(material, name) == VarType.Vector2) {
                material.setVector2(name, value);
            }
        }

        private void setMaterialBoolean(Material material, String name, boolean value) {
            if (!materialDefHasParam(material, name)) {
                logMissingParamOnce(material, name);
                return;
            }
            if (getDefVarType(material, name) == VarType.Boolean) {
                material.setBoolean(name, value);
            }
        }

        private boolean materialDefHasParam(Material material, String name) {
            if (material == null || material.getMaterialDef() == null) {
                return false;
            }
            return material.getMaterialDef().getMaterialParam(name) != null;
        }

        private VarType getDefVarType(Material material, String name) {
            if (material == null || material.getMaterialDef() == null) {
                return null;
            }
            var param = material.getMaterialDef().getMaterialParam(name);
            return param != null ? param.getVarType() : null;
        }

        private UvTransformDescriptor resolveActiveUvTransformDescriptor() {
            if (isolateIndex >= 0 && isolateIndex < geometryList.size()) {
                return buildUvTransformDescriptor(geometryList.get(isolateIndex));
            }
            UvTransformDescriptor best = null;
            for (Geometry geometry : geometryList) {
                UvTransformDescriptor candidate = buildUvTransformDescriptor(geometry);
                if (best == null) {
                    best = candidate;
                    continue;
                }
                if (candidate.tiles.y > best.tiles.y || candidate.tiles.x > best.tiles.x) {
                    best = candidate;
                }
            }
            return best != null
                    ? best
                    : new UvTransformDescriptor(
                            uvTransformMode,
                            new Vector2f(0f, 0f),
                            new Vector2f(1f, 1f),
                            new Vector2f(0f, 0f),
                            new Vector2f(0f, 0f),
                            false,
                            false,
                            false);
        }

        private Material getUvDebugFallbackMaterial() {
            if (uvDebugFallbackMaterial == null) {
                Material fallback = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
                fallback.setColor("Color", ColorRGBA.Magenta);
                uvDebugFallbackMaterial = fallback;
            }
            return uvDebugFallbackMaterial;
        }

        private void cycleUvDebugMode() {
            cycleUvDebugMode(1);
        }

        private void cycleUvDebugMode(int direction) {
            UvDebugMode[] modes = UvDebugMode.values();
            int index = 0;
            for (int i = 0; i < modes.length; i++) {
                if (modes[i] == uvDebugMode) {
                    index = i;
                    break;
                }
            }
            int next = (index + direction) % modes.length;
            if (next < 0) {
                next += modes.length;
            }
            uvDebugMode = modes[next];
            applyDebugMaterials();
            applyUvTransformToActiveMaterials();
            logUvStateChange("UV debug");
            logUvDebugForIsolation();
        }

        private void cycleUvTransform(int direction) {
            UvTransformMode[] modes = UvTransformMode.values();
            int index = 0;
            for (int i = 0; i < modes.length; i++) {
                if (modes[i] == uvTransformMode) {
                    index = i;
                    break;
                }
            }
            int next = (index + direction) % modes.length;
            if (next < 0) {
                next += modes.length;
            }
            uvTransformMode = modes[next];
            applyDebugMaterials();
            applyUvTransformToActiveMaterials();
            logUvStateChange("UV transform");
        }

        private void applyUvOffsetParams(Geometry geometry, Material material) {
            if (material == null) {
                return;
            }
            UvTransformDescriptor descriptor = buildUvTransformDescriptor(geometry);
            material.setFloat("UvOffsetEnabled", descriptor.offsetEnabled ? 1f : 0f);
            material.setVector2("UvOffset", descriptor.offset);
            applyUvTransformParams(material, descriptor);
            if (geometry != null && (isolateIndex >= 0 && geometryList.get(isolateIndex) == geometry)) {
                logger.info(
                        "UV offset: enabled={} offset=({}, {}) appliedAxes=({},{})",
                        descriptor.offsetEnabled,
                        descriptor.offset.x,
                        descriptor.offset.y,
                        descriptor.applyU ? "U" : "-",
                        descriptor.applyV ? "V" : "-");
            }
        }

        private void adjustSelectedTile(int deltaU, int deltaV) {
            UvTransformDescriptor descriptor = resolveActiveUvTransformDescriptor();
            int maxTilesU = Math.max(1, Math.round(descriptor.tiles.x));
            int maxTilesV = Math.max(1, Math.round(descriptor.tiles.y));
            if (maxTilesU > 1) {
                selectedTileU = clampInt(selectedTileU + deltaU, 0, maxTilesU - 1);
            }
            if (maxTilesV > 1) {
                selectedTileV = clampInt(selectedTileV + deltaV, 0, maxTilesV - 1);
            }
            if (uvVerbose) {
                logSelectedTileMaterialState("UV tile before");
            }
            applyUvTransformToActiveMaterials();
            if (uvVerbose) {
                logSelectedTileMaterialState("UV tile set");
                logUvVerboseInfo(
                        "UV tile changed: selectedTile=({},{}) tiles=({}, {}) uvBase=({}, {})",
                        formatInt(selectedTileU),
                        formatInt(selectedTileV),
                        formatInt(descriptor.tiles.x),
                        formatInt(descriptor.tiles.y),
                        formatInt(descriptor.baseFloor.x),
                        formatInt(descriptor.baseFloor.y));
            }
            logUvStateChange("UV tile");
        }

        private void toggleDebugTileTint() {
            debugTileTintEnabled = !debugTileTintEnabled;
            int updated = 0;
            for (Geometry geometry : geometryList) {
                Material material = geometry.getMaterial();
                if (material == null || !materialDefHasParam(material, "DebugTileTint")) {
                    continue;
                }
                setMaterialBoolean(material, "DebugTileTint", debugTileTintEnabled);
                updated++;
            }
            logger.info("DebugTileTint = {} (updated {} materials)", debugTileTintEnabled, updated);
            applyUvTransformToActiveMaterials();
        }

        private void logSelectedTileMaterialState(String label) {
            if (isolateIndex < 0 || isolateIndex >= geometryList.size()) {
                return;
            }
            Geometry geometry = geometryList.get(isolateIndex);
            Material material = geometry.getMaterial();
            if (material == null) {
                return;
            }
            Object selected = material.getParam("SelectedTile") != null
                    ? material.getParam("SelectedTile").getValue()
                    : null;
            Object tint = material.getParam("DebugTileTint") != null
                    ? material.getParam("DebugTileTint").getValue()
                    : null;
            String defName = material.getMaterialDef() != null
                    ? material.getMaterialDef().getName()
                    : "<unknown>";
            boolean defHasSel = materialDefHasParam(material, "SelectedTile");
            boolean defHasBase = materialDefHasParam(material, "UvBase");
            boolean defHasTiles = materialDefHasParam(material, "Tiles")
                    || materialDefHasParam(material, "TilesU")
                    || materialDefHasParam(material, "TilesV");
            boolean defHasTint = materialDefHasParam(material, "DebugTileTint");
            logUvVerboseDebug(
                    "{}: geom={} matId={} def={} SelectedTile={} DebugTileTint={} defHas[Sel={} Base={} Tiles={} Tint={}]",
                    label,
                    geometry.getName(),
                    System.identityHashCode(material),
                    defName,
                    selected,
                    tint,
                    defHasSel,
                    defHasBase,
                    defHasTiles,
                    defHasTint);
        }

        private void applyAtlasDecodeParamsToGeometry(Geometry geometry) {
            Material material = geometry.getMaterial();
            if (material == null || material.getMaterialDef() == null) {
                return;
            }
            String defName = material.getMaterialDef().getName();
            if (!"AtlasDecodeUnshaded".equals(defName)) {
                return;
            }
            int matId = System.identityHashCode(material);
            Integer lastId = materialIds.put(geometry, matId);
            if (lastId == null || lastId != matId) {
                logUvVerboseInfo(
                        "Material replaced: geom={} oldMatId={} newMatId={} def={}",
                        geometry.getName(),
                        lastId,
                        matId,
                        defName);
            }
            UvTransformDescriptor descriptor = buildUvTransformDescriptor(geometry);
            applyUvTransformParams(material, descriptor);
            Object selected = material.getParam("SelectedTile") != null
                    ? material.getParam("SelectedTile").getValue()
                    : null;
            Object tint = material.getParam("DebugTileTint") != null
                    ? material.getParam("DebugTileTint").getValue()
                    : null;
            boolean hasMode = materialDefHasParam(material, "UvMode");
            boolean hasBase = materialDefHasParam(material, "UvBase");
            boolean hasTilesU = materialDefHasParam(material, "TilesU");
            boolean hasTilesV = materialDefHasParam(material, "TilesV");
            boolean hasSelected = materialDefHasParam(material, "SelectedTile");
            boolean hasTint = materialDefHasParam(material, "DebugTileTint");
            logUvVerboseDebug(
                    "Atlas params set: geom={} matId={} def={} has[UvMode={} UvBase={} TilesU={} TilesV={} Sel={} Tint={}] SelectedTile={} DebugTileTint={}",
                    geometry.getName(),
                    matId,
                    defName,
                    hasMode,
                    hasBase,
                    hasTilesU,
                    hasTilesV,
                    hasSelected,
                    hasTint,
                    selected,
                    tint);
        }

        private void ensureAtlasDecodeMaterial(Geometry geometry) {
            if (geometry == null) {
                return;
            }
            Material current = geometry.getMaterial();
            String defName = current != null && current.getMaterialDef() != null
                    ? current.getMaterialDef().getName()
                    : "<none>";
            if ("AtlasDecodeUnshaded".equals(defName)) {
                return;
            }
            Material original = getBaseRenderMaterial(geometry);
            Texture albedo = original != null ? findAlbedoTexture(original) : null;
            if (albedo == null) {
                if (original != null) {
                    clearBaseColorTextures(original);
                    ensureBaseColorFactor(original);
                    geometry.setMaterial(original);
                    logApplyMaterial(geometry, original);
                    logTextureOverride(geometry, original, false, "none");
                } else {
                    Material fallback = getUntexturedFallbackMaterial();
                    geometry.setMaterial(fallback);
                    logApplyMaterial(geometry, fallback);
                    logTextureOverride(geometry, fallback, false, "untextured_fallback");
                }
                return;
            }
            Material material = new Material(assetManager, "assets/MatDefs/Debug/AtlasDecodeUnshaded.j3md");
            if (albedo != null) {
                material.setTexture("AlbedoMap", albedo);
            }
            material.setColor("BaseColorFactor", resolveBaseColorFactor(original));
            geometry.setMaterial(material);
            logApplyMaterial(geometry, material);
            logTextureOverride(geometry, material, true, "atlas_decode");
            logUvVerboseInfo(
                    "AtlasDecode bound: geom={} matId={} def={}",
                    geometry.getName(),
                    System.identityHashCode(material),
                    material.getMaterialDef() != null
                            ? material.getMaterialDef().getName()
                            : "<unknown>");
        }

        private static String formatInt(double value) {
            if (!Double.isFinite(value)) {
                return "nan";
            }
            return String.format(Locale.ROOT, "%.0f", value);
        }

        private static String formatPercent(double value) {
            if (!Double.isFinite(value)) {
                return "nan";
            }
            return String.format(Locale.ROOT, "%.1f", value * 100.0);
        }

        private void logUvVerboseInfo(String message, Object... args) {
            if (!uvVerbose) {
                return;
            }
            logger.info(message, args);
        }

        private void logUvVerboseDebug(String message, Object... args) {
            if (!uvVerbose) {
                return;
            }
            logger.debug(message, args);
        }

        private void logTextureOverride(
                Geometry geometry, Material material, boolean hasTexture, String overrideChoice) {
            if (!uvVerbose || geometry == null || material == null || material.getMaterialDef() == null) {
                return;
            }
            logger.info(
                    "UV material: geom={} matDef={} hasTexture={} override={}",
                    geometry.getName(),
                    material.getMaterialDef().getName(),
                    hasTexture,
                    overrideChoice);
        }

        private void logMissingParamOnce(Material material, String paramName) {
            if (!uvVerbose || material == null || material.getMaterialDef() == null) {
                return;
            }
            String defName = material.getMaterialDef().getName();
            String key = defName + ":" + paramName;
            if (!missingParamWarnings.add(key)) {
                return;
            }
            logger.info("Material {} missing param {}; skipping set.", defName, paramName);
        }

        private void logUvDebugForIsolation() {
            if (isolateIndex >= 0 && isolateIndex < geometryList.size()) {
                Geometry geometry = geometryList.get(isolateIndex);
                logIsolationInfo(
                        geometry, geometryPaths.getOrDefault(geometry, "modelRoot"), isolateIndex, geometryList.size());
            } else {
                logUvVerboseInfo("UV debug active; use isolate (8) to inspect UV bounds per geometry.");
            }
        }

        private void applyOutOfRangeUvWrapFix() {
            if (uvTransformMode == UvTransformMode.ATLAS_DECODE) {
                return;
            }
            if (geometryList.isEmpty()) {
                return;
            }
            for (Geometry geometry : geometryList) {
                Mesh mesh = geometry.getMesh();
                UvBounds bounds = computeUvBounds(mesh);
                if (bounds == null || !bounds.outOfRange) {
                    continue;
                }
                Material material = geometry.getMaterial();
                if (material == null) {
                    continue;
                }
                applyRepeatWrapIfNeeded(geometry, material, bounds, "BaseColorMap");
                applyRepeatWrapIfNeeded(geometry, material, bounds, "DiffuseMap");
                applyRepeatWrapIfNeeded(geometry, material, bounds, "ColorMap");
            }
        }

        private void applyRepeatWrapIfNeeded(Geometry geometry, Material material, UvBounds bounds, String paramName) {
            Texture texture = getTextureParam(material, paramName);
            if (texture == null) {
                return;
            }
            Texture.WrapMode beforeS = texture.getWrap(Texture.WrapAxis.S);
            Texture.WrapMode beforeT = texture.getWrap(Texture.WrapAxis.T);
            if (beforeS == Texture.WrapMode.Repeat && beforeT == Texture.WrapMode.Repeat) {
                return;
            }
            texture.setWrap(Texture.WrapMode.Repeat);
            forcedWrapTextures.add(texture);
            logger.info(String.format(
                    "Forcing albedo wrap Repeat for %s (map=%s): UV0 outOfRange=true min(%.6f, %.6f) max(%.6f, %.6f) wrap %s/%s -> %s/%s",
                    geometry.getName(),
                    paramName,
                    bounds.minU,
                    bounds.minV,
                    bounds.maxU,
                    bounds.maxV,
                    beforeS,
                    beforeT,
                    texture.getWrap(Texture.WrapAxis.S),
                    texture.getWrap(Texture.WrapAxis.T)));
        }

        private void applyUnlitToSpatial(Spatial spatial) {
            if (spatial instanceof Geometry geometry) {
                Material original = originalMaterials.get(geometry);
                if (original == null) {
                    original = geometry.getMaterial();
                    if (original != null) {
                        originalMaterials.put(geometry, original);
                    }
                }
                Material unlit = buildUnlitMaterialForGeometry(geometry, original);
                geometry.setMaterial(unlit);
                return;
            }
            if (spatial instanceof Node node) {
                for (Spatial child : node.getChildren()) {
                    applyUnlitToSpatial(child);
                }
            }
        }

        private Material buildUnlitMaterialForGeometry(Geometry geometry, Material original) {
            Texture albedo = null;
            if (original != null) {
                albedo = findAlbedoTexture(original);
            }
            if (albedo != null) {
                if (uvTransformMode == UvTransformMode.OFF) {
                    Material cached = unlitMaterialCache.get(albedo);
                    if (cached != null) {
                        return cached;
                    }
                }
                Material unlit = new Material(assetManager, "assets/MatDefs/Debug/UnlitUvOffset.j3md");
                unlit.setTexture("ColorMap", albedo);
                applyUvOffsetParams(geometry, unlit);
                if (uvTransformMode == UvTransformMode.OFF) {
                    unlitMaterialCache.put(albedo, unlit);
                }
                return unlit;
            }
            ColorRGBA baseColor = resolveBaseColorFactor(original);
            Material fallback = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            fallback.setColor("Color", baseColor);
            return fallback;
        }

        private Texture findAlbedoTexture(Material material) {
            MatParamTexture baseColor = material.getTextureParam("BaseColorMap");
            if (baseColor != null && baseColor.getTextureValue() != null) {
                return baseColor.getTextureValue();
            }
            MatParamTexture colorMap = material.getTextureParam("ColorMap");
            if (colorMap != null) {
                return colorMap.getTextureValue();
            }
            MatParamTexture diffuse = material.getTextureParam("DiffuseMap");
            if (diffuse != null) {
                return diffuse.getTextureValue();
            }
            return null;
        }

        private ColorRGBA resolveBaseColorFactor(Material material) {
            if (material == null) {
                return ColorRGBA.White;
            }
            ColorRGBA baseColor = getColorParam(material, "BaseColor");
            if (baseColor != null) {
                return baseColor;
            }
            ColorRGBA diffuse = getColorParam(material, "Diffuse");
            if (diffuse != null) {
                return diffuse;
            }
            return ColorRGBA.White;
        }

        private void restoreOriginalMaterials() {
            for (Map.Entry<Geometry, Material> entry : originalMaterials.entrySet()) {
                Geometry geometry = entry.getKey();
                if (geometry != null) {
                    geometry.setMaterial(entry.getValue());
                    logApplyMaterial(geometry, entry.getValue());
                }
            }
            unlitMaterialCache.clear();
            unlitFallbackMaterial = null;
        }

        private void restoreBaseMaterials() {
            if (!renderMaterials.isEmpty()) {
                for (Map.Entry<Geometry, Material> entry : renderMaterials.entrySet()) {
                    Geometry geometry = entry.getKey();
                    if (geometry != null) {
                        geometry.setMaterial(entry.getValue());
                        logApplyMaterial(geometry, entry.getValue());
                    }
                }
                unlitMaterialCache.clear();
                unlitFallbackMaterial = null;
                return;
            }
            restoreOriginalMaterials();
        }

        private void dumpMaterialDiagnostics() {
            if (modelRoot == null) {
                return;
            }
            DiagnosticsCounter counter = new DiagnosticsCounter();
            Map<String, Set<String>> textureToMaterialSignatures = new HashMap<>();
            dumpSpatialDiagnostics(modelRoot, "modelRoot", counter, textureToMaterialSignatures);
            warnOnTextureReuse(textureToMaterialSignatures);
            logger.info(
                    "Material dump summary: baseColorMap={}, diffuseMap={}, colorMap={}, none={}",
                    counter.baseColorMap,
                    counter.diffuseMap,
                    counter.colorMap,
                    counter.none);
        }

        private void dumpSpatialDiagnostics(
                Spatial spatial,
                String path,
                DiagnosticsCounter counter,
                Map<String, Set<String>> textureToMaterialSignatures) {
            if (spatial instanceof Geometry geometry) {
                dumpGeometryDiagnostics(geometry, path, counter, textureToMaterialSignatures);
                return;
            }
            if (spatial instanceof Node node) {
                for (Spatial child : node.getChildren()) {
                    String childPath = path + "/" + child.getName();
                    dumpSpatialDiagnostics(child, childPath, counter, textureToMaterialSignatures);
                }
            }
        }

        private void dumpGeometryDiagnostics(
                Geometry geometry,
                String path,
                DiagnosticsCounter counter,
                Map<String, Set<String>> textureToMaterialSignatures) {
            Mesh mesh = geometry.getMesh();
            boolean hasVertexColors = mesh != null && mesh.getBuffer(VertexBuffer.Type.Color) != null;
            int uvSets = countUvSets(mesh);

            Material originalMaterial = getOriginalMaterial(geometry);
            Material currentMaterial = geometry.getMaterial();
            String originalDef = originalMaterial != null && originalMaterial.getMaterialDef() != null
                    ? originalMaterial.getMaterialDef().getAssetName()
                    : "<unknown>";
            String currentDef = currentMaterial != null && currentMaterial.getMaterialDef() != null
                    ? currentMaterial.getMaterialDef().getAssetName()
                    : "<unknown>";

            logger.info("Geometry: {} (path={})", geometry.getName(), path);
            int vertexCount = mesh != null ? mesh.getVertexCount() : 0;
            int triangleCount = mesh != null ? mesh.getTriangleCount() : 0;
            logger.info(
                    "  Mesh: vertexColors={} uvSets={} vertices={} triangles={}",
                    hasVertexColors,
                    uvSets,
                    vertexCount,
                    triangleCount);
            logUvBounds(mesh);
            logger.info("  Original MaterialDef: {}", originalDef);
            dumpGeometryProvenance(geometry, path, originalMaterial, currentMaterial);

            Texture baseColorMap = originalMaterial != null ? getTextureParam(originalMaterial, "BaseColorMap") : null;
            Texture diffuseMap = originalMaterial != null ? getTextureParam(originalMaterial, "DiffuseMap") : null;
            Texture colorMap = originalMaterial != null ? getTextureParam(originalMaterial, "ColorMap") : null;
            ColorRGBA baseColor = originalMaterial != null ? getColorParam(originalMaterial, "BaseColor") : null;
            ColorRGBA diffuseColor = originalMaterial != null ? getColorParam(originalMaterial, "Diffuse") : null;

            if (baseColorMap != null) {
                counter.baseColorMap++;
                logger.info(
                        "  BaseColorMap: {} forcedRepeat={}",
                        describeTexture(baseColorMap),
                        forcedWrapTextures.contains(baseColorMap));
                trackTextureReuse(textureToMaterialSignatures, baseColorMap, originalMaterial, geometry);
            } else {
                logger.info("  BaseColorMap: <none>");
            }
            if (diffuseMap != null) {
                counter.diffuseMap++;
                logger.info(
                        "  DiffuseMap: {} forcedRepeat={}",
                        describeTexture(diffuseMap),
                        forcedWrapTextures.contains(diffuseMap));
                trackTextureReuse(textureToMaterialSignatures, diffuseMap, originalMaterial, geometry);
            } else {
                logger.info("  DiffuseMap: <none>");
            }
            if (colorMap != null) {
                counter.colorMap++;
                logger.info(
                        "  ColorMap: {} forcedRepeat={}",
                        describeTexture(colorMap),
                        forcedWrapTextures.contains(colorMap));
                trackTextureReuse(textureToMaterialSignatures, colorMap, originalMaterial, geometry);
            } else {
                logger.info("  ColorMap: <none>");
            }

            if (baseColorMap == null && diffuseMap == null && colorMap == null) {
                counter.none++;
            }

            if (baseColor != null) {
                logger.info("  BaseColor: {}", baseColor);
            }
            if (diffuseColor != null) {
                logger.info("  Diffuse: {}", diffuseColor);
            }

            logger.info("  Current MaterialDef: {}", currentDef);
            Texture currentColorMap = currentMaterial != null ? getTextureParam(currentMaterial, "ColorMap") : null;
            if (currentColorMap != null) {
                logger.info("  Current ColorMap: {}", describeTexture(currentColorMap));
            }
        }

        private void dumpGeometryProvenance(
                Geometry geometry, String path, Material originalMaterial, Material currentMaterial) {
            logger.info("  Provenance:");
            logUserData("    Spatial", geometry);
            if (originalMaterial != null) {
                logMaterialParams("    OriginalParams", originalMaterial);
            }
            if (currentMaterial != null && currentMaterial != originalMaterial) {
                logMaterialParams("    CurrentParams", currentMaterial);
            }

            String gltfMaterialName = findUserDataString(geometry, "material");
            if (gltfMaterialName != null) {
                logger.info("    glTF material: {}", gltfMaterialName);
            } else {
                logger.info("    glTF material: <unknown>");
            }

            if (originalMaterial != null) {
                logGltfTextureSlot("    glTF baseColor", originalMaterial, "BaseColorMap");
                logGltfTextureSlot("    glTF metallicRoughness", originalMaterial, "MetallicRoughnessMap");
                logGltfTextureSlot("    glTF normal", originalMaterial, "NormalMap");
                logGltfTextureSlot("    glTF emissive", originalMaterial, "EmissiveMap");
                logGltfTextureSlot("    glTF occlusion", originalMaterial, "OcclusionMap");
            }
        }

        private void logUserData(String prefix, Spatial spatial) {
            Collection<String> keys = spatial.getUserDataKeys();
            if (keys == null || keys.isEmpty()) {
                logger.info("{} userData: <none>", prefix);
                return;
            }
            logger.info("{} userData keys: {}", prefix, keys);
            for (String key : keys) {
                Object value = spatial.getUserData(key);
                logger.info("{} {} = {}", prefix, key, value);
            }
        }

        private void logMaterialParams(String prefix, Material material) {
            Map<String, Object> params = new TreeMap<>();
            for (var param : material.getParams()) {
                params.put(param.getName(), param.getValue());
            }
            logger.info("{} params: {}", prefix, params.keySet());
        }

        private void logGltfTextureSlot(String label, Material material, String paramName) {
            Texture texture = getTextureParam(material, paramName);
            if (texture == null) {
                logger.info("{}: <none>", label);
                return;
            }
            String keyName = textureKeyName(texture);
            String sampler = "wrapS=" + texture.getWrap(Texture.WrapAxis.S)
                    + " wrapT=" + texture.getWrap(Texture.WrapAxis.T)
                    + " min=" + texture.getMinFilter()
                    + " mag=" + texture.getMagFilter()
                    + " aniso=" + texture.getAnisotropicFilter();
            String size = texture.getImage() != null
                    ? (texture.getImage().getWidth() + "x" + texture.getImage().getHeight())
                    : "unknown";
            String embedded = "unknown";
            if (texture.getKey() != null) {
                String name = texture.getKey().getName();
                if (name != null
                        && (name.startsWith("Embedded") || name.startsWith("embedded") || name.startsWith("data:"))) {
                    embedded = "true";
                } else {
                    embedded = "false";
                }
            }
            logger.info(
                    "{}: texture={} image={} size={} sampler={} embedded={}",
                    label,
                    keyName,
                    keyName,
                    size,
                    sampler,
                    embedded);
        }

        private String findUserDataString(Spatial spatial, String needle) {
            Collection<String> keys = spatial.getUserDataKeys();
            if (keys == null || keys.isEmpty()) {
                return null;
            }
            for (String key : keys) {
                if (key == null) {
                    continue;
                }
                if (key.toLowerCase(Locale.ROOT).contains(needle)) {
                    Object value = spatial.getUserData(key);
                    if (value != null) {
                        return value.toString();
                    }
                }
            }
            return null;
        }

        private void warnOnTextureReuse(Map<String, Set<String>> textureToMaterialSignatures) {
            for (Map.Entry<String, Set<String>> entry : textureToMaterialSignatures.entrySet()) {
                if (entry.getValue().size() > 1) {
                    logger.warn("Texture reuse across materials: {} -> {}", entry.getKey(), entry.getValue());
                }
            }
        }

        private void trackTextureReuse(
                Map<String, Set<String>> textureToMaterialSignatures,
                Texture texture,
                Material material,
                Geometry geometry) {
            if (texture == null || material == null || geometry == null) {
                return;
            }
            String key = textureKeyName(texture);
            String signature = buildMaterialSignature(material, geometry);
            textureToMaterialSignatures
                    .computeIfAbsent(key, ignored -> new HashSet<>())
                    .add(signature);
        }

        private String buildMaterialSignature(Material material, Geometry geometry) {
            String def = material.getMaterialDef() != null
                    ? material.getMaterialDef().getName()
                    : "<unknown>";
            String gltfMaterial = findUserDataString(geometry, "material");
            ColorRGBA baseColor = getColorParam(material, "BaseColor");
            ColorRGBA diffuse = getColorParam(material, "Diffuse");
            String factor = baseColor != null ? baseColor.toString() : (diffuse != null ? diffuse.toString() : "none");
            if (gltfMaterial != null) {
                return def + "|gltf=" + gltfMaterial + "|factor=" + factor;
            }
            return def + "|factor=" + factor;
        }

        private void dumpIsolatedProvenance() {
            if (isolateIndex < 0 || isolateIndex >= geometryList.size()) {
                logger.info("No isolated geometry selected.");
                return;
            }
            Geometry geometry = geometryList.get(isolateIndex);
            String path = geometryPaths.getOrDefault(geometry, "modelRoot");
            Material originalMaterial = getOriginalMaterial(geometry);
            Material currentMaterial = geometry.getMaterial();
            logger.info("Isolated provenance dump:");
            dumpGeometryDiagnostics(geometry, path, new DiagnosticsCounter(), new HashMap<>());
            logger.info(
                    "Isolated current MaterialDef: {}",
                    currentMaterial != null && currentMaterial.getMaterialDef() != null
                            ? currentMaterial.getMaterialDef().getAssetName()
                            : "<unknown>");
        }

        private Material getOriginalMaterial(Geometry geometry) {
            if (geometry == null) {
                return null;
            }
            Material original = originalMaterials.get(geometry);
            return original != null ? original : geometry.getMaterial();
        }

        private Material getBaseRenderMaterial(Geometry geometry) {
            if (geometry == null) {
                return null;
            }
            Material render = renderMaterials.get(geometry);
            if (render != null) {
                return render;
            }
            return getOriginalMaterial(geometry);
        }

        private Texture getTextureParam(Material material, String name) {
            MatParamTexture param = material.getTextureParam(name);
            return param != null ? param.getTextureValue() : null;
        }

        private ColorRGBA getColorParam(Material material, String name) {
            var param = material.getParam(name);
            if (param != null && param.getVarType() == VarType.Vector4) {
                return (ColorRGBA) param.getValue();
            }
            if (param != null && param.getVarType() == VarType.Vector3) {
                Vector3f v = (Vector3f) param.getValue();
                return new ColorRGBA(v.x, v.y, v.z, 1f);
            }
            return null;
        }

        private String describeTexture(Texture texture) {
            String name = texture.getName();
            if ((name == null || name.isBlank()) && texture.getKey() != null) {
                name = texture.getKey().toString();
            }
            if (name == null || name.isBlank()) {
                name = "<unnamed>";
            }
            int width = texture.getImage() != null ? texture.getImage().getWidth() : -1;
            int height = texture.getImage() != null ? texture.getImage().getHeight() : -1;
            String size = width > 0 && height > 0 ? (width + "x" + height) : "unknown";
            String colorSpace = textureColorSpace(texture);
            return String.format(
                    "%s size=%s wrapS=%s wrapT=%s min=%s mag=%s aniso=%d colorspace=%s",
                    name,
                    size,
                    texture.getWrap(Texture.WrapAxis.S),
                    texture.getWrap(Texture.WrapAxis.T),
                    texture.getMinFilter(),
                    texture.getMagFilter(),
                    texture.getAnisotropicFilter(),
                    colorSpace);
        }

        private String textureColorSpace(Texture texture) {
            if (texture == null || texture.getImage() == null) {
                return "unknown";
            }
            ColorSpace space = texture.getImage().getColorSpace();
            return space != null ? space.toString() : "unknown";
        }

        private int countUvSets(Mesh mesh) {
            if (mesh == null) {
                return 0;
            }
            int count = 0;
            if (mesh.getBuffer(VertexBuffer.Type.TexCoord) != null) {
                count++;
            }
            if (mesh.getBuffer(VertexBuffer.Type.TexCoord2) != null) {
                count++;
            }
            if (mesh.getBuffer(VertexBuffer.Type.TexCoord3) != null) {
                count++;
            }
            if (mesh.getBuffer(VertexBuffer.Type.TexCoord4) != null) {
                count++;
            }
            return count;
        }

        private MeshStats buildMeshStats(Mesh mesh) {
            if (mesh == null) {
                return null;
            }
            int uvSets = countUvSets(mesh);
            int vertices = mesh.getVertexCount();
            int triangles = mesh.getTriangleCount();
            boolean hasVertexColors = mesh.getBuffer(VertexBuffer.Type.Color) != null;
            return new MeshStats(uvSets, vertices, triangles, hasVertexColors);
        }

        private static final class DiagnosticsCounter {
            private int baseColorMap;
            private int diffuseMap;
            private int colorMap;
            private int none;
        }

        private void cycleIsolation() {
            if (geometryList.isEmpty()) {
                logger.info("No geometries to isolate");
                return;
            }
            isolateIndex++;
            if (isolateIndex >= geometryList.size()) {
                isolateIndex = 0;
            }
            applyIsolation(isolateIndex);
        }

        private void clearIsolation() {
            isolateIndex = -1;
            for (Geometry geometry : geometryList) {
                geometry.setCullHint(Spatial.CullHint.Inherit);
            }
            logger.info("Isolation cleared (showing all geometries)");
            applyUvTransformToActiveMaterials();
        }

        private void applyIsolation(int index) {
            int total = geometryList.size();
            if (index < 0 || index >= total) {
                return;
            }
            Geometry active = geometryList.get(index);
            for (Geometry geometry : geometryList) {
                geometry.setCullHint(geometry == active ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            }
            String path = geometryPaths.getOrDefault(active, "modelRoot");
            logIsolationInfo(active, path, index, total);
            applyUvTransformToActiveMaterials();
        }

        private void logIsolationInfo(Geometry geometry, String path, int index, int total) {
            if (!uvVerbose) {
                logIsolationInfoCompact(geometry, path, index, total);
                return;
            }
            Material original = getOriginalMaterial(geometry);
            Material current = geometry != null ? geometry.getMaterial() : null;
            String matDef = original != null && original.getMaterialDef() != null
                    ? original.getMaterialDef().getAssetName()
                    : "<unknown>";
            String currentDef = current != null && current.getMaterialDef() != null
                    ? current.getMaterialDef().getAssetName()
                    : "<unknown>";
            String gltfMaterialName = findUserDataString(geometry, "material");
            Texture baseColorMap = original != null ? getTextureParam(original, "BaseColorMap") : null;
            Texture diffuseMap = original != null ? getTextureParam(original, "DiffuseMap") : null;
            Texture colorMap = original != null ? getTextureParam(original, "ColorMap") : null;
            Texture normalMap = original != null ? getTextureParam(original, "NormalMap") : null;
            Texture metallicRoughnessMap = original != null ? getTextureParam(original, "MetallicRoughnessMap") : null;
            Texture occlusionMap = original != null ? getTextureParam(original, "OcclusionMap") : null;
            Texture emissiveMap = original != null ? getTextureParam(original, "EmissiveMap") : null;
            ColorRGBA baseColor = original != null ? getColorParam(original, "BaseColor") : null;
            ColorRGBA diffuseColor = original != null ? getColorParam(original, "Diffuse") : null;

            logger.info("Isolated geometry {}/{}: {} (path={})", index + 1, total, geometry.getName(), path);
            Mesh mesh = geometry.getMesh();
            int uvSets = countUvSets(mesh);
            int vertexCount = mesh != null ? mesh.getVertexCount() : 0;
            int triangleCount = mesh != null ? mesh.getTriangleCount() : 0;
            boolean hasVertexColors = mesh != null && mesh.getBuffer(VertexBuffer.Type.Color) != null;
            logger.info(
                    "  Mesh: vertexColors={} uvSets={} vertices={} triangles={}",
                    hasVertexColors,
                    uvSets,
                    vertexCount,
                    triangleCount);
            logUvBounds(mesh);
            logger.info("  UV transform: mode={}", uvTransformMode.label);
            UvTileStats tiles = computeUvTileStats(mesh);
            if (tiles != null) {
                logger.info(
                        "  UV0 tiles inferred: floorMin(U,V)=({},{}) ceilMax(U,V)=({},{}) tiles(U,V)=({},{})",
                        formatInt(tiles.floorMinU),
                        formatInt(tiles.floorMinV),
                        formatInt(tiles.ceilMaxU),
                        formatInt(tiles.ceilMaxV),
                        formatInt(tiles.tilesU),
                        formatInt(tiles.tilesV));
            }
            UvTransformDescriptor descriptor = buildUvTransformDescriptor(geometry);
            logger.info(
                    "  UV transform descriptor: mode={} offset=({}, {}) tiles=({}, {}) selectedTile=({}, {}) baseFloor=({}, {})",
                    descriptor.mode.label,
                    formatInt(descriptor.offset.x),
                    formatInt(descriptor.offset.y),
                    formatInt(descriptor.tiles.x),
                    formatInt(descriptor.tiles.y),
                    formatInt(descriptor.selectedTile.x),
                    formatInt(descriptor.selectedTile.y),
                    formatInt(descriptor.baseFloor.x),
                    formatInt(descriptor.baseFloor.y));
            UvOffsetStats stats = computeUvOffsetStats(mesh, computeUvBounds(mesh));
            if (stats != null) {
                logger.info(
                        "  UV offset: enabled={} meanOffset({},{}) withinEps({}%, {}%) appliedAxes=({},{})",
                        descriptor.offsetEnabled,
                        formatInt(stats.meanOffsetU),
                        formatInt(stats.meanOffsetV),
                        formatPercent(stats.percentWithinEpsU),
                        formatPercent(stats.percentWithinEpsV),
                        descriptor.applyU ? "U" : "-",
                        descriptor.applyV ? "V" : "-");
            }
            logger.info("  Original MaterialDef: {}", matDef);
            logger.info("  Current MaterialDef: {}", currentDef);
            logger.info("  glTF material: {}", gltfMaterialName != null ? gltfMaterialName : "<unknown>");
            if (original != null) {
                logMaterialParams("  OriginalParams", original);
            }
            if (current != null && current != original) {
                logMaterialParams("  CurrentParams", current);
            }
            logger.info(
                    "  BaseColorMap: {} forcedRepeat={}",
                    baseColorMap != null ? describeTexture(baseColorMap) : "<none>",
                    baseColorMap != null && forcedWrapTextures.contains(baseColorMap));
            logger.info(
                    "  DiffuseMap: {} forcedRepeat={}",
                    diffuseMap != null ? describeTexture(diffuseMap) : "<none>",
                    diffuseMap != null && forcedWrapTextures.contains(diffuseMap));
            logger.info(
                    "  ColorMap: {} forcedRepeat={}",
                    colorMap != null ? describeTexture(colorMap) : "<none>",
                    colorMap != null && forcedWrapTextures.contains(colorMap));
            logger.info("  NormalMap: {}", normalMap != null ? describeTexture(normalMap) : "<none>");
            logger.info(
                    "  MetallicRoughnessMap: {}",
                    metallicRoughnessMap != null ? describeTexture(metallicRoughnessMap) : "<none>");
            logger.info("  OcclusionMap: {}", occlusionMap != null ? describeTexture(occlusionMap) : "<none>");
            logger.info("  EmissiveMap: {}", emissiveMap != null ? describeTexture(emissiveMap) : "<none>");
            if (baseColor != null) {
                logger.info("  BaseColor: {}", baseColor);
            }
            if (diffuseColor != null) {
                logger.info("  Diffuse: {}", diffuseColor);
            }

            UvBounds bounds = computeUvBounds(mesh);
            String uvSummary = "<none>";
            if (bounds != null && stats != null) {
                double spanU = bounds.maxU - bounds.minU;
                double spanV = bounds.maxV - bounds.minV;
                uvSummary = String.format(
                        Locale.ROOT,
                        "min(%.3f,%.3f) max(%.3f,%.3f) span(%.3f,%.3f) meanOffset(%.0f,%.0f) withinEps(%.0f%%,%.0f%%)",
                        bounds.minU,
                        bounds.minV,
                        bounds.maxU,
                        bounds.maxV,
                        spanU,
                        spanV,
                        stats.meanOffsetU,
                        stats.meanOffsetV,
                        stats.percentWithinEpsU * 100.0,
                        stats.percentWithinEpsV * 100.0);
            } else if (bounds != null) {
                double spanU = bounds.maxU - bounds.minU;
                double spanV = bounds.maxV - bounds.minV;
                uvSummary = String.format(
                        Locale.ROOT,
                        "min(%.3f,%.3f) max(%.3f,%.3f) span(%.3f,%.3f)",
                        bounds.minU,
                        bounds.minV,
                        bounds.maxU,
                        bounds.maxV,
                        spanU,
                        spanV);
            }
            logger.info("  UV0 summary: {}", uvSummary);
            if (bounds != null && bounds.outOfRange && tiles != null && (tiles.tilesU > 1.0 || tiles.tilesV > 1.0)) {
                logger.info(
                        "  UV0 note: out-of-range UVs detected; atlas_decode may help (tilesU={}, tilesV={})",
                        formatInt(tiles.tilesU),
                        formatInt(tiles.tilesV));
            }
            logAtlasDecodeReadiness(geometry, original, bounds, tiles, descriptor);
        }

        private void logIsolationInfoCompact(Geometry geometry, String path, int index, int total) {
            if (geometry == null) {
                return;
            }
            Mesh mesh = geometry.getMesh();
            MeshStats meshStats = buildMeshStats(mesh);
            UvBounds bounds = computeUvBounds(mesh);
            UvTileStats tiles = computeUvTileStats(mesh);
            UvTransformDescriptor descriptor = buildUvTransformDescriptor(geometry);
            Material original = getOriginalMaterial(geometry);
            AtlasDecodeReadiness readiness = computeAtlasDecodeReadiness(original, bounds, tiles, descriptor);
            String uvSummary = "<none>";
            if (bounds != null) {
                double spanU = bounds.maxU - bounds.minU;
                double spanV = bounds.maxV - bounds.minV;
                uvSummary = String.format(
                        Locale.ROOT,
                        "min(%.3f,%.3f) max(%.3f,%.3f) span(%.3f,%.3f)",
                        bounds.minU,
                        bounds.minV,
                        bounds.maxU,
                        bounds.maxV,
                        spanU,
                        spanV);
            }
            String tilesSummary = tiles != null
                    ? String.format(
                            Locale.ROOT,
                            "tiles(%s,%s) floorMin(%s,%s)",
                            formatInt(tiles.tilesU),
                            formatInt(tiles.tilesV),
                            formatInt(tiles.floorMinU),
                            formatInt(tiles.floorMinV))
                    : "tiles(n/a)";
            logger.info(
                    "Isolated geometry {}/{}: {} (path={}) uvSets={} UV0={} {} baseFloor=({}, {}) atlas_decode={} ({})",
                    index + 1,
                    total,
                    geometry.getName(),
                    path,
                    meshStats != null ? meshStats.uvSets : 0,
                    uvSummary,
                    tilesSummary,
                    formatInt(descriptor.baseFloor.x),
                    formatInt(descriptor.baseFloor.y),
                    readiness.ready() ? "ready" : "not_ready",
                    readiness.reason());
        }

        private AtlasDecodeReadiness computeAtlasDecodeReadiness(
                Material original, UvBounds bounds, UvTileStats tiles, UvTransformDescriptor descriptor) {
            String reason = "ready";
            boolean ready = true;
            if (original == null) {
                ready = false;
                reason = "no material";
            } else if (findAlbedoTexture(original) == null) {
                ready = false;
                reason = "no baseColor texture found";
            } else if (bounds == null) {
                ready = false;
                reason = "no uv0";
            } else if (tiles == null) {
                ready = false;
                reason = "no uv0 tiles";
            } else {
                boolean baseOffset = Math.abs(descriptor.baseFloor.x) > 0.0f || Math.abs(descriptor.baseFloor.y) > 0.0f;
                boolean tilesSpan = tiles.tilesU > 1.0 || tiles.tilesV > 1.0;
                boolean outOfRange = bounds.outOfRange;
                if (!(baseOffset || tilesSpan || outOfRange)) {
                    ready = false;
                    reason = "tilesU/tilesV == 1 and baseFloor == 0 and inRange";
                }
            }
            return new AtlasDecodeReadiness(ready, reason);
        }

        private void logAtlasDecodeReadiness(
                Geometry geometry,
                Material original,
                UvBounds bounds,
                UvTileStats tiles,
                UvTransformDescriptor descriptor) {
            if (!uvVerbose) {
                return;
            }
            AtlasDecodeReadiness readiness = computeAtlasDecodeReadiness(original, bounds, tiles, descriptor);
            logUvVerboseInfo(
                    "  atlas_decode ready: {} reason={} baseFloor=({}, {}) tiles=({}, {}) selectedTile=({}, {})",
                    readiness.ready(),
                    readiness.reason(),
                    formatInt(descriptor.baseFloor.x),
                    formatInt(descriptor.baseFloor.y),
                    tiles != null ? formatInt(tiles.tilesU) : "n/a",
                    tiles != null ? formatInt(tiles.tilesV) : "n/a",
                    formatInt(descriptor.selectedTile.x),
                    formatInt(descriptor.selectedTile.y));
        }

        private void logAtlasDecodeMaterialBinding(
                Geometry geometry, Material material, UvTransformDescriptor descriptor, Texture albedo) {
            if (!uvVerbose) {
                return;
            }
            String defPath = material.getMaterialDef() != null
                    ? material.getMaterialDef().getAssetName()
                    : "<unknown>";
            String texParam = "AlbedoMap";
            String texName = textureKeyName(albedo);
            boolean hasParamDef = material.getMaterialDef() != null
                    && material.getMaterialDef().getMaterialParam(texParam) != null;
            boolean hasMode =
                    materialDefHasParam(material, "UvTransformMode") || materialDefHasParam(material, "UvMode");
            boolean hasBase = materialDefHasParam(material, "UvBase");
            boolean hasTiles = materialDefHasParam(material, "Tiles")
                    || materialDefHasParam(material, "TilesU")
                    || materialDefHasParam(material, "TilesV");
            boolean hasSelected = materialDefHasParam(material, "SelectedTile");
            String textureParams =
                    formatTextureParamPresence(material, "Texture", "ColorMap", "AlbedoMap", "BaseColorMap");
            logUvVerboseInfo(
                    "atlas_decode binding: geom={} matDefPath={} param={} paramInDef={} texture={} texParams={} has[Mode={} Base={} Tiles={} Sel={}] selectedTile=({}, {}) tiles=({}, {}) baseFloor=({}, {}) descriptorMode={}",
                    geometry.getName(),
                    defPath,
                    texParam,
                    hasParamDef,
                    texName,
                    textureParams,
                    hasMode,
                    hasBase,
                    hasTiles,
                    hasSelected,
                    formatInt(descriptor.selectedTile.x),
                    formatInt(descriptor.selectedTile.y),
                    formatInt(descriptor.tiles.x),
                    formatInt(descriptor.tiles.y),
                    formatInt(descriptor.baseFloor.x),
                    formatInt(descriptor.baseFloor.y),
                    descriptor.mode.label);
        }

        private String formatTextureParamPresence(Material material, String... paramNames) {
            if (material == null || paramNames == null) {
                return "none";
            }
            List<String> present = new ArrayList<>();
            for (String param : paramNames) {
                boolean inDef = material.getMaterialDef() != null
                        && material.getMaterialDef().getMaterialParam(param) != null;
                if (inDef) {
                    present.add(param);
                }
            }
            if (present.isEmpty()) {
                return "none";
            }
            return String.join(",", present);
        }

        private void dumpAlbedoTextures() {
            if (modelRoot == null) {
                return;
            }
            String modelStem = modelPath.getFileName().toString();
            int dot = modelStem.lastIndexOf('.');
            if (dot > 0) {
                modelStem = modelStem.substring(0, dot);
            }
            modelStem = sanitizeName(modelStem);
            Path outputDir =
                    Paths.get(System.getProperty("user.home"), ".kepplr", "glbviewer", "texture-dumps", modelStem);
            try {
                Files.createDirectories(outputDir);
            } catch (Exception e) {
                logger.warn("Failed to create texture dump directory {}: {}", outputDir, e.getMessage());
                return;
            }

            Map<String, Texture> unique = new LinkedHashMap<>();
            for (Geometry geometry : geometryList) {
                Material original = getOriginalMaterial(geometry);
                if (original == null) {
                    continue;
                }
                Texture albedo = findAlbedoTexture(original);
                if (albedo == null) {
                    continue;
                }
                String key = textureKeyName(albedo);
                unique.putIfAbsent(key, albedo);
            }

            int dumped = 0;
            dumpedTextureNames.clear();
            for (Map.Entry<String, Texture> entry : unique.entrySet()) {
                if (writeTexturePng(entry.getValue(), entry.getKey(), outputDir)) {
                    dumped++;
                }
            }
            logger.info("Dumped {} albedo textures to {}", dumped, outputDir);
        }

        private void dumpPbrTextures() {
            if (modelRoot == null) {
                return;
            }
            String modelStem = modelPath.getFileName().toString();
            int dot = modelStem.lastIndexOf('.');
            if (dot > 0) {
                modelStem = modelStem.substring(0, dot);
            }
            modelStem = sanitizeName(modelStem);
            Path outputDir = Paths.get(
                    System.getProperty("user.home"), ".kepplr", "glbviewer", "texture-dumps", modelStem, "pbr");
            try {
                Files.createDirectories(outputDir);
            } catch (Exception e) {
                logger.warn("Failed to create texture dump directory {}: {}", outputDir, e.getMessage());
                return;
            }

            Map<String, Texture> unique = new LinkedHashMap<>();
            for (Geometry geometry : geometryList) {
                Material original = getOriginalMaterial(geometry);
                if (original == null) {
                    continue;
                }
                addTextureIfPresent(unique, original, "BaseColorMap");
                addTextureIfPresent(unique, original, "DiffuseMap");
                addTextureIfPresent(unique, original, "ColorMap");
                addTextureIfPresent(unique, original, "NormalMap");
                addTextureIfPresent(unique, original, "MetallicRoughnessMap");
                addTextureIfPresent(unique, original, "OcclusionMap");
                addTextureIfPresent(unique, original, "EmissiveMap");
            }

            int dumped = 0;
            dumpedTextureNames.clear();
            for (Map.Entry<String, Texture> entry : unique.entrySet()) {
                if (writeTexturePng(entry.getValue(), entry.getKey(), outputDir)) {
                    dumped++;
                }
            }
            logger.info("Dumped {} PBR textures to {}", dumped, outputDir);
        }

        private void addTextureIfPresent(Map<String, Texture> unique, Material material, String paramName) {
            Texture texture = getTextureParam(material, paramName);
            if (texture == null) {
                return;
            }
            unique.putIfAbsent(textureKeyName(texture), texture);
        }

        private void applyAlbedoSamplingPreset(SamplingPreset preset) {
            if (geometryList.isEmpty()) {
                logger.info("No geometries to update albedo sampling");
                return;
            }
            if (currentSamplingPreset == preset) {
                restoreAlbedoSamplingOriginal();
                return;
            }
            Set<Texture> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Geometry geometry : geometryList) {
                Texture texture = getActiveAlbedoTexture(geometry);
                if (texture == null || !visited.add(texture)) {
                    continue;
                }
                originalSamplerStates.putIfAbsent(texture, SamplerState.capture(texture));
                SamplerState original = originalSamplerStates.get(texture);
                SamplerState next = preset.apply(original);
                SamplerState prev = SamplerState.capture(texture);
                texture.setMinFilter(next.minFilter);
                texture.setMagFilter(next.magFilter);
                texture.setAnisotropicFilter(next.anisotropy);
                logger.info(
                        "Albedo sampling {}: {} present=true min {} -> {}, mag {} -> {}, aniso {} -> {}",
                        preset.label,
                        textureKeyName(texture),
                        prev.minFilter,
                        next.minFilter,
                        prev.magFilter,
                        next.magFilter,
                        prev.anisotropy,
                        next.anisotropy);
            }
            currentSamplingPreset = preset;
        }

        private void restoreAlbedoSamplingOriginal() {
            if (originalSamplerStates.isEmpty()) {
                logger.info("No original sampler state to restore");
                return;
            }
            Set<Texture> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Geometry geometry : geometryList) {
                Texture texture = getActiveAlbedoTexture(geometry);
                if (texture == null || !visited.add(texture)) {
                    continue;
                }
                SamplerState original = originalSamplerStates.get(texture);
                if (original == null) {
                    continue;
                }
                SamplerState prev = SamplerState.capture(texture);
                texture.setMinFilter(original.minFilter);
                texture.setMagFilter(original.magFilter);
                texture.setAnisotropicFilter(original.anisotropy);
                logger.info(
                        "Albedo sampling restore: {} min {} -> {}, mag {} -> {}, aniso {} -> {}",
                        textureKeyName(texture),
                        prev.minFilter,
                        original.minFilter,
                        prev.magFilter,
                        original.magFilter,
                        prev.anisotropy,
                        original.anisotropy);
            }
            currentSamplingPreset = null;
        }

        private Texture getActiveAlbedoTexture(Geometry geometry) {
            if (geometry == null) {
                return null;
            }
            Material material = unlitDebugEnabled ? getOriginalMaterial(geometry) : geometry.getMaterial();
            if (material == null) {
                return null;
            }
            return findAlbedoTexture(material);
        }

        private void logUvBounds(Mesh mesh) {
            if (!uvVerbose) {
                return;
            }
            UvBounds bounds = computeUvBounds(mesh);
            if (bounds == null) {
                logger.info("  UV0: <none>");
                return;
            }
            double floorMinU = Math.floor(bounds.minU);
            double ceilMaxU = Math.ceil(bounds.maxU);
            double floorMinV = Math.floor(bounds.minV);
            double ceilMaxV = Math.ceil(bounds.maxV);
            double tilesU = ceilMaxU - floorMinU;
            double tilesV = ceilMaxV - floorMinV;
            double spanU = bounds.maxU - bounds.minU;
            double spanV = bounds.maxV - bounds.minV;
            logger.info(String.format(
                    "  UV0 bounds: min(%.6f, %.6f) max(%.6f, %.6f) outOfRange=%s span(%.6f, %.6f) tiles(%.0f, %.0f) floor/ceil U(%.0f/%.0f) V(%.0f/%.0f)",
                    bounds.minU,
                    bounds.minV,
                    bounds.maxU,
                    bounds.maxV,
                    bounds.outOfRange,
                    spanU,
                    spanV,
                    tilesU,
                    tilesV,
                    floorMinU,
                    ceilMaxU,
                    floorMinV,
                    ceilMaxV));
            UvOffsetStats stats = computeUvOffsetStats(mesh, bounds);
            if (stats != null) {
                logger.info(String.format(
                        Locale.ROOT,
                        "  UV0 offset: meanOffset(%s,%s) withinEps(%s%%, %s%%) eps=+/-%.2f",
                        formatInt(stats.meanOffsetU),
                        formatInt(stats.meanOffsetV),
                        formatPercent(stats.percentWithinEpsU),
                        formatPercent(stats.percentWithinEpsV),
                        UV_OFFSET_EPSILON));
            }
        }

        private UvBounds computeUvBounds(Mesh mesh) {
            if (mesh == null) {
                return null;
            }
            VertexBuffer tex = mesh.getBuffer(VertexBuffer.Type.TexCoord);
            if (tex == null || !(tex.getData() instanceof FloatBuffer fb)) {
                return null;
            }
            FloatBuffer buf = fb.duplicate();
            buf.rewind();
            if (buf.remaining() < 2) {
                return null;
            }
            float minU = Float.POSITIVE_INFINITY;
            float minV = Float.POSITIVE_INFINITY;
            float maxU = Float.NEGATIVE_INFINITY;
            float maxV = Float.NEGATIVE_INFINITY;
            boolean outOfRange = false;
            while (buf.remaining() >= 2) {
                float u = buf.get();
                float v = buf.get();
                minU = Math.min(minU, u);
                minV = Math.min(minV, v);
                maxU = Math.max(maxU, u);
                maxV = Math.max(maxV, v);
                if (u < -UV_EPSILON || u > 1f + UV_EPSILON || v < -UV_EPSILON || v > 1f + UV_EPSILON) {
                    outOfRange = true;
                }
            }
            return new UvBounds(minU, minV, maxU, maxV, outOfRange);
        }

        private UvTileStats computeUvTileStats(Mesh mesh) {
            UvBounds bounds = computeUvBounds(mesh);
            if (bounds == null) {
                return null;
            }
            UvOffsetStats stats = computeUvOffsetStats(mesh, bounds);
            double floorMinU = Math.floor(bounds.minU);
            double floorMinV = Math.floor(bounds.minV);
            double ceilMaxU = Math.ceil(bounds.maxU);
            double ceilMaxV = Math.ceil(bounds.maxV);
            double tilesU = Math.max(1.0, ceilMaxU - floorMinU);
            double tilesV = Math.max(1.0, ceilMaxV - floorMinV);
            double spanU = bounds.maxU - bounds.minU;
            double spanV = bounds.maxV - bounds.minV;
            boolean uLooksOffset = tilesU <= 1.0 && floorMinU == 1.0 && ceilMaxU == 2.0 && spanU >= 0.8 && spanU <= 1.2;
            boolean vLooksOffset = tilesV <= 1.0 && floorMinV == 1.0 && ceilMaxV == 2.0 && spanV >= 0.8 && spanV <= 1.2;
            if (uLooksOffset) {
                if (stats == null
                        || (stats.percentWithinEpsU >= UV_OFFSET_THRESHOLD && Math.abs(stats.meanOffsetU - 1.0) <= 0.2)
                        || bounds.outOfRange) {
                    tilesU = 2.0;
                }
            }
            if (vLooksOffset) {
                if (stats == null
                        || (stats.percentWithinEpsV >= UV_OFFSET_THRESHOLD && Math.abs(stats.meanOffsetV - 1.0) <= 0.2)
                        || bounds.outOfRange) {
                    tilesV = 2.0;
                }
            }
            return new UvTileStats(floorMinU, floorMinV, ceilMaxU, ceilMaxV, tilesU, tilesV);
        }

        private UvTransformDescriptor buildUvTransformDescriptor(Geometry geometry) {
            Mesh mesh = geometry != null ? geometry.getMesh() : null;
            UvBounds bounds = computeUvBounds(mesh);
            UvOffsetStats stats = computeUvOffsetStats(mesh, bounds);
            UvTileStats tiles = computeUvTileStats(mesh);
            Vector2f offset = new Vector2f(0f, 0f);
            boolean applyU = false;
            boolean applyV = false;
            if (uvTransformMode == UvTransformMode.OFF || uvTransformMode == UvTransformMode.ATLAS_DECODE) {
                // no offset
            } else if (stats != null) {
                if (stats.percentWithinEpsU >= UV_OFFSET_THRESHOLD) {
                    offset.x = (float) stats.meanOffsetU;
                    applyU = true;
                }
                if (stats.percentWithinEpsV >= UV_OFFSET_THRESHOLD) {
                    offset.y = (float) stats.meanOffsetV;
                    applyV = true;
                }
            }
            Vector2f tilesVec =
                    new Vector2f(tiles != null ? (float) tiles.tilesU : 1f, tiles != null ? (float) tiles.tilesV : 1f);
            Vector2f baseFloor = new Vector2f(
                    tiles != null ? (float) tiles.floorMinU : 0f, tiles != null ? (float) tiles.floorMinV : 0f);
            if (uvTransformMode == UvTransformMode.ATLAS_DECODE) {
                int atlasTilesU = 1;
                int tilesU = Math.max(1, atlasTilesU);
                int atlasTilesV = 1;
                int tilesV = Math.max(1, atlasTilesV);
                if (tiles != null) {
                    tilesU = Math.max(tilesU, (int) Math.round(tiles.tilesU));
                    tilesV = Math.max(tilesV, (int) Math.round(tiles.tilesV));
                    baseFloor.x = (float) tiles.floorMinU;
                    baseFloor.y = (float) tiles.floorMinV;
                }
                if (stats != null) {
                    if (stats.percentWithinEpsU >= UV_OFFSET_THRESHOLD) {
                        double meanU = Math.rint(stats.meanOffsetU);
                        baseFloor.x = (float) meanU;
                        if (tilesU == 1 && Math.abs(meanU) >= 1.0) {
                            tilesU = Math.max(tilesU, (int) Math.abs(meanU) + 1);
                        }
                    }
                    if (stats.percentWithinEpsV >= UV_OFFSET_THRESHOLD) {
                        double meanV = Math.rint(stats.meanOffsetV);
                        baseFloor.y = (float) meanV;
                        if (tilesV == 1 && Math.abs(meanV) >= 1.0) {
                            tilesV = Math.max(tilesV, (int) Math.abs(meanV) + 1);
                        }
                    }
                }
                if (tilesU == 1 && Math.abs(baseFloor.x) >= 1.0f) {
                    tilesU = Math.max(tilesU, (int) Math.abs(baseFloor.x) + 1);
                }
                if (tilesV == 1 && Math.abs(baseFloor.y) >= 1.0f) {
                    tilesV = Math.max(tilesV, (int) Math.abs(baseFloor.y) + 1);
                }
                tilesVec = new Vector2f(tilesU, tilesV);
            }
            int clampedU = selectedTileU;
            int clampedV = selectedTileV;
            int clampMaxU = Math.max(0, Math.round(tilesVec.x) - 1);
            int clampMaxV = Math.max(0, Math.round(tilesVec.y) - 1);
            clampedU = clampInt(selectedTileU, 0, clampMaxU);
            clampedV = clampInt(selectedTileV, 0, clampMaxV);
            Vector2f selectedTile = new Vector2f(clampedU, clampedV);
            boolean offsetEnabled = uvTransformMode == UvTransformMode.OFF ? false : (applyU || applyV);
            if (!applyU) {
                offset.x = 0f;
            }
            if (!applyV) {
                offset.y = 0f;
            }
            return new UvTransformDescriptor(
                    uvTransformMode, offset, tilesVec, selectedTile, baseFloor, offsetEnabled, applyU, applyV);
        }

        private UvOffsetStats computeUvOffsetStats(Mesh mesh, UvBounds bounds) {
            if (mesh == null || bounds == null) {
                return null;
            }
            VertexBuffer tex = mesh.getBuffer(VertexBuffer.Type.TexCoord);
            if (tex == null || !(tex.getData() instanceof FloatBuffer fb)) {
                return null;
            }
            FloatBuffer buf = fb.duplicate();
            buf.rewind();
            int total = buf.remaining() / 2;
            if (total <= 0) {
                return null;
            }
            double sumU = 0.0;
            double sumV = 0.0;
            int samples = 0;
            for (int i = 0; i < total; i++) {
                int pos = i * 2;
                if (pos + 1 >= buf.limit()) {
                    break;
                }
                float u = buf.get(pos);
                float v = buf.get(pos + 1);
                sumU += u;
                sumV += v;
                samples++;
            }
            if (samples == 0) {
                return null;
            }
            double meanU = sumU / samples;
            double meanV = sumV / samples;
            double meanOffsetU = Math.rint(meanU);
            double meanOffsetV = Math.rint(meanV);

            int withinU = 0;
            int withinV = 0;
            samples = 0;
            for (int i = 0; i < total; i++) {
                int pos = i * 2;
                if (pos + 1 >= buf.limit()) {
                    break;
                }
                float u = buf.get(pos);
                float v = buf.get(pos + 1);
                double fu = u - meanOffsetU;
                double fv = v - meanOffsetV;
                if (fu >= -UV_OFFSET_EPSILON && fu <= 1.0 + UV_OFFSET_EPSILON) {
                    withinU++;
                }
                if (fv >= -UV_OFFSET_EPSILON && fv <= 1.0 + UV_OFFSET_EPSILON) {
                    withinV++;
                }
                samples++;
            }
            double percentU = samples > 0 ? (double) withinU / samples : 0.0;
            double percentV = samples > 0 ? (double) withinV / samples : 0.0;
            return new UvOffsetStats(meanOffsetU, meanOffsetV, percentU, percentV);
        }

        private record SamplerState(Texture.MinFilter minFilter, Texture.MagFilter magFilter, int anisotropy) {
            private static SamplerState capture(Texture texture) {
                return new SamplerState(texture.getMinFilter(), texture.getMagFilter(), texture.getAnisotropicFilter());
            }
        }

        private enum SamplingPreset {
            NEAREST("nearest", Texture.MinFilter.NearestNoMipMaps, Texture.MagFilter.Nearest, 0),
            LINEAR("linear", Texture.MinFilter.BilinearNoMipMaps, Texture.MagFilter.Bilinear, 8);

            private final String label;
            private final Texture.MinFilter minFilter;
            private final Texture.MagFilter magFilter;
            private final int anisotropy;

            SamplingPreset(String label, Texture.MinFilter minFilter, Texture.MagFilter magFilter, int anisotropy) {
                this.label = label;
                this.minFilter = minFilter;
                this.magFilter = magFilter;
                this.anisotropy = anisotropy;
            }

            private SamplerState apply(SamplerState original) {
                int aniso = anisotropy > 0 ? anisotropy : original.anisotropy;
                return new SamplerState(minFilter, magFilter, aniso);
            }
        }

        private record UvBounds(float minU, float minV, float maxU, float maxV, boolean outOfRange) {}

        private record UvOffsetStats(
                double meanOffsetU, double meanOffsetV, double percentWithinEpsU, double percentWithinEpsV) {}

        private record UvTileStats(
                double floorMinU, double floorMinV, double ceilMaxU, double ceilMaxV, double tilesU, double tilesV) {}

        private record MeshStats(int uvSets, int vertexCount, int triangleCount, boolean hasVertexColors) {}

        private record AtlasDecodeReadiness(boolean ready, String reason) {}

        private record UvTransformDescriptor(
                UvTransformMode mode,
                Vector2f offset,
                Vector2f tiles,
                Vector2f selectedTile,
                Vector2f baseFloor,
                boolean offsetEnabled,
                boolean applyU,
                boolean applyV) {}

        private enum UvDebugMode {
            OFF("off"),
            FRACT("fract"),
            CHECKER("checker"),
            SANITY("sanity");

            private final String label;

            UvDebugMode(String label) {
                this.label = label;
            }
        }

        private enum UvTransformMode {
            OFF("off", 0f),
            OFFSET_ONLY("offset_only", 1f),
            ATLAS_DECODE("atlas_decode", 2f);

            private final String label;
            private final float shaderValue;

            UvTransformMode(String label, float shaderValue) {
                this.label = label;
                this.shaderValue = shaderValue;
            }
        }

        private boolean writeTexturePng(Texture texture, String baseName, Path outputDir) {
            Image image = texture != null ? texture.getImage() : null;
            if (image == null) {
                logger.warn("Skipping texture {}: no image data", baseName);
                return false;
            }
            BufferedImage buffered = toBufferedImage(image);
            if (buffered == null) {
                logger.warn("Skipping texture {}: failed to convert to BufferedImage", baseName);
                return false;
            }
            int width = buffered.getWidth();
            int height = buffered.getHeight();
            String safeName = sanitizeName(baseName);
            String filename = safeName + "_" + width + "x" + height + ".png";
            filename = ensureUniqueFilename(filename);
            Path output = outputDir.resolve(filename);
            try {
                ImageIO.write(buffered, "png", output.toFile());
                logger.info("  Wrote {}", output);
                return true;
            } catch (Exception e) {
                logger.warn("Failed writing texture {}: {}", output, e.getMessage());
                return false;
            }
        }

        private BufferedImage toBufferedImage(Image image) {
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) {
                return null;
            }
            BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
            ImageRaster raster = ImageRaster.create(image);
            ColorRGBA color = new ColorRGBA();
            for (int y = 0; y < height; y++) {
                int dstY = height - 1 - y;
                for (int x = 0; x < width; x++) {
                    raster.getPixel(x, y, color);
                    int a = (int) (clamp01(color.a) * 255f);
                    int r = (int) (clamp01(color.r) * 255f);
                    int g = (int) (clamp01(color.g) * 255f);
                    int b = (int) (clamp01(color.b) * 255f);
                    int argb = (a << 24) | (r << 16) | (g << 8) | b;
                    buffered.setRGB(x, dstY, argb);
                }
            }
            return buffered;
        }

        private float clamp01(float value) {
            if (value < 0f) {
                return 0f;
            }
            if (value > 1f) {
                return 1f;
            }
            return value;
        }

        private String textureKeyName(Texture texture) {
            if (texture == null) {
                return "<null>";
            }
            String name = texture.getName();
            if ((name == null || name.isBlank()) && texture.getKey() != null) {
                name = texture.getKey().toString();
            }
            if (name == null || name.isBlank()) {
                return "<unnamed>";
            }
            return name;
        }

        private String sanitizeName(String name) {
            if (name == null || name.isBlank()) {
                return "unnamed";
            }
            return name.replaceAll("[^A-Za-z0-9._-]+", "_");
        }

        private String ensureUniqueFilename(String filename) {
            String candidate = filename;
            int index = 1;
            while (!dumpedTextureNames.add(candidate)) {
                int dot = filename.lastIndexOf('.');
                String base = dot >= 0 ? filename.substring(0, dot) : filename;
                String ext = dot >= 0 ? filename.substring(dot) : "";
                candidate = base + "_" + index + ext;
                index++;
            }
            return candidate;
        }

        private void ensureAxes() {
            if (worldAxesNode == null) {
                float length = Math.max(modelRadius * 1.5f, 1f);
                worldAxesNode = buildAxesNode(length);
                worldAxesNode.setName("worldAxes");
                rootNode.attachChild(worldAxesNode);
                worldAxesNode.setCullHint(Spatial.CullHint.Always);
            }
        }

        private void collectGeometries(Spatial spatial, String path) {
            if (spatial instanceof Geometry geometry) {
                geometryList.add(geometry);
                geometryPaths.put(geometry, path);
                return;
            }
            if (spatial instanceof Node node) {
                for (Spatial child : node.getChildren()) {
                    String childName = child.getName() != null ? child.getName() : "<unnamed>";
                    collectGeometries(child, path + "/" + childName);
                }
            }
        }

        /**
         * Centers the model (and any attached debug axes) by translating modelRoot so the average vertex position is at
         * (0,0,0).
         *
         * <p>We translate modelRoot (not just the model) so that axes remain co-located at the origin of the centered
         * geometry.
         */
        private void centerModelRootAtOrigin() {
            modelRoot.updateGeometricState();
            Vector3f average = computeAverageVertexPositionInLocal(modelRoot);
            if (average == null) {
                BoundingVolume bound = modelRoot.getWorldBound();
                if (bound == null) {
                    return;
                }
                // Convert world-center back into modelRoot local space.
                average = modelRoot.worldToLocal(bound.getCenter(), null);
            }
            modelRoot.setLocalTranslation(modelRoot.getLocalTranslation().subtract(average));
        }

        private void configureCamera() {
            // Use modelRoot bounds so the camera framing matches the same transforms applied to the model.
            modelRoot.updateGeometricState();
            BoundingVolume bound = modelRoot.getWorldBound();

            float radius = 1f;
            if (bound instanceof BoundingSphere sphere) {
                radius = sphere.getRadius();
            } else if (bound instanceof BoundingBox box) {
                radius = Math.max(box.getXExtent(), Math.max(box.getYExtent(), box.getZExtent()));
            } else if (bound != null) {
                radius = Math.max(bound.getVolume(), 1f);
            }

            modelRadius = radius;

            orbitDistance = Math.max(modelRadius * 3f, 2f);

            // Start from an asymmetric view: rotate a bit around Y then around X
            orbitRot = new Quaternion()
                    .fromAngleAxis(0.7f, Vector3f.UNIT_Y)
                    .mult(new Quaternion().fromAngleAxis(0.35f, Vector3f.UNIT_X));

            updateOrbitCamera();
            flyCam.setEnabled(false);
        }

        private void updateOrbitCamera() {
            // Camera sits on +Z in orbit space, then gets rotated around the model
            Vector3f offset = orbitRot.mult(new Vector3f(0f, 0f, orbitDistance));
            cam.setLocation(offset);
            Vector3f up = orbitRot.mult(Vector3f.UNIT_Y);
            if (up.lengthSquared() == 0f) {
                up = Vector3f.UNIT_Y;
            }
            cam.lookAt(Vector3f.ZERO, up);
        }

        private void addLights() {
            AmbientLight ambient = new AmbientLight();
            ambient.setColor(ColorRGBA.White.mult(0.4f));
            rootNode.addLight(ambient);

            DirectionalLight dir = new DirectionalLight();
            dir.setColor(ColorRGBA.White);
            dir.setDirection(new Vector3f(-1f, -1f, -1f).normalizeLocal());
            rootNode.addLight(dir);
        }

        private void setupInput() {
            inputManager.addMapping(ROTATE_LEFT, new KeyTrigger(KeyInput.KEY_LEFT));
            inputManager.addMapping(ROTATE_RIGHT, new KeyTrigger(KeyInput.KEY_RIGHT));
            inputManager.addMapping(ROTATE_UP, new KeyTrigger(KeyInput.KEY_UP));
            inputManager.addMapping(ROTATE_DOWN, new KeyTrigger(KeyInput.KEY_DOWN));
            inputManager.addMapping(TOGGLE_AXES, new KeyTrigger(KeyInput.KEY_X));
            inputManager.addMapping(ZOOM_IN, new KeyTrigger(KeyInput.KEY_PGUP));
            inputManager.addMapping(ZOOM_OUT, new KeyTrigger(KeyInput.KEY_PGDN));
            inputManager.addMapping(PRESET_QUALITY, new KeyTrigger(KeyInput.KEY_F6));
            inputManager.addMapping(PRESET_NOMIP, new KeyTrigger(KeyInput.KEY_F7));
            inputManager.addMapping(PRESET_NEAREST, new KeyTrigger(KeyInput.KEY_F8));
            inputManager.addMapping(PRESET_PREV, new KeyTrigger(KeyInput.KEY_9));
            inputManager.addMapping(PRESET_NEXT, new KeyTrigger(KeyInput.KEY_0));
            inputManager.addMapping(CYCLE_UV_DEBUG, new KeyTrigger(KeyInput.KEY_U));
            inputManager.addMapping(CYCLE_UV_TRANSFORM, new KeyTrigger(KeyInput.KEY_G));
            inputManager.addMapping(TILE_U_DEC, new KeyTrigger(KeyInput.KEY_J));
            inputManager.addMapping(TILE_U_INC, new KeyTrigger(KeyInput.KEY_L));
            inputManager.addMapping(TILE_V_INC, new KeyTrigger(KeyInput.KEY_I));
            inputManager.addMapping(TILE_V_DEC, new KeyTrigger(KeyInput.KEY_K));
            inputManager.addMapping(TOGGLE_UNLIT, new KeyTrigger(KeyInput.KEY_Y));
            inputManager.addMapping(DUMP_MATERIALS, new KeyTrigger(KeyInput.KEY_P));
            inputManager.addMapping(CYCLE_ISOLATE, new KeyTrigger(KeyInput.KEY_8));
            inputManager.addMapping(CLEAR_ISOLATION, new KeyTrigger(KeyInput.KEY_7));
            inputManager.addMapping(DUMP_ALBEDO, new KeyTrigger(KeyInput.KEY_6));
            inputManager.addMapping(DUMP_PBR, new KeyTrigger(KeyInput.KEY_5));
            inputManager.addMapping(SAMPLE_NEAREST, new KeyTrigger(KeyInput.KEY_COMMA));
            inputManager.addMapping(SAMPLE_LINEAR, new KeyTrigger(KeyInput.KEY_PERIOD));
            inputManager.addMapping(DUMP_ISOLATED, new KeyTrigger(KeyInput.KEY_O));
            inputManager.addMapping(TOGGLE_TILE_TINT, new KeyTrigger(KeyInput.KEY_T));
            inputManager.addListener(this, ROTATE_LEFT, ROTATE_RIGHT, ROTATE_UP, ROTATE_DOWN, ZOOM_IN, ZOOM_OUT);
            inputManager.addListener(
                    this,
                    TOGGLE_AXES,
                    PRESET_QUALITY,
                    PRESET_NOMIP,
                    PRESET_NEAREST,
                    PRESET_PREV,
                    PRESET_NEXT,
                    CYCLE_UV_DEBUG,
                    CYCLE_UV_TRANSFORM,
                    TILE_U_DEC,
                    TILE_U_INC,
                    TILE_V_INC,
                    TILE_V_DEC,
                    TOGGLE_UNLIT,
                    DUMP_MATERIALS,
                    CYCLE_ISOLATE,
                    CLEAR_ISOLATION,
                    DUMP_ALBEDO,
                    DUMP_PBR,
                    SAMPLE_NEAREST,
                    SAMPLE_LINEAR,
                    DUMP_ISOLATED,
                    TOGGLE_TILE_TINT);

            logger.info(
                    "Input mappings: J={} L={} I={} K={}",
                    inputManager.hasMapping(TILE_U_DEC),
                    inputManager.hasMapping(TILE_U_INC),
                    inputManager.hasMapping(TILE_V_INC),
                    inputManager.hasMapping(TILE_V_DEC));
        }

        @Override
        public void simpleUpdate(float tpf) {
            if (sizeText == null) {
                return;
            }
            sizeText.setLocalTranslation(10f, cam.getHeight() - 10f, 0f);
            updateDebugHud();
            if (uvTransformMode == UvTransformMode.ATLAS_DECODE) {
                for (Geometry geometry : geometryList) {
                    Material material = geometry.getMaterial();
                    if (material == null || material.getMaterialDef() == null) {
                        continue;
                    }
                    if (!"AtlasDecodeUnshaded".equals(material.getMaterialDef().getName())) {
                        continue;
                    }
                    int matId = System.identityHashCode(material);
                    Integer lastId = materialIds.put(geometry, matId);
                    if (lastId == null || lastId != matId) {
                        logUvVerboseDebug(
                                "Material replaced (frame): geom={} oldMatId={} newMatId={} def={}",
                                geometry.getName(),
                                lastId,
                                matId,
                                material.getMaterialDef().getName());
                        applyAtlasDecodeParamsToGeometry(geometry);
                    }
                }
            }
        }

        private void updateDebugHud() {
            if (guiFont == null) {
                return;
            }
            if (debugHudText == null) {
                debugHudText = new BitmapText(guiFont, false);
                debugHudText.setSize(guiFont.getCharSet().getRenderedSize());
                debugHudText.setColor(ColorRGBA.White);
                guiNode.attachChild(debugHudText);
            }
            String geomLabel = "all";
            if (isolateIndex >= 0 && isolateIndex < geometryList.size()) {
                Geometry geometry = geometryList.get(isolateIndex);
                geomLabel = String.format("%d/%d:%s", isolateIndex + 1, geometryList.size(), geometry.getName());
            }
            String uvMode = uvDebugMode.label;
            String albedo = unlitDebugEnabled ? "on" : "off";
            String tileLabel = "";
            if (isolateIndex >= 0 && isolateIndex < geometryList.size()) {
                Geometry geometry = geometryList.get(isolateIndex);
                UvTransformDescriptor descriptor = buildUvTransformDescriptor(geometry);
                tileLabel = String.format(
                        " tile=(%s,%s)", formatInt(descriptor.selectedTile.x), formatInt(descriptor.selectedTile.y));
            }
            String hud = String.format(
                    "geom=%s  uvMode=%s  uvTransform=%s%s  albedo=%s",
                    geomLabel, uvMode, uvTransformMode.label, tileLabel, albedo);
            debugHudText.setText(hud);
            debugHudText.setLocalTranslation(10f, cam.getHeight() - 30f, 0f);
        }

        @Override
        public void onAnalog(String name, float value, float tpf) {
            float a = ROTATE_SPEED * value;

            switch (name) {
                case ROTATE_LEFT -> orbitRot = orbitRot.mult(new Quaternion().fromAngleAxis(+a, Vector3f.UNIT_Y));
                case ROTATE_RIGHT -> orbitRot = orbitRot.mult(new Quaternion().fromAngleAxis(-a, Vector3f.UNIT_Y));
                case ROTATE_UP -> orbitRot = orbitRot.mult(new Quaternion().fromAngleAxis(+a, Vector3f.UNIT_X));
                case ROTATE_DOWN -> orbitRot = orbitRot.mult(new Quaternion().fromAngleAxis(-a, Vector3f.UNIT_X));

                case ZOOM_IN -> orbitDistance = Math.max(0.1f, orbitDistance - ZOOM_SPEED * value);
                case ZOOM_OUT -> orbitDistance += ZOOM_SPEED * value;

                default -> {}
            }

            orbitRot.normalizeLocal();
            updateOrbitCamera();
        }

        @Override
        public void onAction(String name, boolean isPressed, float tpf) {
            if (!isPressed) {
                return;
            }
            switch (name) {
                case TOGGLE_AXES -> toggleAxes();
                case PRESET_QUALITY -> applySamplerPreset(modelRoot, SamplerPreset.QUALITY_DEFAULT, "F6");
                case PRESET_NOMIP -> applySamplerPreset(modelRoot, SamplerPreset.NO_MIPMAPS_DEBUG, "F7");
                case PRESET_NEAREST -> applySamplerPreset(modelRoot, SamplerPreset.NEAREST_DEBUG, "F8");
                case PRESET_PREV -> handlePrevNextKey(-1);
                case PRESET_NEXT -> handlePrevNextKey(1);
                case CYCLE_UV_DEBUG -> cycleUvDebugMode();
                case CYCLE_UV_TRANSFORM -> cycleUvTransform(1);
                case TILE_U_DEC -> adjustSelectedTile(-1, 0);
                case TILE_U_INC -> adjustSelectedTile(1, 0);
                case TILE_V_INC -> adjustSelectedTile(0, 1);
                case TILE_V_DEC -> adjustSelectedTile(0, -1);
                case TOGGLE_UNLIT -> toggleUnlitDebug();
                case DUMP_MATERIALS -> dumpMaterialDiagnostics();
                case CYCLE_ISOLATE -> cycleIsolation();
                case CLEAR_ISOLATION -> clearIsolation();
                case DUMP_ALBEDO -> dumpAlbedoTextures();
                case DUMP_PBR -> dumpPbrTextures();
                case SAMPLE_NEAREST -> applyAlbedoSamplingPreset(SamplingPreset.NEAREST);
                case SAMPLE_LINEAR -> applyAlbedoSamplingPreset(SamplingPreset.LINEAR);
                case DUMP_ISOLATED -> dumpIsolatedProvenance();
                case TOGGLE_TILE_TINT -> toggleDebugTileTint();
                default -> {}
            }
        }

        private void logUvStateChange(String source) {
            String selectedSummary = String.format("(%d,%d)", selectedTileU, selectedTileV);
            String tilesSummary = "n/a";
            String baseSummary = "n/a";
            UvTransformDescriptor descriptor = null;
            if (isolateIndex >= 0 && isolateIndex < geometryList.size()) {
                Geometry geometry = geometryList.get(isolateIndex);
                descriptor = buildUvTransformDescriptor(geometry);
                UvTileStats tiles = computeUvTileStats(geometry.getMesh());
                if (tiles != null) {
                    tilesSummary = String.format("(%s,%s)", formatInt(tiles.tilesU), formatInt(tiles.tilesV));
                }
            } else if (uvTransformMode != UvTransformMode.OFF) {
                descriptor = currentUvDescriptor != null ? currentUvDescriptor : resolveActiveUvTransformDescriptor();
                if (descriptor != null) {
                    tilesSummary =
                            String.format("(%s,%s)", formatInt(descriptor.tiles.x), formatInt(descriptor.tiles.y));
                } else if (uvVerbose) {
                    logger.warn("UV transform descriptor is null (BUG: not computed for current selection)");
                }
            }
            if (descriptor != null) {
                selectedSummary = String.format(
                        "(%s,%s)", formatInt(descriptor.selectedTile.x), formatInt(descriptor.selectedTile.y));
                baseSummary =
                        String.format("(%s,%s)", formatInt(descriptor.baseFloor.x), formatInt(descriptor.baseFloor.y));
            }
            if (uvVerbose) {
                logUvVerboseInfo(
                        "{}: uvDebug={} uvTransform={} selectedTile={} tiles={} uvBase={} tint={}",
                        source,
                        uvDebugMode.label,
                        uvTransformMode.label,
                        selectedSummary,
                        tilesSummary,
                        baseSummary,
                        debugTileTintEnabled);
                return;
            }
            logger.info(
                    "UV mode: uvDebug={} uvTransform={} tile={} tint={}",
                    uvDebugMode.label,
                    uvTransformMode.label,
                    selectedSummary,
                    debugTileTintEnabled);
        }

        private void logUvDebugAtlasState(Geometry geometry, UvTransformDescriptor descriptor) {
            if (!uvVerbose || geometry == null || descriptor == null) {
                return;
            }
            if (uvDebugMode == UvDebugMode.OFF) {
                return;
            }
            logger.info(
                    "UV debug atlas: geom={} uvDebug={} uvTransform={} tiles=({}, {}) uvBase=({}, {}) selectedTile=({}, {})",
                    geometry.getName(),
                    uvDebugMode.label,
                    uvTransformMode.label,
                    formatInt(descriptor.tiles.x),
                    formatInt(descriptor.tiles.y),
                    formatInt(descriptor.baseFloor.x),
                    formatInt(descriptor.baseFloor.y),
                    formatInt(descriptor.selectedTile.x),
                    formatInt(descriptor.selectedTile.y));
        }

        private int clampInt(int value, int min, int max) {
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
        }

        private void toggleAxes() {
            ensureAxes();
            axesVisible = !axesVisible;
            Spatial.CullHint hint = axesVisible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
            worldAxesNode.setCullHint(hint);
        }

        private Node buildAxesNode(float length) {
            Node node = new Node("axes");
            node.attachChild(buildAxisArrow("axis-x", new Vector3f(1f, 0f, 0f), ColorRGBA.Red, length));
            node.attachChild(buildAxisArrow("axis-y", new Vector3f(0f, 1f, 0f), ColorRGBA.Green, length));
            node.attachChild(buildAxisArrow("axis-z", new Vector3f(0f, 0f, 1f), ColorRGBA.Blue, length));
            node.setCullHint(Spatial.CullHint.Always);
            return node;
        }

        private Geometry buildAxisArrow(String name, Vector3f dir, ColorRGBA color, float length) {
            Arrow arrow = new Arrow(dir.normalize().mult(length));
            arrow.setLineWidth(2f);
            Geometry geom = new Geometry(name, arrow);
            Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            mat.setColor("Color", color);
            geom.setMaterial(mat);
            return geom;
        }

        private void updateSizeAnnotation() {
            if (modelRoot == null || guiFont == null) {
                return;
            }

            // Use bounds of modelRoot so it reflects the same transform applied to the scene.
            modelRoot.updateModelBound();
            BoundingVolume bound = modelRoot.getWorldBound();
            if (bound == null) {
                return;
            }

            float sizeX;
            float sizeY;
            float sizeZ;
            float radius;

            if (bound instanceof BoundingBox box) {
                sizeX = box.getXExtent() * 2f;
                sizeY = box.getYExtent() * 2f;
                sizeZ = box.getZExtent() * 2f;
                radius = Math.max(box.getXExtent(), Math.max(box.getYExtent(), box.getZExtent()));
            } else if (bound instanceof BoundingSphere sphere) {
                radius = sphere.getRadius();
                sizeX = radius * 2f;
                sizeY = radius * 2f;
                sizeZ = radius * 2f;
            } else {
                radius = Math.max(bound.getVolume(), 1f);
                sizeX = radius * 2f;
                sizeY = radius * 2f;
                sizeZ = radius * 2f;
            }

            if (sizeText == null) {
                sizeText = new BitmapText(guiFont, false);
                sizeText.setSize(guiFont.getCharSet().getRenderedSize());
                sizeText.setColor(ColorRGBA.White);
                guiNode.attachChild(sizeText);
            }

            sizeText.setText(String.format("Size: %.3f x %.3f x %.3f (R=%.3f)", sizeX, sizeY, sizeZ, radius));
            sizeText.setLocalTranslation(10f, cam.getHeight() - 10f, 0f);
        }

        /**
         * Compute average vertex position in the *local coordinates of the reference root*.
         *
         * <p>This makes centering robust even if the model is a Node containing many geometries.
         */
        private Vector3f computeAverageVertexPositionInLocal(Node referenceRoot) {
            Vector3f sum = new Vector3f();
            int[] count = new int[1];
            accumulateVertexPositions(referenceRoot, referenceRoot, sum, count);
            if (count[0] == 0) {
                return null;
            }
            return sum.divide((float) count[0]);
        }

        /**
         * Accumulate vertex positions from all geometries under {@code spatial}, transforming each vertex into the
         * local space of {@code referenceRoot}.
         */
        private void accumulateVertexPositions(Spatial spatial, Node referenceRoot, Vector3f sum, int[] count) {
            if (spatial instanceof Geometry geometry) {
                Mesh mesh = geometry.getMesh();
                VertexBuffer posBuffer = mesh.getBuffer(VertexBuffer.Type.Position);
                if (posBuffer == null) {
                    return;
                }

                FloatBuffer positions = (FloatBuffer) posBuffer.getData();
                int vertexCount = mesh.getVertexCount();

                Vector3f local = new Vector3f();
                Vector3f world = new Vector3f();
                Vector3f rootLocal = new Vector3f();

                for (int i = 0; i < vertexCount; i++) {
                    int p = i * 3;
                    if (p + 2 >= positions.capacity()) {
                        break;
                    }
                    local.set(positions.get(p), positions.get(p + 1), positions.get(p + 2));
                    geometry.localToWorld(local, world);
                    referenceRoot.worldToLocal(world, rootLocal);
                    sum.addLocal(rootLocal);
                    count[0]++;
                }
                return;
            }

            if (spatial instanceof Node node) {
                for (Spatial child : node.getChildren()) {
                    accumulateVertexPositions(child, referenceRoot, sum, count);
                }
            }
        }

        /**
         * Logs how the injected quaternion maps unit axes.
         *
         * <p>Useful for debugging orientation: compare these vectors against what you expect (Cosmographia / NAIF FK).
         */
        private void logModelToBodyDebug() {
            Vector3f ex = modelToBodyFixedQuat.mult(Vector3f.UNIT_X);
            Vector3f ey = modelToBodyFixedQuat.mult(Vector3f.UNIT_Y);
            Vector3f ez = modelToBodyFixedQuat.mult(Vector3f.UNIT_Z);

            logger.info("Body-fixed axes in glTF space after Q_modelToBody:");
            logger.info("  +X -> {}", ex);
            logger.info("  +Y -> {}", ey);
            logger.info("  +Z -> {}", ez);

            logger.info("rootNode children count={}", rootNode.getChildren().size());
            for (Spatial s : rootNode.getChildren()) {
                logger.info(
                        "root child: name='{}' type={} id={}",
                        s.getName(),
                        s.getClass().getName(),
                        System.identityHashCode(s));
            }
            logger.info(
                    "modelRoot: name='{}' type={} id={}",
                    modelRoot.getName(),
                    modelRoot.getClass().getName(),
                    System.identityHashCode(modelRoot));
        }
    }
}
