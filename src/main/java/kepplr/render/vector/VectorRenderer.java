package kepplr.render.vector;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.render.frustum.FrustumLayer;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.EphemerisID;
import picante.surfaces.Ellipsoid;

/**
 * Renders a list of {@link VectorDefinition} objects as line segments in the JME scene graph.
 *
 * <p>For each definition, this renderer:
 *
 * <ol>
 *   <li>Resolves the origin body's heliocentric J2000 position.
 *   <li>Calls {@link VectorType#computeDirection} — no branching over type.
 *   <li>Projects the scale to screen space and skips vectors shorter than
 *       {@link KepplrConstants#VECTOR_MIN_VISIBLE_LENGTH_PX}.
 *   <li>Assigns the line to the correct frustum layer via {@link FrustumLayer#assign}.
 *   <li>Creates a two-vertex {@link Mesh#Mode#Lines} geometry and attaches it.
 * </ol>
 *
 * <p>Previously attached geometries are detached before each rebuild. One {@link Geometry} per active
 * {@link VectorDefinition} is created.
 *
 * <p>All methods must be called on the JME render thread (CLAUDE.md Rule 4). No switch/case or if/else chain over
 * {@link VectorType} exists anywhere in this class.
 */
class VectorRenderer {

    private static final Logger logger = LogManager.getLogger(VectorRenderer.class);

    private final AssetManager assetManager;
    private final Map<FrustumLayer, Node> layerNodes;

    /**
     * Active geometries keyed by the geometry itself, with the layer recorded for efficient detachment. Rebuilt on
     * every call to {@link #update}.
     */
    private final Map<Geometry, FrustumLayer> attachedGeoms = new LinkedHashMap<>();

    /**
     * @param nearNode near frustum root node
     * @param midNode mid frustum root node
     * @param farNode far frustum root node
     * @param assetManager JME asset manager for material creation
     */
    VectorRenderer(Node nearNode, Node midNode, Node farNode, AssetManager assetManager) {
        this.assetManager = assetManager;
        Map<FrustumLayer, Node> nodes = new EnumMap<>(FrustumLayer.class);
        nodes.put(FrustumLayer.NEAR, nearNode);
        nodes.put(FrustumLayer.MID, midNode);
        nodes.put(FrustumLayer.FAR, farNode);
        this.layerNodes = nodes;
    }

    /**
     * Rebuild all vector geometries for the current simulation time.
     *
     * <p>Detaches all previously attached geometries. Returns immediately (no vectors rendered) if {@code focusedBodyId
     * == -1}, if the focused body has no shape data, or if {@code definitions} is empty. Arrow length is computed each
     * call as focused-body mean radius × {@link KepplrConstants#VECTOR_ARROW_FOCUS_BODY_RADIUS_MULTIPLE} ×
     * {@link VectorDefinition#getScaleFactor()}.
     *
     * <p>Definitions whose direction is unavailable or whose projected length falls below
     * {@link KepplrConstants#VECTOR_MIN_VISIBLE_LENGTH_PX} are silently skipped.
     *
     * @param definitions active vector definitions to render
     * @param et current simulation ET (TDB seconds past J2000)
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km (length ≥ 3)
     * @param cam active JME camera (used for screen-space length projection)
     * @param focusedBodyId NAIF ID of the currently focused body, or −1 if none
     */
    void update(
            List<VectorDefinition> definitions, double et, double[] cameraHelioJ2000, Camera cam, int focusedBodyId) {
        detachAll();
        if (focusedBodyId == -1) {
            return;
        }
        if (definitions == null || definitions.isEmpty()) {
            return;
        }

        // Resolve focused body mean radius at point-of-use (Architecture Rule 3).
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        EphemerisID focusedBodyEphId;
        try {
            focusedBodyEphId = eph.getSpiceBundle().getObject(focusedBodyId);
        } catch (Exception e) {
            logger.warn("VectorRenderer: no EphemerisID for focused body NAIF {}: {}", focusedBodyId, e.getMessage());
            return;
        }
        if (focusedBodyEphId == null) {
            logger.warn("VectorRenderer: no EphemerisID for focused body NAIF {}", focusedBodyId);
            return;
        }
        Ellipsoid shape = eph.getShape(focusedBodyEphId);
        double meanRadiusKm;
        if (shape == null) {
            // Spacecraft and other shape-less bodies (no PCK entry) use the default radius so
            // vector arrows are still rendered at a visible scale (§7.6, KepplrConstants).
            meanRadiusKm = KepplrConstants.BODY_DEFAULT_RADIUS_KM;
        } else {
            meanRadiusKm = (shape.getA() + shape.getB() + shape.getC()) / 3.0;
        }

        for (VectorDefinition def : definitions) {
            try {
                double arrowLengthKm = computeArrowLengthKm(meanRadiusKm, def.getScaleFactor());
                renderOne(def, arrowLengthKm, et, cameraHelioJ2000, cam);
            } catch (Exception e) {
                logger.warn("Vector render failed for '{}': {}", def.getLabel(), e.getMessage());
            }
        }
    }

    /**
     * Computes arrow length in km from the focused body mean radius and the definition's scale factor.
     *
     * <p>Package-private for unit testing.
     *
     * @param meanRadiusKm focused body mean radius in km
     * @param scaleFactor dimensionless multiplier from {@link VectorDefinition#getScaleFactor()}
     * @return arrow length in km
     */
    static double computeArrowLengthKm(double meanRadiusKm, double scaleFactor) {
        return meanRadiusKm * KepplrConstants.VECTOR_ARROW_FOCUS_BODY_RADIUS_MULTIPLE * scaleFactor;
    }

    /** Detach all geometries currently in the scene graph. Called by {@link VectorManager#disableVector}. */
    void detach() {
        detachAll();
    }

    // ── private helpers ───────────────────────────────────────────────────────────────────────

    private void renderOne(
            VectorDefinition def, double arrowLengthKm, double et, double[] cameraHelioJ2000, Camera cam) {
        // 1. Resolve direction via the strategy — no type branching here.
        VectorIJK dir = def.getVectorType().computeDirection(def.getOriginNaifId(), et);
        if (dir == null) {
            return;
        }

        // 2. Resolve origin body heliocentric position.
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        VectorIJK originHelio = eph.getHeliocentricPositionJ2000(def.getOriginNaifId(), et);
        if (originHelio == null) {
            logger.warn("Vector '{}': no heliocentric position for NAIF {}", def.getLabel(), def.getOriginNaifId());
            return;
        }

        // Camera-relative origin position (floating origin).
        double ox = originHelio.getI() - cameraHelioJ2000[0];
        double oy = originHelio.getJ() - cameraHelioJ2000[1];
        double oz = originHelio.getK() - cameraHelioJ2000[2];
        double distKm = Math.sqrt(ox * ox + oy * oy + oz * oz);

        // 3. Skip if projected screen length < threshold (avoids sub-pixel clutter).
        if (distKm > 0.0) {
            double halfFovRad = Math.toRadians(KepplrConstants.CAMERA_FOV_Y_DEG / 2.0);
            double screenLengthPx = (arrowLengthKm / distKm) * (cam.getHeight() / 2.0) / Math.tan(halfFovRad);
            if (screenLengthPx < KepplrConstants.VECTOR_MIN_VISIBLE_LENGTH_PX) {
                return;
            }
        }

        // 4. Endpoint in camera-relative scene space.
        double ex = ox + dir.getI() * arrowLengthKm;
        double ey = oy + dir.getJ() * arrowLengthKm;
        double ez = oz + dir.getK() * arrowLengthKm;

        // 5. Frustum layer assignment (same logic as body rendering, §8.3).
        FrustumLayer layer = FrustumLayer.assign(distKm, 0.0);

        // 6. Build a two-vertex Lines mesh.
        Vector3f start = new Vector3f((float) ox, (float) oy, (float) oz);
        Vector3f end = new Vector3f((float) ex, (float) ey, (float) ez);

        Mesh mesh = new Mesh();
        mesh.setMode(Mesh.Mode.Lines);
        mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(start, end));
        mesh.setBuffer(VertexBuffer.Type.Index, 2, BufferUtils.createIntBuffer(new int[] {0, 1}));
        mesh.updateBound();

        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", def.getColor());

        Geometry geom = new Geometry("vector-" + def.getLabel(), mesh);
        geom.setMaterial(mat);

        layerNodes.get(layer).attachChild(geom);
        attachedGeoms.put(geom, layer);
    }

    private void detachAll() {
        for (Map.Entry<Geometry, FrustumLayer> entry : attachedGeoms.entrySet()) {
            Node node = layerNodes.get(entry.getValue());
            if (node != null) {
                node.detachChild(entry.getKey());
            }
        }
        attachedGeoms.clear();
    }
}
