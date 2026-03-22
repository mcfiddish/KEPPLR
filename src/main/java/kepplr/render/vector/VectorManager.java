package kepplr.render.vector;

import com.jme3.asset.AssetManager;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Coordinates the active set of {@link VectorDefinition} instances and drives {@link VectorRenderer}.
 *
 * <p>Called once per frame from the JME render thread. Vector geometry is rebuilt only when the active set changes or
 * the simulation time advances beyond {@link KepplrConstants#VECTOR_STALENESS_THRESHOLD_SEC}.
 *
 * <h3>Identity keying</h3>
 *
 * <p>{@link VectorDefinition} does not override {@code equals}/{@code hashCode}, so this class uses an
 * {@link IdentityHashMap} to key by object reference. Callers must pass the <em>same instance</em> to
 * {@link #disableVector} that they passed to {@link #enableVector}; a different object with identical fields will not
 * be recognised.
 *
 * <h3>Graceful degradation</h3>
 *
 * <p>Definitions whose {@link VectorType#computeDirection} returns {@code null} (no ephemeris coverage, no body-fixed
 * frame, etc.) are silently skipped by {@link VectorRenderer}; no exception propagates to the caller.
 *
 * <p>All methods must be called on the JME render thread (CLAUDE.md Rule 4).
 */
public class VectorManager {

    private static final Logger logger = LogManager.getLogger(VectorManager.class);

    /**
     * Identity-keyed set of active definitions.
     *
     * <p>The value is always {@code Boolean.TRUE}; only the key identity matters.
     */
    private final Map<VectorDefinition, Boolean> identitySet = new IdentityHashMap<>();

    /**
     * Insertion-ordered list of active definitions used for deterministic iteration during rendering.
     *
     * <p>Kept in sync with {@link #identitySet}. {@link ArrayList#remove(Object)} uses reference equality (inherited
     * from {@link Object#equals}) for {@link VectorDefinition}, which is identical to identity comparison since
     * {@code VectorDefinition} does not override {@code equals}.
     */
    private final List<VectorDefinition> orderedDefinitions = new ArrayList<>();

    private final VectorRenderer renderer;

    /** Whether the active definition set has changed since the last render (used for future optimizations). */
    private boolean dirty = false;

    /**
     * @param nearNode near frustum root node
     * @param midNode mid frustum root node
     * @param farNode far frustum root node
     * @param assetManager JME asset manager for material creation
     */
    public VectorManager(Node nearNode, Node midNode, Node farNode, AssetManager assetManager) {
        this.renderer = new VectorRenderer(nearNode, midNode, farNode, assetManager);
    }

    /**
     * Enable a vector overlay.
     *
     * <p>If the same instance is already active, this is a no-op. No ephemeris access occurs here.
     *
     * @param definition vector definition to activate; the caller must retain this reference and pass it to
     *     {@link #disableVector} to remove it later
     */
    public void enableVector(VectorDefinition definition) {
        if (!identitySet.containsKey(definition)) {
            identitySet.put(definition, Boolean.TRUE);
            orderedDefinitions.add(definition);
            dirty = true;
        }
    }

    /**
     * Disable and remove a vector overlay.
     *
     * <p>If the definition is not in the active set, this is a no-op. Must be the same instance passed to
     * {@link #enableVector}.
     *
     * @param definition vector definition to deactivate
     */
    public void disableVector(VectorDefinition definition) {
        if (identitySet.containsKey(definition)) {
            identitySet.remove(definition);
            orderedDefinitions.remove(definition); // uses reference equality — safe; see class doc
            dirty = true;
        }
    }

    /**
     * Update all active vector overlays for the current simulation time.
     *
     * <p>Geometry is rebuilt every frame because vector origins are expressed in camera-relative scene coordinates
     * (floating origin), which change whenever the camera moves. The {@link VectorType#computeDirection} calls are
     * inexpensive (single SPICE lookup per vector), so rebuilding every frame is negligible in cost.
     *
     * <p>No vectors are rendered when {@code focusedBodyId == -1} (no focus body) or when the focused body has no shape
     * data — see {@link VectorRenderer#update}.
     *
     * @param et current simulation ET (TDB seconds past J2000)
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km (length ≥ 3)
     * @param cam active JME camera (used for screen-space length projection)
     * @param focusedBodyId NAIF ID of the currently focused body, or −1 if none
     * @param sceneRadiusLookup function returning the effective rendered radius (km) for a NAIF ID, or 0.0 if unknown;
     *     used by origin-body-radius vector types (e.g. body-fixed axes) to scale arrows relative to the body they are
     *     drawn on rather than the focused body
     */
    public void update(
            double et,
            double[] cameraHelioJ2000,
            Camera cam,
            int focusedBodyId,
            IntToDoubleFunction sceneRadiusLookup) {
        try {
            renderer.update(
                    List.copyOf(orderedDefinitions), et, cameraHelioJ2000, cam, focusedBodyId, sceneRadiusLookup);
            dirty = false;
        } catch (Exception e) {
            logger.warn("VectorManager.update() failed at ET={}: {}", et, e.getMessage());
        }
    }

    /**
     * Returns an unmodifiable view of the currently active definitions, in insertion order.
     *
     * <p>Package-private for tests.
     */
    List<VectorDefinition> getActiveDefinitions() {
        return List.copyOf(orderedDefinitions);
    }
}
