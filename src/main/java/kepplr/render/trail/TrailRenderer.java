package kepplr.render.trail;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kepplr.render.frustum.FrustumLayer;

/**
 * Renders one body's orbital trail as line segments in the JME scene graph.
 *
 * <p>Trail segments are assigned to the appropriate frustum layer by passing the camera-relative midpoint distance of
 * each segment to {@link FrustumLayer#assign(double, double)} (REDESIGN.md §8.3). One {@link Geometry} per occupied
 * frustum layer is attached to the corresponding layer root node.
 *
 * <p>Color is derived from the body's NAIF ID via a golden-ratio hash — deterministic, visually well-distributed, and
 * data-driven (no switch/case over NAIF IDs).
 *
 * <p>All methods must be called on the JME render thread (CLAUDE.md Rule 4).
 */
class TrailRenderer {

    /** Golden ratio conjugate for well-distributed hue generation. */
    private static final double GOLDEN_RATIO_CONJUGATE = 0.618033988749895;

    private final AssetManager assetManager;
    private final ColorRGBA color;
    private final Map<FrustumLayer, Node> layerNodes;

    /**
     * Fraction of the trail (measured from the newest end) beyond which alpha is zero.
     *
     * <p>Value 0.9 means the trail fades to fully transparent 90% of the way back in time. The oldest 10% of samples
     * are drawn with alpha = 0 and contribute no visible pixels.
     */
    private static final float FADE_CUTOFF = 0.9f;

    /** Currently attached geometries, keyed by layer. Cleared and rebuilt on each update. */
    private final Map<FrustumLayer, Geometry> attached = new EnumMap<>(FrustumLayer.class);

    /**
     * @param naifId NAIF ID of the body whose trail this renderer represents
     * @param assetManager JME asset manager for material creation
     * @param nearNode near frustum root node
     * @param midNode mid frustum root node
     * @param farNode far frustum root node
     */
    TrailRenderer(int naifId, AssetManager assetManager, Node nearNode, Node midNode, Node farNode) {
        this.assetManager = assetManager;
        this.color = naifIdToColor(naifId);
        this.layerNodes = Map.of(
                FrustumLayer.NEAR, nearNode,
                FrustumLayer.MID, midNode,
                FrustumLayer.FAR, farNode);
    }

    /**
     * Rebuild trail geometry from the given heliocentric sample positions.
     *
     * <p>Converts each position to camera-relative coordinates (floating origin), assigns each segment to a frustum
     * layer, and attaches the resulting geometries to the layer nodes. Previously attached geometries are detached
     * before rebuilding.
     *
     * <p>The list is expected to be time-ordered oldest-first (as produced by {@link TrailSampler#sample}). Alpha is
     * 1.0 at the newest end and fades linearly to 0.0 at {@link #FADE_CUTOFF} of the way back in time. Segments beyond
     * that fraction are emitted with alpha 0 and contribute no visible pixels.
     *
     * @param samples heliocentric J2000 positions in km, oldest-first (each element is {@code double[3]})
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km (length ≥ 3)
     * @param sampleOffsetOrNull offset to add to each sample position before rendering (km), or {@code null} for no
     *     offset; used to shift satellite trails by the live barycenter drift
     */
    void update(List<double[]> samples, double[] cameraHelioJ2000, double[] sampleOffsetOrNull) {
        detachAll();

        if (samples == null || samples.size() < 2) {
            return;
        }

        double ox = sampleOffsetOrNull != null ? sampleOffsetOrNull[0] : 0.0;
        double oy = sampleOffsetOrNull != null ? sampleOffsetOrNull[1] : 0.0;
        double oz = sampleOffsetOrNull != null ? sampleOffsetOrNull[2] : 0.0;

        int n = samples.size();
        // ET range: samples[0] = oldest, samples[n-1] = newest (centerEt).
        double oldestEt = samples.get(0)[3];
        double newestEt = samples.get(n - 1)[3];

        // Collect line-segment vertex pairs and per-vertex alphas per frustum layer.
        // Each segment is stored as two consecutive vertices (v_start, v_end) so the index buffer
        // can pair them directly in Mesh.Mode.Lines without a strip connection across layers.
        Map<FrustumLayer, List<Vector3f>> layerPairs = new EnumMap<>(FrustumLayer.class);
        Map<FrustumLayer, List<Float>> layerAlphas = new EnumMap<>(FrustumLayer.class);
        for (FrustumLayer layer : FrustumLayer.values()) {
            layerPairs.put(layer, new ArrayList<>());
            layerAlphas.put(layer, new ArrayList<>());
        }

        for (int i = 0; i < n - 1; i++) {
            double[] p0 = samples.get(i);
            double[] p1 = samples.get(i + 1);

            double midX = (p0[0] + ox + p1[0] + ox) / 2.0 - cameraHelioJ2000[0];
            double midY = (p0[1] + oy + p1[1] + oy) / 2.0 - cameraHelioJ2000[1];
            double midZ = (p0[2] + oz + p1[2] + oz) / 2.0 - cameraHelioJ2000[2];
            double dist = Math.sqrt(midX * midX + midY * midY + midZ * midZ);

            FrustumLayer layer = FrustumLayer.assign(dist, 0.0);
            layerPairs.get(layer).add(toScene(p0, ox, oy, oz, cameraHelioJ2000));
            layerPairs.get(layer).add(toScene(p1, ox, oy, oz, cameraHelioJ2000));
            layerAlphas.get(layer).add(vertexAlpha(samples.get(i)[3], newestEt, oldestEt));
            layerAlphas.get(layer).add(vertexAlpha(samples.get(i + 1)[3], newestEt, oldestEt));
        }

        // Build and attach one Geometry per layer that has at least one segment.
        for (FrustumLayer layer : FrustumLayer.values()) {
            List<Vector3f> pairs = layerPairs.get(layer);
            if (pairs.isEmpty()) {
                continue;
            }

            Vector3f[] verts = pairs.toArray(new Vector3f[0]);
            int[] indices = new int[verts.length];
            for (int i = 0; i < verts.length; i++) {
                indices[i] = i;
            }

            // Build per-vertex color buffer: RGB from trail color, A from fade calculation.
            List<Float> alphas = layerAlphas.get(layer);
            float[] colorData = new float[verts.length * 4];
            for (int i = 0; i < verts.length; i++) {
                colorData[i * 4] = color.r;
                colorData[i * 4 + 1] = color.g;
                colorData[i * 4 + 2] = color.b;
                colorData[i * 4 + 3] = alphas.get(i);
            }

            Mesh mesh = new Mesh();
            mesh.setMode(Mesh.Mode.Lines);
            mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(verts));
            mesh.setBuffer(VertexBuffer.Type.Color, 4, BufferUtils.createFloatBuffer(colorData));
            mesh.setBuffer(VertexBuffer.Type.Index, 2, BufferUtils.createIntBuffer(indices));
            mesh.updateBound();

            Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            mat.setBoolean("VertexColor", true);
            mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);

            Geometry geom = new Geometry("trail-" + layer.name(), mesh);
            geom.setMaterial(mat);
            geom.setQueueBucket(RenderQueue.Bucket.Transparent);

            layerNodes.get(layer).attachChild(geom);
            attached.put(layer, geom);
        }
    }

    /**
     * Compute the alpha for a sample at a given ET.
     *
     * <p>Alpha is 1.0 at {@code newestEt} and fades linearly to 0.0 at {@link #FADE_CUTOFF} of the total duration back
     * in time, based on actual elapsed time rather than sample index. This is correct for non-uniform (adaptive)
     * sampling where index spacing ≠ time spacing.
     *
     * @param sampleEt ET of this sample
     * @param newestEt ET of the newest sample (body's current position, alpha = 1.0)
     * @param oldestEt ET of the oldest sample (alpha = 0.0 at FADE_CUTOFF fraction)
     * @return alpha in [0, 1]
     */
    private static float vertexAlpha(double sampleEt, double newestEt, double oldestEt) {
        double totalDuration = newestEt - oldestEt;
        if (totalDuration <= 0.0) return 1.0f;
        // ageFraction: 0.0 = newest, 1.0 = oldest
        double ageFraction = (newestEt - sampleEt) / totalDuration;
        return (float) Math.max(0.0, 1.0 - ageFraction / FADE_CUTOFF);
    }

    /** Detach all trail geometry from scene graph nodes. */
    void detach() {
        detachAll();
    }

    private void detachAll() {
        for (Map.Entry<FrustumLayer, Geometry> entry : attached.entrySet()) {
            Node node = layerNodes.get(entry.getKey());
            if (node != null) {
                node.detachChild(entry.getValue());
            }
        }
        attached.clear();
    }

    /**
     * Convert a heliocentric J2000 position (plus offset) to camera-relative scene coordinates (floating origin).
     *
     * @param helioPos heliocentric J2000 position in km
     * @param ox x-component of offset in km (0 for planets)
     * @param oy y-component of offset in km (0 for planets)
     * @param oz z-component of offset in km (0 for planets)
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km
     * @return camera-relative position as a JME {@link Vector3f}
     */
    private static Vector3f toScene(double[] helioPos, double ox, double oy, double oz, double[] cameraHelioJ2000) {
        return new Vector3f(
                (float) (helioPos[0] + ox - cameraHelioJ2000[0]),
                (float) (helioPos[1] + oy - cameraHelioJ2000[1]),
                (float) (helioPos[2] + oz - cameraHelioJ2000[2]));
    }

    /**
     * Derive a trail color from a NAIF ID using a golden-ratio hash.
     *
     * <p>The golden-ratio conjugate (≈ 0.618…) distributes hues evenly across the color wheel regardless of the input
     * sequence, so nearby NAIF IDs receive visually distinct colors. Saturation = 0.8, brightness = 1.0, alpha = 0.9.
     *
     * @param naifId NAIF integer ID
     * @return a {@link ColorRGBA} unique to this ID
     */
    private static ColorRGBA naifIdToColor(int naifId) {
        double hue = (naifId * GOLDEN_RATIO_CONJUGATE) % 1.0;
        if (hue < 0.0) hue += 1.0;

        // HSB → RGB (S = 0.8, B = 1.0)
        float h = (float) hue;
        float s = 0.8f;
        float b = 1.0f;

        int hi = (int) (h * 6) % 6;
        float f = h * 6 - (int) (h * 6);
        float p = b * (1 - s);
        float q = b * (1 - f * s);
        float t = b * (1 - (1 - f) * s);

        float r, g, bv;
        switch (hi) {
            case 0 -> {
                r = b;
                g = t;
                bv = p;
            }
            case 1 -> {
                r = q;
                g = b;
                bv = p;
            }
            case 2 -> {
                r = p;
                g = b;
                bv = t;
            }
            case 3 -> {
                r = p;
                g = q;
                bv = b;
            }
            case 4 -> {
                r = t;
                g = p;
                bv = b;
            }
            default -> {
                r = b;
                g = p;
                bv = q;
            }
        }

        return new ColorRGBA(r, g, bv, 0.9f);
    }
}
