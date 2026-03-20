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
import picante.mechanics.StateTransformFunction;
import picante.spice.fov.FOVFactory;
import picante.spice.fov.FOVSpice;

/**
 * Builds and updates translucent frustum pyramid overlays for every instrument returned by
 * {@link KEPPLREphemeris#getInstruments()} (Step 22).
 *
 * <h3>Geometry</h3>
 *
 * <p>Each frustum is a closed pyramid whose apex sits at the camera-relative J2000 position of the instrument's center
 * body and whose base vertices are:
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
 *   <li>Frustum shortening when the boresight intersects a body
 *   <li>Per-instrument color configuration (hardcoded cyan)
 *   <li>LOD or distance culling of frustums
 * </ul>
 */
public final class InstrumentFrustumManager {

    private static final Logger logger = LogManager.getLogger();

    /** Translucent cyan material color (hardcoded for this step). */
    private static final ColorRGBA FRUSTUM_COLOR = new ColorRGBA(0f, 1f, 1f, 0.25f);

    // ── Per-frustum-layer scene nodes ─────────────────────────────────────────
    private final Map<FrustumLayer, Node> layerNodes;

    /** Retained so {@link #reload()} can rebuild entries without a new constructor call. */
    private final AssetManager assetManager;

    // ── Per-instrument render entries (insertion-ordered for deterministic iteration) ──
    /** Keyed by instrument NAIF code. */
    private final Map<Integer, FrustumEntry> entriesByCode = new LinkedHashMap<>();
    /** Keyed by instrument name (from {@code instrument.id().getName()}) for name-based lookup. */
    private final Map<String, FrustumEntry> entriesByName = new LinkedHashMap<>();

    /** Geometries currently attached to a layer node, keyed for O(1) detach-all. */
    private final Map<Geometry, FrustumLayer> attachedGeoms = new LinkedHashMap<>();

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

            // Update mesh vertex positions in the entry's FloatBuffer
            entry.updateMesh(apexX, apexY, apexZ, j2000ToInstrument);

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
            Node targetNode = layerNodes.get(layer);
            targetNode.attachChild(entry.geometry);
            attachedGeoms.put(entry.geometry, layer);
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
            attachedGeoms.remove(entry.geometry);
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
        for (Geometry geom : attachedGeoms.keySet()) {
            geom.removeFromParent();
        }
        attachedGeoms.clear();
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
        logger.info("=== Frustum diagnostic for {} at ET={} ===", name, currentEt);
        logger.info(
                "  Camera helio-J2000  : ({}, {}, {})", cameraHelioJ2000[0], cameraHelioJ2000[1], cameraHelioJ2000[2]);
        logger.info(
                "  Apex helio-J2000    : ({}, {}, {})",
                centerPosJ2000.getI(),
                centerPosJ2000.getJ(),
                centerPosJ2000.getK());
        logger.info("  Apex camera-relative: ({}, {}, {})", apexX, apexY, apexZ);
        logger.info("  Apex dist from cam  : {} km", Math.sqrt(apexX * apexX + apexY * apexY + apexZ * apexZ));

        // Boresight: transform from instrument frame to J2000 via transpose of j2000ToInstrument
        VectorIJK boresightJ2000 = new VectorIJK();
        j2000ToInstrument.mtxv(entry.fovSpice.getBoresight(), boresightJ2000);
        double bLen = boresightJ2000.getLength();
        if (bLen > 1e-10) {
            boresightJ2000 = new VectorIJK(
                    boresightJ2000.getI() / bLen, boresightJ2000.getJ() / bLen, boresightJ2000.getK() / bLen);
        }
        logger.info(
                "  Boresight J2000 (unit): ({}, {}, {})",
                boresightJ2000.getI(),
                boresightJ2000.getJ(),
                boresightJ2000.getK());

        // All configured bodies — position at current ET
        logger.info("  --- Helio-J2000 positions of configured bodies at ET={} ---", currentEt);
        KEPPLRConfiguration config = KEPPLRConfiguration.getInstance();
        for (String bodyName : config.bodies()) {
            int naifId = config.bodyBlock(bodyName).naifID();
            VectorIJK pos = eph.getHeliocentricPositionJ2000(naifId, currentEt);
            if (pos != null) {
                logger.info(
                        "    {} ({}): ({}, {}, {})",
                        BodyLookupService.formatName(naifId),
                        naifId,
                        pos.getI(),
                        pos.getJ(),
                        pos.getK());
            } else {
                logger.info("    {} ({}): position unavailable", bodyName, naifId);
            }
        }
        for (var sc : eph.getSpacecraft()) {
            int naifId = sc.code();
            VectorIJK pos = eph.getHeliocentricPositionJ2000(naifId, currentEt);
            if (pos != null) {
                logger.info(
                        "    {} ({}): ({}, {}, {})",
                        BodyLookupService.formatName(naifId),
                        naifId,
                        pos.getI(),
                        pos.getJ(),
                        pos.getK());
            } else {
                logger.info("    {} ({}): position unavailable", sc.id().getName(), naifId);
            }
        }
        logger.info("=== end frustum diagnostic ===");
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
            return result;

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
            return result;

        } else {
            return raw;
        }
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

    // ── Inner class: per-instrument render state ──────────────────────────────

    private static final class FrustumEntry {

        final Instrument instrument;
        final FOVSpice fovSpice;
        /** Effective polygon bounds: raw for RECTANGLE/POLYGON, approximated for CIRCLE/ELLIPSE. */
        final List<UnwritableVectorIJK> effectiveBounds;

        final Geometry geometry;
        /** Direct reference to the position buffer for in-place vertex updates. */
        final FloatBuffer posBuffer;

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
        }

        /**
         * Recompute vertex positions in-place for the given apex and frame rotation.
         *
         * @param apexX camera-relative apex X in km
         * @param apexY camera-relative apex Y in km
         * @param apexZ camera-relative apex Z in km
         * @param j2000ToInstrument J2000 → instrument-frame rotation matrix; its transpose (= instrument → J2000) is
         *     applied to each bound vector via {@link RotationMatrixIJK#mtxv}
         */
        void updateMesh(double apexX, double apexY, double apexZ, RotationMatrixIJK j2000ToInstrument) {
            List<UnwritableVectorIJK> bounds = effectiveBounds;
            int n = bounds.size();

            // Transform bound vectors from instrument frame to J2000 and compute base vertices.
            // j2000ToInstrument.mtxv(v) = transpose(j2000ToInstrument) × v = instrumentToJ2000 × v
            float[] bx = new float[n];
            float[] by = new float[n];
            float[] bz = new float[n];
            VectorIJK scratch = new VectorIJK();

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
                    bx[i] = (float) (apexX + scratch.getI() * ext);
                    by[i] = (float) (apexY + scratch.getJ() * ext);
                    bz[i] = (float) (apexZ + scratch.getK() * ext);
                }
            }

            float ax = (float) apexX;
            float ay = (float) apexY;
            float az = (float) apexZ;

            posBuffer.rewind();

            // Side faces: n triangles (apex → base[i] → base[(i+1)%n])
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                posBuffer.put(ax).put(ay).put(az);
                posBuffer.put(bx[i]).put(by[i]).put(bz[i]);
                posBuffer.put(bx[j]).put(by[j]).put(bz[j]);
            }

            // Base cap: fan from base[0] — (n−2) triangles
            for (int i = 1; i < n - 1; i++) {
                posBuffer.put(bx[0]).put(by[0]).put(bz[0]);
                posBuffer.put(bx[i]).put(by[i]).put(bz[i]);
                posBuffer.put(bx[i + 1]).put(by[i + 1]).put(bz[i + 1]);
            }

            posBuffer.rewind();
            geometry.getMesh().getBuffer(VertexBuffer.Type.Position).updateData(posBuffer);
            geometry.getMesh().updateBound();
        }
    }
}
