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
import kepplr.state.SimulationState;
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
 *   <li>Filter: skip bodies without a body-fixed frame (§4.1) or with no valid ephemeris at current ET (returns null
 *       position).
 *   <li>For each visible body: compute camera-relative position, apparent pixel radius, cull decision (§7.3), and
 *       frustum assignment (§8).
 *   <li>Run screen-space cluster suppression on sprite-class bodies (§7.3), exempting bodies in an active interaction
 *       state.
 *   <li>Update or create the body's {@link BodySceneNode}; position and rotation updated in-place (geometry is not
 *       recreated per frame).
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
    private final SimulationState state;
    private final SaturnRingManager saturnRingManager;
    private final EclipseShadowManager eclipseShadowManager;

    /**
     * Persistent map from EphemerisID to scene node wrapper. Geometry is created once and reused; only
     * position/rotation/attachment are updated per frame.
     */
    private final Map<EphemerisID, BodySceneNode> bodyNodes = new HashMap<>();

    /**
     * Set of EphemerisIDs that belong to configured spacecraft. Used to skip spacecraft in the general body loop so
     * they are not double-rendered. Populated on first {@link #update} call.
     */
    private Set<EphemerisID> spacecraftIdSet;

    /**
     * Intermediate per-body data collected in pass 1 before scene-node updates. Holds everything needed to apply
     * decisions in pass 2.
     */
    private record BodyFrame(
            EphemerisID bodyId,
            int naifId,
            float dx,
            float dy,
            float dz,
            double dist,
            double bodyRadiusKm,
            CullDecision decision,
            RotationMatrixIJK rotation) {}

    /**
     * @param nearNode near-frustum root node (bodies 1 m – 1100 km)
     * @param midNode mid-frustum root node (bodies 900 km – 1.1×10⁹ km)
     * @param farNode far-frustum root node (bodies 9×10⁸ km – 10¹⁵ km)
     * @param assetManager JME asset manager for material and texture loading
     * @param state simulation state; read each frame for exempt body IDs during cluster suppression
     */
    public BodySceneManager(
            Node nearNode, Node midNode, Node farNode, AssetManager assetManager, SimulationState state) {
        this.nearNode = nearNode;
        this.midNode = midNode;
        this.farNode = farNode;
        this.assetManager = assetManager;
        this.state = state;
        this.saturnRingManager = new SaturnRingManager(assetManager);
        this.eclipseShadowManager = new EclipseShadowManager(state, saturnRingManager);
    }

    /**
     * Update all body scene nodes for the current simulation time.
     *
     * <p>Ephemeris is acquired at point-of-use per §3.3.
     *
     * @param et current ephemeris time (TDB seconds past J2000)
     * @param cameraHelioJ2000 camera heliocentric J2000 position (km), double[3]
     * @param cam current render camera (used for apparent-radius calculation and screen projection)
     * @return non-culled bodies visible this frame, sorted by ascending distance; never null
     */
    public List<BodyInView> update(double et, double[] cameraHelioJ2000, Camera cam) {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();

        initSpacecraftSet(eph);

        int viewportHeight = cam.getHeight();
        float fovYDeg = KepplrConstants.CAMERA_FOV_Y_DEG;

        // ── Pass 1: collect body frames ────────────────────────────────────────────────────────
        List<BodyFrame> frames = new ArrayList<>();

        for (EphemerisID bodyId : eph.getKnownBodies()) {
            if (spacecraftIdSet.contains(bodyId)) continue;
            if (!eph.hasBodyFixedFrame(bodyId)) continue;

            VectorIJK helioPos = eph.getHeliocentricPositionJ2000(bodyId, et);
            if (helioPos == null) continue;

            int naifId = eph.getSpiceBundle().getObjectCode(bodyId).orElse(-999);

            float dx = (float) (helioPos.getI() - cameraHelioJ2000[0]);
            float dy = (float) (helioPos.getJ() - cameraHelioJ2000[1]);
            float dz = (float) (helioPos.getK() - cameraHelioJ2000[2]);
            double dist = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);

            Ellipsoid shape = eph.getShape(bodyId);
            double bodyRadius = meanRadius(shape);

            double apparentPx = BodyCuller.computeApparentRadiusPx(bodyRadius, dist, viewportHeight, fovYDeg);
            CullDecision decision = BodyCuller.decide(apparentPx);

            RotationMatrixIJK rotation = null;
            if (decision == CullDecision.DRAW_FULL) {
                rotation = eph.getJ2000ToBodyFixedRotation(bodyId, et);
            }

            frames.add(new BodyFrame(bodyId, naifId, dx, dy, dz, dist, bodyRadius, decision, rotation));
        }

        // ── Cluster suppression pass (§7.3) ────────────────────────────────────────────────────
        Set<Integer> clusterCulled = computeClusterSuppression(frames, cam);

        // ── Pass 2: apply decisions ────────────────────────────────────────────────────────────
        Set<EphemerisID> visibleThisFrame = new HashSet<>();
        List<BodyInView> inView = new ArrayList<>();
        BodySceneNode saturnBsn = null;

        for (BodyFrame frame : frames) {
            CullDecision effective = frame.decision();
            if (effective == CullDecision.DRAW_SPRITE && clusterCulled.contains(frame.naifId())) {
                effective = CullDecision.CULL;
            }

            if (effective == CullDecision.CULL) {
                BodySceneNode stale = bodyNodes.get(frame.bodyId());
                if (stale != null) stale.detach();
                continue;
            }

            visibleThisFrame.add(frame.bodyId());
            if (isInCameraFov(frame.dx(), frame.dy(), frame.dz(), frame.dist(), cam)) {
                inView.add(new BodyInView(frame.bodyId().getName(), frame.naifId(), frame.dist()));
            }

            Ellipsoid shape = KEPPLRConfiguration.getInstance().getEphemeris().getShape(frame.bodyId());
            BodySceneNode bsn = bodyNodes.computeIfAbsent(
                    frame.bodyId(), id -> BodyNodeFactory.createBodyNode(id, frame.naifId(), shape, assetManager));

            bsn.updatePosition(new Vector3f(frame.dx(), frame.dy(), frame.dz()));

            if (effective == CullDecision.DRAW_FULL && frame.rotation() != null) {
                bsn.updateRotation(frame.rotation());
            }

            FrustumLayer layer = FrustumLayer.assign(frame.dist(), frame.bodyRadiusKm());
            bsn.apply(effective, layer, nearNode, midNode, farNode, frame.dist(), viewportHeight, fovYDeg);

            if (frame.naifId() == KepplrConstants.SATURN_NAIF_ID && effective == CullDecision.DRAW_FULL) {
                saturnBsn = bsn;
            }
        }

        // ── Saturn ring update ──────────────────────────────────────────────────────────────────
        saturnRingManager.update(saturnBsn, cameraHelioJ2000);

        // ── Eclipse shadow update (Step 16b) ───────────────────────────────────────────────────
        eclipseShadowManager.update(bodyNodes.values(), saturnBsn, et, cameraHelioJ2000);

        // ── Spacecraft (always point sprites) ──────────────────────────────────────────────────
        KEPPLREphemeris eph2 = KEPPLRConfiguration.getInstance().getEphemeris();
        for (Spacecraft sc : eph2.getSpacecraft()) {
            VectorIJK helioPos = eph2.getHeliocentricPositionJ2000(sc.id(), et);
            if (helioPos == null) continue;

            float dx = (float) (helioPos.getI() - cameraHelioJ2000[0]);
            float dy = (float) (helioPos.getJ() - cameraHelioJ2000[1]);
            float dz = (float) (helioPos.getK() - cameraHelioJ2000[2]);
            double dist = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);

            int naifId = eph2.getSpiceBundle().getObjectCode(sc.id()).orElse(-999);
            visibleThisFrame.add(sc.id());
            if (isInCameraFov(dx, dy, dz, dist, cam)) {
                inView.add(new BodyInView(sc.id().getName(), naifId, dist));
            }

            BodySceneNode bsn =
                    bodyNodes.computeIfAbsent(sc.id(), id -> BodyNodeFactory.createSpacecraftNode(sc, assetManager));

            bsn.updatePosition(new Vector3f(dx, dy, dz));

            FrustumLayer layer = FrustumLayer.assign(dist, 0.0);
            bsn.apply(CullDecision.DRAW_SPRITE, layer, nearNode, midNode, farNode, dist, viewportHeight, fovYDeg);
        }

        // ── Detach bodies no longer visible ────────────────────────────────────────────────────
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

    /**
     * Run screen-space cluster suppression on all sprite-class bodies from pass 1.
     *
     * <p>Projects each sprite to screen space using the current camera. Bodies that project behind the camera (depth
     * outside [0, 1]) are excluded from suppression to avoid spurious culling.
     */
    private Set<Integer> computeClusterSuppression(List<BodyFrame> frames, Camera cam) {
        Set<Integer> exemptIds = exemptBodyIds();
        List<BodyCuller.SpriteCandidate> candidates = new ArrayList<>();

        for (BodyFrame frame : frames) {
            if (frame.decision() != CullDecision.DRAW_SPRITE) continue;
            Vector3f screen = cam.getScreenCoordinates(new Vector3f(frame.dx(), frame.dy(), frame.dz()));
            if (screen.z < 0f || screen.z > 1f) continue; // behind or beyond the camera
            boolean exempt = exemptIds.contains(frame.naifId());
            candidates.add(
                    new BodyCuller.SpriteCandidate(frame.naifId(), screen.x, screen.y, frame.bodyRadiusKm(), exempt));
        }

        return BodyCuller.computeClusterCulls(candidates, KepplrConstants.SPRITE_CLUSTER_PROXIMITY_PX);
    }

    /** Returns the set of NAIF IDs currently in an active interaction state (exempt from cluster suppression). */
    private Set<Integer> exemptBodyIds() {
        Set<Integer> exempt = new HashSet<>();
        int sel = state.selectedBodyIdProperty().get();
        int foc = state.focusedBodyIdProperty().get();
        int tgt = state.targetedBodyIdProperty().get();
        int trk = state.trackedBodyIdProperty().get();
        if (sel != -1) exempt.add(sel);
        if (foc != -1) exempt.add(foc);
        if (tgt != -1) exempt.add(tgt);
        if (trk != -1) exempt.add(trk);
        return exempt;
    }

    private void initSpacecraftSet(KEPPLREphemeris eph) {
        if (spacecraftIdSet != null) return;
        spacecraftIdSet = new HashSet<>();
        for (Spacecraft sc : eph.getSpacecraft()) {
            spacecraftIdSet.add(sc.id());
        }
    }

    /**
     * Returns true if the body at scene-relative offset (dx, dy, dz) is within the camera's viewing cone, using
     * {@link KepplrConstants#BODIES_IN_VIEW_COS_THRESHOLD} as the cutoff.
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
