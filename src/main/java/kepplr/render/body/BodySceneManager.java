package kepplr.render.body;

import com.jme3.asset.AssetManager;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.ephemeris.Spacecraft;
import kepplr.render.frustum.FrustumLayer;
import kepplr.state.BodyInView;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.RotationMatrixIJK;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.EphemerisID;
import picante.surfaces.Ellipsoid;

/**
 * Manages all body and spacecraft scene nodes across three frustum layers.
 *
 * <p>Called once per frame from {@code KepplrApp.simpleUpdate()}. On each call:
 *
 * <ol>
 *   <li>Acquire ephemeris at point-of-use (§3.3 — never stored).
 *   <li>Enumerate all known bodies and spacecraft.
 *   <li>Filter: skip bodies without a body-fixed frame (§4.1) or with no valid ephemeris at current
 *       ET (returns null position).
 *   <li>For each visible body: compute camera-relative position, apparent pixel radius, cull
 *       decision (§7.3), and frustum assignment (§8).
 *   <li>Update or create the body's {@link BodySceneNode}; position and rotation updated in-place
 *       (geometry is not recreated per frame).
 *   <li>Detach any body that was visible last frame but is no longer renderable.
 * </ol>
 *
 * <p>All operations run on the JME render thread (CLAUDE.md Rule 4).
 */
public class BodySceneManager {

    private static final Logger logger = LogManager.getLogger();

    private final Node nearNode;
    private final Node midNode;
    private final Node farNode;
    private final AssetManager assetManager;

    /**
     * Persistent map from EphemerisID to scene node wrapper. Geometry is created once and reused;
     * only position/rotation/attachment are updated per frame.
     */
    private final Map<EphemerisID, BodySceneNode> bodyNodes = new HashMap<>();

    /**
     * Set of EphemerisIDs that belong to configured spacecraft. Used to skip spacecraft in the
     * general body loop so they are not double-rendered. Populated on first {@link #update} call.
     */
    private Set<EphemerisID> spacecraftIdSet;

    /**
     * @param nearNode     near-frustum root node (bodies 1 m – 1100 km)
     * @param midNode      mid-frustum root node (bodies 900 km – 1.1×10⁹ km)
     * @param farNode      far-frustum root node (bodies 9×10⁸ km – 10¹⁵ km)
     * @param assetManager JME asset manager for material and texture loading
     */
    public BodySceneManager(Node nearNode, Node midNode, Node farNode, AssetManager assetManager) {
        this.nearNode = nearNode;
        this.midNode = midNode;
        this.farNode = farNode;
        this.assetManager = assetManager;
    }

    /**
     * Update all body scene nodes for the current simulation time.
     *
     * <p>Ephemeris is acquired at point-of-use per §3.3.
     *
     * @param et               current ephemeris time (TDB seconds past J2000)
     * @param cameraHelioJ2000 camera heliocentric J2000 position (km), double[3]
     * @param cam              current render camera (used for apparent-radius calculation)
     * @return non-culled bodies visible this frame, sorted by ascending distance; never null
     */
    public List<BodyInView> update(double et, double[] cameraHelioJ2000, Camera cam) {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();

        initSpacecraftSet(eph);

        int viewportHeight = cam.getHeight();
        float fovYDeg = KepplrConstants.CAMERA_FOV_Y_DEG;

        Set<EphemerisID> visibleThisFrame = new HashSet<>();
        List<BodyInView> inView = new ArrayList<>();

        // ── Natural bodies (from getKnownBodies()) ─────────────────────────────────────────────
        for (EphemerisID bodyId : eph.getKnownBodies()) {

            // Skip spacecraft — handled separately below
            if (spacecraftIdSet.contains(bodyId)) continue;

            // §4.1: must have a body-fixed frame
            if (!eph.hasBodyFixedFrame(bodyId)) continue;

            // §4.1: must have valid ephemeris at current ET
            VectorIJK helioPos = eph.getHeliocentricPositionJ2000(bodyId, et);
            if (helioPos == null) continue;

            int naifId = eph.getSpiceBundle().getObjectCode(bodyId).orElse(-999);

            // Camera-relative position (floating origin)
            double dx = helioPos.getI() - cameraHelioJ2000[0];
            double dy = helioPos.getJ() - cameraHelioJ2000[1];
            double dz = helioPos.getK() - cameraHelioJ2000[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            Ellipsoid shape = eph.getShape(bodyId);
            double bodyRadius = meanRadius(shape);

            // §7.3: culling decision
            double apparentPx = BodyCuller.computeApparentRadiusPx(bodyRadius, dist, viewportHeight, fovYDeg);
            CullDecision decision = BodyCuller.decide(apparentPx, naifId);

            if (decision == CullDecision.CULL) {
                // Satellite too small — detach if previously visible
                BodySceneNode stale = bodyNodes.get(bodyId);
                if (stale != null) stale.detach();
                continue;
            }

            visibleThisFrame.add(bodyId);
            if (isInCameraFov(dx, dy, dz, dist, cam)) {
                inView.add(new BodyInView(bodyId.getName(), naifId, dist));
            }

            // Get or create scene node
            BodySceneNode bsn = bodyNodes.computeIfAbsent(
                    bodyId, id -> BodyNodeFactory.createBodyNode(id, naifId, shape, assetManager));

            bsn.updatePosition(new Vector3f((float) dx, (float) dy, (float) dz));

            if (decision == CullDecision.DRAW_FULL) {
                RotationMatrixIJK rot = eph.getJ2000ToBodyFixedRotation(bodyId, et);
                if (rot != null) bsn.updateRotation(rot);
            }

            FrustumLayer layer = FrustumLayer.assign(dist, bodyRadius);
            bsn.apply(decision, layer, nearNode, midNode, farNode, dist, viewportHeight, fovYDeg);
        }

        // ── Spacecraft (always point sprites) ──────────────────────────────────────────────────
        for (Spacecraft sc : eph.getSpacecraft()) {
            VectorIJK helioPos = eph.getHeliocentricPositionJ2000(sc.id(), et);
            if (helioPos == null) continue;

            double dx = helioPos.getI() - cameraHelioJ2000[0];
            double dy = helioPos.getJ() - cameraHelioJ2000[1];
            double dz = helioPos.getK() - cameraHelioJ2000[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            int naifId = eph.getSpiceBundle().getObjectCode(sc.id()).orElse(-999);
            visibleThisFrame.add(sc.id());
            if (isInCameraFov(dx, dy, dz, dist, cam)) {
                inView.add(new BodyInView(sc.id().getName(), naifId, dist));
            }

            BodySceneNode bsn = bodyNodes.computeIfAbsent(
                    sc.id(), id -> BodyNodeFactory.createSpacecraftNode(sc, assetManager));

            bsn.updatePosition(new Vector3f((float) dx, (float) dy, (float) dz));

            // Spacecraft always render as sprites — no cull check, no rotation needed
            FrustumLayer layer = FrustumLayer.assign(dist, 0.0);
            bsn.apply(CullDecision.DRAW_SPRITE, layer, nearNode, midNode, farNode,
                    dist, viewportHeight, fovYDeg);
        }

        // ── Detach bodies no longer visible (out of ephemeris coverage, etc.) ─────────────────
        bodyNodes.entrySet().removeIf(entry -> {
            if (!visibleThisFrame.contains(entry.getKey())) {
                entry.getValue().detach();
                return true;
            }
            return false;
        });

        inView.sort(Comparator.comparingDouble(BodyInView::distanceKm));
        return inView;
    }

    // ── private helpers ──────────────────────────────────────────────────────────────────────────

    private void initSpacecraftSet(KEPPLREphemeris eph) {
        if (spacecraftIdSet != null) return;
        spacecraftIdSet = new HashSet<>();
        for (Spacecraft sc : eph.getSpacecraft()) {
            spacecraftIdSet.add(sc.id());
        }
    }

    /**
     * Returns true if the body at scene-relative offset (dx, dy, dz) is within the camera's
     * viewing cone, using {@link KepplrConstants#BODIES_IN_VIEW_COS_THRESHOLD} as the cutoff.
     */
    private static boolean isInCameraFov(double dx, double dy, double dz, double dist, Camera cam) {
        if (dist <= 0.0) return false;
        float bx = (float) (dx / dist);
        float by = (float) (dy / dist);
        float bz = (float) (dz / dist);
        Vector3f camDir = cam.getDirection();
        float dot = camDir.x * bx + camDir.y * by + camDir.z * bz;
        return dot >= KepplrConstants.BODIES_IN_VIEW_COS_THRESHOLD;
    }

    private static double meanRadius(Ellipsoid shape) {
        if (shape == null) return 0.0;
        return (shape.getA() + shape.getB() + shape.getC()) / 3.0;
    }
}
