package kepplr.render.vector;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.Quaternion;
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
import java.util.function.IntToDoubleFunction;
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
 * Renders a list of {@link VectorDefinition} objects as arrows (line + cone arrowhead) in the JME scene graph.
 *
 * <p>For each definition, this renderer:
 *
 * <ol>
 *   <li>Resolves the origin body's heliocentric J2000 position.
 *   <li>Calls {@link VectorType#computeDirection} — no branching over type.
 *   <li>Projects the scale to screen space and skips vectors shorter than
 *       {@link KepplrConstants#VECTOR_MIN_VISIBLE_LENGTH_PX}.
 *   <li>Assigns the geometry to the correct frustum layer via {@link FrustumLayer#assign}.
 *   <li>Creates a line segment with an arrowhead cone at the tip.
 * </ol>
 *
 * <p>Arrow length for types where {@link VectorType#usesOriginBodyRadius()} returns {@code true} (e.g. body-fixed axes)
 * is based on the <em>origin</em> body's radius. For all other types it is based on the focused body's radius.
 *
 * <p>Previously attached geometries are detached before each rebuild.
 *
 * <p>All methods must be called on the JME render thread (CLAUDE.md Rule 4). No switch/case or if/else chain over
 * {@link VectorType} exists anywhere in this class.
 */
class VectorRenderer {

    private static final Logger logger = LogManager.getLogger(VectorRenderer.class);

    /** Fraction of the arrow shaft length used for the arrowhead cone height. */
    private static final float ARROWHEAD_LENGTH_FRACTION = 0.12f;

    /** Ratio of arrowhead base radius to arrowhead height. */
    private static final float ARROWHEAD_RADIUS_RATIO = 0.35f;

    /** Number of radial segments for the arrowhead cone mesh. */
    private static final int ARROWHEAD_SEGMENTS = 8;

    /** Line width in pixels for vector shafts. */
    private static final float LINE_WIDTH = 2f;

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
     * == -1} or if {@code definitions} is empty.
     *
     * <p>For definitions whose {@link VectorType#usesOriginBodyRadius()} returns {@code true}, the arrow length is
     * based on the origin body's effective radius (from the scene, with ephemeris fallback). For all others it is based
     * on the focused body's radius.
     *
     * <p>Definitions whose direction is unavailable or whose projected length falls below
     * {@link KepplrConstants#VECTOR_MIN_VISIBLE_LENGTH_PX} are silently skipped.
     *
     * @param definitions active vector definitions to render
     * @param et current simulation ET (TDB seconds past J2000)
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km (length >= 3)
     * @param cam active JME camera (used for screen-space length projection)
     * @param focusedBodyId NAIF ID of the currently focused body, or -1 if none
     * @param sceneRadiusLookup function returning the effective rendered radius (km) for a NAIF ID, or 0.0 if unknown;
     *     used for origin-body radius when {@link VectorType#usesOriginBodyRadius()} is true
     */
    void update(
            List<VectorDefinition> definitions,
            double et,
            double[] cameraHelioJ2000,
            Camera cam,
            int focusedBodyId,
            IntToDoubleFunction sceneRadiusLookup) {
        detachAll();
        if (focusedBodyId == -1) {
            return;
        }
        if (definitions == null || definitions.isEmpty()) {
            return;
        }

        // Resolve focused body mean radius at point-of-use (Architecture Rule 3).
        double focusedMeanRadiusKm = resolveMeanRadiusKm(focusedBodyId, sceneRadiusLookup);

        for (VectorDefinition def : definitions) {
            try {
                double baseRadiusKm;
                if (def.getVectorType().usesOriginBodyRadius()) {
                    baseRadiusKm = resolveMeanRadiusKm(def.getOriginNaifId(), sceneRadiusLookup);
                } else {
                    baseRadiusKm = focusedMeanRadiusKm;
                }
                double arrowLengthKm = computeArrowLengthKm(baseRadiusKm, def.getScaleFactor());
                renderOne(def, arrowLengthKm, et, cameraHelioJ2000, cam);
            } catch (Exception e) {
                logger.warn("Vector render failed for '{}': {}", def.getLabel(), e.getMessage());
            }
        }
    }

    /**
     * Computes arrow length in km from a body mean radius and the definition's scale factor.
     *
     * <p>Package-private for unit testing.
     *
     * @param meanRadiusKm body mean radius in km (focused or origin body depending on vector type)
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

    /**
     * Resolve the effective radius for the given NAIF body ID. Prefers the scene-derived radius (which accounts for GLB
     * bounding radius and spacecraft scale factors), falling back to ephemeris shape data, then to
     * {@link KepplrConstants#BODY_DEFAULT_RADIUS_KM}.
     */
    private static double resolveMeanRadiusKm(int naifId, IntToDoubleFunction sceneRadiusLookup) {
        // Try scene-derived radius first (accounts for GLB bounding radius / spacecraft scale).
        if (sceneRadiusLookup != null) {
            double sceneRadius = sceneRadiusLookup.applyAsDouble(naifId);
            if (sceneRadius > 0.0) {
                return sceneRadius;
            }
        }
        // Fall back to ephemeris shape data.
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        EphemerisID ephId;
        try {
            ephId = eph.getSpiceBundle().getObject(naifId);
        } catch (Exception e) {
            return KepplrConstants.BODY_DEFAULT_RADIUS_KM;
        }
        if (ephId == null) {
            return KepplrConstants.BODY_DEFAULT_RADIUS_KM;
        }
        Ellipsoid shape = eph.getShape(ephId);
        if (shape == null) {
            return KepplrConstants.BODY_DEFAULT_RADIUS_KM;
        }
        return (shape.getA() + shape.getB() + shape.getC()) / 3.0;
    }

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
            double screenLengthPx =
                    (arrowLengthKm / distKm) * (cam.getHeight() / 2.0) / Math.tan(Math.toRadians(cam.getFov() / 2.0));
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

        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", def.getColor());

        // 6. Build the line shaft (origin → shaft end, leaving room for arrowhead).
        Vector3f startF = new Vector3f((float) ox, (float) oy, (float) oz);
        Vector3f endF = new Vector3f((float) ex, (float) ey, (float) ez);
        Vector3f dirF = endF.subtract(startF);
        float shaftLen = dirF.length();
        if (shaftLen < 1e-12f) {
            return;
        }
        Vector3f dirUnit = dirF.divide(shaftLen);

        float headLen = shaftLen * ARROWHEAD_LENGTH_FRACTION;
        Vector3f shaftEnd = endF.subtract(dirUnit.mult(headLen));

        Mesh lineMesh = new Mesh();
        lineMesh.setMode(Mesh.Mode.Lines);
        lineMesh.setLineWidth(LINE_WIDTH);
        lineMesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(startF, shaftEnd));
        lineMesh.setBuffer(VertexBuffer.Type.Index, 2, BufferUtils.createIntBuffer(new int[] {0, 1}));
        lineMesh.updateBound();

        Geometry lineGeom = new Geometry("vector-" + def.getLabel(), lineMesh);
        lineGeom.setMaterial(mat);
        layerNodes.get(layer).attachChild(lineGeom);
        attachedGeoms.put(lineGeom, layer);

        // 7. Build the arrowhead cone at the tip.
        float headRadius = headLen * ARROWHEAD_RADIUS_RATIO;
        Mesh coneMesh = buildConeMesh(headLen, headRadius);
        Geometry coneGeom = new Geometry("arrow-" + def.getLabel(), coneMesh);
        coneGeom.setMaterial(mat);

        // Orient cone: default cone axis is +Y; rotate to align with dirUnit.
        Quaternion rot = new Quaternion();
        rot.lookAt(dirUnit, Vector3f.UNIT_Y);
        // lookAt aligns -Z with dirUnit; we need +Y aligned, so apply a 90° pitch correction.
        Quaternion pitch = new Quaternion().fromAngleAxis((float) (Math.PI / 2.0), Vector3f.UNIT_X);
        coneGeom.setLocalRotation(rot.mult(pitch));
        coneGeom.setLocalTranslation(shaftEnd);

        layerNodes.get(layer).attachChild(coneGeom);
        attachedGeoms.put(coneGeom, layer);
    }

    /**
     * Build a cone mesh with the apex at (0, height, 0) and base centred at the origin in the XZ plane.
     *
     * <p>The cone is oriented along +Y so that the apex points in the positive Y direction. After construction the
     * caller rotates it to align with the vector direction.
     */
    private static Mesh buildConeMesh(float height, float radius) {
        int segments = ARROWHEAD_SEGMENTS;
        // vertices: segments for base ring + 1 apex + 1 base centre
        int vertCount = segments + 2;
        float[] positions = new float[vertCount * 3];

        // Base ring vertices (y=0, in XZ plane)
        for (int i = 0; i < segments; i++) {
            double angle = 2.0 * Math.PI * i / segments;
            positions[i * 3] = radius * (float) Math.cos(angle);
            positions[i * 3 + 1] = 0.0f;
            positions[i * 3 + 2] = radius * (float) Math.sin(angle);
        }
        // Apex vertex
        int apexIdx = segments;
        positions[apexIdx * 3] = 0.0f;
        positions[apexIdx * 3 + 1] = height;
        positions[apexIdx * 3 + 2] = 0.0f;
        // Base centre vertex
        int centreIdx = segments + 1;
        positions[centreIdx * 3] = 0.0f;
        positions[centreIdx * 3 + 1] = 0.0f;
        positions[centreIdx * 3 + 2] = 0.0f;

        // Triangles: side faces (apex, ring[i], ring[i+1]) + base faces (centre, ring[i+1], ring[i])
        int triCount = segments * 2;
        int[] indices = new int[triCount * 3];
        for (int i = 0; i < segments; i++) {
            int next = (i + 1) % segments;
            // Side face
            indices[i * 6] = apexIdx;
            indices[i * 6 + 1] = i;
            indices[i * 6 + 2] = next;
            // Base face
            indices[i * 6 + 3] = centreIdx;
            indices[i * 6 + 4] = next;
            indices[i * 6 + 5] = i;
        }

        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(positions));
        mesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(indices));
        mesh.updateBound();
        return mesh;
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
