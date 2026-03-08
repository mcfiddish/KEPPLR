package kepplr.render;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.util.BufferUtils;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import kepplr.stars.Star;
import kepplr.stars.StarCatalog;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.VectorIJK;

/**
 * Renders a {@link StarCatalog} as greyscale point sprites placed at a fixed distance inside the FAR viewport.
 *
 * <p>Stars are bucketed by integer visual magnitude so that all stars in a given magnitude bin share a single
 * {@link Mesh} with a uniform point size and intensity. This reduces per-frame draw call count to O(magnitude-range)
 * rather than O(star-count).
 *
 * <p>Must be called from the JME render thread (CLAUDE.md Rule 4).
 */
class StarFieldRenderer {

    private static final Logger logger = LogManager.getLogger(StarFieldRenderer.class);

    private final Node farNode;
    private final AssetManager assetManager;

    /** Geometries attached to farNode in the previous update; removed at the start of each update. */
    private final List<Geometry> attached = new ArrayList<>();

    StarFieldRenderer(Node farNode, AssetManager assetManager) {
        this.farNode = farNode;
        this.assetManager = assetManager;
    }

    /**
     * Rebuild the star field geometry from the catalog for the given simulation time.
     *
     * <p>All previously attached geometries are detached first, then new ones are built and attached.
     *
     * @param catalog star catalog to render
     * @param magnitudeCutoff stars dimmer than this visual magnitude are excluded
     * @param et simulation ET (TDB seconds past J2000); passed to {@link Star#getLocation}
     */
    void update(StarCatalog<? extends Star> catalog, double magnitudeCutoff, double et) {
        // Remove previously attached geometry
        for (Geometry g : attached) {
            g.removeFromParent();
        }
        attached.clear();

        // Bucket stars by integer floor of magnitude
        TreeMap<Integer, List<Star>> buckets = new TreeMap<>();
        VectorIJK buf = new VectorIJK();
        for (Star star : catalog) {
            double vmag = star.getMagnitude();
            if (Double.isNaN(vmag) || vmag > magnitudeCutoff) {
                continue;
            }
            int key = (int) Math.floor(vmag);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(star);
        }

        // Build one mesh per bucket
        double brightVmag = KepplrConstants.STAR_FIELD_BRIGHT_VMAG;
        double range = magnitudeCutoff - brightVmag;

        for (Map.Entry<Integer, List<Star>> entry : buckets.entrySet()) {
            int bucketKey = entry.getKey();
            List<Star> stars = entry.getValue();

            double bucketMidVmag = bucketKey + 0.5;
            double t = clamp((bucketMidVmag - brightVmag) / range, 0.0, 1.0);
            float pointSize = KepplrConstants.STAR_POINT_SIZE_BRIGHT_PX
                    + (float) t * (KepplrConstants.STAR_POINT_SIZE_FAINT_PX - KepplrConstants.STAR_POINT_SIZE_BRIGHT_PX);
            float intensity = (float) clamp(1.0 - t, 0.0, 1.0);

            // BufferUtils.createFloatBuffer() allocates a direct buffer required by JME's OpenGL renderer.
            // FloatBuffer.allocate() produces a heap buffer that causes a SIGSEGV during GPU upload.
            FloatBuffer positions = BufferUtils.createFloatBuffer(stars.size() * 3);
            for (Star star : stars) {
                VectorIJK dir = star.getLocation(et, buf);
                double scale = KepplrConstants.STAR_FIELD_DISTANCE_KM / dir.getLength();
                positions.put((float) (dir.getI() * scale));
                positions.put((float) (dir.getJ() * scale));
                positions.put((float) (dir.getK() * scale));
            }
            positions.flip();

            // Mesh.setPointSize() was removed in JME 3.8; gl_PointSize is set via Star.j3md uniform instead
            Mesh mesh = new Mesh();
            mesh.setMode(Mesh.Mode.Points);
            mesh.setBuffer(Type.Position, 3, positions);
            mesh.updateBound();

            // Custom star shader: sets gl_PointSize via a uniform (Mesh.setPointSize was removed in JME 3.8)
            Material mat = new Material(assetManager, "kepplr/shaders/Star.j3md");
            mat.setColor("Color", new ColorRGBA(intensity, intensity, intensity, 1.0f));
            mat.setFloat("PointSize", pointSize);

            Geometry geom = new Geometry("stars_bucket_" + bucketKey, mesh);
            geom.setMaterial(mat);
            farNode.attachChild(geom);
            attached.add(geom);
        }

        logger.debug("StarFieldRenderer: {} buckets, {} total stars attached", buckets.size(),
                attached.stream().mapToInt(g -> g.getMesh().getVertexCount()).sum());
    }

    /** Detach all currently attached star geometries from the scene graph. */
    void detach() {
        for (Geometry g : attached) {
            g.removeFromParent();
        }
        attached.clear();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
