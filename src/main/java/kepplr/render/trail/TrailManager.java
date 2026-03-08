package kepplr.render.trail;

import com.jme3.asset.AssetManager;
import com.jme3.scene.Node;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.VectorIJK;

/**
 * Coordinates trail sampling and rendering for all enabled bodies.
 *
 * <p>Called once per frame from the JME render thread. Sampling ({@link TrailSampler}) is
 * rate-limited by the staleness threshold: SPICE calls are made only for trails whose cached ET
 * has drifted more than {@link KepplrConstants#TRAIL_STALENESS_THRESHOLD_SEC} from the current ET.
 * After a resample the state is fresh and the next resample will not occur until one simulated day
 * has elapsed.
 *
 * <p>Geometry is rebuilt from cached samples every frame (camera-relative conversion via floating
 * origin). Bodies with no valid ephemeris are logged and skipped; no exception propagates to the
 * caller.
 *
 * <p>All methods must be called on the JME render thread (CLAUDE.md Rule 4).
 */
public class TrailManager {

    private static final Logger logger = LogManager.getLogger();

    private final Node nearNode;
    private final Node midNode;
    private final Node farNode;
    private final AssetManager assetManager;

    /** NAIF IDs for which trails are active. Insertion-ordered for deterministic update sequence. */
    private final Set<Integer> enabledIds = new LinkedHashSet<>();

    /** Cached SPICE samples per NAIF ID. Replaced whenever the trail is resampled. */
    private final Map<Integer, TrailState> trailStates = new HashMap<>();

    /** Renderer per NAIF ID; created on first update after enableTrail. */
    private final Map<Integer, TrailRenderer> renderers = new HashMap<>();

    /**
     * @param nearNode near frustum root node
     * @param midNode mid frustum root node
     * @param farNode far frustum root node
     * @param assetManager JME asset manager for trail material creation
     */
    public TrailManager(Node nearNode, Node midNode, Node farNode, AssetManager assetManager) {
        this.nearNode = nearNode;
        this.midNode = midNode;
        this.farNode = farNode;
        this.assetManager = assetManager;
    }

    /**
     * Enable a trail for the given body.
     *
     * <p>The trail will be sampled and rendered on the next {@link #update} call that falls within
     * the resample window. No ephemeris access occurs here.
     *
     * @param naifId NAIF integer ID of the body
     */
    public void enableTrail(int naifId) {
        enabledIds.add(naifId);
    }

    /**
     * Disable and remove the trail for the given body.
     *
     * <p>If the body has no active trail, this is a no-op (no exception thrown).
     *
     * @param naifId NAIF integer ID of the body
     */
    public void disableTrail(int naifId) {
        enabledIds.remove(naifId);
        TrailRenderer renderer = renderers.remove(naifId);
        if (renderer != null) {
            renderer.detach();
        }
        trailStates.remove(naifId);
    }

    /**
     * Update all active trails for the current simulation time.
     *
     * <p>SPICE resampling is performed whenever the staleness threshold is exceeded. Geometry is
     * always rebuilt from cached samples (floating-origin camera conversion). Bodies whose
     * ephemeris throws are logged and skipped.
     *
     * @param currentEt current simulation ET (TDB seconds past J2000)
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km (length ≥ 3)
     */
    public void update(double currentEt, double[] cameraHelioJ2000) {
        for (int naifId : enabledIds) {
            TrailState state = trailStates.get(naifId);
            boolean stale = state == null
                    || Math.abs(currentEt - state.sampledEt()) > KepplrConstants.TRAIL_STALENESS_THRESHOLD_SEC;

            if (stale) {
                try {
                    double duration = TrailSampler.computeTrailDurationSec(naifId, currentEt);
                    List<double[]> samples = TrailSampler.sample(naifId, currentEt, duration, "J2000");
                    int barycenterId = -1;
                    double[] baryAnchorKm = null;
                    if (isSatellite(naifId)) {
                        barycenterId = naifId / 100;
                        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
                        VectorIJK anchor = eph.getHeliocentricPositionJ2000(barycenterId, currentEt);
                        if (anchor != null) {
                            baryAnchorKm = new double[]{anchor.getI(), anchor.getJ(), anchor.getK()};
                        }
                    }
                    state = new TrailState(currentEt, samples, barycenterId, baryAnchorKm);
                    trailStates.put(naifId, state);
                } catch (Exception e) {
                    logger.warn("Trail resample failed for NAIF {}: {}", naifId, e.getMessage());
                }
            }

            if (state != null && !state.samples().isEmpty()) {
                TrailRenderer renderer = renderers.computeIfAbsent(
                        naifId, id -> new TrailRenderer(id, assetManager, nearNode, midNode, farNode));
                try {
                    double[] offset = null;
                    if (state.barycenterId() >= 0 && state.baryAnchorKm() != null) {
                        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
                        VectorIJK liveAnchor =
                                eph.getHeliocentricPositionJ2000(state.barycenterId(), currentEt);
                        if (liveAnchor != null) {
                            double[] ba = state.baryAnchorKm();
                            offset = new double[]{
                                liveAnchor.getI() - ba[0],
                                liveAnchor.getJ() - ba[1],
                                liveAnchor.getK() - ba[2]
                            };
                        }
                    }
                    renderer.update(state.samples(), cameraHelioJ2000, offset);
                } catch (Exception e) {
                    logger.warn("Trail render failed for NAIF {}: {}", naifId, e.getMessage());
                }
            }
        }
    }

    /** Returns true if {@code naifId} identifies a natural satellite (same rule as BodyCuller). */
    private static boolean isSatellite(int naifId) {
        return naifId >= 100 && naifId <= 999 && naifId % 100 != 99;
    }

    /**
     * Returns an unmodifiable view of the currently enabled NAIF IDs.
     *
     * <p>Package-private; intended for use in tests to inspect internal state without triggering
     * ephemeris or JME calls.
     */
    Set<Integer> getEnabledIds() {
        return Collections.unmodifiableSet(enabledIds);
    }

    /** Immutable snapshot of a trail's sampled state. */
    private record TrailState(
            double sampledEt,
            List<double[]> samples,
            int barycenterId,       // naifId/100 for satellites, -1 for planets
            double[] baryAnchorKm   // H_bary at sampledEt (km), null for planets
    ) {}
}
