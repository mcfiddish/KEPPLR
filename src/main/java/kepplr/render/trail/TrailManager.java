package kepplr.render.trail;

import com.jme3.asset.AssetManager;
import com.jme3.scene.Node;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.state.SimulationState;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.VectorIJK;

/**
 * Coordinates trail sampling and rendering for all enabled bodies.
 *
 * <p>Called once per frame from the JME render thread. The trail for each body has two parts:
 *
 * <ol>
 *   <li><b>Cached samples</b> — the full adaptive backward pass produced by {@link TrailSampler#sample} at the last
 *       resample time ({@code sampledEt}). This is recomputed only when {@code |currentEt − sampledEt|} exceeds
 *       {@link KepplrConstants#TRAIL_STALENESS_THRESHOLD_SEC}.
 *   <li><b>Live segment</b> — bridges the gap between {@code sampledEt} and {@code currentEt}. It consists of <em>fixed
 *       intermediate points</em> (appended once each time {@code currentEt} crosses a
 *       {@link KepplrConstants#TRAIL_MAX_ARC_DEG} boundary, then never moved) plus a single <em>moving endpoint</em>
 *       recomputed at {@code currentEt} every frame. Intermediate points are fixed so they do not shimmer; only the
 *       final 0–2° segment grows and resets.
 * </ol>
 *
 * <p>All methods must be called on the JME render thread (CLAUDE.md Rule 4).
 */
public class TrailManager {

    private static final Logger logger = LogManager.getLogger();

    private final Node nearNode;
    private final Node midNode;
    private final Node farNode;
    private final AssetManager assetManager;

    /**
     * Simulation state; read each frame on the JME render thread for the active render quality (§9.4).
     *
     * <p>The sample cap is derived from {@code state.renderQualityProperty().get().trailSamplesPerPeriod()} so quality
     * changes propagate without requiring an external setter call (CLAUDE.md Rule 2).
     */
    private final SimulationState state;

    /** NAIF IDs for which trails are active. Insertion-ordered for deterministic update sequence. */
    private final Set<Integer> enabledIds = new LinkedHashSet<>();

    /** Cached SPICE samples per NAIF ID. Replaced on full resample. */
    private final Map<Integer, TrailState> trailStates = new HashMap<>();

    /**
     * Fixed intermediate live-segment points per NAIF ID.
     *
     * <p>Each entry grows by one point every time {@code currentEt} crosses a {@link KepplrConstants#TRAIL_MAX_ARC_DEG}
     * boundary past {@code sampledEt}. Cleared on full resample. Points are ordered oldest-first (ascending ET).
     */
    private final Map<Integer, List<double[]>> liveFixedMap = new HashMap<>();

    /** Renderer per NAIF ID; created on first update after enableTrail. */
    private final Map<Integer, TrailRenderer> renderers = new HashMap<>();

    /**
     * @param nearNode near frustum root node
     * @param midNode mid frustum root node
     * @param farNode far frustum root node
     * @param assetManager JME asset manager for trail material creation
     * @param state simulation state; read each frame for render quality (§9.4)
     */
    public TrailManager(Node nearNode, Node midNode, Node farNode, AssetManager assetManager, SimulationState state) {
        this.nearNode = nearNode;
        this.midNode = midNode;
        this.farNode = farNode;
        this.assetManager = assetManager;
        this.state = state;
    }

    /** Enable a trail for the given body. No ephemeris access occurs here. */
    public void enableTrail(int naifId) {
        enabledIds.add(naifId);
    }

    /** Disable and remove the trail for the given body. If the body has no active trail, this is a no-op. */
    public void disableTrail(int naifId) {
        enabledIds.remove(naifId);
        TrailRenderer renderer = renderers.remove(naifId);
        if (renderer != null) {
            renderer.detach();
        }
        trailStates.remove(naifId);
        liveFixedMap.remove(naifId);
    }

    /**
     * Update all active trails for the current simulation time.
     *
     * @param currentEt current simulation ET (TDB seconds past J2000)
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km (length ≥ 3)
     */
    public void update(double currentEt, double[] cameraHelioJ2000) {
        for (int naifId : enabledIds) {
            TrailState state = trailStates.get(naifId);
            double stalenessThreshold = state == null
                    ? 0.0
                    : Math.min(
                            KepplrConstants.TRAIL_STALENESS_THRESHOLD_SEC,
                            state.durationSec() * KepplrConstants.TRAIL_STALENESS_FRACTION);
            boolean stale = state == null || Math.abs(currentEt - state.sampledEt()) > stalenessThreshold;

            if (stale) {
                try {
                    double duration = TrailSampler.computeTrailDurationSec(naifId, currentEt);
                    int maxSamples = this.state.renderQualityProperty().get().trailSamplesPerPeriod();
                    List<double[]> samples = TrailSampler.sample(naifId, currentEt, duration, "J2000", maxSamples);
                    int barycenterId = -1;
                    double[] baryAnchorKm = null;
                    if (isSatellite(naifId)) {
                        barycenterId = naifId / 100;
                        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
                        VectorIJK anchor = eph.getHeliocentricPositionJ2000(barycenterId, currentEt);
                        if (anchor != null) {
                            baryAnchorKm = new double[] {anchor.getI(), anchor.getJ(), anchor.getK()};
                        }
                    }
                    state = new TrailState(currentEt, duration, samples, barycenterId, baryAnchorKm);
                    trailStates.put(naifId, state);
                    liveFixedMap.put(naifId, new ArrayList<>()); // clear live segment on resample
                } catch (Exception e) {
                    logger.warn("Trail resample failed for NAIF {}: {}", naifId, e.getMessage());
                }
            }

            if (state == null || state.samples().isEmpty()) continue;

            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();

            // ── Live segment ──────────────────────────────────────────────────────────────────
            // Fixed intermediate points: appended once each time currentEt crosses a stepSec
            // boundary. Stored permanently so they never move between frames (no shimmer).
            List<double[]> liveFixed = liveFixedMap.computeIfAbsent(naifId, id -> new ArrayList<>());
            double stepSec = Math.toRadians(KepplrConstants.TRAIL_MIN_ARC_DEG) / (2.0 * Math.PI / state.durationSec());
            double lastFixedEt =
                    liveFixed.isEmpty() ? state.sampledEt() : liveFixed.get(liveFixed.size() - 1)[3];
            while (currentEt - lastFixedEt >= stepSec) {
                double newFixedEt = lastFixedEt + stepSec;
                double[] p = TrailSampler.sampleOnePosition(
                        naifId, state.barycenterId(), state.baryAnchorKm(), newFixedEt, eph);
                if (p != null) liveFixed.add(p);
                lastFixedEt = newFixedEt;
            }

            // Moving endpoint: always at currentEt, recomputed every frame.
            // Only the final 0–stepSec segment between the last fixed point and the moving
            // endpoint grows and resets; everything behind it is stable.
            double[] movingPos =
                    TrailSampler.sampleOnePosition(naifId, state.barycenterId(), state.baryAnchorKm(), currentEt, eph);

            // ── Render ────────────────────────────────────────────────────────────────────────
            TrailRenderer renderer = renderers.computeIfAbsent(
                    naifId, id -> new TrailRenderer(id, assetManager, nearNode, midNode, farNode));
            try {
                double[] offset = null;
                if (state.barycenterId() >= 0 && state.baryAnchorKm() != null) {
                    VectorIJK liveAnchor = eph.getHeliocentricPositionJ2000(state.barycenterId(), currentEt);
                    if (liveAnchor != null) {
                        double[] ba = state.baryAnchorKm();
                        offset = new double[] {
                            liveAnchor.getI() - ba[0], liveAnchor.getJ() - ba[1], liveAnchor.getK() - ba[2]
                        };
                    }
                }

                List<double[]> combined = new ArrayList<>(state.samples().size() + liveFixed.size() + 1);
                combined.addAll(state.samples());
                combined.addAll(liveFixed);
                if (movingPos != null) combined.add(movingPos);

                renderer.update(combined, cameraHelioJ2000, offset);
            } catch (Exception e) {
                logger.warn("Trail render failed for NAIF {}: {}", naifId, e.getMessage());
            }
        }
    }

    /** Returns true if {@code naifId} identifies a natural satellite or Pluto (orbiting its barycenter). */
    private static boolean isSatellite(int naifId) {
        return (naifId >= 100 && naifId <= 999 && naifId % 100 != 99) || naifId == KepplrConstants.PLUTO_NAIF_ID;
    }

    /** Returns an unmodifiable view of the currently enabled NAIF IDs. Package-private for tests. */
    Set<Integer> getEnabledIds() {
        return Collections.unmodifiableSet(enabledIds);
    }

    /** Immutable snapshot of a trail's sampled state. */
    private record TrailState(
            double sampledEt, double durationSec, List<double[]> samples, int barycenterId, double[] baryAnchorKm) {}
}
