package kepplr.render;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.render.frustum.FrustumLayer;
import kepplr.state.SimulationState;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.mechanics.EphemerisID;
import picante.surfaces.Ellipsoid;

/**
 * Renders the Sun halo as an additive world-space billboard, dynamically assigned to the correct frustum layer each
 * frame.
 *
 * <p>Algorithm derived from prototype: SunHaloController + SunHaloFilter — reimplemented for new architecture.
 *
 * <h3>Approach</h3>
 *
 * <p>A centered 2×2 quad is placed at the Sun's scene position (= {@code −cameraHelioJ2000} under floating origin) each
 * frame and oriented to face the camera via {@link Node#lookAt}. The quad is scaled to {@code sunMeanRadius ×
 * KepplrConstants#SUN_HALO_MAX_RADIUS_MULTIPLIER} km so the halo edge stays at a constant multiple of the Sun's angular
 * diameter from any camera distance.
 *
 * <p>The material uses {@link RenderState.BlendMode#Additive} with depth-test ON and depth-write OFF. This means:
 *
 * <ul>
 *   <li>Bodies in front of the Sun (planets, Saturn's rings) write depth and naturally occlude the halo — no special
 *       occluder code is needed. The ring {@code SUN_HALO_OCCLUDER} marker from Step 16a is honoured automatically.
 *   <li>The Sun's own sphere disk does not interfere — the shader's {@code innerGate} smoothstep already fades the halo
 *       to zero inside the limb, so even if the Sun sphere clips the billboard centre, there is nothing to see.
 *   <li>JME frustum-culls the billboard when the Sun is entirely off-screen.
 * </ul>
 *
 * <h3>Independence from body culling</h3>
 *
 * <p>This renderer is independent of {@link kepplr.render.body.BodySceneManager}. The halo renders regardless of
 * whether the Sun's {@link kepplr.render.body.BodySceneNode} is in {@code DRAW_FULL}, {@code DRAW_SPRITE}, or
 * {@code CULL} state.
 *
 * <h3>Dynamic frustum layer assignment</h3>
 *
 * <p>Each frame, {@link FrustumLayer#assign} is called with the camera-to-Sun distance. If the assigned layer has
 * changed since the last frame, {@code haloNode} is re-parented from the old layer node to the new one. This ensures
 * the halo is depth-tested against the correct depth buffer regardless of the camera's distance from the Sun.
 *
 * <p>All methods must be called on the JME render thread (CLAUDE.md Rule 4).
 */
class SunHaloRenderer {

    private static final Logger logger = LogManager.getLogger(SunHaloRenderer.class);

    private static final String MAT_DEF = "kepplr/shaders/SunHalo/SunHalo.j3md";

    private final Node farNode;
    private final Node midNode;
    private final Node nearNode;
    private final AssetManager assetManager;
    private final SimulationState state;

    private Node haloNode;
    private Material haloMat;

    /** The layer node that currently owns {@code haloNode}; null before the first {@link #update} call. */
    private Node currentLayerNode;

    /** Accumulated wall time in seconds — drives the corona shimmer animation. */
    private float haloTimeSec = 0f;

    /**
     * @param farNode far frustum root node
     * @param midNode mid frustum root node
     * @param nearNode near frustum root node
     * @param assetManager JME asset manager for material loading
     * @param state simulation state; read each frame for render quality
     */
    SunHaloRenderer(Node farNode, Node midNode, Node nearNode, AssetManager assetManager, SimulationState state) {
        this.farNode = farNode;
        this.midNode = midNode;
        this.nearNode = nearNode;
        this.assetManager = assetManager;
        this.state = state;
    }

    /**
     * Build the halo geometry without attaching it to any layer node yet.
     *
     * <p>Call once from {@code KepplrApp.simpleInitApp()} after the frustum nodes exist. The first call to
     * {@link #update} will attach the halo to the correct layer.
     */
    void init() {
        java.awt.Color awtColor =
                KEPPLRConfiguration.getInstance().bodyBlock("SUN").color();
        ColorRGBA haloColor =
                new ColorRGBA(awtColor.getRed() / 255f, awtColor.getGreen() / 255f, awtColor.getBlue() / 255f, 1.0f);

        haloMat = new Material(assetManager, MAT_DEF);
        haloMat.setColor("SunColor", haloColor);
        haloMat.setFloat("MaxRadiusInR", KepplrConstants.SUN_HALO_MAX_RADIUS_MULTIPLIER);
        haloMat.setFloat("Falloff", KepplrConstants.SUN_HALO_FALLOFF_HIGH);
        haloMat.setFloat("AlphaScale", KepplrConstants.SUN_HALO_ALPHA_SCALE_HIGH);
        haloMat.setFloat("Time", 0f);
        haloMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Additive);
        haloMat.getAdditionalRenderState().setDepthWrite(false);
        haloMat.getAdditionalRenderState().setDepthTest(true);

        Geometry haloGeom = new Geometry("sun-halo", buildCenteredQuad());
        haloGeom.setMaterial(haloMat);
        // Transparent bucket: renders after opaque geometry so depth buffer is fully populated
        // by planets and rings before the halo fragment shader runs.
        haloGeom.setQueueBucket(RenderQueue.Bucket.Transparent);

        haloNode = new Node("sun-halo-node");
        haloNode.attachChild(haloGeom);
        // Not attached to any layer yet; update() handles the initial attachment.
    }

    /**
     * Update halo position, orientation, scale, and quality parameters for the current frame.
     *
     * <p>Also re-parents the halo to the correct frustum layer node when the camera-to-Sun distance crosses a layer
     * boundary.
     *
     * <p>Called once per frame from {@code KepplrApp.simpleUpdate()} on the JME render thread.
     *
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km (length ≥ 3)
     * @param cam active JME camera (used for billboard orientation)
     * @param tpf time-per-frame in seconds (wall time); drives shimmer animation
     */
    void update(double[] cameraHelioJ2000, Camera cam, float tpf) {
        haloTimeSec += tpf;

        // Acquire Sun mean radius at point-of-use (Architecture Rule 3).
        double sunMeanRadiusKm = resolveSunMeanRadiusKm();
        if (sunMeanRadiusKm <= 0.0) {
            haloNode.setCullHint(Spatial.CullHint.Always);
            return;
        }

        // Camera-to-Sun distance (Sun is at helio origin; scene pos = −camera).
        double sunDistKm = Math.sqrt(cameraHelioJ2000[0] * cameraHelioJ2000[0]
                + cameraHelioJ2000[1] * cameraHelioJ2000[1]
                + cameraHelioJ2000[2] * cameraHelioJ2000[2]);

        double billboardRadiusKm = effectiveBillboardRadiusKm(sunMeanRadiusKm, sunDistKm);

        // Re-parent to the correct frustum layer when the assignment changes.
        FrustumLayer targetLayer = FrustumLayer.assign(sunDistKm, 0.0);
        Node targetNode = layerNode(targetLayer);
        if (targetNode != currentLayerNode) {
            if (currentLayerNode != null) {
                currentLayerNode.detachChild(haloNode);
            }
            targetNode.attachChild(haloNode);
            currentLayerNode = targetNode;
        }

        // Sun heliocentric position = (0,0,0); in floating-origin scene space: Sun = −camera.
        float sx = (float) -cameraHelioJ2000[0];
        float sy = (float) -cameraHelioJ2000[1];
        float sz = (float) -cameraHelioJ2000[2];
        haloNode.setLocalTranslation(sx, sy, sz);
        haloNode.setLocalScale((float) billboardRadiusKm);

        // Orient billboard to face the camera (camera is at ZERO in floating origin).
        haloNode.lookAt(Vector3f.ZERO, cam.getUp());

        // Update per-frame shader uniforms.
        RenderQuality quality = state.renderQualityProperty().get();
        haloMat.setFloat("Falloff", quality.sunHaloFalloff());
        haloMat.setFloat("AlphaScale", quality.sunHaloAlphaScale());
        haloMat.setFloat("Time", haloTimeSec);

        haloNode.setCullHint(Spatial.CullHint.Inherit); // re-enable JME frustum culling
    }

    // ── package-private for tests ──────────────────────────────────────────────────────────────

    /**
     * Computes billboard radius in km from the Sun mean radius and the halo multiplier.
     *
     * <p>Package-private for unit testing.
     *
     * @param sunMeanRadiusKm Sun mean radius in km
     * @return half-size of the billboard in km = {@code sunMeanRadiusKm × SUN_HALO_MAX_RADIUS_MULTIPLIER}
     */
    static double computeBillboardRadiusKm(double sunMeanRadiusKm) {
        return sunMeanRadiusKm * KepplrConstants.SUN_HALO_MAX_RADIUS_MULTIPLIER;
    }

    /**
     * Returns the billboard radius that guarantees a minimum apparent angular diameter of 1° regardless of distance.
     *
     * <p>The physical radius ({@link #computeBillboardRadiusKm}) shrinks angularly with distance. When it would subtend
     * less than {@link KepplrConstants#SUN_HALO_MIN_APPARENT_HALF_ANGLE_RAD}, this method returns the distance-scaled
     * minimum instead.
     *
     * <p>Package-private for unit testing.
     *
     * @param sunMeanRadiusKm Sun mean radius in km
     * @param sunDistKm camera-to-Sun distance in km (must be &gt; 0)
     * @return effective billboard half-size in km
     */
    static double effectiveBillboardRadiusKm(double sunMeanRadiusKm, double sunDistKm) {
        double physical = computeBillboardRadiusKm(sunMeanRadiusKm);
        double minimum = sunDistKm * Math.tan(KepplrConstants.SUN_HALO_MIN_APPARENT_HALF_ANGLE_RAD);
        return Math.max(physical, minimum);
    }

    // ── private helpers ───────────────────────────────────────────────────────────────────────

    private Node layerNode(FrustumLayer layer) {
        return switch (layer) {
            case NEAR -> nearNode;
            case MID -> midNode;
            case FAR -> farNode;
        };
    }

    private double resolveSunMeanRadiusKm() {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        EphemerisID sunId;
        try {
            sunId = eph.getSpiceBundle().getObject(KepplrConstants.SUN_NAIF_ID);
        } catch (Exception e) {
            logger.warn("SunHaloRenderer: cannot resolve Sun EphemerisID: {}", e.getMessage());
            return 0.0;
        }
        if (sunId == null) {
            logger.warn("SunHaloRenderer: Sun EphemerisID is null");
            return 0.0;
        }
        Ellipsoid sunShape = eph.getShape(sunId);
        if (sunShape == null) {
            logger.warn("SunHaloRenderer: no shape data for Sun (NAIF {})", KepplrConstants.SUN_NAIF_ID);
            return 0.0;
        }
        return (sunShape.getA() + sunShape.getB() + sunShape.getC()) / 3.0;
    }

    /**
     * Builds a centered 2×2 quad in the XY plane (normal = +Z, UV 0–1 over the full quad).
     *
     * <p>Centered at the origin so {@link Node#setLocalTranslation} places the billboard centre at the Sun's scene
     * position.
     */
    private static Mesh buildCenteredQuad() {
        Mesh mesh = new Mesh();
        mesh.setBuffer(
                VertexBuffer.Type.Position,
                3,
                BufferUtils.createFloatBuffer(-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f));
        mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, BufferUtils.createFloatBuffer(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f));
        mesh.setBuffer(
                VertexBuffer.Type.Normal,
                3,
                BufferUtils.createFloatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f));
        mesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(new int[] {0, 1, 2, 0, 2, 3}));
        mesh.updateBound();
        return mesh;
    }
}
