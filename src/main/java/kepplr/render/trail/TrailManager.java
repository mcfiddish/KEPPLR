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
import picante.math.vectorspace.RotationMatrixIJK;
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
 * <p>When the active camera frame is {@link CameraFrame#SYNODIC} or {@link CameraFrame#BODY_FIXED}, trails are drawn in
 * the corresponding rotating frame.
 *
 * <ul>
 *   <li><b>SYNODIC</b>: at resample time, each sample's reference-body-relative position is projected onto the synodic
 *       basis at the sample epoch. At render time the stored synodic coordinates are re-expressed in J2000 via the
 *       current synodic basis.
 *   <li><b>BODY_FIXED</b>: the reference body is always the focused body, regardless of any per-body
 *       {@code setTrailReferenceBody} setting. At resample time, each sample's focus-body-relative position is
 *       expressed in the focused body's rotating frame via {@code getJ2000ToBodyFixedRotation}. At render time the
 *       stored body-fixed coordinates are rotated back to J2000 via the transpose of the current rotation matrix.
 * </ul>
 *
 * <p>In both cases {@link TrailRenderer} receives plain J2000 positions; it is frame-unaware.
 *
 * <p>The active (post-fallback) frame is read from {@code activeCameraFrameProperty()} so trail rendering always
 * matches what the camera is actually doing. Switching frames (or changing focus/selected bodies while in a rotating
 * frame) triggers an immediate resample.
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
     * Simulation state; read each frame on the JME render thread.
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
     * <p>Cleared on full resample. Each element is {@code double[4]}: [x, y, z] anchored J2000 km, [3] = ET.
     */
    private final Map<Integer, List<double[]>> liveFixedMap = new HashMap<>();

    /**
     * Synodic-frame coordinates of live fixed points, parallel to {@link #liveFixedMap}. Each element is
     * {@code double[4]}: [sx, sy, sz] synodic coords, [3] = ET.
     */
    private final Map<Integer, List<double[]>> liveFixedSynodicMap = new HashMap<>();

    /**
     * Body-fixed-frame coordinates of live fixed points, parallel to {@link #liveFixedMap}. Each element is
     * {@code double[4]}: [bx, by, bz] body-fixed coords in km, [3] = ET.
     */
    private final Map<Integer, List<double[]>> liveFixedBodyFixedMap = new HashMap<>();

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
        liveFixedBodyFixedMap.remove(naifId);
    }

    /**
     * Update all active trails for the current simulation time.
     *
     * @param currentEt current simulation ET (TDB seconds past J2000)
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km (length ≥ 3)
     */
    public void update(double currentEt, double[] cameraHelioJ2000) {
        // Use the active (post-fallback) frame so trail rendering always matches the camera.
        // If BODY_FIXED or SYNODIC fell back to INERTIAL, activeCameraFrameProperty reflects that.
        CameraFrame currentFrame = this.state.activeCameraFrameProperty().get();

        // Mirror the synodic focus/selected resolution in KepplrApp (Step 19c): explicit
        // override IDs take precedence over the interaction-state focused/selected bodies.
        int synodicFocusOverride = this.state.synodicFrameFocusIdProperty().get();
        int synodicSelectedOverride =
                this.state.synodicFrameSelectedIdProperty().get();
        int focusId = (synodicFocusOverride != -1)
                ? synodicFocusOverride
                : this.state.focusedBodyIdProperty().get();
        int selectedId = (synodicSelectedOverride != -1)
                ? synodicSelectedOverride
                : this.state.selectedBodyIdProperty().get();

        boolean synodic = currentFrame == CameraFrame.SYNODIC && focusId != -1 && selectedId != -1;
        boolean bodyFixed = currentFrame == CameraFrame.BODY_FIXED && focusId != -1;

        for (int naifId : enabledIds) {
            TrailState state = trailStates.get(naifId);
            double stalenessThreshold = state == null
                    ? 0.0
                    : Math.min(
                            KepplrConstants.TRAIL_STALENESS_THRESHOLD_SEC,
                            state.durationSec() * KepplrConstants.TRAIL_STALENESS_FRACTION);

            // Resolve configured reference body (may be overridden below for BODY_FIXED).
            int configuredRef = this.state.trailReferenceBodyProperty(naifId).get();
            int effectiveRef = configuredRef != -1
                    ? configuredRef
                    : (TrailSampler.usesPrimaryRelativeTrail(naifId) ? TrailSampler.getPrimaryID(naifId) : -1);

            // In BODY_FIXED mode the trail is always centred on the focus body; any per-body
            // setTrailReferenceBody setting is ignored because the frame defines the origin.
            int barycenterId = (bodyFixed) ? focusId : effectiveRef;

            // Switching to a rotating frame, or changing the key bodies while in one, requires a
            // full resample to recompute the rotating-frame coordinate cache.
            boolean synodicParamChanged = synodic
                    && state != null
                    && (state.synodicFocusId() != focusId || state.synodicSelectedId() != selectedId);
            boolean bodyFixedParamChanged = bodyFixed && state != null && state.bodyFixedFocusId() != focusId;

            boolean stale = state == null
                    || Math.abs(currentEt - state.sampledEt()) > stalenessThreshold
                    || state.barycenterId() != barycenterId
                    || synodicParamChanged
                    || bodyFixedParamChanged;

            if (stale) {
                try {
                    double customDuration =
                            this.state.trailDurationProperty(naifId).get();
                    double duration = (customDuration >= 0)
                            ? customDuration
                            : TrailSampler.computeTrailDurationSec(naifId, currentEt);
                    int maxSamples = this.state.renderQualityProperty().get().trailSamplesPerPeriod();

                    KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
                    double[] baryAnchorKm = null;
                    if (barycenterId != -1) {
                        VectorIJK anchor = eph.getHeliocentricPositionJ2000(barycenterId, currentEt);
                        if (anchor != null) {
                            baryAnchorKm = new double[] {anchor.getI(), anchor.getJ(), anchor.getK()};
                        }
                    }

                    // Use sampleWithExplicitRef when a reference body is known so that non-satellite
                    // bodies (e.g. spacecraft) also get correctly anchored samples.
                    List<double[]> samples = (barycenterId != -1 && baryAnchorKm != null)
                            ? TrailSampler.sampleWithExplicitRef(
                                    naifId, barycenterId, baryAnchorKm, currentEt, duration, "J2000", maxSamples)
                            : TrailSampler.sample(naifId, currentEt, duration, "J2000", maxSamples);

                    // Synodic: project each sample's reference-relative position onto the synodic
                    // basis at the sample epoch.
                    List<double[]> synodicSamples = null;
                    int synodicFocusId = -1, synodicSelectedId = -1;
                    if (synodic && baryAnchorKm != null) {
                        synodicSamples = computeSynodicSamples(samples, baryAnchorKm, focusId, selectedId);
                        synodicFocusId = focusId;
                        synodicSelectedId = selectedId;
                    }

                    // Body-fixed: express each sample's focus-relative position in the focus body's
                    // rotating frame via R(ET_i) · dP_i.
                    List<double[]> bodyFixedSamples = null;
                    int bodyFixedFocusId = -1;
                    if (bodyFixed && baryAnchorKm != null) {
                        bodyFixedSamples = computeBodyFixedSamples(samples, baryAnchorKm, focusId, eph);
                        bodyFixedFocusId = focusId;
                    }

                    state = new TrailState(
                            currentEt,
                            duration,
                            samples,
                            barycenterId,
                            baryAnchorKm,
                            synodicFocusId,
                            synodicSelectedId,
                            synodicSamples,
                            bodyFixedFocusId,
                            bodyFixedSamples);
                    trailStates.put(naifId, state);
                    liveFixedMap.put(naifId, new ArrayList<>());
                    liveFixedSynodicMap.put(naifId, new ArrayList<>());
                    liveFixedBodyFixedMap.put(naifId, new ArrayList<>());
                } catch (Exception e) {
                    logger.warn("Trail resample failed for NAIF {}: {}", naifId, e.getMessage());
                }
            }

            if (state == null || state.samples().isEmpty()) continue;

            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();

            // ── Live segment ──────────────────────────────────────────────────────────────────
            List<double[]> liveFixed = liveFixedMap.computeIfAbsent(naifId, id -> new ArrayList<>());
            List<double[]> liveFixedSynodic = liveFixedSynodicMap.computeIfAbsent(naifId, id -> new ArrayList<>());
            List<double[]> liveFixedBodyFixed = liveFixedBodyFixedMap.computeIfAbsent(naifId, id -> new ArrayList<>());
            double stepSec = Math.toRadians(KepplrConstants.TRAIL_MIN_ARC_DEG) / (2.0 * Math.PI / state.durationSec());
            double lastFixedEt =
                    liveFixed.isEmpty() ? state.sampledEt() : liveFixed.get(liveFixed.size() - 1)[3];
            while (currentEt - lastFixedEt >= stepSec) {
                double newFixedEt = lastFixedEt + stepSec;
                double[] p = TrailSampler.sampleOnePosition(
                        naifId, state.barycenterId(), state.baryAnchorKm(), newFixedEt, eph);
                if (p != null) {
                    liveFixed.add(p);
                    if (synodic && state.baryAnchorKm() != null) {
                        liveFixedSynodic.add(projectSynodic(p, state.baryAnchorKm(), focusId, selectedId, newFixedEt));
                    }
                    if (bodyFixed && state.baryAnchorKm() != null) {
                        liveFixedBodyFixed.add(projectBodyFixed(p, state.baryAnchorKm(), focusId, newFixedEt, eph));
                    }
                }
                lastFixedEt = newFixedEt;
            }

            double[] movingPos =
                    TrailSampler.sampleOnePosition(naifId, state.barycenterId(), state.baryAnchorKm(), currentEt, eph);

            // ── Render ────────────────────────────────────────────────────────────────────────
            TrailRenderer renderer = renderers.computeIfAbsent(
                    naifId, id -> new TrailRenderer(id, assetManager, nearNode, midNode, farNode));
            try {
                if (bodyFixed && state.bodyFixedSamples() != null && state.baryAnchorKm() != null) {
                    // Body-fixed render path: rotate stored body-fixed coords back to J2000
                    // via R_now^T, then pass to the renderer as plain J2000 positions.
                    RotationMatrixIJK rNow = eph.getJ2000ToBodyFixedRotation(focusId, currentEt);
                    VectorIJK focusNow = eph.getHeliocentricPositionJ2000(focusId, currentEt);
                    if (rNow != null && focusNow != null) {
                        List<double[]> transformed = buildBodyFixedRenderList(
                                state.bodyFixedSamples(),
                                liveFixedBodyFixed,
                                movingPos,
                                state.baryAnchorKm(),
                                rNow,
                                focusNow);
                        renderer.update(transformed, cameraHelioJ2000, null);
                    } else {
                        renderJ2000(renderer, state, liveFixed, movingPos, cameraHelioJ2000, eph);
                    }
                } else if (synodic && state.synodicSamples() != null && state.baryAnchorKm() != null) {
                    // Synodic render path: re-express stored synodic coords in J2000 via B_now.
                    SynodicFrame.Basis bNow = SynodicFrame.compute(focusId, selectedId, currentEt);
                    VectorIJK refNow = eph.getHeliocentricPositionJ2000(state.barycenterId(), currentEt);
                    if (bNow != null && refNow != null) {
                        List<double[]> transformed = buildSynodicRenderList(
                                state.synodicSamples(),
                                liveFixedSynodic,
                                movingPos,
                                state.baryAnchorKm(),
                                bNow,
                                refNow);
                        renderer.update(transformed, cameraHelioJ2000, null);
                    } else {
                        renderJ2000(renderer, state, liveFixed, movingPos, cameraHelioJ2000, eph);
                    }
                } else {
                    renderJ2000(renderer, state, liveFixed, movingPos, cameraHelioJ2000, eph);
                }
            } catch (Exception e) {
                logger.warn("Trail render failed for NAIF {}: {}", naifId, e.getMessage());
            }
        }
    }

    // ── Synodic frame helpers ─────────────────────────────────────────────────────────────────

    /**
     * Project each sample's reference-body-relative position onto the synodic basis at the sample epoch.
     *
     * <p>For each sample at ET_i: {@code dP = sample[0..2] − baryAnchorKm}, then project onto {@code B_i =
     * SynodicFrame.compute(focusId, selectedId, ET_i)}. If {@code B_i} is null (degenerate), the raw J2000 relative
     * components are stored as a fallback.
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
     * @return {@code double[4]}: [sx, sy, sz, et]
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
        return new double[] {dPx, dPy, dPz, et}; // degenerate fallback
    }

    /**
     * Transform synodic-frame coordinates into J2000 for rendering.
     *
     * <p>For each synodic sample {@code (sx, sy, sz, et)}: {@code p = refNow + sx·x_now + sy·y_now + sz·z_now}. The
     * moving endpoint (at {@code currentEt}) is included as {@code movingPos + (refNow − baryAnchorKm)}.
     */
    private static List<double[]> buildSynodicRenderList(
            List<double[]> synodicSamples,
            List<double[]> liveFixedSynodic,
            double[] movingPos,
            double[] baryAnchorKm,
            SynodicFrame.Basis bNow,
            VectorIJK refNow) {
        List<double[]> result = new ArrayList<>(synodicSamples.size() + liveFixedSynodic.size() + 1);
        VectorIJK xn = bNow.xAxis(), yn = bNow.yAxis(), zn = bNow.zAxis();
        double rx = refNow.getI(), ry = refNow.getJ(), rz = refNow.getK();
        for (double[] s : synodicSamples) {
            result.add(synodicToJ2000(s[0], s[1], s[2], s[3], xn, yn, zn, rx, ry, rz));
        }
        for (double[] s : liveFixedSynodic) {
            result.add(synodicToJ2000(s[0], s[1], s[2], s[3], xn, yn, zn, rx, ry, rz));
        }
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

    private static double[] synodicToJ2000(
            double sx,
            double sy,
            double sz,
            double et,
            VectorIJK xn,
            VectorIJK yn,
            VectorIJK zn,
            double rx,
            double ry,
            double rz) {
        return new double[] {
            rx + sx * xn.getI() + sy * yn.getI() + sz * zn.getI(),
            ry + sx * xn.getJ() + sy * yn.getJ() + sz * zn.getJ(),
            rz + sx * xn.getK() + sy * yn.getK() + sz * zn.getK(),
            et
        };
    }

    // ── Body-fixed frame helpers ──────────────────────────────────────────────────────────────

    /**
     * Express each sample's focus-relative J2000 position in the focus body's rotating frame.
     *
     * <p>For each sample at ET_i: {@code dP = sample[0..2] − baryAnchorKm}, then {@code bf = R_i · dP} where {@code R_i
     * = getJ2000ToBodyFixedRotation(focusId, ET_i)}. If the rotation is unavailable, the raw J2000 relative components
     * are stored as a fallback.
     *
     * @return parallel list of {@code double[4]} — [bx, by, bz, et] — one per input sample
     */
    private static List<double[]> computeBodyFixedSamples(
            List<double[]> samples, double[] baryAnchorKm, int focusId, KEPPLREphemeris eph) {
        List<double[]> result = new ArrayList<>(samples.size());
        for (double[] sample : samples) {
            result.add(projectBodyFixed(sample, baryAnchorKm, focusId, sample[3], eph));
        }
        return result;
    }

    /**
     * Express a single focus-relative J2000 position in the focus body's rotating frame at the given epoch.
     *
     * @return {@code double[4]}: [bx, by, bz, et]
     */
    private static double[] projectBodyFixed(
            double[] sample, double[] baryAnchorKm, int focusId, double et, KEPPLREphemeris eph) {
        double dPx = sample[0] - baryAnchorKm[0];
        double dPy = sample[1] - baryAnchorKm[1];
        double dPz = sample[2] - baryAnchorKm[2];
        try {
            RotationMatrixIJK r = eph.getJ2000ToBodyFixedRotation(focusId, et);
            if (r != null) {
                return new double[] {
                    r.get(0, 0) * dPx + r.get(0, 1) * dPy + r.get(0, 2) * dPz,
                    r.get(1, 0) * dPx + r.get(1, 1) * dPy + r.get(1, 2) * dPz,
                    r.get(2, 0) * dPx + r.get(2, 1) * dPy + r.get(2, 2) * dPz,
                    et
                };
            }
        } catch (Exception e) {
            // fall through
        }
        return new double[] {dPx, dPy, dPz, et}; // fallback: inertial relative coords
    }

    /**
     * Transform body-fixed coordinates back to J2000 for rendering.
     *
     * <p>For each body-fixed sample {@code (bx, by, bz, et)}: {@code p = focusNow + R_now^T · (bx, by, bz)}. The
     * transpose {@code R_now^T} maps body-fixed → J2000. The moving endpoint is included as {@code movingPos +
     * (focusNow − baryAnchorKm)}.
     */
    private static List<double[]> buildBodyFixedRenderList(
            List<double[]> bodyFixedSamples,
            List<double[]> liveFixedBodyFixed,
            double[] movingPos,
            double[] baryAnchorKm,
            RotationMatrixIJK rNow,
            VectorIJK focusNow) {
        List<double[]> result = new ArrayList<>(bodyFixedSamples.size() + liveFixedBodyFixed.size() + 1);
        double fx = focusNow.getI(), fy = focusNow.getJ(), fz = focusNow.getK();
        for (double[] s : bodyFixedSamples) {
            result.add(bodyFixedToJ2000(s[0], s[1], s[2], s[3], rNow, fx, fy, fz));
        }
        for (double[] s : liveFixedBodyFixed) {
            result.add(bodyFixedToJ2000(s[0], s[1], s[2], s[3], rNow, fx, fy, fz));
        }
        if (movingPos != null) {
            result.add(new double[] {
                movingPos[0] + fx - baryAnchorKm[0],
                movingPos[1] + fy - baryAnchorKm[1],
                movingPos[2] + fz - baryAnchorKm[2],
                movingPos[3]
            });
        }
        return result;
    }

    /**
     * Apply {@code R^T} (body-fixed → J2000) to a single body-fixed coordinate triple.
     *
     * <p>{@code R} is the J2000→body-fixed matrix, so {@code R^T} maps body-fixed→J2000. {@code (R^T)[i][j] = R[j][i] =
     * R.get(j, i)}.
     */
    private static double[] bodyFixedToJ2000(
            double bx, double by, double bz, double et, RotationMatrixIJK rNow, double fx, double fy, double fz) {
        return new double[] {
            fx + rNow.get(0, 0) * bx + rNow.get(1, 0) * by + rNow.get(2, 0) * bz,
            fy + rNow.get(0, 1) * bx + rNow.get(1, 1) * by + rNow.get(2, 1) * bz,
            fz + rNow.get(0, 2) * bx + rNow.get(1, 2) * by + rNow.get(2, 2) * bz,
            et
        };
    }

    // ── J2000 render path ─────────────────────────────────────────────────────────────────────

    /** Render the trail in inertial J2000 (heliocentric, with live-anchor offset). */
    private static void renderJ2000(
            TrailRenderer renderer,
            TrailState state,
            List<double[]> liveFixed,
            double[] movingPos,
            double[] cameraHelioJ2000,
            KEPPLREphemeris eph) {
        double[] offset = null;
        if (state.barycenterId() >= 0 && state.baryAnchorKm() != null) {
            VectorIJK liveNow = eph.getHeliocentricPositionJ2000(
                    state.barycenterId(), movingPos != null ? movingPos[3] : state.sampledEt());
            if (liveNow != null) {
                double[] ba = state.baryAnchorKm();
                offset = new double[] {liveNow.getI() - ba[0], liveNow.getJ() - ba[1], liveNow.getK() - ba[2]};
            }
        }
        List<double[]> combined = new ArrayList<>(state.samples().size() + liveFixed.size() + 1);
        combined.addAll(state.samples());
        combined.addAll(liveFixed);
        if (movingPos != null) combined.add(movingPos);
        renderer.update(combined, cameraHelioJ2000, offset);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────────────────────

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
        liveFixedBodyFixedMap.clear();
    }

    /** Returns an unmodifiable view of the currently enabled NAIF IDs. Package-private for tests. */
    Set<Integer> getEnabledIds() {
        return Collections.unmodifiableSet(enabledIds);
    }

    /**
     * Immutable snapshot of a trail's sampled state.
     *
     * <p>{@code synodicSamples} is non-null only when sampled in {@link CameraFrame#SYNODIC} mode with a known
     * reference body. {@code bodyFixedSamples} is non-null only when sampled in {@link CameraFrame#BODY_FIXED} mode.
     * Both are {@code double[4]} per element: rotating-frame coordinates [0,1,2] and ET [3].
     */
    private record TrailState(
            double sampledEt,
            double durationSec,
            List<double[]> samples,
            int barycenterId,
            double[] baryAnchorKm,
            int synodicFocusId,
            int synodicSelectedId,
            List<double[]> synodicSamples,
            int bodyFixedFocusId,
            List<double[]> bodyFixedSamples) {}
}
