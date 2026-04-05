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
import kepplr.camera.CameraFrame;
import kepplr.camera.SynodicFrame;
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
 * <h3>Camera-frame-relative trails (§7.5)</h3>
 *
 * <p>When the camera frame is {@link CameraFrame#SYNODIC}, trails are drawn in the rotating synodic frame defined by
 * {@code focusedBodyId} → {@code selectedBodyId}. At resample time, each sample's position relative to its reference
 * body is projected onto the synodic basis at the sample epoch and stored as a parallel {@code synodicSamples} list.
 * At render time the stored synodic coordinates are re-expressed in J2000 via the <em>current</em> synodic basis, then
 * passed to {@link TrailRenderer} as ordinary J2000 positions (the renderer is frame-unaware).
 *
 * <p>Switching to SYNODIC mode (or changing the focus/selected bodies while in SYNODIC mode) triggers an immediate
 * resample so the synodic coordinate cache is populated. Switching back to INERTIAL mode uses the existing J2000
 * samples without any re-sampling.
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
     * Fixed intermediate live-segment J2000 points per NAIF ID.
     *
     * <p>Each entry grows by one point every time {@code currentEt} crosses a {@link KepplrConstants#TRAIL_MAX_ARC_DEG}
     * boundary past {@code sampledEt}. Cleared on full resample. Points are ordered oldest-first (ascending ET).
     * Each element is {@code double[4]}: [x, y, z] anchored J2000 position in km and [3] = ET.
     */
    private final Map<Integer, List<double[]>> liveFixedMap = new HashMap<>();

    /**
     * Synodic-frame coordinates of live fixed points per NAIF ID, parallel to {@link #liveFixedMap}.
     *
     * <p>Each element is {@code double[4]}: [0,1,2] = synodic (sx, sy, sz) coordinates and [3] = ET.
     * Populated only when the camera frame is {@link CameraFrame#SYNODIC} and the body has a reference body.
     * Cleared on full resample alongside {@link #liveFixedMap}.
     */
    private final Map<Integer, List<double[]>> liveFixedSynodicMap = new HashMap<>();

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
        liveFixedSynodicMap.remove(naifId);
    }

    /**
     * Update all active trails for the current simulation time.
     *
     * @param currentEt current simulation ET (TDB seconds past J2000)
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km (length ≥ 3)
     */
    public void update(double currentEt, double[] cameraHelioJ2000) {
        CameraFrame currentFrame = this.state.cameraFrameProperty().get();
        int focusId = this.state.focusedBodyIdProperty().get();
        int selectedId = this.state.selectedBodyIdProperty().get();
        boolean synodic = currentFrame == CameraFrame.SYNODIC && focusId != -1 && selectedId != -1;

        for (int naifId : enabledIds) {
            TrailState state = trailStates.get(naifId);
            double stalenessThreshold = state == null
                    ? 0.0
                    : Math.min(
                            KepplrConstants.TRAIL_STALENESS_THRESHOLD_SEC,
                            state.durationSec() * KepplrConstants.TRAIL_STALENESS_FRACTION);

            // Resolve effective reference body: configured override, then NAIF heuristic.
            int configuredRef = this.state.trailReferenceBodyProperty(naifId).get();
            int effectiveRef = (configuredRef != -1) ? configuredRef : (isSatellite(naifId) ? naifId / 100 : -1);

            // In SYNODIC mode, a change of focus or selected body means the synodic coordinate
            // cache is stale and must be recomputed (full resample required).
            boolean synodicParamChanged = synodic && state != null
                    && (state.synodicFocusId() != focusId || state.synodicSelectedId() != selectedId);

            boolean stale = state == null
                    || Math.abs(currentEt - state.sampledEt()) > stalenessThreshold
                    || state.barycenterId() != effectiveRef // reference body changed
                    || synodicParamChanged;

            if (stale) {
                try {
                    double customDuration = this.state.trailDurationProperty(naifId).get();
                    double duration = (customDuration >= 0)
                            ? customDuration
                            : TrailSampler.computeTrailDurationSec(naifId, currentEt);
                    int maxSamples = this.state.renderQualityProperty().get().trailSamplesPerPeriod();
                    int barycenterId = effectiveRef;
                    double[] baryAnchorKm = null;
                    if (barycenterId != -1) {
                        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
                        VectorIJK anchor = eph.getHeliocentricPositionJ2000(barycenterId, currentEt);
                        if (anchor != null) {
                            baryAnchorKm = new double[] {anchor.getI(), anchor.getJ(), anchor.getK()};
                        }
                    }
                    // Use sampleWithExplicitRef when a reference body is known so that non-satellite
                    // bodies (e.g. spacecraft) also get correctly anchored samples.  For natural
                    // satellites the result is identical to sample() since the same anchor is used.
                    List<double[]> samples = (barycenterId != -1 && baryAnchorKm != null)
                            ? TrailSampler.sampleWithExplicitRef(
                                    naifId, barycenterId, baryAnchorKm, currentEt, duration, "J2000", maxSamples)
                            : TrailSampler.sample(naifId, currentEt, duration, "J2000", maxSamples);

                    // In SYNODIC mode, project each sample onto the synodic basis at the sample
                    // epoch.  The resulting (sx, sy, sz) coordinates are re-expressed in J2000
                    // at render time using the current synodic basis, so the trail is drawn in
                    // the rotating frame.  Only possible when a reference body is known.
                    List<double[]> synodicSamples = null;
                    int synodicFocusId = -1, synodicSelectedId = -1;
                    if (synodic && baryAnchorKm != null) {
                        synodicSamples = computeSynodicSamples(samples, baryAnchorKm, focusId, selectedId);
                        synodicFocusId = focusId;
                        synodicSelectedId = selectedId;
                    }

                    state = new TrailState(currentEt, duration, samples, barycenterId, baryAnchorKm,
                            synodicFocusId, synodicSelectedId, synodicSamples);
                    trailStates.put(naifId, state);
                    liveFixedMap.put(naifId, new ArrayList<>());      // clear live segment on resample
                    liveFixedSynodicMap.put(naifId, new ArrayList<>());
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
            List<double[]> liveFixedSynodic = liveFixedSynodicMap.computeIfAbsent(naifId, id -> new ArrayList<>());
            double stepSec =
                    Math.toRadians(KepplrConstants.TRAIL_MIN_ARC_DEG) / (2.0 * Math.PI / state.durationSec());
            double lastFixedEt =
                    liveFixed.isEmpty() ? state.sampledEt() : liveFixed.get(liveFixed.size() - 1)[3];
            while (currentEt - lastFixedEt >= stepSec) {
                double newFixedEt = lastFixedEt + stepSec;
                double[] p = TrailSampler.sampleOnePosition(
                        naifId, state.barycenterId(), state.baryAnchorKm(), newFixedEt, eph);
                if (p != null) {
                    liveFixed.add(p);
                    if (synodic && state.baryAnchorKm() != null) {
                        liveFixedSynodic.add(
                                projectSynodic(p, state.baryAnchorKm(), focusId, selectedId, newFixedEt));
                    }
                }
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
                if (synodic && state.synodicSamples() != null && state.baryAnchorKm() != null) {
                    // Synodic render path: re-express stored synodic coords in J2000 using the
                    // current synodic basis, then pass to the renderer as plain J2000 positions.
                    SynodicFrame.Basis bNow = SynodicFrame.compute(focusId, selectedId, currentEt);
                    VectorIJK refNow = eph.getHeliocentricPositionJ2000(state.barycenterId(), currentEt);
                    if (bNow != null && refNow != null) {
                        List<double[]> transformed = buildSynodicRenderList(
                                state.synodicSamples(), liveFixedSynodic, movingPos,
                                state.baryAnchorKm(), bNow, refNow);
                        renderer.update(transformed, cameraHelioJ2000, null);
                        continue; // skip the J2000 render path below
                    }
                    // Fall through to J2000 if synodic frame is degenerate at currentEt.
                }

                // J2000 render path (inertial frame, or synodic fallback).
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

    /**
     * Project each sample's reference-body-relative position onto the synodic basis at the sample epoch.
     *
     * <p>For each sample at ET_i:
     * <ol>
     *   <li>{@code dP = sample[0..2] − baryAnchorKm} (body position relative to reference body at ET_i)
     *   <li>{@code B_i = SynodicFrame.compute(focusId, selectedId, ET_i)}
     *   <li>Synodic coords: {@code (dP·x_i, dP·y_i, dP·z_i)}, stored with the sample ET at index 3
     * </ol>
     *
     * <p>If {@code B_i} is null (degenerate axis at that epoch), the raw J2000 relative components are stored
     * as a fallback.
     *
     * @return parallel list of {@code double[4]} — [sx, sy, sz, et] — one per input sample
     */
    private static List<double[]> computeSynodicSamples(
            List<double[]> samples, double[] baryAnchorKm, int focusId, int selectedId) {
        List<double[]> result = new ArrayList<>(samples.size());
        for (double[] sample : samples) {
            result.add(projectSynodic(sample, baryAnchorKm, focusId, selectedId, sample[3]));
        }
        return result;
    }

    /**
     * Project a single anchored J2000 position onto the synodic basis at the given epoch.
     *
     * @param sample anchored J2000 position (indices 0–2 used; index 3 = ET)
     * @param baryAnchorKm reference body's J2000 position at the resample epoch (the anchor)
     * @return {@code double[4]}: synodic coords [sx, sy, sz] and [3] = {@code et}
     */
    private static double[] projectSynodic(
            double[] sample, double[] baryAnchorKm, int focusId, int selectedId, double et) {
        double dPx = sample[0] - baryAnchorKm[0];
        double dPy = sample[1] - baryAnchorKm[1];
        double dPz = sample[2] - baryAnchorKm[2];

        SynodicFrame.Basis bi = SynodicFrame.compute(focusId, selectedId, et);
        if (bi != null) {
            VectorIJK x = bi.xAxis(), y = bi.yAxis(), z = bi.zAxis();
            return new double[] {
                dPx * x.getI() + dPy * x.getJ() + dPz * x.getK(),
                dPx * y.getI() + dPy * y.getJ() + dPz * y.getK(),
                dPx * z.getI() + dPy * z.getJ() + dPz * z.getK(),
                et
            };
        }
        // Degenerate: store raw relative coords as a fallback (inertial approximation)
        return new double[] {dPx, dPy, dPz, et};
    }

    /**
     * Transform synodic-frame coordinates into J2000 for rendering.
     *
     * <p>For each synodic sample {@code (sx, sy, sz, et)}:
     * <pre>
     *   p_j2000 = refNow + sx·x_now + sy·y_now + sz·z_now
     * </pre>
     *
     * <p>For the moving endpoint ({@code movingPos}) at {@code currentEt}: since the synodic basis at
     * {@code currentEt} is {@code B_now}, the transformation is the identity — the J2000 scene position
     * of the moving endpoint equals {@code refNow + (body − ref) at currentEt}, which is obtained by
     * applying the live-anchor offset to {@code movingPos}.
     *
     * @param synodicSamples cached synodic coords (each {@code double[4]} = [sx, sy, sz, et])
     * @param liveFixedSynodic synodic coords of live fixed points (same format)
     * @param movingPos anchored J2000 position at currentEt (from {@link TrailSampler#sampleOnePosition}),
     *     or {@code null}
     * @param baryAnchorKm reference body J2000 position at resample epoch
     * @param bNow current synodic frame basis in J2000
     * @param refNow reference body's J2000 position at currentEt
     * @return combined list of J2000 positions ready for {@link TrailRenderer#update}
     */
    private static List<double[]> buildSynodicRenderList(
            List<double[]> synodicSamples,
            List<double[]> liveFixedSynodic,
            double[] movingPos,
            double[] baryAnchorKm,
            SynodicFrame.Basis bNow,
            VectorIJK refNow) {
        List<double[]> result =
                new ArrayList<>(synodicSamples.size() + liveFixedSynodic.size() + 1);

        VectorIJK xn = bNow.xAxis(), yn = bNow.yAxis(), zn = bNow.zAxis();
        double rx = refNow.getI(), ry = refNow.getJ(), rz = refNow.getK();

        for (double[] s : synodicSamples) {
            result.add(synodicToJ2000(s[0], s[1], s[2], s[3], xn, yn, zn, rx, ry, rz));
        }
        for (double[] s : liveFixedSynodic) {
            result.add(synodicToJ2000(s[0], s[1], s[2], s[3], xn, yn, zn, rx, ry, rz));
        }

        // Moving endpoint: at currentEt so B_i == B_now — transform is the identity.
        // refNow + (body − ref) at currentEt = movingPos[0..2] + (refNow − baryAnchorKm).
        if (movingPos != null) {
            result.add(new double[] {
                movingPos[0] + rx - baryAnchorKm[0],
                movingPos[1] + ry - baryAnchorKm[1],
                movingPos[2] + rz - baryAnchorKm[2],
                movingPos[3]
            });
        }
        return result;
    }

    /** Convert a single synodic coordinate triple to a J2000 scene position {@code double[4]}. */
    private static double[] synodicToJ2000(
            double sx, double sy, double sz, double et,
            VectorIJK xn, VectorIJK yn, VectorIJK zn,
            double rx, double ry, double rz) {
        return new double[] {
            rx + sx * xn.getI() + sy * yn.getI() + sz * zn.getI(),
            ry + sx * xn.getJ() + sy * yn.getJ() + sz * zn.getJ(),
            rz + sx * xn.getK() + sy * yn.getK() + sz * zn.getK(),
            et
        };
    }

    /** Returns true if {@code naifId} identifies a natural satellite or Pluto (orbiting its barycenter). */
    private static boolean isSatellite(int naifId) {
        return (naifId >= 100 && naifId <= 999 && naifId % 100 != 99) || naifId == KepplrConstants.PLUTO_NAIF_ID;
    }

    /** Disable all trails and release all scene-graph resources. Call before discarding this manager. */
    public void dispose() {
        for (TrailRenderer renderer : renderers.values()) {
            renderer.detach();
        }
        renderers.clear();
        enabledIds.clear();
        trailStates.clear();
        liveFixedMap.clear();
        liveFixedSynodicMap.clear();
    }

    /** Returns an unmodifiable view of the currently enabled NAIF IDs. Package-private for tests. */
    Set<Integer> getEnabledIds() {
        return Collections.unmodifiableSet(enabledIds);
    }

    /**
     * Immutable snapshot of a trail's sampled state.
     *
     * <p>{@code synodicSamples} is non-null only when the trail was sampled in {@link CameraFrame#SYNODIC} mode and the
     * body has a known reference body ({@code baryAnchorKm != null}). Each element is {@code double[4]}:
     * [sx, sy, sz] synodic coordinates and [3] = sample ET.
     */
    private record TrailState(
            double sampledEt,
            double durationSec,
            List<double[]> samples,
            int barycenterId,
            double[] baryAnchorKm,
            int synodicFocusId,
            int synodicSelectedId,
            List<double[]> synodicSamples) {}
}
