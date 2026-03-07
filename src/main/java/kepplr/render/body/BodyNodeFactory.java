package kepplr.render.body;

import com.jme3.asset.AssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Sphere;
import com.jme3.texture.Texture;
import java.awt.Color;
import java.nio.file.Path;
import kepplr.config.BodyBlock;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.Spacecraft;
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
 * <h3>Body node hierarchy</h3>
 *
 * <pre>
 * ephemerisNode
 * ├── bodyFixedNode   (J2000 → body-fixed rotation)
 * │   └── textureAlignNode  (center-longitude yaw)
 * │       └── fullGeom  (textured or untextured ellipsoid)
 * └── spriteGeom  (unit sphere, scaled per frame to constant pixel size)
 * </pre>
 *
 * <p>Initially: {@code fullGeom} is visible ({@code CullHint.Inherit}), {@code spriteGeom} is
 * hidden ({@code CullHint.Always}). {@link BodySceneNode#apply} switches between them each frame.
 *
 * <h3>Sun (NAIF 10)</h3>
 *
 * <p>The Sun uses an {@code Unshaded} material so it renders as fully emissive and is not
 * self-shadowed by the scene's PointLight (REDESIGN.md §7.6).
 *
 * <h3>Missing texture data (§12.3)</h3>
 *
 * <p>If a body's configured texture path is absent or fails to load, the body renders as an
 * untextured sphere in its configured hex color.
 */
public final class BodyNodeFactory {

    private static final Logger logger = LogManager.getLogger();

    /** NAIF ID of the Sun — used to select the emissive (Unshaded) material. */
    private static final int SUN_NAIF_ID = 10;

    /**
     * Fallback mean radius (km) used when a body has no PCK shape data. Logged as a warning.
     */
    private static final float DEFAULT_RADIUS_KM = 1.0f;

    private BodyNodeFactory() {}

    /**
     * Create a {@link BodySceneNode} for a celestial body.
     *
     * @param bodyId       body EphemerisID (used for scene node names)
     * @param naifId       integer NAIF ID (used to select Sun material and look up BodyBlock)
     * @param shape        body ellipsoid from {@code KEPPLREphemeris.getShape()}; null → default
     *                     radius, untextured
     * @param assetManager JME asset manager
     * @return fully assembled BodySceneNode; fullGeom visible, spriteGeom hidden
     */
    public static BodySceneNode createBodyNode(
            EphemerisID bodyId, int naifId, Ellipsoid shape, AssetManager assetManager) {

        float radiusKm = meanRadius(shape, bodyId.getName());

        // Full-geometry ellipsoid (unit sphere scaled to body radius)
        Sphere mesh = new Sphere(KepplrConstants.BODY_SPHERE_TESSELLATION,
                KepplrConstants.BODY_SPHERE_TESSELLATION, 1f);
        mesh.setTextureMode(Sphere.TextureMode.Projected);
        mesh.updateBound();

        Geometry fullGeom = new Geometry(bodyId.getName(), mesh);
        fullGeom.setLocalScale(radiusKm);
        fullGeom.setMaterial(createBodyMaterial(naifId, bodyId.getName(), assetManager));
        fullGeom.setCullHint(Spatial.CullHint.Inherit);

        // Texture-alignment node: yaw by center longitude in body-fixed frame
        Node textureAlignNode = new Node(bodyId.getName() + "-tex-align");
        double centerLonRad = lookupCenterLon(bodyId.getName());
        if (centerLonRad != 0.0) {
            Quaternion texRot = new Quaternion();
            texRot.fromAngleAxis((float) centerLonRad, Vector3f.UNIT_Z);
            textureAlignNode.setLocalRotation(texRot);
        }
        textureAlignNode.attachChild(fullGeom);

        // Body-fixed node: updated each frame with J2000 → body-fixed rotation
        Node bodyFixedNode = new Node(bodyId.getName() + "-body-fixed");
        bodyFixedNode.attachChild(textureAlignNode);

        // Point-sprite: tiny unit sphere, hidden by default
        Geometry spriteGeom = buildSprite(bodyId.getName(), naifId, assetManager);

        // Ephemeris node: positioned at body's camera-relative location each frame
        Node ephemerisNode = new Node(bodyId.getName() + "-ephemeris");
        ephemerisNode.attachChild(bodyFixedNode);
        ephemerisNode.attachChild(spriteGeom);

        return new BodySceneNode(ephemerisNode, bodyFixedNode, fullGeom, spriteGeom);
    }

    /**
     * Create a {@link BodySceneNode} for a spacecraft, rendered exclusively as a point sprite.
     *
     * <p>The {@code fullGeom} is permanently hidden; shape model rendering is deferred.
     *
     * @param spacecraft   spacecraft descriptor
     * @param assetManager JME asset manager
     * @return BodySceneNode whose fullGeom is always culled
     */
    public static BodySceneNode createSpacecraftNode(Spacecraft spacecraft, AssetManager assetManager) {
        String name = spacecraft.id().getName();
        int naifId = spacecraft.code();

        // Dummy full geom (permanently hidden — shape model rendering deferred)
        Sphere dummyMesh = new Sphere(4, 4, 1f);
        Geometry fullGeom = new Geometry(name + "-shape", dummyMesh);
        fullGeom.setMaterial(unshadedMaterial(ColorRGBA.White, assetManager));
        fullGeom.setCullHint(Spatial.CullHint.Always);

        Node textureAlignNode = new Node(name + "-tex-align");
        textureAlignNode.attachChild(fullGeom);
        Node bodyFixedNode = new Node(name + "-body-fixed");
        bodyFixedNode.attachChild(textureAlignNode);

        Geometry spriteGeom = buildSprite(name, naifId, assetManager);
        // Spacecraft always render as sprites: make sprite visible by default
        spriteGeom.setCullHint(Spatial.CullHint.Inherit);

        Node ephemerisNode = new Node(name + "-ephemeris");
        ephemerisNode.attachChild(bodyFixedNode);
        ephemerisNode.attachChild(spriteGeom);

        return new BodySceneNode(ephemerisNode, bodyFixedNode, fullGeom, spriteGeom);
    }

    // ── private helpers ──────────────────────────────────────────────────────────────────────────

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
     * <p>The Sun uses {@code Unshaded.j3md} (fully emissive; §7.6). All other bodies use
     * {@code Lighting.j3md} with a texture if one is configured, otherwise a flat diffuse color
     * (§12.3).
     */
    private static Material createBodyMaterial(int naifId, String bodyName, AssetManager assetManager) {
        if (naifId == SUN_NAIF_ID) {
            return createSunMaterial(bodyName, assetManager);
        }

        Material mat = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        mat.setBoolean("UseMaterialColors", true);
        mat.setColor("Diffuse", ColorRGBA.White);
        mat.setColor("Ambient", new ColorRGBA(0.05f, 0.05f, 0.05f, 1f));

        BodyBlock block = bodyBlockFor(bodyName);
        if (block != null) {
            String texPath = block.textureMap();
            if (texPath != null && !texPath.isBlank()) {
                try {
                    Path resolved = KEPPLRConfiguration.getInstance().getPathInResources(texPath);
                    assetManager.registerLocator(resolved.getParent().toString(), FileLocator.class);
                    Texture tex = assetManager.loadTexture(resolved.getFileName().toString());
                    tex.setWrap(Texture.WrapMode.Repeat);
                    mat.setTexture("DiffuseMap", tex);
                    return mat;
                } catch (Exception e) {
                    logger.warn("Could not load texture for {}: {}", bodyName, e.getMessage());
                }
            }
            // No texture — use configured body color (§12.3 untextured sphere)
            mat.setColor("Diffuse", toColorRGBA(block.color()));
        }
        return mat;
    }

    /**
     * Create an Unshaded material for the Sun so it is fully emissive and not self-shadowed
     * (REDESIGN.md §7.6).
     *
     * <p>Note for future shadow step: the Sun's radius is stored in its {@link BodySceneNode}'s
     * fullGeom scale; retrieve it from there when defining analytic shadow geometry.
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
                    Texture tex = assetManager.loadTexture(resolved.getFileName().toString());
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
        Sphere spriteMesh = new Sphere(4, 4, 1f);
        Geometry spriteGeom = new Geometry(name + "-sprite", spriteMesh);
        ColorRGBA color = spriteColorFor(name, naifId);
        spriteGeom.setMaterial(unshadedMaterial(color, assetManager));
        spriteGeom.setCullHint(Spatial.CullHint.Always);
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

    private static ColorRGBA spriteColorFor(String bodyName, int naifId) {
        BodyBlock block = bodyBlockFor(bodyName);
        if (block != null) {
            try {
                return toColorRGBA(block.color());
            } catch (Exception ignored) {}
        }
        return ColorRGBA.White;
    }

    private static ColorRGBA toColorRGBA(Color awtColor) {
        return new ColorRGBA(
                awtColor.getRed() / 255f,
                awtColor.getGreen() / 255f,
                awtColor.getBlue() / 255f,
                1f);
    }
}
