package kepplr.render;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.BodyLookupService;
import kepplr.ephemeris.Instrument;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.render.frustum.FrustumLayer;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.RotationMatrixIJK;
import picante.math.vectorspace.UnwritableVectorIJK;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.EphemerisID;
import picante.mechanics.StateTransformFunction;
import picante.spice.fov.FOVFactory;
import picante.spice.fov.FOVSpice;
import picante.surfaces.Ellipsoid;

/**
 * Builds and updates translucent frustum pyramid overlays for every instrument returned by
 * {@link KEPPLREphemeris#getInstruments()} (Step 22).
 *
 * <h3>Geometry</h3>
 *
 * <p>Each frustum is a translucent pyramid whose apex sits at the camera-relative J2000 position of the instrument's
 * center body. Boundary rays that intersect another body's reference ellipsoid stop at that surface; rays that miss
 * continue to the default fixed extent. A live footprint polyline is drawn from the same body intersections, and when
 * persistence recording is enabled, closed live footprints are accumulated into a continuous body-fixed surface swath.
 * Rays with no body hit fall back to the default extent:
 *
 * <pre>
 * base[i] = apex + normalize(boundVector_in_J2000) × INSTRUMENT_FRUSTUM_DEFAULT_EXTENT_KM
 * </pre>
 *
 * <p>Bound vectors come from {@link FOVSpice#getBounds()} and are in the instrument frame. They are rotated into J2000
 * by applying {@code transpose(J2000→instrumentFrame)} via {@link RotationMatrixIJK#mtxv}.
 *
 * <h3>Update strategy</h3>
 *
 * <p>Vertex positions are recomputed in-place every frame (via {@link FloatBuffer} mutation) to avoid allocation churn.
 * Each call to {@link #update} detaches all visible geometries from their frustum layer nodes, recomputes positions,
 * then re-attaches to the correct layer for the current frame.
 *
 * <h3>Threading</h3>
 *
 * <p>All methods must be called on the JME render thread (CLAUDE.md Rule 4).
 *
 * <h3>Out of scope for this step</h3>
 *
 * <ul>
 *   <li>Boresight line rendering
 *   <li>Per-instrument color configuration (hardcoded cyan)
 *   <li>Mesh-model surface intersection (ellipsoids only in the current implementation)
 * </ul>
 */
public final class InstrumentFrustumManager {

    private static final Logger logger = LogManager.getLogger();

    /** Translucent cyan material color (hardcoded for this step). */
    private static final ColorRGBA FRUSTUM_COLOR = new ColorRGBA(0f, 1f, 1f, 0.25f);

    private static final ColorRGBA FRUSTUM_OUTLINE_COLOR = new ColorRGBA(1f, 1f, 1f, 1f);
    private static final ColorRGBA FOOTPRINT_COLOR = new ColorRGBA(0f, 1f, 1f, 0.9f);
    private static final double FOOTPRINT_SURFACE_OFFSET_KM = 0.01;
    private static final double PERSISTENT_COVERAGE_SURFACE_OFFSET_KM = 0.1;

    // ── Per-frustum-layer scene nodes ─────────────────────────────────────────
    private final Map<FrustumLayer, Node> layerNodes;

    /** Retained so {@link #reload()} can rebuild entries without a new constructor call. */
    private final AssetManager assetManager;

    // ── Per-instrument render entries (insertion-ordered for deterministic iteration) ──
    /** Keyed by instrument NAIF code. */
    private final Map<Integer, FrustumEntry> entriesByCode = new LinkedHashMap<>();
    /** Keyed by instrument name (from {@code instrument.id().getName()}) for name-based lookup. */
    private final Map<String, FrustumEntry> entriesByName = new LinkedHashMap<>();
    /** Retained body-surface swaths keyed by instrument/body pair. */
    private final Map<CoverageKey, PersistentCoverageOverlay> persistentCoverageOverlays = new LinkedHashMap<>();

    /**
     * Construct the manager and build one {@link Geometry} per instrument.
     *
     * <p>Instruments with a null or empty {@link FOVSpice#getBounds()} list are skipped with a WARN log and never
     * rendered.
     *
     * @param nearNode near frustum root node
     * @param midNode mid frustum root node
     * @param farNode far frustum root node
     * @param assetManager JME asset manager for material creation
     */
    public InstrumentFrustumManager(Node nearNode, Node midNode, Node farNode, AssetManager assetManager) {
        Map<FrustumLayer, Node> nodes = new EnumMap<>(FrustumLayer.class);
        nodes.put(FrustumLayer.NEAR, nearNode);
        nodes.put(FrustumLayer.MID, midNode);
        nodes.put(FrustumLayer.FAR, farNode);
        this.layerNodes = nodes;
        this.assetManager = assetManager;

        buildEntries();
    }

    /** Rebuild all instrument entries from the current {@link KEPPLREphemeris}. */
    private void buildEntries() {
        detachAll();
        entriesByCode.clear();
        entriesByName.clear();
        persistentCoverageOverlays.clear();

        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        for (Instrument instrument : eph.getInstruments()) {
            String name = instrument.id().getName();
            if (instrument.fov() == null) {
                logger.warn("Instrument {} has null FOV; skipped", name);
                continue;
            }
            FOVSpice fovSpice = instrument.fov().getFovSpice();
            if (fovSpice == null
                    || fovSpice.getBounds() == null
                    || fovSpice.getBounds().isEmpty()) {
                logger.warn("Instrument {} has no FOV bound vectors; skipped", name);
                continue;
            }
            FrustumEntry entry = new FrustumEntry(instrument, fovSpice, assetManager);
            entriesByCode.put(instrument.code(), entry);
            entriesByName.put(name, entry);
        }
        if (entriesByCode.isEmpty()) {
            logger.info("InstrumentFrustumManager: no instruments available (no IK loaded)");
        } else {
            logger.info(
                    "InstrumentFrustumManager: {} instrument(s) loaded: {}",
                    entriesByCode.size(),
                    entriesByCode.values().stream()
                            .map(e -> e.instrument.id().getName() + "(" + e.instrument.code() + ")")
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
    }

    /**
     * Rebuild instrument entries after a configuration reload.
     *
     * <p>Must be called on the JME render thread (enqueued via {@code Application.enqueue}). Detaches any currently
     * visible frustum geometry, clears all entries, then re-reads {@link KEPPLREphemeris#getInstruments()} from the new
     * kernel configuration.
     */
    public void reload() {
        logger.info("InstrumentFrustumManager.reload() called");
        buildEntries();
    }

    /**
     * Compute the far-plane distance required to keep all visible frustums intact when rendered in the NEAR layer.
     *
     * <p>This is used by {@link kepplr.render.KepplrApp} to expand the near camera range before rendering long
     * instrument frustums whose apex is nearby but whose default extent reaches far beyond the normal near-layer depth.
     */
    public double requiredNearFrustumFarKm(double currentEt, double[] cameraHelioJ2000) {
        if (entriesByCode.isEmpty()) {
            return FrustumLayer.NEAR.farKm;
        }

        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        double requiredFarKm = FrustumLayer.NEAR.farKm;

        for (FrustumEntry entry : entriesByCode.values()) {
            if (!entry.visible) continue;

            VectorIJK centerPosJ2000 = eph.getHeliocentricPositionJ2000(entry.instrument.center(), currentEt);
            if (centerPosJ2000 == null) {
                continue;
            }

            StateTransformFunction stf = eph.j2000ToFrame(entry.instrument.frameID());
            RotationMatrixIJK j2000ToInstrument;
            try {
                j2000ToInstrument = stf.getStateTransform(currentEt).getRotation();
            } catch (Exception e) {
                continue;
            }

            double apexX = centerPosJ2000.getI() - cameraHelioJ2000[0];
            double apexY = centerPosJ2000.getJ() - cameraHelioJ2000[1];
            double apexZ = centerPosJ2000.getK() - cameraHelioJ2000[2];
            double apexDistKm = Math.sqrt(apexX * apexX + apexY * apexY + apexZ * apexZ);
            requiredFarKm = Math.max(requiredFarKm, apexDistKm);

            VectorIJK scratch = new VectorIJK();
            for (UnwritableVectorIJK bound : entry.effectiveBounds) {
                j2000ToInstrument.mtxv(bound, scratch);
                double len = scratch.getLength();
                if (len < 1e-10) {
                    continue;
                }
                double ext = KepplrConstants.INSTRUMENT_FRUSTUM_DEFAULT_EXTENT_KM / len;
                double baseX = apexX + scratch.getI() * ext;
                double baseY = apexY + scratch.getJ() * ext;
                double baseZ = apexZ + scratch.getK() * ext;
                double baseDistKm = Math.sqrt(baseX * baseX + baseY * baseY + baseZ * baseZ);
                requiredFarKm = Math.max(requiredFarKm, baseDistKm);
            }
        }

        return requiredFarKm;
    }

    // ── Per-frame update ──────────────────────────────────────────────────────

    /**
     * Recompute all visible frustum geometries for the current simulation time and re-attach them to the appropriate
     * frustum layer.
     *
     * <p>Must be called once per frame from the JME render thread ({@code simpleUpdate}).
     *
     * @param currentEt current simulation ET (TDB seconds past J2000)
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km (length ≥ 3)
     */
    public void update(double currentEt, double[] cameraHelioJ2000) {
        detachAll();
        if (entriesByCode.isEmpty()) {
            // Logged once at construction; no further action needed each frame.
            return;
        }

        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();

        for (FrustumEntry entry : entriesByCode.values()) {
            if (!entry.visible) continue;

            // Apex: center body's J2000 heliocentric position, made camera-relative
            VectorIJK centerPosJ2000 = eph.getHeliocentricPositionJ2000(entry.instrument.center(), currentEt);
            if (centerPosJ2000 == null) {
                logger.warn(
                        "update(): no heliocentric position for center body of {} at ET={};" + " center={}",
                        entry.instrument.id().getName(),
                        currentEt,
                        entry.instrument.center());
                continue;
            }

            double apexX = centerPosJ2000.getI() - cameraHelioJ2000[0];
            double apexY = centerPosJ2000.getJ() - cameraHelioJ2000[1];
            double apexZ = centerPosJ2000.getK() - cameraHelioJ2000[2];

            // Instrument-frame → J2000 rotation: transpose of (J2000 → instrument frame)
            StateTransformFunction stf = eph.j2000ToFrame(entry.instrument.frameID());
            RotationMatrixIJK j2000ToInstrument;
            try {
                j2000ToInstrument = stf.getStateTransform(currentEt).getRotation();
            } catch (Exception e) {
                logger.warn(
                        "Cannot get frame transform for instrument {} at ET={}: {}",
                        entry.instrument.id().getName(),
                        currentEt,
                        e.getMessage());
                continue;
            }

            VectorIJK boresightJ2000 = instrumentToJ2000(entry.fovSpice.getBoresight(), j2000ToInstrument);
            BodyIntersection target =
                    findNearestIntersection(eph, entry.instrument.center(), centerPosJ2000, boresightJ2000, currentEt);

            // Update the frustum mesh and live footprint from the same target body solution.
            GeometryMetrics metrics = entry.updateGeometry(
                    apexX, apexY, apexZ, centerPosJ2000, j2000ToInstrument, target, cameraHelioJ2000);
            if (entry.persistenceEnabled && entry.hasLiveFootprint()) {
                recordPersistentFootprintIfNeeded(entry);
            }

            // One-shot diagnostics: log apex, boresight, and all body positions on first render
            // after the frustum is toggled on.
            if (entry.logPending) {
                entry.logPending = false;
                logDiagnostics(
                        entry,
                        centerPosJ2000,
                        apexX,
                        apexY,
                        apexZ,
                        j2000ToInstrument,
                        currentEt,
                        cameraHelioJ2000,
                        eph);
            }

            // Render the shell in the layer containing its actually-rendered geometry. Boundary
            // rays that hit the target stop at the surface; miss rays continue to the default
            // extent, preserving off-body regions without letting the shell emerge from the far
            // side of the body along hit directions.
            FrustumLayer frustumLayer = FrustumLayer.assign(Math.max(metrics.renderedMaxDistanceKm(), 0.0), 0.0);
            layerNodes.get(frustumLayer).attachChild(entry.geometry);
            layerNodes.get(frustumLayer).attachChild(entry.outlineGeometry);
            if (entry.hasNearSegment()) {
                layerNodes.get(FrustumLayer.NEAR).attachChild(entry.nearGeometry);
                layerNodes.get(FrustumLayer.NEAR).attachChild(entry.nearOutlineGeometry);
            }

            if (entry.hasLiveFootprint()) {
                FrustumLayer footprintLayer = FrustumLayer.assign(Math.max(metrics.footprintDistanceKm(), 0.0), 0.0);
                layerNodes.get(footprintLayer).attachChild(entry.footprintGeometry);
            }
        }

        for (PersistentCoverageOverlay overlay : persistentCoverageOverlays.values()) {
            overlay.updateGeometry(currentEt, cameraHelioJ2000);
            FrustumLayer overlayLayer = FrustumLayer.assign(Math.max(overlay.lastDistanceKm, 0.0), 0.0);
            layerNodes.get(overlayLayer).attachChild(overlay.geometry);
        }
    }

    // ── Visibility control ────────────────────────────────────────────────────

    /**
     * Show or hide the frustum for the instrument identified by NAIF code.
     *
     * @param naifCode instrument NAIF code
     * @param visible {@code true} to show, {@code false} to hide
     */
    public void setVisible(int naifCode, boolean visible) {
        FrustumEntry entry = entriesByCode.get(naifCode);
        if (entry == null) {
            logger.debug("setVisible({}, {}) — no entry for code {}", naifCode, visible, naifCode);
            return;
        }
        if (visible == entry.visible) return;
        logger.info(
                "setVisible({}, {}): {} visibility changed",
                naifCode,
                visible,
                entry.instrument.id().getName());
        if (visible) {
            entry.logPending = true; // log diagnostics on the first rendered frame
        }
        entry.visible = visible;
        if (!visible) {
            entry.geometry.removeFromParent();
            entry.outlineGeometry.removeFromParent();
            entry.nearGeometry.removeFromParent();
            entry.nearOutlineGeometry.removeFromParent();
            entry.footprintGeometry.removeFromParent();
        }
    }

    public void setPersistenceEnabled(int naifCode, boolean enabled) {
        FrustumEntry entry = entriesByCode.get(naifCode);
        if (entry == null) {
            logger.debug("setPersistenceEnabled({}, {}) — no entry for code {}", naifCode, enabled, naifCode);
            return;
        }
        entry.persistenceEnabled = enabled;
    }

    public void setPersistenceEnabled(String instrumentName, boolean enabled) {
        FrustumEntry entry = entriesByName.get(instrumentName);
        if (entry != null) {
            setPersistenceEnabled(entry.instrument.code(), enabled);
        }
    }

    public void clearPersistentFootprints() {
        for (PersistentCoverageOverlay overlay : persistentCoverageOverlays.values()) {
            overlay.geometry.removeFromParent();
        }
        persistentCoverageOverlays.clear();
    }

    public void clearPersistentFootprints(int instrumentNaifCode) {
        persistentCoverageOverlays.entrySet().removeIf(entry -> {
            if (entry.getKey().instrumentCode == instrumentNaifCode) {
                entry.getValue().geometry.removeFromParent();
                return true;
            }
            return false;
        });
    }

    /**
     * Show or hide the frustum for the instrument identified by name.
     *
     * @param instrumentName instrument name as returned by {@code instrument.id().getName()}
     * @param visible {@code true} to show, {@code false} to hide
     */
    public void setVisible(String instrumentName, boolean visible) {
        FrustumEntry entry = entriesByName.get(instrumentName);
        if (entry != null) {
            setVisible(entry.instrument.code(), visible);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void detachAll() {
        for (FrustumEntry entry : entriesByCode.values()) {
            entry.geometry.removeFromParent();
            entry.outlineGeometry.removeFromParent();
            entry.nearGeometry.removeFromParent();
            entry.nearOutlineGeometry.removeFromParent();
            entry.footprintGeometry.removeFromParent();
        }
        for (PersistentCoverageOverlay overlay : persistentCoverageOverlays.values()) {
            overlay.geometry.removeFromParent();
        }
    }

    private void recordPersistentFootprintIfNeeded(FrustumEntry entry) {
        if (entry.liveFootprintBodyId == null || entry.liveFootprintBodyFixed.isEmpty() || !entry.liveFootprintClosed) {
            return;
        }
        CoverageKey key = new CoverageKey(entry.instrument.code(), entry.liveFootprintBodyId);
        PersistentCoverageOverlay overlay = persistentCoverageOverlays.computeIfAbsent(
                key,
                k -> new PersistentCoverageOverlay(assetManager, entry.instrument.code(), entry.liveFootprintBodyId));
        overlay.accumulate(entry.liveFootprintBodyFixed);
    }

    static VectorIJK instrumentToJ2000(UnwritableVectorIJK instrumentVector, RotationMatrixIJK j2000ToInstrument) {
        VectorIJK result = new VectorIJK();
        j2000ToInstrument.mtxv(instrumentVector, result);
        return result;
    }

    static BodyIntersection intersectBodyEllipsoid(
            VectorIJK sourceJ2000,
            VectorIJK rayJ2000,
            VectorIJK bodyPosJ2000,
            RotationMatrixIJK j2000ToBodyFixed,
            Ellipsoid shape,
            EphemerisID bodyId) {

        Objects.requireNonNull(sourceJ2000, "sourceJ2000");
        Objects.requireNonNull(rayJ2000, "rayJ2000");
        Objects.requireNonNull(bodyPosJ2000, "bodyPosJ2000");
        Objects.requireNonNull(j2000ToBodyFixed, "j2000ToBodyFixed");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(bodyId, "bodyId");

        VectorIJK sourceBodyFixed = new VectorIJK(
                sourceJ2000.getI() - bodyPosJ2000.getI(),
                sourceJ2000.getJ() - bodyPosJ2000.getJ(),
                sourceJ2000.getK() - bodyPosJ2000.getK());
        j2000ToBodyFixed.mxv(sourceBodyFixed, sourceBodyFixed);

        VectorIJK rayBodyFixed = new VectorIJK();
        j2000ToBodyFixed.mxv(rayJ2000, rayBodyFixed);
        if (rayBodyFixed.getLength() < 1e-12 || !shape.intersects(sourceBodyFixed, rayBodyFixed)) {
            return null;
        }

        VectorIJK hitBodyFixed = shape.compute(sourceBodyFixed, rayBodyFixed, new VectorIJK());
        double dx = hitBodyFixed.getI() - sourceBodyFixed.getI();
        double dy = hitBodyFixed.getJ() - sourceBodyFixed.getJ();
        double dz = hitBodyFixed.getK() - sourceBodyFixed.getK();
        double distanceKm = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(distanceKm >= 0.0) || Double.isNaN(distanceKm)) {
            return null;
        }
        return new BodyIntersection(bodyId, bodyPosJ2000, j2000ToBodyFixed, shape, hitBodyFixed, distanceKm);
    }

    static BodyIntersection findNearestIntersection(
            KEPPLREphemeris eph,
            EphemerisID sourceBodyId,
            VectorIJK sourceJ2000,
            VectorIJK rayJ2000,
            double currentEt) {
        BodyIntersection best = null;
        for (EphemerisID bodyId : eph.getKnownBodies()) {
            if (bodyId.equals(sourceBodyId)) {
                continue;
            }
            Ellipsoid shape = eph.getShape(bodyId);
            if (shape == null) {
                continue;
            }
            VectorIJK bodyPosJ2000 = eph.getHeliocentricPositionJ2000(bodyId, currentEt);
            RotationMatrixIJK j2000ToBodyFixed = eph.getJ2000ToBodyFixedRotation(bodyId, currentEt);
            if (bodyPosJ2000 == null || j2000ToBodyFixed == null) {
                continue;
            }
            BodyIntersection hit =
                    intersectBodyEllipsoid(sourceJ2000, rayJ2000, bodyPosJ2000, j2000ToBodyFixed, shape, bodyId);
            if (hit != null && (best == null || hit.distanceKm() < best.distanceKm())) {
                best = hit;
            }
        }
        return best;
    }

    static BodyIntersection intersectTargetBody(
            VectorIJK sourceJ2000, VectorIJK rayJ2000, BodyIntersection targetBody) {
        if (targetBody == null) {
            return null;
        }
        return intersectBodyEllipsoid(
                sourceJ2000,
                rayJ2000,
                targetBody.bodyPosJ2000(),
                targetBody.j2000ToBodyFixed(),
                targetBody.shape(),
                targetBody.bodyId());
    }

    /**
     * One-shot diagnostic log fired the first time a frustum entry is rendered after being toggled on. Prints the apex
     * and boresight in heliocentric J2000, the camera position, and the heliocentric J2000 positions of every
     * configured body and spacecraft at the current ET.
     */
    private static void logDiagnostics(
            FrustumEntry entry,
            VectorIJK centerPosJ2000,
            double apexX,
            double apexY,
            double apexZ,
            RotationMatrixIJK j2000ToInstrument,
            double currentEt,
            double[] cameraHelioJ2000,
            KEPPLREphemeris eph) {

        String name = entry.instrument.id().getName();

        // Apex heliocentric J2000 (= center body position, e.g. spacecraft)
        logger.debug("=== Frustum diagnostic for {} at ET={} ===", name, currentEt);
        logger.debug(
                "  Camera helio-J2000  : ({}, {}, {})", cameraHelioJ2000[0], cameraHelioJ2000[1], cameraHelioJ2000[2]);
        logger.debug(
                "  Apex helio-J2000    : ({}, {}, {})",
                centerPosJ2000.getI(),
                centerPosJ2000.getJ(),
                centerPosJ2000.getK());
        logger.debug("  Apex camera-relative: ({}, {}, {})", apexX, apexY, apexZ);
        logger.debug("  Apex dist from cam  : {} km", Math.sqrt(apexX * apexX + apexY * apexY + apexZ * apexZ));

        // Boresight: transform from instrument frame to J2000 via transpose of j2000ToInstrument
        VectorIJK boresightJ2000 = new VectorIJK();
        j2000ToInstrument.mtxv(entry.fovSpice.getBoresight(), boresightJ2000);
        double bLen = boresightJ2000.getLength();
        if (bLen > 1e-10) {
            boresightJ2000 = new VectorIJK(
                    boresightJ2000.getI() / bLen, boresightJ2000.getJ() / bLen, boresightJ2000.getK() / bLen);
        }
        logger.debug(
                "  Boresight J2000 (unit): ({}, {}, {})",
                boresightJ2000.getI(),
                boresightJ2000.getJ(),
                boresightJ2000.getK());

        // All configured bodies — position at current ET
        logger.debug("  --- Helio-J2000 positions of configured bodies at ET={} ---", currentEt);
        KEPPLRConfiguration config = KEPPLRConfiguration.getInstance();
        for (String bodyName : config.bodies()) {
            int naifId = config.bodyBlock(bodyName).naifID();
            VectorIJK pos = eph.getHeliocentricPositionJ2000(naifId, currentEt);
            if (pos != null) {
                logger.debug(
                        "    {} ({}): ({}, {}, {})",
                        BodyLookupService.formatName(naifId),
                        naifId,
                        pos.getI(),
                        pos.getJ(),
                        pos.getK());
            } else {
                logger.debug("    {} ({}): position unavailable", bodyName, naifId);
            }
        }
        for (var sc : eph.getSpacecraft()) {
            int naifId = sc.code();
            VectorIJK pos = eph.getHeliocentricPositionJ2000(naifId, currentEt);
            if (pos != null) {
                logger.debug(
                        "    {} ({}): ({}, {}, {})",
                        BodyLookupService.formatName(naifId),
                        naifId,
                        pos.getI(),
                        pos.getJ(),
                        pos.getK());
            } else {
                logger.debug("    {} ({}): position unavailable", sc.id().getName(), naifId);
            }
        }
        logger.debug("=== end frustum diagnostic ===");
    }

    // ── FOV polygon approximation ─────────────────────────────────────────────

    /**
     * Returns the effective polygon bounds for the given FOV:
     *
     * <ul>
     *   <li>RECTANGLE / POLYGON — raw bounds from {@link FOVSpice#getBounds()} (already polygonal).
     *   <li>CIRCLE — the single bound vector is rotated around the boresight axis in
     *       {@link KepplrConstants#INSTRUMENT_FRUSTUM_CIRCLE_APPROX_SIDES} equal steps.
     *   <li>ELLIPSE — the two semi-axis bound vectors are combined as {@code cos(t)·a + sin(t)·b} for
     *       {@link KepplrConstants#INSTRUMENT_FRUSTUM_CIRCLE_APPROX_SIDES} steps.
     * </ul>
     */
    private static List<UnwritableVectorIJK> approximateBounds(FOVSpice fovSpice) {
        int n = KepplrConstants.INSTRUMENT_FRUSTUM_CIRCLE_APPROX_SIDES;
        FOVFactory.Shape shape = fovSpice.getShape();
        List<UnwritableVectorIJK> raw = fovSpice.getBounds();

        List<UnwritableVectorIJK> boundary;

        if (shape == FOVFactory.Shape.CIRCLE) {
            // Rotate the single bound vector around the boresight axis in n equal steps.
            UnwritableVectorIJK boresightRaw = fovSpice.getBoresight();
            double bLen = boresightRaw.getLength();
            if (bLen < 1e-10) return raw;
            VectorIJK axis =
                    new VectorIJK(boresightRaw.getI() / bLen, boresightRaw.getJ() / bLen, boresightRaw.getK() / bLen);
            VectorIJK v0 = new VectorIJK(raw.get(0));
            List<UnwritableVectorIJK> result = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                result.add(rotateAroundAxis(v0, axis, 2 * Math.PI * k / n));
            }
            boundary = result;

        } else if (shape == FOVFactory.Shape.ELLIPSE) {
            // Parameterize the ellipse: v(t) = cos(t)·a + sin(t)·b
            UnwritableVectorIJK a = raw.get(0);
            UnwritableVectorIJK b = raw.get(1);
            List<UnwritableVectorIJK> result = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                double t = 2 * Math.PI * k / n;
                double cos = Math.cos(t);
                double sin = Math.sin(t);
                result.add(new VectorIJK(
                        cos * a.getI() + sin * b.getI(),
                        cos * a.getJ() + sin * b.getJ(),
                        cos * a.getK() + sin * b.getK()));
            }
            boundary = result;

        } else {
            boundary = raw;
        }

        return densifyBoundary(boundary);
    }

    /**
     * Rotate vector {@code v} by {@code angle} radians around unit axis {@code axis} using Rodrigues' rotation formula:
     * {@code v_rot = v·cos(θ) + (axis×v)·sin(θ) + axis·(axis·v)·(1−cos(θ))}.
     */
    private static VectorIJK rotateAroundAxis(VectorIJK v, VectorIJK axis, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double dot = axis.getI() * v.getI() + axis.getJ() * v.getJ() + axis.getK() * v.getK();
        // axis × v
        double cx = axis.getJ() * v.getK() - axis.getK() * v.getJ();
        double cy = axis.getK() * v.getI() - axis.getI() * v.getK();
        double cz = axis.getI() * v.getJ() - axis.getJ() * v.getI();
        return new VectorIJK(
                v.getI() * cos + cx * sin + axis.getI() * dot * (1 - cos),
                v.getJ() * cos + cy * sin + axis.getJ() * dot * (1 - cos),
                v.getK() * cos + cz * sin + axis.getK() * dot * (1 - cos));
    }

    private static List<UnwritableVectorIJK> densifyBoundary(List<UnwritableVectorIJK> raw) {
        if (raw == null || raw.size() < 2) {
            return raw;
        }
        int subdivisions = Math.max(1, KepplrConstants.INSTRUMENT_FRUSTUM_EDGE_SUBDIVISIONS);
        if (subdivisions <= 1) {
            return raw;
        }

        int n = raw.size();
        List<UnwritableVectorIJK> result = new ArrayList<>(n * subdivisions);
        for (int i = 0; i < n; i++) {
            UnwritableVectorIJK a = raw.get(i);
            UnwritableVectorIJK b = raw.get((i + 1) % n);
            for (int s = 0; s < subdivisions; s++) {
                double t = (double) s / subdivisions;
                result.add(interpolateBoundaryVector(a, b, t));
            }
        }
        return result;
    }

    private static VectorIJK interpolateBoundaryVector(UnwritableVectorIJK a, UnwritableVectorIJK b, double t) {
        double x = a.getI() + (b.getI() - a.getI()) * t;
        double y = a.getJ() + (b.getJ() - a.getJ()) * t;
        double z = a.getK() + (b.getK() - a.getK()) * t;
        return new VectorIJK(x, y, z);
    }

    static record BodyIntersection(
            EphemerisID bodyId,
            VectorIJK bodyPosJ2000,
            RotationMatrixIJK j2000ToBodyFixed,
            Ellipsoid shape,
            VectorIJK hitBodyFixed,
            double distanceKm) {}

    static record GeometryMetrics(double renderedMaxDistanceKm, double footprintDistanceKm) {}

    private record CoverageKey(int instrumentCode, EphemerisID bodyId) {}

    /**
     * Retained swath geometry for one instrument/body pair.
     *
     * <p>Rather than rasterizing coverage into a fixed-resolution lat/lon mask, the current implementation stores the
     * actual closed footprint polygons in body-fixed coordinates and renders both those polygons and the swept strips
     * between successive footprints. That preserves close-up edge fidelity without exploding global tile counts.
     */
    private final class PersistentCoverageOverlay {
        final EphemerisID bodyId;
        final Ellipsoid shape;
        final Geometry geometry;
        final List<List<VectorIJK>> recordedPolygons = new ArrayList<>();
        FloatBuffer posBuffer = BufferUtils.createFloatBuffer(3);
        double lastDistanceKm = -1.0;
        boolean dirty = true;

        PersistentCoverageOverlay(AssetManager assetManager, int instrumentCode, EphemerisID bodyId) {
            this.bodyId = bodyId;
            this.shape = KEPPLRConfiguration.getInstance().getEphemeris().getShape(bodyId);

            Mesh mesh = new Mesh();
            mesh.setMode(Mesh.Mode.Triangles);
            mesh.setBuffer(VertexBuffer.Type.Position, 3, posBuffer);
            mesh.updateCounts();
            mesh.updateBound();

            Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            mat.setColor("Color", new ColorRGBA(0f, 1f, 1f, 0.35f));
            mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            mat.getAdditionalRenderState().setDepthWrite(false);
            mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
            mat.getAdditionalRenderState().setPolyOffset(-1f, -4f);

            this.geometry =
                    new Geometry("instrument-persistent-coverage-" + instrumentCode + "-" + bodyId.getName(), mesh);
            this.geometry.setMaterial(mat);
            this.geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
        }

        void accumulate(List<VectorIJK> polygonBodyFixed) {
            if (shape == null || polygonBodyFixed.size() < 3) {
                return;
            }
            recordedPolygons.add(copyPolygon(polygonBodyFixed));
            dirty = true;
        }

        private List<VectorIJK> copyPolygon(List<VectorIJK> polygonBodyFixed) {
            List<VectorIJK> copy = new ArrayList<>(polygonBodyFixed.size());
            for (VectorIJK p : polygonBodyFixed) {
                copy.add(new VectorIJK(p));
            }
            return copy;
        }

        void updateGeometry(double currentEt, double[] cameraHelioJ2000) {
            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
            VectorIJK bodyPosJ2000 = eph.getHeliocentricPositionJ2000(bodyId, currentEt);
            RotationMatrixIJK j2000ToBodyFixed = eph.getJ2000ToBodyFixedRotation(bodyId, currentEt);
            if (shape == null || bodyPosJ2000 == null || j2000ToBodyFixed == null || recordedPolygons.isEmpty()) {
                geometry.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
                lastDistanceKm = -1.0;
                return;
            }

            int triangleVertexCount = 0;
            for (List<VectorIJK> polygon : recordedPolygons) {
                if (polygon.size() >= 3) {
                    triangleVertexCount += 3 * (polygon.size() - 2);
                }
            }
            for (int i = 1; i < recordedPolygons.size(); i++) {
                List<VectorIJK> previous = recordedPolygons.get(i - 1);
                List<VectorIJK> current = recordedPolygons.get(i);
                if (previous.size() == current.size() && previous.size() >= 2) {
                    triangleVertexCount += previous.size() * 6;
                }
            }

            if (dirty || posBuffer.capacity() < triangleVertexCount * 3) {
                posBuffer = BufferUtils.createFloatBuffer(Math.max(triangleVertexCount, 1) * 3);
                geometry.getMesh().setBuffer(VertexBuffer.Type.Position, 3, posBuffer);
            } else {
                posBuffer.clear();
            }

            lastDistanceKm = -1.0;
            for (List<VectorIJK> polygon : recordedPolygons) {
                appendPolygon(posBuffer, polygon, j2000ToBodyFixed, bodyPosJ2000, cameraHelioJ2000);
            }
            for (int i = 1; i < recordedPolygons.size(); i++) {
                List<VectorIJK> previous = recordedPolygons.get(i - 1);
                List<VectorIJK> current = recordedPolygons.get(i);
                if (previous.size() == current.size() && previous.size() >= 2) {
                    appendBridgeStrip(posBuffer, previous, current, j2000ToBodyFixed, bodyPosJ2000, cameraHelioJ2000);
                }
            }
            posBuffer.flip();
            geometry.getMesh().setBuffer(VertexBuffer.Type.Position, 3, posBuffer);
            geometry.getMesh().updateCounts();
            geometry.getMesh().updateBound();
            geometry.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);
            dirty = false;
        }

        private void appendPolygon(
                FloatBuffer buffer,
                List<VectorIJK> polygon,
                RotationMatrixIJK j2000ToBodyFixed,
                VectorIJK bodyPosJ2000,
                double[] cameraHelioJ2000) {
            if (polygon.size() < 3) {
                return;
            }
            float[] origin = projectSurfacePoint(polygon.get(0), j2000ToBodyFixed, bodyPosJ2000, cameraHelioJ2000);
            for (int i = 1; i < polygon.size() - 1; i++) {
                float[] p1 = projectSurfacePoint(polygon.get(i), j2000ToBodyFixed, bodyPosJ2000, cameraHelioJ2000);
                float[] p2 = projectSurfacePoint(polygon.get(i + 1), j2000ToBodyFixed, bodyPosJ2000, cameraHelioJ2000);
                putVertex(buffer, origin);
                putVertex(buffer, p1);
                putVertex(buffer, p2);
                lastDistanceKm = minDistance(lastDistanceKm, origin);
                lastDistanceKm = minDistance(lastDistanceKm, p1);
                lastDistanceKm = minDistance(lastDistanceKm, p2);
            }
        }

        private void appendBridgeStrip(
                FloatBuffer buffer,
                List<VectorIJK> previous,
                List<VectorIJK> current,
                RotationMatrixIJK j2000ToBodyFixed,
                VectorIJK bodyPosJ2000,
                double[] cameraHelioJ2000) {
            int n = previous.size();
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float[] a0 = projectSurfacePoint(previous.get(i), j2000ToBodyFixed, bodyPosJ2000, cameraHelioJ2000);
                float[] a1 = projectSurfacePoint(previous.get(j), j2000ToBodyFixed, bodyPosJ2000, cameraHelioJ2000);
                float[] b1 = projectSurfacePoint(current.get(j), j2000ToBodyFixed, bodyPosJ2000, cameraHelioJ2000);
                float[] b0 = projectSurfacePoint(current.get(i), j2000ToBodyFixed, bodyPosJ2000, cameraHelioJ2000);
                putVertex(buffer, a0);
                putVertex(buffer, a1);
                putVertex(buffer, b1);
                putVertex(buffer, a0);
                putVertex(buffer, b1);
                putVertex(buffer, b0);
                lastDistanceKm = minDistance(lastDistanceKm, a0);
                lastDistanceKm = minDistance(lastDistanceKm, a1);
                lastDistanceKm = minDistance(lastDistanceKm, b1);
                lastDistanceKm = minDistance(lastDistanceKm, b0);
            }
        }

        private float[] projectSurfacePoint(
                VectorIJK bodyFixedPoint,
                RotationMatrixIJK j2000ToBodyFixed,
                VectorIJK bodyPosJ2000,
                double[] cameraHelioJ2000) {
            VectorIJK bodyFixed = offsetSurfacePoint(bodyFixedPoint);
            VectorIJK world = new VectorIJK();
            j2000ToBodyFixed.mtxv(bodyFixed, world);
            return new float[] {
                (float) (world.getI() + bodyPosJ2000.getI() - cameraHelioJ2000[0]),
                (float) (world.getJ() + bodyPosJ2000.getJ() - cameraHelioJ2000[1]),
                (float) (world.getK() + bodyPosJ2000.getK() - cameraHelioJ2000[2])
            };
        }

        private VectorIJK offsetSurfacePoint(VectorIJK bodyFixedPoint) {
            VectorIJK normal = shape.computeOutwardNormal(bodyFixedPoint, new VectorIJK());
            double nLen = normal.getLength();
            double scale = nLen > 1e-12 ? PERSISTENT_COVERAGE_SURFACE_OFFSET_KM / nLen : 0.0;
            return new VectorIJK(
                    bodyFixedPoint.getI() + normal.getI() * scale,
                    bodyFixedPoint.getJ() + normal.getJ() * scale,
                    bodyFixedPoint.getK() + normal.getK() * scale);
        }

        private double minDistance(double current, float[] vertex) {
            double d = Math.sqrt(
                    (double) vertex[0] * vertex[0] + (double) vertex[1] * vertex[1] + (double) vertex[2] * vertex[2]);
            return current < 0.0 ? d : Math.min(current, d);
        }

        private void putVertex(FloatBuffer buffer, float[] vertex) {
            buffer.put(vertex[0]).put(vertex[1]).put(vertex[2]);
        }
    }

    // ── Inner class: per-instrument render state ──────────────────────────────

    private static final class FrustumEntry {

        final Instrument instrument;
        final FOVSpice fovSpice;
        /** Effective polygon bounds: raw for RECTANGLE/POLYGON, approximated for CIRCLE/ELLIPSE. */
        final List<UnwritableVectorIJK> effectiveBounds;

        final Geometry geometry;
        final Geometry outlineGeometry;
        final Geometry nearGeometry;
        final Geometry nearOutlineGeometry;
        final Geometry footprintGeometry;
        /** Direct reference to the position buffer for in-place vertex updates. */
        final FloatBuffer posBuffer;

        final FloatBuffer outlinePosBuffer;
        final FloatBuffer nearPosBuffer;
        final FloatBuffer nearOutlinePosBuffer;
        final FloatBuffer footprintPosBuffer;
        boolean footprintVisible = false;
        boolean nearSegmentVisible = false;
        boolean persistenceEnabled = false;
        EphemerisID liveFootprintBodyId = null;
        List<VectorIJK> liveFootprintBodyFixed = List.of();
        boolean liveFootprintClosed = false;

        boolean visible = false;
        /**
         * Set to {@code true} by {@link InstrumentFrustumManager#setVisible} when toggled on; cleared after first
         * render.
         */
        boolean logPending = false;

        FrustumEntry(Instrument instrument, FOVSpice fovSpice, AssetManager assetManager) {
            this.instrument = instrument;
            this.fovSpice = fovSpice;
            this.effectiveBounds = InstrumentFrustumManager.approximateBounds(fovSpice);

            List<UnwritableVectorIJK> bounds = effectiveBounds;
            int n = bounds.size();

            // Mesh layout (non-indexed triangles):
            //   Side faces : n triangles  (apex, base[i], base[(i+1)%n])
            //   Base cap   : (n−2) triangles (fan from base[0])
            // Total triangles = n + (n−2) = 2n−2
            // Total vertices  = 3 × (2n−2)
            int numVerts = 3 * (2 * n - 2);
            FloatBuffer buf = BufferUtils.createFloatBuffer(numVerts * 3);

            Mesh mesh = new Mesh();
            mesh.setMode(Mesh.Mode.Triangles);
            mesh.setBuffer(VertexBuffer.Type.Position, 3, buf);
            mesh.updateBound();

            this.posBuffer = buf;

            Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            mat.setColor("Color", FRUSTUM_COLOR);
            mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            mat.getAdditionalRenderState().setDepthWrite(false);
            mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

            this.geometry = new Geometry("instrument-frustum-" + instrument.id().getName(), mesh);
            this.geometry.setMaterial(mat);
            this.geometry.setQueueBucket(RenderQueue.Bucket.Transparent);

            Mesh nearMesh = new Mesh();
            nearMesh.setMode(Mesh.Mode.Triangles);
            this.nearPosBuffer = BufferUtils.createFloatBuffer(numVerts * 3);
            nearMesh.setBuffer(VertexBuffer.Type.Position, 3, nearPosBuffer);
            nearMesh.updateBound();

            this.nearGeometry =
                    new Geometry("instrument-frustum-near-" + instrument.id().getName(), nearMesh);
            this.nearGeometry.setMaterial(mat.clone());
            this.nearGeometry.setQueueBucket(RenderQueue.Bucket.Transparent);
            this.nearGeometry.setCullHint(com.jme3.scene.Spatial.CullHint.Always);

            Mesh outlineMesh = new Mesh();
            outlineMesh.setMode(Mesh.Mode.Lines);
            this.outlinePosBuffer = BufferUtils.createFloatBuffer(n * 4 * 3);
            outlineMesh.setBuffer(VertexBuffer.Type.Position, 3, outlinePosBuffer);
            outlineMesh.updateCounts();
            outlineMesh.updateBound();

            Material outlineMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            outlineMat.setColor("Color", FRUSTUM_OUTLINE_COLOR);
            outlineMat.getAdditionalRenderState().setLineWidth(3f);
            outlineMat.getAdditionalRenderState().setDepthWrite(false);
            outlineMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

            this.outlineGeometry =
                    new Geometry("instrument-frustum-outline-" + instrument.id().getName(), outlineMesh);
            this.outlineGeometry.setMaterial(outlineMat);
            this.outlineGeometry.setQueueBucket(RenderQueue.Bucket.Transparent);

            Mesh nearOutlineMesh = new Mesh();
            nearOutlineMesh.setMode(Mesh.Mode.Lines);
            this.nearOutlinePosBuffer = BufferUtils.createFloatBuffer(n * 4 * 3);
            nearOutlineMesh.setBuffer(VertexBuffer.Type.Position, 3, nearOutlinePosBuffer);
            nearOutlineMesh.updateCounts();
            nearOutlineMesh.updateBound();

            this.nearOutlineGeometry = new Geometry(
                    "instrument-frustum-near-outline-" + instrument.id().getName(), nearOutlineMesh);
            this.nearOutlineGeometry.setMaterial(outlineMat.clone());
            this.nearOutlineGeometry.setQueueBucket(RenderQueue.Bucket.Transparent);
            this.nearOutlineGeometry.setCullHint(com.jme3.scene.Spatial.CullHint.Always);

            Mesh footprintMesh = new Mesh();
            footprintMesh.setMode(Mesh.Mode.LineStrip);
            this.footprintPosBuffer = BufferUtils.createFloatBuffer((n + 1) * 3);
            footprintMesh.setBuffer(VertexBuffer.Type.Position, 3, footprintPosBuffer);
            footprintMesh.updateCounts();
            footprintMesh.updateBound();

            Material footprintMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            footprintMat.setColor("Color", FOOTPRINT_COLOR);
            footprintMat.getAdditionalRenderState().setLineWidth(2f);
            footprintMat.getAdditionalRenderState().setDepthWrite(false);
            footprintMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

            this.footprintGeometry =
                    new Geometry("instrument-footprint-" + instrument.id().getName(), footprintMesh);
            this.footprintGeometry.setMaterial(footprintMat);
            this.footprintGeometry.setQueueBucket(RenderQueue.Bucket.Transparent);
            this.footprintGeometry.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        }

        /**
         * Recompute frustum and live-footprint vertex positions in-place for the given apex and frame rotation.
         *
         * @param apexX camera-relative apex X in km
         * @param apexY camera-relative apex Y in km
         * @param apexZ camera-relative apex Z in km
         * @param sourceJ2000 instrument source position in heliocentric J2000
         * @param j2000ToInstrument J2000 → instrument-frame rotation matrix; its transpose (= instrument → J2000) is
         *     applied to each bound vector via {@link RotationMatrixIJK#mtxv}
         * @param targetBody boresight-selected target body intersection, or {@code null} when the boresight misses
         * @param cameraHelioJ2000 camera heliocentric J2000 position in km
         * @return max camera-relative frustum distance plus representative live-footprint distance
         */
        GeometryMetrics updateGeometry(
                double apexX,
                double apexY,
                double apexZ,
                VectorIJK sourceJ2000,
                RotationMatrixIJK j2000ToInstrument,
                BodyIntersection targetBody,
                double[] cameraHelioJ2000) {
            List<UnwritableVectorIJK> bounds = effectiveBounds;
            int n = bounds.size();

            // Transform bound vectors from instrument frame to J2000 and compute:
            //   1. default-extent base vertices for the full frustum shell
            //   2. optional body-intersection points used for the live footprint
            // j2000ToInstrument.mtxv(v) = transpose(j2000ToInstrument) × v = instrumentToJ2000 × v
            float[] bx = new float[n];
            float[] by = new float[n];
            float[] bz = new float[n];
            float[] clipX = new float[n];
            float[] clipY = new float[n];
            float[] clipZ = new float[n];
            float[] endX = new float[n];
            float[] endY = new float[n];
            float[] endZ = new float[n];
            float[] footprintX = new float[n];
            float[] footprintY = new float[n];
            float[] footprintZ = new float[n];
            VectorIJK[] footprintBodyFixed = new VectorIJK[n];
            boolean[] hitMask = new boolean[n];
            int footprintCount = 0;
            double footprintDistanceKm = -1.0;
            VectorIJK scratch = new VectorIJK();
            VectorIJK worldPoint = new VectorIJK();
            VectorIJK worldNormal = new VectorIJK();

            for (int i = 0; i < n; i++) {
                j2000ToInstrument.mtxv(bounds.get(i), scratch);
                double len = scratch.getLength();
                if (len < 1e-10) {
                    // Degenerate bound vector — place base vertex at apex to avoid NaN
                    bx[i] = (float) apexX;
                    by[i] = (float) apexY;
                    bz[i] = (float) apexZ;
                    clipX[i] = (float) apexX;
                    clipY[i] = (float) apexY;
                    clipZ[i] = (float) apexZ;
                    endX[i] = (float) apexX;
                    endY[i] = (float) apexY;
                    endZ[i] = (float) apexZ;
                } else {
                    double ext = KepplrConstants.INSTRUMENT_FRUSTUM_DEFAULT_EXTENT_KM / len;
                    bx[i] = (float) (apexX + scratch.getI() * ext);
                    by[i] = (float) (apexY + scratch.getJ() * ext);
                    bz[i] = (float) (apexZ + scratch.getK() * ext);
                    clipX[i] = bx[i];
                    clipY[i] = by[i];
                    clipZ[i] = bz[i];
                    endX[i] = bx[i];
                    endY[i] = by[i];
                    endZ[i] = bz[i];
                    double baseDistanceKm =
                            Math.sqrt((double) bx[i] * bx[i] + (double) by[i] * by[i] + (double) bz[i] * bz[i]);
                    if (targetBody != null) {
                        BodyIntersection edgeHit = intersectTargetBody(sourceJ2000, scratch, targetBody);
                        if (edgeHit != null) {
                            edgeHit.j2000ToBodyFixed().mtxv(edgeHit.hitBodyFixed(), worldPoint);
                            worldPoint.setTo(
                                    worldPoint.getI() + edgeHit.bodyPosJ2000().getI(),
                                    worldPoint.getJ() + edgeHit.bodyPosJ2000().getJ(),
                                    worldPoint.getK() + edgeHit.bodyPosJ2000().getK());

                            float hitX = (float) (worldPoint.getI() - cameraHelioJ2000[0]);
                            float hitY = (float) (worldPoint.getJ() - cameraHelioJ2000[1]);
                            float hitZ = (float) (worldPoint.getK() - cameraHelioJ2000[2]);
                            clipX[i] = hitX;
                            clipY[i] = hitY;
                            clipZ[i] = hitZ;
                            endX[i] = hitX;
                            endY[i] = hitY;
                            endZ[i] = hitZ;

                            VectorIJK normalBodyFixed =
                                    edgeHit.shape().computeOutwardNormal(edgeHit.hitBodyFixed(), new VectorIJK());
                            edgeHit.j2000ToBodyFixed().mtxv(normalBodyFixed, worldNormal);
                            double normalLen = worldNormal.getLength();
                            double scale = normalLen > 1e-12 ? FOOTPRINT_SURFACE_OFFSET_KM / normalLen : 0.0;
                            footprintX[i] = (float) (hitX + worldNormal.getI() * scale);
                            footprintY[i] = (float) (hitY + worldNormal.getJ() * scale);
                            footprintZ[i] = (float) (hitZ + worldNormal.getK() * scale);
                            footprintBodyFixed[i] = new VectorIJK(edgeHit.hitBodyFixed());
                            footprintCount++;
                            hitMask[i] = true;

                            double hitDistanceKm =
                                    Math.sqrt((double) hitX * hitX + (double) hitY * hitY + (double) hitZ * hitZ);
                            if (footprintDistanceKm < 0.0 || hitDistanceKm < footprintDistanceKm) {
                                footprintDistanceKm = hitDistanceKm;
                            }
                        }
                    }
                }
            }

            float ax = (float) apexX;
            float ay = (float) apexY;
            float az = (float) apexZ;

            posBuffer.clear();
            outlinePosBuffer.clear();
            nearPosBuffer.clear();
            nearOutlinePosBuffer.clear();

            int[] run = longestHitRun(hitMask);
            int clipStart = run[0];
            int clipCount = run[1];
            boolean closedClip = targetBody != null && clipCount == n;
            double renderedMaxDistanceKm = Math.sqrt(apexX * apexX + apexY * apexY + apexZ * apexZ);
            double apexDistanceKm = renderedMaxDistanceKm;
            double nearLimitKm = FrustumLayer.NEAR.farKm;
            boolean buildNearSegment = apexDistanceKm < nearLimitKm;

            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                renderedMaxDistanceKm = Math.max(renderedMaxDistanceKm, distanceKm(endX[i], endY[i], endZ[i]));
                renderedMaxDistanceKm = Math.max(renderedMaxDistanceKm, distanceKm(endX[j], endY[j], endZ[j]));
                posBuffer.put(ax).put(ay).put(az);
                posBuffer.put(endX[i]).put(endY[i]).put(endZ[i]);
                posBuffer.put(endX[j]).put(endY[j]).put(endZ[j]);

                outlinePosBuffer.put(ax).put(ay).put(az);
                outlinePosBuffer.put(endX[i]).put(endY[i]).put(endZ[i]);
                outlinePosBuffer.put(endX[i]).put(endY[i]).put(endZ[i]);
                outlinePosBuffer.put(endX[j]).put(endY[j]).put(endZ[j]);

                if (buildNearSegment) {
                    appendNearTriangle(
                            nearPosBuffer,
                            nearOutlinePosBuffer,
                            ax,
                            ay,
                            az,
                            endX[i],
                            endY[i],
                            endZ[i],
                            endX[j],
                            endY[j],
                            endZ[j],
                            nearLimitKm);
                }
            }

            posBuffer.flip();
            geometry.getMesh().setBuffer(VertexBuffer.Type.Position, 3, posBuffer);
            geometry.getMesh().updateCounts();
            geometry.getMesh().updateBound();

            outlinePosBuffer.flip();
            outlineGeometry.getMesh().setBuffer(VertexBuffer.Type.Position, 3, outlinePosBuffer);
            outlineGeometry.getMesh().updateCounts();
            outlineGeometry.getMesh().updateBound();

            nearPosBuffer.flip();
            if (nearPosBuffer.hasRemaining()) {
                nearGeometry.getMesh().setBuffer(VertexBuffer.Type.Position, 3, nearPosBuffer);
                nearGeometry.getMesh().updateCounts();
                nearGeometry.getMesh().updateBound();
                nearGeometry.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);
                nearSegmentVisible = true;
            } else {
                nearGeometry.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
                nearSegmentVisible = false;
            }

            nearOutlinePosBuffer.flip();
            if (nearOutlinePosBuffer.hasRemaining()) {
                nearOutlineGeometry.getMesh().setBuffer(VertexBuffer.Type.Position, 3, nearOutlinePosBuffer);
                nearOutlineGeometry.getMesh().updateCounts();
                nearOutlineGeometry.getMesh().updateBound();
                nearOutlineGeometry.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);
            } else {
                nearOutlineGeometry.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
            }

            if (footprintCount >= 3) {
                int footprintStart = clipStart;
                int footprintCountVisible = clipCount;
                boolean closedFootprint = footprintCount == n;
                List<VectorIJK> retainedBodyFixed =
                        new ArrayList<>(closedFootprint ? footprintCount : footprintCountVisible);
                footprintPosBuffer.clear();
                if (closedFootprint) {
                    for (int i = 0; i < footprintCount; i++) {
                        footprintPosBuffer.put(footprintX[i]).put(footprintY[i]).put(footprintZ[i]);
                        retainedBodyFixed.add(new VectorIJK(footprintBodyFixed[i]));
                    }
                    footprintPosBuffer.put(footprintX[0]).put(footprintY[0]).put(footprintZ[0]);
                } else {
                    for (int step = 0; step < footprintCountVisible; step++) {
                        int idx = (footprintStart + step) % n;
                        footprintPosBuffer
                                .put(footprintX[idx])
                                .put(footprintY[idx])
                                .put(footprintZ[idx]);
                        retainedBodyFixed.add(new VectorIJK(footprintBodyFixed[idx]));
                    }
                }
                footprintPosBuffer.flip();
                footprintGeometry.getMesh().setBuffer(VertexBuffer.Type.Position, 3, footprintPosBuffer);
                footprintGeometry.getMesh().updateCounts();
                footprintGeometry.getMesh().updateBound();
                footprintGeometry.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);
                footprintVisible = true;
                liveFootprintBodyId = targetBody != null ? targetBody.bodyId() : null;
                liveFootprintBodyFixed = retainedBodyFixed;
                liveFootprintClosed = closedFootprint;
                return new GeometryMetrics(renderedMaxDistanceKm, footprintDistanceKm);
            }

            footprintGeometry.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
            footprintVisible = false;
            liveFootprintBodyId = null;
            liveFootprintBodyFixed = List.of();
            liveFootprintClosed = false;
            return new GeometryMetrics(renderedMaxDistanceKm, -1.0);
        }

        boolean hasLiveFootprint() {
            return footprintVisible;
        }

        boolean hasNearSegment() {
            return nearSegmentVisible;
        }

        private static int[] longestHitRun(boolean[] hitMask) {
            int n = hitMask.length;
            int bestStart = 0;
            int bestLen = 0;
            int currentStart = -1;
            int currentLen = 0;

            for (int pass = 0; pass < 2 * n; pass++) {
                int idx = pass % n;
                if (hitMask[idx]) {
                    if (currentLen == 0) {
                        currentStart = idx;
                    }
                    currentLen++;
                    if (currentLen > bestLen && currentLen <= n) {
                        bestLen = currentLen;
                        bestStart = currentStart;
                    }
                } else {
                    currentLen = 0;
                    currentStart = -1;
                }
            }

            return new int[] {bestStart, Math.min(bestLen, n)};
        }

        private static double distanceKm(float x, float y, float z) {
            return Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        }

        private static void appendNearTriangle(
                FloatBuffer triBuffer,
                FloatBuffer lineBuffer,
                float ax,
                float ay,
                float az,
                float bx,
                float by,
                float bz,
                float cx,
                float cy,
                float cz,
                double nearLimitKm) {
            float[] bNear = clampEndpoint(ax, ay, az, bx, by, bz, nearLimitKm);
            float[] cNear = clampEndpoint(ax, ay, az, cx, cy, cz, nearLimitKm);
            if (bNear == null || cNear == null) {
                return;
            }

            triBuffer.put(ax).put(ay).put(az);
            triBuffer.put(bNear[0]).put(bNear[1]).put(bNear[2]);
            triBuffer.put(cNear[0]).put(cNear[1]).put(cNear[2]);

            lineBuffer.put(ax).put(ay).put(az);
            lineBuffer.put(bNear[0]).put(bNear[1]).put(bNear[2]);
            lineBuffer.put(bNear[0]).put(bNear[1]).put(bNear[2]);
            lineBuffer.put(cNear[0]).put(cNear[1]).put(cNear[2]);
        }

        private static float[] clampEndpoint(
                float ax, float ay, float az, float bx, float by, float bz, double nearLimitKm) {
            double bDist = distanceKm(bx, by, bz);
            if (bDist <= nearLimitKm) {
                return new float[] {bx, by, bz};
            }

            double dx = bx - ax;
            double dy = by - ay;
            double dz = bz - az;
            double qa = dx * dx + dy * dy + dz * dz;
            double qb = 2.0 * (ax * dx + ay * dy + az * dz);
            double qc = ax * ax + ay * ay + az * az - nearLimitKm * nearLimitKm;
            double disc = qb * qb - 4.0 * qa * qc;
            if (disc < 0.0 || qa < 1e-12) {
                return null;
            }

            double sqrtDisc = Math.sqrt(disc);
            double t0 = (-qb - sqrtDisc) / (2.0 * qa);
            double t1 = (-qb + sqrtDisc) / (2.0 * qa);
            double t = Double.NaN;
            if (t0 >= 0.0 && t0 <= 1.0) {
                t = t0;
            } else if (t1 >= 0.0 && t1 <= 1.0) {
                t = t1;
            }
            if (Double.isNaN(t)) {
                return null;
            }
            return new float[] {(float) (ax + dx * t), (float) (ay + dy * t), (float) (az + dz * t)};
        }
    }
}
