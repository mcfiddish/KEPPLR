package kepplr.render.body;

import com.jme3.asset.AssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.material.MatParamTexture;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingSphere;
import com.jme3.bounding.BoundingVolume;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Sphere;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;
import java.awt.Color;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import kepplr.config.BodyBlock;
import kepplr.config.KEPPLRConfiguration;
import kepplr.config.SpacecraftBlock;
import kepplr.ephemeris.Spacecraft;
import kepplr.render.util.GLTFUtils;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.mechanics.EphemerisID;
import picante.surfaces.Ellipsoid;

/**
 * Creates JME scene-graph node hierarchies for rendered bodies and spacecraft.
 *
 * <p>All methods are static; this class is not instantiated.
 *
 * <h3>Body node hierarchy — ellipsoid (no shape model)</h3>
 *
 * <pre>
 * ephemerisNode
 * ├── bodyFixedNode   (J2000 → body-fixed rotation)
 * │   └── textureAlignNode  (center-longitude yaw)
 * │       └── fullGeom  (textured or untextured ellipsoid)
 * └── spriteGeom  (unit sphere, scaled per frame to constant pixel size)
 * </pre>
 *
 * <h3>Body node hierarchy — GLB shape model (§14.6.3)</h3>
 *
 * <pre>
 * ephemerisNode
 * ├── bodyFixedNode   (J2000 → body-fixed rotation, updated each frame)
 * │   └── glbModelRoot  (localRotation = modelToBodyFixedQuat, set once at load)
 * │       └── [loaded GLB Spatial]
 * └── spriteGeom  (unchanged; used when apparent radius is below the sprite threshold)
 * </pre>
 *
 * <p>When a GLB is present, {@code textureAlignNode} and {@code fullGeom} (the ellipsoid) are created but not attached
 * to the scene. {@code fullGeom} retains its {@code EclipseLighting.j3md} material and {@code "eclipseMaterial"}
 * UserData so that {@link EclipseShadowManager} can continue computing analytic shadow uniforms. Since the ellipsoid is
 * not attached it is never rendered; eclipse shadow application to the GLB geometry nodes is deferred (REDESIGN.md
 * §9.3).
 *
 * <h3>Spacecraft node hierarchy — GLB shape model (§14.6.4)</h3>
 *
 * <pre>
 * ephemerisNode
 * ├── bodyFixedNode
 * │   └── glbModelRoot  (localRotation = modelToBodyFixedQuat; scale = 0.001 × block.scale())
 * │       └── [loaded GLB Spatial, EclipseLighting material applied]
 * └── spriteGeom  (hidden when glbModelRoot is present)
 * </pre>
 *
 * <h3>Sun (NAIF 10)</h3>
 *
 * <p>The Sun uses an {@code Unshaded} material so it renders as fully emissive and is not self-shadowed by the scene's
 * PointLight (REDESIGN.md §7.6).
 *
 * <h3>Missing texture data (§12.3)</h3>
 *
 * <p>If a body's configured texture path is absent or fails to load, the body renders as an untextured sphere in its
 * configured hex color.
 */
public final class BodyNodeFactory {

    private static final Logger logger = LogManager.getLogger();

    /** Fallback mean radius (km) used when a body has no PCK shape data. Logged as a warning. */
    private static final float DEFAULT_RADIUS_KM = 1.0f;

    private BodyNodeFactory() {}

    /**
     * Create a {@link BodySceneNode} for a celestial body.
     *
     * <p>If {@code BodyBlock.shapeModel()} is non-blank, loads the GLB and attaches it as {@code glbModelRoot} under
     * {@code bodyFixedNode}. Falls back to the ellipsoid on any load failure (WARN logged, simulation continues).
     *
     * <p><b>Usage example:</b>
     *
     * <pre>{@code
     * BodySceneNode bsn = BodyNodeFactory.createBodyNode(bodyId, naifId, shape, assetManager);
     * bsn.apply(CullDecision.DRAW_FULL, FrustumLayer.MID, nearNode, midNode, farNode, dist, h, fov);
     * }</pre>
     *
     * @param bodyId body EphemerisID (used for scene node names)
     * @param naifId integer NAIF ID (used to select Sun material and look up BodyBlock)
     * @param shape body ellipsoid from {@code KEPPLREphemeris.getShape()}; null → default radius, untextured
     * @param assetManager JME asset manager
     * @return fully assembled BodySceneNode; fullGeom or glbModelRoot visible, spriteGeom hidden
     */
    public static BodySceneNode createBodyNode(
            EphemerisID bodyId, int naifId, Ellipsoid shape, AssetManager assetManager) {

        float radiusKm = meanRadius(shape, bodyId.getName());

        // Full-geometry ellipsoid (unit sphere scaled to body radius)
        Sphere mesh =
                new Sphere(KepplrConstants.BODY_SPHERE_TESSELLATION, KepplrConstants.BODY_SPHERE_TESSELLATION, 1f);
        mesh.setTextureMode(Sphere.TextureMode.Projected);
        mesh.updateBound();

        Geometry fullGeom = new Geometry(bodyId.getName(), mesh);
        if (shape != null) {
            fullGeom.setLocalScale((float) shape.getA(), (float) shape.getB(), (float) shape.getC());
        } else {
            fullGeom.setLocalScale(radiusKm);
        }
        fullGeom.setMaterial(createBodyMaterial(naifId, bodyId.getName(), assetManager));
        fullGeom.setCullHint(Spatial.CullHint.Inherit);
        fullGeom.setUserData("naifId", naifId);
        if (naifId != KepplrConstants.SUN_NAIF_ID) {
            fullGeom.setUserData("eclipseMaterial", true);
        }

        // Texture-alignment node: yaw by center longitude + π in body-fixed frame.
        // JME Sphere (TextureMode.Projected) places texture U=0 at local −X, so the seam is
        // 180° away from the body-fixed prime meridian (+X). Adding π corrects for that offset
        // so texture longitude 0 aligns with body-fixed longitude 0 when centerLon = 0.
        Node textureAlignNode = new Node(bodyId.getName() + "-tex-align");
        double centerLonRad = lookupCenterLon(bodyId.getName());
        Quaternion texRot = new Quaternion();
        texRot.fromAngleAxis((float) (centerLonRad + Math.PI), Vector3f.UNIT_Z);
        textureAlignNode.setLocalRotation(texRot);
        textureAlignNode.attachChild(fullGeom);

        // Body-fixed node: updated each frame with J2000 → body-fixed rotation.
        Node bodyFixedNode = new Node(bodyId.getName() + "-body-fixed");

        // Attempt to load a GLB shape model if configured.
        Node glbModelRoot = tryLoadBodyGlb(bodyId.getName(), bodyFixedNode, assetManager);

        if (glbModelRoot == null) {
            // No GLB (or load failed): use the standard ellipsoid branch.
            bodyFixedNode.attachChild(textureAlignNode);
        } else {
            // Apply EclipseLighting to the GLB geometries, sharing fullGeom's Material instance.
            // Sharing the same instance means EclipseShadowManager's per-frame uniform updates
            // (SunPosition, OccluderPositions, etc.) on fullGeom.getMaterial() automatically
            // propagate to all GLB geometry nodes — no changes to EclipseShadowManager needed.
            // If the GLB has an embedded BaseColorMap, it overrides the DiffuseMap on the shared
            // material so the shape-model texture is used in preference to any external texture
            // configured in the body block.
            applyEclipseLightingToGlb(glbModelRoot, fullGeom.getMaterial());
        }
        // When glbModelRoot != null, textureAlignNode is intentionally NOT attached to
        // bodyFixedNode. fullGeom (the ellipsoid) is kept as an EclipseShadowManager proxy:
        // its CullHint is managed by BodySceneNode.apply() so the shadow manager can identify
        // this as a DRAW_FULL body. Because textureAlignNode is detached, fullGeom is never
        // rendered.

        // Point-sprite: tiny unit sphere, hidden by default
        Geometry spriteGeom = buildSprite(bodyId.getName(), naifId, assetManager);

        // Ephemeris node: positioned at body's camera-relative location each frame
        Node ephemerisNode = new Node(bodyId.getName() + "-ephemeris");
        ephemerisNode.setUserData("naifId", naifId);
        ephemerisNode.setUserData("bodyRadiusKm", (double) radiusKm);
        ephemerisNode.attachChild(bodyFixedNode);
        ephemerisNode.attachChild(spriteGeom);

        return new BodySceneNode(ephemerisNode, bodyFixedNode, fullGeom, spriteGeom, naifId, glbModelRoot, false);
    }

    /**
     * Create a {@link BodySceneNode} for a spacecraft.
     *
     * <p>If {@code SpacecraftBlock.shapeModel()} is non-blank, loads the GLB (units: meters) and attaches it as
     * {@code glbModelRoot} under {@code bodyFixedNode} with a uniform scale of {@code 0.001 × SpacecraftBlock.scale()}
     * to convert to km. GLB-embedded PBR materials are used as-is. Falls back to the point sprite on any load failure
     * (WARN logged).
     *
     * <p><b>Usage example:</b>
     *
     * <pre>{@code
     * BodySceneNode bsn = BodyNodeFactory.createSpacecraftNode(sc, assetManager);
     * bsn.apply(CullDecision.DRAW_SPRITE, FrustumLayer.FAR, nearNode, midNode, farNode, dist, h, fov);
     * }</pre>
     *
     * @param spacecraft spacecraft descriptor
     * @param assetManager JME asset manager
     * @return BodySceneNode; glbModelRoot visible (if loaded) or spriteGeom visible
     */
    public static BodySceneNode createSpacecraftNode(Spacecraft spacecraft, AssetManager assetManager) {
        String name = spacecraft.id().getName();
        int naifId = spacecraft.code();

        // fullGeom: permanently hidden ellipsoid; always CullHint.Always so it is never rendered.
        // Uses Unshaded.White (no lighting needed — it's invisible). The "eclipseMaterial" tag
        // tells EclipseShadowManager to run the per-geometry spacecraft GLB lighting pass below.
        Sphere dummyMesh = new Sphere(4, 4, 1f);
        Geometry fullGeom = new Geometry(name + "-shape", dummyMesh);
        fullGeom.setMaterial(unshadedMaterial(ColorRGBA.White, assetManager));
        fullGeom.setCullHint(Spatial.CullHint.Always);
        fullGeom.setUserData("eclipseMaterial", true);

        Node textureAlignNode = new Node(name + "-tex-align");
        textureAlignNode.attachChild(fullGeom);
        Node bodyFixedNode = new Node(name + "-body-fixed");
        bodyFixedNode.attachChild(textureAlignNode);

        Geometry spriteGeom = buildSprite(name, naifId, assetManager);

        // Attempt to load a GLB shape model if configured.
        Node glbModelRoot = tryLoadSpacecraftGlb(spacecraft, bodyFixedNode, assetManager);

        // Compute the GLB bounding radius (in km) for label suppression: labels disappear
        // when the shape model is large enough on screen, just like natural body labels.
        // For sprite-only spacecraft (no GLB), bodyRadiusKm stays 0.0 so the label always shows.
        double glbRadiusKm = 0.0;
        if (glbModelRoot != null) {
            // Apply per-geometry EclipseLighting materials so each mesh part of the spacecraft
            // retains its own color from the glTF PBR material. EclipseShadowManager's spacecraft
            // pass walks the GLB tree and updates each geometry's material directly.
            applyEclipseLightingToSpacecraftGlb(glbModelRoot, naifId, assetManager);

            // Force-compute world bounds for the bodyFixedNode subtree (glbModelRoot is attached).
            // The GLB is in meters scaled by scaleFactor → km, so the bound radius is in km.
            bodyFixedNode.updateGeometricState();
            BoundingVolume bv = glbModelRoot.getWorldBound();
            if (bv instanceof BoundingSphere bs) {
                glbRadiusKm = bs.getCenter().length() + bs.getRadius();
            } else if (bv instanceof BoundingBox bb) {
                Vector3f ext = bb.getExtent(null);
                glbRadiusKm = bb.getCenter().length() + Math.max(ext.x, Math.max(ext.y, ext.z));
            }
        }

        // Spacecraft sprite is visible by default; hidden if GLB was loaded.
        spriteGeom.setCullHint(glbModelRoot != null ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);

        Node ephemerisNode = new Node(name + "-ephemeris");
        ephemerisNode.setUserData("naifId", naifId);
        ephemerisNode.setUserData("bodyRadiusKm", glbRadiusKm);
        ephemerisNode.attachChild(bodyFixedNode);
        ephemerisNode.attachChild(spriteGeom);

        return new BodySceneNode(
                ephemerisNode, bodyFixedNode, fullGeom, spriteGeom, naifId, glbModelRoot, glbModelRoot != null);
    }

    // ── GLB loading helpers ───────────────────────────────────────────────────────────────────────

    /**
     * Attempt to load a body GLB shape model and attach it to {@code bodyFixedNode}.
     *
     * <p>Returns the {@code glbModelRoot} Node on success, {@code null} on null/blank path, missing file, or any load
     * failure. All failures are logged at WARN; the simulation continues normally.
     *
     * <p>GLB files for natural bodies are authored in <b>kilometers</b> — no scale transform is applied (§14.6.3,
     * D-027).
     *
     * @param bodyName body name used for BodyBlock lookup and node naming
     * @param bodyFixedNode node to attach glbModelRoot to on success
     * @param assetManager JME asset manager (resourcesFolder already registered as FileLocator)
     * @return glbModelRoot Node, or null if no GLB is configured or load failed
     */
    private static Node tryLoadBodyGlb(String bodyName, Node bodyFixedNode, AssetManager assetManager) {
        BodyBlock block = bodyBlockFor(bodyName);
        if (block == null) return null;

        String modelPath = block.shapeModel();
        if (modelPath == null || modelPath.isBlank()) return null;

        // Resolve: absolute if starts with "/", else run-dir + resourcesFolder + configPath.
        Path resolvedPath = resolveShapeModelPath(modelPath);

        try {
            // JME's AssetManager cannot accept absolute paths directly. Register the
            // file's parent directory as a FileLocator and load by filename only.
            assetManager.registerLocator(resolvedPath.getParent().toString(), FileLocator.class);
            Spatial model = assetManager.loadModel(resolvedPath.getFileName().toString());
            applySamplerPreset(model, SamplerPreset.QUALITY_DEFAULT);

            Quaternion modelToBodyFixed = GLTFUtils.readModelToBodyFixedQuatFromGlb(resolvedPath);

            Node glbModelRoot = new Node(bodyName + "-glbModelRoot");
            glbModelRoot.attachChild(model);
            // modelToBodyFixedQuat applied once at load time; maps glTF model-space into the
            // body-fixed frame expected by SPICE. Never updated per-frame — the time-varying
            // SPICE body-fixed → J2000 rotation is carried by bodyFixedNode (§14.6.5).
            glbModelRoot.setLocalRotation(modelToBodyFixed);
            bodyFixedNode.attachChild(glbModelRoot);

            logger.info("Loaded GLB shape model for body {}: {}", bodyName, resolvedPath);
            return glbModelRoot;

        } catch (Exception e) {
            logger.warn(
                    "Failed to load GLB shape model for body {} ({}): {} — falling back to ellipsoid",
                    bodyName,
                    resolvedPath,
                    e.getMessage());
            logger.warn(e.getLocalizedMessage(), e);
            return null;
        }
    }

    /**
     * Attempt to load a spacecraft GLB shape model and attach it to {@code bodyFixedNode}.
     *
     * <p>Returns the {@code glbModelRoot} Node on success, {@code null} on null/blank path, missing file, or any load
     * failure. All failures are logged at WARN; the simulation continues normally.
     *
     * <p>GLB files for spacecraft are authored in <b>meters</b>. A uniform scale of {@code 0.001 ×
     * SpacecraftBlock.scale()} is applied to convert to km (§14.6.4, D-027). GLB-embedded PBR materials are used as-is
     * — no KEPPLR body material pipeline (D-028).
     *
     * @param spacecraft spacecraft descriptor
     * @param bodyFixedNode node to attach glbModelRoot to on success
     * @param assetManager JME asset manager (resourcesFolder already registered as FileLocator)
     * @return glbModelRoot Node, or null if no GLB is configured or load failed
     */
    private static Node tryLoadSpacecraftGlb(Spacecraft spacecraft, Node bodyFixedNode, AssetManager assetManager) {
        String modelPath = spacecraft.shapeModel();
        if (modelPath == null || modelPath.isBlank()) return null;

        String name = spacecraft.id().getName();
        int naifId = spacecraft.code();

        // Resolve: absolute if starts with "/", else run-dir + resourcesFolder + configPath.
        Path resolvedPath = resolveShapeModelPath(modelPath);

        try {
            // JME's AssetManager cannot accept absolute paths directly. Register the
            // file's parent directory as a FileLocator and load by filename only.
            assetManager.registerLocator(resolvedPath.getParent().toString(), FileLocator.class);
            Spatial model = assetManager.loadModel(resolvedPath.getFileName().toString());
            applySamplerPreset(model, SamplerPreset.QUALITY_DEFAULT);

            Quaternion modelToBodyFixed = GLTFUtils.readModelToBodyFixedQuatFromGlb(resolvedPath);

            // Scale: GLB is in meters; KEPPLR world units are km (§14.6.4, D-027).
            double scaleFactor = 0.001 * spacecraftScaleFactor(naifId);

            Node glbModelRoot = new Node(name + "-glbModelRoot");
            glbModelRoot.attachChild(model);
            // modelToBodyFixedQuat applied once at load time; maps glTF model-space into the
            // body-fixed frame expected by SPICE. Never updated per-frame — the time-varying
            // SPICE body-fixed → J2000 rotation is carried by bodyFixedNode (§14.6.5).
            glbModelRoot.setLocalRotation(modelToBodyFixed);
            glbModelRoot.setLocalScale((float) scaleFactor);
            bodyFixedNode.attachChild(glbModelRoot);

            logger.info("Loaded GLB shape model for spacecraft {} (scale {}): {}", name, scaleFactor, resolvedPath);
            return glbModelRoot;

        } catch (Exception e) {
            logger.warn(
                    "Failed to load GLB shape model for spacecraft {} ({}): {} — falling back to sprite",
                    name,
                    resolvedPath,
                    e.getMessage());
            logger.warn(e.getLocalizedMessage(), e);
            return null;
        }
    }

    // ── Sampler preset application ────────────────────────────────────────────────────────────────

    /**
     * Apply a {@link SamplerPreset} to every texture in the loaded Spatial's geometry subtree.
     *
     * <p>Visits all {@link Geometry} nodes recursively. For each geometry, checks the {@code BaseColorMap} and
     * {@code DiffuseMap} material parameters. Each unique texture (by identity) is updated at most once. Updates: wrap
     * mode → {@code Clamp}, min/mag filter, anisotropy, and image colorspace → sRGB.
     *
     * <p><b>Usage example:</b>
     *
     * <pre>{@code
     * Spatial model = assetManager.loadModel("shapes/eros.glb");
     * BodyNodeFactory.applySamplerPreset(model, SamplerPreset.QUALITY_DEFAULT);
     * }</pre>
     *
     * @param spatial root Spatial of the loaded GLB
     * @param preset sampler preset to apply
     */
    static void applySamplerPreset(Spatial spatial, SamplerPreset preset) {
        if (spatial == null || preset == null) return;
        Set<Texture> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        applySamplerPresetRecursive(spatial, preset, visited);
    }

    private static void applySamplerPresetRecursive(Spatial spatial, SamplerPreset preset, Set<Texture> visited) {
        if (spatial instanceof Geometry geometry) {
            Material mat = geometry.getMaterial();
            if (mat != null) {
                applySamplerToParam(mat, "BaseColorMap", preset, visited);
                applySamplerToParam(mat, "DiffuseMap", preset, visited);
            }
            return;
        }
        if (spatial instanceof Node node) {
            for (Spatial child : node.getChildren()) {
                applySamplerPresetRecursive(child, preset, visited);
            }
        }
    }

    private static void applySamplerToParam(
            Material material, String paramName, SamplerPreset preset, Set<Texture> visited) {
        MatParamTexture param = material.getTextureParam(paramName);
        if (param == null) return;
        Texture texture = param.getTextureValue();
        if (texture == null || !visited.add(texture)) return;

        texture.setWrap(Texture.WrapMode.Clamp);
        texture.setMagFilter(preset.magFilter);
        texture.setMinFilter(preset.minFilter);
        texture.setAnisotropicFilter(preset.anisotropy);
        if (texture.getImage() != null && texture.getImage().getColorSpace() != ColorSpace.sRGB) {
            texture.getImage().setColorSpace(ColorSpace.sRGB);
        }
    }

    // ── EclipseLighting application for GLB bodies ────────────────────────────────────────────────

    /**
     * Replace the material on every {@link Geometry} in a body GLB tree with the provided {@code EclipseLighting}
     * material instance.
     *
     * <p>Using the same {@link Material} instance as {@code fullGeom} means {@link EclipseShadowManager}'s per-frame
     * uniform writes ({@code SunPosition}, {@code OccluderPositions}, etc.) automatically propagate to all GLB
     * geometries without any changes to the shadow manager.
     *
     * <p>If a GLB {@link Geometry}'s original PBR material carries a {@code BaseColorMap} texture, it is extracted and
     * set as {@code DiffuseMap} on the shared material (last write wins for multi-mesh models). If there is no texture
     * but a flat {@code BaseColor} is present, it is set as {@code DiffuseColor} instead.
     */
    private static void applyEclipseLightingToGlb(Spatial spatial, Material eclipseMat) {
        if (spatial instanceof Geometry geometry) {
            Material oldMat = geometry.getMaterial();
            if (oldMat != null) {
                // Extract BaseColorMap texture (if any) → DiffuseMap.
                MatParamTexture colorParam = oldMat.getTextureParam("BaseColorMap");
                if (colorParam != null && colorParam.getTextureValue() != null) {
                    Texture tex = colorParam.getTextureValue();
                    // Preserve sampler settings from applySamplerPreset; ensure sRGB tag for
                    // the linearisation step in EclipseLighting.frag.
                    if (tex.getImage() != null && tex.getImage().getColorSpace() != ColorSpace.sRGB) {
                        tex.getImage().setColorSpace(ColorSpace.sRGB);
                    }
                    eclipseMat.setTexture("DiffuseMap", tex);
                }
                // Extract BaseColor factor (if any) → DiffuseColor.
                // In glTF, baseColorFactor is in linear space and multiplies the texture sample.
                // Setting DiffuseColor here allows the shader to apply the tint even when a
                // texture is also present (the shader multiplies DiffuseMap × DiffuseColor).
                var colorVal = oldMat.getParam("BaseColor");
                if (colorVal != null && colorVal.getValue() instanceof ColorRGBA c) {
                    eclipseMat.setColor("DiffuseColor", c);
                }
            }
            geometry.setMaterial(eclipseMat);
            return;
        }
        if (spatial instanceof Node node) {
            for (Spatial child : node.getChildren()) {
                applyEclipseLightingToGlb(child, eclipseMat);
            }
        }
    }

    /**
     * Apply per-geometry {@code EclipseLighting.j3md} materials to every {@link Geometry} in a spacecraft GLB tree.
     *
     * <p>Unlike {@link #applyEclipseLightingToGlb} (which shares one material instance across all GLB geometries), this
     * method creates a fresh {@code EclipseLighting} material for each {@link Geometry} so that multi-part spacecraft
     * models retain their per-mesh colors. Each geometry is also tagged with {@code "eclipseMaterial" = true} so
     * {@link EclipseShadowManager} can locate and update them.
     *
     * <p>Color extraction follows the same glTF PBR→EclipseLighting mapping as {@link #applyEclipseLightingToGlb}:
     *
     * <ul>
     *   <li>{@code BaseColorMap} → {@code DiffuseMap} (sRGB colour-space tag preserved)
     *   <li>{@code BaseColor} factor → {@code DiffuseColor} (applied as a tint by the shader)
     * </ul>
     */
    private static void applyEclipseLightingToSpacecraftGlb(Spatial spatial, int naifId, AssetManager assetManager) {
        if (spatial instanceof Geometry geometry) {
            Material oldMat = geometry.getMaterial();
            Material geomMat = new Material(assetManager, "kepplr/shaders/Bodies/EclipseLighting.j3md");
            geomMat.setFloat("WrapFactor", kepplr.util.KepplrConstants.BODY_WRAP_FACTOR);
            geomMat.setFloat("LimbDarkeningK", kepplr.util.KepplrConstants.BODY_LIMB_DARKENING_K);
            if (oldMat != null) {
                MatParamTexture colorParam = oldMat.getTextureParam("BaseColorMap");
                if (colorParam != null && colorParam.getTextureValue() != null) {
                    Texture tex = colorParam.getTextureValue();
                    if (tex.getImage() != null && tex.getImage().getColorSpace() != ColorSpace.sRGB) {
                        tex.getImage().setColorSpace(ColorSpace.sRGB);
                    }
                    geomMat.setTexture("DiffuseMap", tex);
                }
                var colorVal = oldMat.getParam("BaseColor");
                if (colorVal != null && colorVal.getValue() instanceof ColorRGBA c) {
                    geomMat.setColor("DiffuseColor", c);
                }
            }
            geometry.setMaterial(geomMat);
            geometry.setUserData("eclipseMaterial", true);
            return;
        }
        if (spatial instanceof Node node) {
            for (Spatial child : node.getChildren()) {
                applyEclipseLightingToSpacecraftGlb(child, naifId, assetManager);
            }
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────────────────────────

    private static float meanRadius(Ellipsoid shape, String name) {
        if (shape == null) {
            logger.warn("No shape data for {}; using default radius {} km", name, DEFAULT_RADIUS_KM);
            return DEFAULT_RADIUS_KM;
        }
        return (float) ((shape.getA() + shape.getB() + shape.getC()) / 3.0);
    }

    /**
     * Create the surface material for a body.
     *
     * <p>The Sun (NAIF ID 10) uses {@code Unshaded.j3md} — fully emissive, not self-shadowed (§7.6). All other bodies
     * use the custom {@code EclipseLighting.j3md} shader which computes analytic eclipse shadows and smooth day/night
     * terminator (§9.3). Shadow-specific uniforms ({@code SunPosition}, {@code OccluderPositions}, etc.) are set each
     * frame by {@link EclipseShadowManager}; only material-constant uniforms are set here at creation time.
     *
     * <p>Gate on NAIF ID 10, not on body name, per the project-wide NAIF ID convention.
     */
    private static Material createBodyMaterial(int naifId, String bodyName, AssetManager assetManager) {
        if (naifId == KepplrConstants.SUN_NAIF_ID) {
            return createSunMaterial(bodyName, assetManager);
        }
        return createEclipseMaterial(naifId, bodyName, assetManager);
    }

    /**
     * Create an EclipseLighting material for a non-Sun body.
     *
     * <p>Sets the diffuse texture (if configured) or diffuse color. Shadow uniforms are left at shader defaults (zero
     * occluders, no shadow) and will be overwritten by {@link EclipseShadowManager} every frame.
     *
     * <p>The {@code HasRings} material parameter is set to {@code true} only for Saturn (NAIF ID
     * {@link kepplr.util.KepplrConstants#SATURN_NAIF_ID}) to enable the ring-shadow path in the shader.
     */
    private static Material createEclipseMaterial(int naifId, String bodyName, AssetManager assetManager) {
        Material mat = new Material(assetManager, "kepplr/shaders/Bodies/EclipseLighting.j3md");

        BodyBlock block = bodyBlockFor(bodyName);
        if (block != null) {
            String texPath = block.textureMap();
            if (texPath != null && !texPath.isBlank()) {
                try {
                    Path resolved = KEPPLRConfiguration.getInstance().getPathInResources(texPath);
                    assetManager.registerLocator(resolved.getParent().toString(), FileLocator.class);
                    Texture tex =
                            assetManager.loadTexture(resolved.getFileName().toString());
                    tex.setWrap(Texture.WrapMode.Repeat);
                    // Tag as sRGB so the shader can convert to linear for correct lighting math.
                    if (tex.getImage() != null && tex.getImage().getColorSpace() != ColorSpace.sRGB) {
                        tex.getImage().setColorSpace(ColorSpace.sRGB);
                    }
                    mat.setTexture("DiffuseMap", tex);
                } catch (Exception e) {
                    logger.warn("Could not load texture for {}: {}", bodyName, e.getMessage());
                    if (block.color() != null) {
                        mat.setColor("DiffuseColor", toColorRGBA(block.color()));
                    }
                }
            } else if (block.color() != null) {
                mat.setColor("DiffuseColor", toColorRGBA(block.color()));
            }
        }

        if (naifId == kepplr.util.KepplrConstants.SATURN_NAIF_ID) {
            mat.setBoolean("HasRings", true);
        }

        // Surface shading parameters (Step 23): wrap lighting and Minnaert limb darkening.
        mat.setFloat("WrapFactor", kepplr.util.KepplrConstants.BODY_WRAP_FACTOR);
        mat.setFloat("LimbDarkeningK", kepplr.util.KepplrConstants.BODY_LIMB_DARKENING_K);

        return mat;
    }

    /**
     * Create an Unshaded material for the Sun so it is fully emissive and not self-shadowed (REDESIGN.md §7.6).
     *
     * <p>Note for future shadow step: the Sun's radius is stored in its {@link BodySceneNode}'s fullGeom scale;
     * retrieve it from there when defining analytic shadow geometry.
     */
    private static Material createSunMaterial(String bodyName, AssetManager assetManager) {
        BodyBlock block = bodyBlockFor(bodyName);
        ColorRGBA color = ColorRGBA.Yellow;
        if (block != null) {
            String texPath = block.textureMap();
            if (texPath != null && !texPath.isBlank()) {
                try {
                    Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
                    Path resolved = KEPPLRConfiguration.getInstance().getPathInResources(texPath);
                    assetManager.registerLocator(resolved.getParent().toString(), FileLocator.class);
                    Texture tex =
                            assetManager.loadTexture(resolved.getFileName().toString());
                    tex.setWrap(Texture.WrapMode.Repeat);
                    mat.setTexture("ColorMap", tex);
                    return mat;
                } catch (Exception e) {
                    logger.warn("Could not load Sun texture {}: {}", bodyName, e.getMessage());
                }
            }
            color = toColorRGBA(block.color());
        }
        return unshadedMaterial(color, assetManager);
    }

    private static Geometry buildSprite(String name, int naifId, AssetManager assetManager) {
        Sphere spriteMesh = new Sphere(6, 8, 1f);
        Geometry spriteGeom = new Geometry(name + "-sprite", spriteMesh);
        ColorRGBA color = spriteColorFor(name, naifId);
        spriteGeom.setMaterial(unshadedMaterial(color, assetManager));
        spriteGeom.setCullHint(Spatial.CullHint.Always);
        spriteGeom.setUserData("naifId", naifId);
        return spriteGeom;
    }

    private static Material unshadedMaterial(ColorRGBA color, AssetManager assetManager) {
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", color);
        return mat;
    }

    private static double lookupCenterLon(String bodyName) {
        BodyBlock block = bodyBlockFor(bodyName);
        return block != null ? block.centerLon() : 0.0;
    }

    private static BodyBlock bodyBlockFor(String bodyName) {
        try {
            return KEPPLRConfiguration.getInstance().bodyBlock(bodyName);
        } catch (Exception e) {
            logger.warn("No BodyBlock for {}: {}", bodyName, e.getMessage());
            return null;
        }
    }

    /**
     * Returns the {@code SpacecraftBlock.scale()} factor for the given NAIF ID, or {@code 1.0} if no block is
     * configured.
     */
    private static double spacecraftScaleFactor(int naifId) {
        try {
            SpacecraftBlock block = KEPPLRConfiguration.getInstance().spacecraftBlock(naifId);
            return block != null ? block.scale() : 1.0;
        } catch (Exception e) {
            logger.warn("Could not look up SpacecraftBlock for NAIF {}: {}", naifId, e.getMessage());
            return 1.0;
        }
    }

    private static ColorRGBA spriteColorFor(String bodyName, int naifId) {
        BodyBlock block = bodyBlockFor(bodyName);
        if (block != null) {
            try {
                return toColorRGBA(block.color());
            } catch (Exception ignored) {
            }
        }
        return ColorRGBA.White;
    }

    private static ColorRGBA toColorRGBA(Color awtColor) {
        return new ColorRGBA(awtColor.getRed() / 255f, awtColor.getGreen() / 255f, awtColor.getBlue() / 255f, 1f);
    }

    /**
     * Resolve a shape model path from the configuration file to an absolute {@link Path}.
     *
     * <p>Resolution rules:
     *
     * <ul>
     *   <li>If {@code configPath} starts with {@code "/"}: treated as an absolute file-system path and returned as-is.
     *   <li>Otherwise: resolved relative to the run directory joined with
     *       {@code KEPPLRConfiguration.resourcesFolder()}, then made absolute via {@link Path#toAbsolutePath()} (which
     *       uses the JVM working directory, i.e. the run directory).
     * </ul>
     *
     * <p><b>Usage example:</b>
     *
     * <pre>{@code
     * // config: shapeModel = shapes/phobos.glb, resourcesFolder = resources
     * // result: /run/dir/resources/shapes/phobos.glb
     * Path p = resolveShapeModelPath("shapes/phobos.glb");
     *
     * // config: shapeModel = /data/models/phobos.glb
     * // result: /data/models/phobos.glb
     * Path p = resolveShapeModelPath("/data/models/phobos.glb");
     * }</pre>
     *
     * @param configPath path string from {@code BodyBlock.shapeModel()} or {@code SpacecraftBlock.shapeModel()}
     * @return absolute Path to the shape model file
     */
    private static Path resolveShapeModelPath(String configPath) {
        if (configPath.startsWith("/")) {
            return Path.of(configPath);
        }
        return Path.of(KEPPLRConfiguration.getInstance().resourcesFolder(), configPath)
                .toAbsolutePath();
    }
}
