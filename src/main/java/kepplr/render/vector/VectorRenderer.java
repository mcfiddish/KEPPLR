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
     * <p>Detaches all previously attached geometries, then renders each definition in {@code definitions}. Definitions
     * whose direction is unavailable or whose projected length falls below
     * {@link KepplrConstants#VECTOR_MIN_VISIBLE_LENGTH_PX} are silently skipped.
     *
     * @param definitions active vector definitions to render
     * @param et current simulation ET (TDB seconds past J2000)
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km (length ≥ 3)
     * @param cam active JME camera (used for screen-space length projection)
     */
    void update(List<VectorDefinition> definitions, double et, double[] cameraHelioJ2000, Camera cam) {
        detachAll();
        if (definitions == null || definitions.isEmpty()) {
            return;
        }
        for (VectorDefinition def : definitions) {
            try {
                renderOne(def, et, cameraHelioJ2000, cam);
            } catch (Exception e) {
                logger.warn("Vector render failed for '{}': {}", def.getLabel(), e.getMessage());
            }
        }
    }

    /** Detach all geometries currently in the scene graph. Called by {@link VectorManager#disableVector}. */
    void detach() {
        detachAll();
    }

    // ── private helpers ───────────────────────────────────────────────────────────────────────

    private void renderOne(VectorDefinition def, double et, double[] cameraHelioJ2000, Camera cam) {
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
        double scaleKm = def.getScaleKm();
        if (distKm > 0.0) {
            double halfFovRad = Math.toRadians(KepplrConstants.CAMERA_FOV_Y_DEG / 2.0);
            double screenLengthPx = (scaleKm / distKm) * (cam.getHeight() / 2.0) / Math.tan(halfFovRad);
            if (screenLengthPx < KepplrConstants.VECTOR_MIN_VISIBLE_LENGTH_PX) {
                return;
            }
        }

        // 4. Endpoint in camera-relative scene space.
        double ex = ox + dir.getI() * scaleKm;
        double ey = oy + dir.getJ() * scaleKm;
        double ez = oz + dir.getK() * scaleKm;

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
