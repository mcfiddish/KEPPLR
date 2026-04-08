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
 * <p>Each frustum is a closed pyramid whose apex sits at the camera-relative J2000 position of the instrument's center
 * body. When the instrument boresight intersects another body's reference ellipsoid, the frustum is shortened against
 * that body and a live footprint polyline is drawn on the surface. Otherwise the base vertices fall back to the
 * default fixed extent:
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
*   <li>LOD or distance culling of frustums
* </ul>
 */
public final class InstrumentFrustumManager {

    private static final Logger logger = LogManager.getLogger();

    /** Translucent cyan material color (hardcoded for this step). */
    private static final ColorRGBA FRUSTUM_COLOR = new ColorRGBA(0f, 1f, 1f, 0.25f);
    private static final ColorRGBA FOOTPRINT_COLOR = new ColorRGBA(0f, 1f, 1f, 0.9f);
    private static final double FOOTPRINT_SURFACE_OFFSET_KM = 0.01;

    // ── Per-frustum-layer scene nodes ─────────────────────────────────────────
    private final Map<FrustumLayer, Node> layerNodes;

    /** Retained so {@link #reload()} can rebuild entries without a new constructor call. */
    private final AssetManager assetManager;

    // ── Per-instrument render entries (insertion-ordered for deterministic iteration) ──
    /** Keyed by instrument NAIF code. */
    private final Map<Integer, FrustumEntry> entriesByCode = new LinkedHashMap<>();
    /** Keyed by instrument name (from {@code instrument.id().getName()}) for name-based lookup. */
    private final Map<String, FrustumEntry> entriesByName = new LinkedHashMap<>();

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
            BodyIntersection target = findNearestIntersection(
                    eph, entry.instrument.center(), centerPosJ2000, boresightJ2000, currentEt);

            // Update the frustum mesh and live footprint from the same target body solution.
            double footprintDistanceKm =
                    entry.updateGeometry(apexX, apexY, apexZ, centerPosJ2000, j2000ToInstrument, target, cameraHelioJ2000);

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

            // Assign to the frustum layer that contains the apex (spacecraft position).
            // Using the apex distance — not apex + extent — matches the VectorRenderer pattern
            // (§8.3: assign to the layer containing the geometry's origin).  The base vertices
            // may extend beyond the assigned layer's far plane and be clipped by the GPU, but
            // the visually critical apex region is always rendered in the correct layer.
            double apexDist = Math.sqrt(apexX * apexX + apexY * apexY + apexZ * apexZ);
            FrustumLayer layer = FrustumLayer.assign(apexDist, 0.0);
            layerNodes.get(layer).attachChild(entry.geometry);

            if (entry.hasLiveFootprint()) {
                FrustumLayer footprintLayer = FrustumLayer.assign(Math.max(footprintDistanceKm, 0.0), 0.0);
                layerNodes.get(footprintLayer).attachChild(entry.footprintGeometry);
            }
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
            entry.footprintGeometry.removeFromParent();
        }
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
            entry.footprintGeometry.removeFromParent();
        }
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

    // ── Inner class: per-instrument render state ──────────────────────────────

    private static final class FrustumEntry {

        final Instrument instrument;
        final FOVSpice fovSpice;
        /** Effective polygon bounds: raw for RECTANGLE/POLYGON, approximated for CIRCLE/ELLIPSE. */
        final List<UnwritableVectorIJK> effectiveBounds;

        final Geometry geometry;
        final Geometry footprintGeometry;
        /** Direct reference to the position buffer for in-place vertex updates. */
        final FloatBuffer posBuffer;
        final FloatBuffer footprintPosBuffer;
        boolean footprintVisible = false;

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

            this.footprintGeometry = new Geometry("instrument-footprint-" + instrument.id().getName(), footprintMesh);
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
         * @return representative camera-relative distance of the live footprint, or {@code -1} when no live footprint
         */
        double updateGeometry(
                double apexX,
                double apexY,
                double apexZ,
                VectorIJK sourceJ2000,
                RotationMatrixIJK j2000ToInstrument,
                BodyIntersection targetBody,
                double[] cameraHelioJ2000) {
            List<UnwritableVectorIJK> bounds = effectiveBounds;
            int n = bounds.size();

            // Transform bound vectors from instrument frame to J2000 and compute base vertices.
            // j2000ToInstrument.mtxv(v) = transpose(j2000ToInstrument) × v = instrumentToJ2000 × v
            float[] bx = new float[n];
            float[] by = new float[n];
            float[] bz = new float[n];
            float[] footprintX = new float[n];
            float[] footprintY = new float[n];
            float[] footprintZ = new float[n];
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
                } else {
                    double ext = KepplrConstants.INSTRUMENT_FRUSTUM_DEFAULT_EXTENT_KM / len;
                    BodyIntersection edgeHit = intersectTargetBody(sourceJ2000, scratch, targetBody);
                    if (edgeHit != null) {
                        edgeHit.j2000ToBodyFixed().mtxv(edgeHit.hitBodyFixed(), worldPoint);
                        worldPoint.setTo(
                                worldPoint.getI() + edgeHit.bodyPosJ2000().getI(),
                                worldPoint.getJ() + edgeHit.bodyPosJ2000().getJ(),
                                worldPoint.getK() + edgeHit.bodyPosJ2000().getK());

                        bx[i] = (float) (worldPoint.getI() - cameraHelioJ2000[0]);
                        by[i] = (float) (worldPoint.getJ() - cameraHelioJ2000[1]);
                        bz[i] = (float) (worldPoint.getK() - cameraHelioJ2000[2]);

                        VectorIJK normalBodyFixed =
                                edgeHit.shape().computeOutwardNormal(edgeHit.hitBodyFixed(), new VectorIJK());
                        edgeHit
                                .j2000ToBodyFixed()
                                .mtxv(normalBodyFixed, worldNormal);
                        double normalLen = worldNormal.getLength();
                        double scale = normalLen > 1e-12 ? FOOTPRINT_SURFACE_OFFSET_KM / normalLen : 0.0;
                        footprintX[i] =
                                (float) (bx[i] + worldNormal.getI() * scale);
                        footprintY[i] =
                                (float) (by[i] + worldNormal.getJ() * scale);
                        footprintZ[i] =
                                (float) (bz[i] + worldNormal.getK() * scale);
                        footprintCount++;
                        hitMask[i] = true;

                        double hitDistanceKm =
                                Math.sqrt((double) bx[i] * bx[i] + (double) by[i] * by[i] + (double) bz[i] * bz[i]);
                        if (footprintDistanceKm < 0.0 || hitDistanceKm < footprintDistanceKm) {
                            footprintDistanceKm = hitDistanceKm;
                        }
                    } else {
                        bx[i] = (float) (apexX + scratch.getI() * ext);
                        by[i] = (float) (apexY + scratch.getJ() * ext);
                        bz[i] = (float) (apexZ + scratch.getK() * ext);
                    }
                }
            }

            float ax = (float) apexX;
            float ay = (float) apexY;
            float az = (float) apexZ;

            int sideStart = 0;
            int sideCount = n;
            boolean closedFootprint = false;
            if (targetBody != null) {
                int hitCount = 0;
                for (boolean hit : hitMask) {
                    if (hit) {
                        hitCount++;
                    }
                }
                if (hitCount == n) {
                    closedFootprint = true;
                } else if (hitCount >= 2) {
                    int[] run = longestHitRun(hitMask);
                    sideStart = run[0];
                    sideCount = run[1];
                } else {
                    sideCount = 0;
                }
            } else {
                closedFootprint = true;
            }

            posBuffer.clear();

            // Side faces: one triangle per adjacent sampled pair in the visible run.
            int sidePairs = closedFootprint ? n : Math.max(0, sideCount - 1);
            for (int step = 0; step < sidePairs; step++) {
                int i = (sideStart + step) % n;
                int j = (sideStart + step + 1) % n;
                posBuffer.put(ax).put(ay).put(az);
                posBuffer.put(bx[i]).put(by[i]).put(bz[i]);
                posBuffer.put(bx[j]).put(by[j]).put(bz[j]);
            }

            // Base cap only when the whole sampled boundary hits the target (or no target exists).
            if (closedFootprint) {
                for (int i = 1; i < n - 1; i++) {
                    posBuffer.put(bx[0]).put(by[0]).put(bz[0]);
                    posBuffer.put(bx[i]).put(by[i]).put(bz[i]);
                    posBuffer.put(bx[i + 1]).put(by[i + 1]).put(bz[i + 1]);
                }
            }

            posBuffer.flip();
            geometry.getMesh().setBuffer(VertexBuffer.Type.Position, 3, posBuffer);
            geometry.getMesh().updateCounts();
            geometry.getMesh().updateBound();

            if (footprintCount >= 3) {
                footprintPosBuffer.clear();
                if (closedFootprint) {
                    for (int i = 0; i < footprintCount; i++) {
                        footprintPosBuffer.put(footprintX[i]).put(footprintY[i]).put(footprintZ[i]);
                    }
                    footprintPosBuffer.put(footprintX[0]).put(footprintY[0]).put(footprintZ[0]);
                } else {
                    for (int step = 0; step < sideCount; step++) {
                        int idx = (sideStart + step) % n;
                        footprintPosBuffer.put(
                                footprintX[idx]).put(footprintY[idx]).put(footprintZ[idx]);
                    }
                }
                footprintPosBuffer.flip();
                footprintGeometry.getMesh().setBuffer(VertexBuffer.Type.Position, 3, footprintPosBuffer);
                footprintGeometry.getMesh().updateCounts();
                footprintGeometry.getMesh().updateBound();
                footprintGeometry.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);
                footprintVisible = true;
                return footprintDistanceKm;
            }

            footprintGeometry.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
            footprintVisible = false;
            return -1.0;
        }

        boolean hasLiveFootprint() {
            return footprintVisible;
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
    }
}
