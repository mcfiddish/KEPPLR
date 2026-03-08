package kepplr.render;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.util.BufferUtils;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import kepplr.stars.Star;
import kepplr.stars.StarCatalog;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.VectorIJK;

/**
 * Renders a {@link StarCatalog} as point sprites placed at a fixed distance inside the FAR viewport.
 *
 * <p>All stars are rendered in a single draw call using per-vertex buffers for position, size, and
 * color. A two-Gaussian core+halo GLSL fragment shader produces realistic star glows with additive
 * blending.
 *
 * <p>Must be called from the JME render thread (CLAUDE.md Rule 4).
 */
class StarFieldRenderer {

    private static final Logger logger = LogManager.getLogger(StarFieldRenderer.class);

    private final Node farNode;
    private final AssetManager assetManager;

    /** Single geometry attached to farNode in the previous update; null if nothing is attached. */
    private Geometry attached = null;

    StarFieldRenderer(Node farNode, AssetManager assetManager) {
        this.farNode = farNode;
        this.assetManager = assetManager;
    }

    /**
     * Rebuild the star field geometry from the catalog for the given simulation time.
     *
     * <p>All previously attached geometries are detached first, then a new one is built and attached.
     * All qualifying stars are placed in a single mesh with per-vertex size and color data, rendered
     * with a two-Gaussian core+halo shader and additive blending.
     *
     * @param catalog star catalog to render
     * @param magnitudeCutoff stars dimmer than this visual magnitude are excluded
     * @param et simulation ET (TDB seconds past J2000); passed to {@link Star#getLocation}
     */
    void update(StarCatalog<? extends Star> catalog, double magnitudeCutoff, double et) {
        // Detach previously attached geometry
        if (attached != null) {
            attached.removeFromParent();
            attached = null;
        }

        // Collect qualifying stars
        List<Star> qualifying = new ArrayList<>();
        VectorIJK buf = new VectorIJK();
        for (Star star : catalog) {
            double vmag = star.getMagnitude();
            if (!Double.isNaN(vmag) && vmag <= magnitudeCutoff) {
                qualifying.add(star);
            }
        }

        if (qualifying.isEmpty()) {
            return;
        }

        int count = qualifying.size();

        // Allocate direct buffers — BufferUtils.createFloatBuffer() is required for GPU upload;
        // FloatBuffer.allocate() produces a heap buffer that causes a SIGSEGV during GPU upload.
        FloatBuffer positions = BufferUtils.createFloatBuffer(count * 3);
        FloatBuffer texCoords = BufferUtils.createFloatBuffer(count * 2); // [pointSizePx, haloRadiusPx]
        FloatBuffer colors    = BufferUtils.createFloatBuffer(count * 4); // [coreBrightness×3, haloStrength]

        for (Star star : qualifying) {
            double vmag = star.getMagnitude();
            VectorIJK dir = star.getLocation(et, buf);
            double scale = KepplrConstants.STAR_FIELD_DISTANCE_KM / dir.getLength();
            positions.put((float) (dir.getI() * scale));
            positions.put((float) (dir.getJ() * scale));
            positions.put((float) (dir.getK() * scale));

            // Flux-based size and intensity formulas
            double fluxRatio = Math.pow(10.0, -0.4 * (vmag - KepplrConstants.STAR_MAG_REF));
            double log2flux  = Math.log(fluxRatio) / Math.log(2.0);

            float coreBrightness = (float) clamp(1.0 - Math.exp(-KepplrConstants.STAR_CORE_K * fluxRatio), 0.0, 1.0);
            float haloStrength   = (float) clamp(log2flux / KepplrConstants.STAR_HALO_SCALE, 0.0, 1.0);
            float pointSizePx    = (float) clamp(KepplrConstants.STAR_POINT_BASE_PX + KepplrConstants.STAR_POINT_SLOPE * log2flux,
                                                 1.0, KepplrConstants.STAR_POINT_MAX_PX);
            float haloRadiusPx   = (float) clamp(KepplrConstants.STAR_HALO_BASE_PX + KepplrConstants.STAR_HALO_SLOPE * log2flux,
                                                 0.0, KepplrConstants.STAR_HALO_MAX_PX);

            texCoords.put(pointSizePx);
            texCoords.put(haloRadiusPx);

            colors.put(coreBrightness);
            colors.put(coreBrightness);
            colors.put(coreBrightness);
            colors.put(haloStrength);
        }

        positions.flip();
        texCoords.flip();
        colors.flip();

        Mesh mesh = new Mesh();
        mesh.setMode(Mesh.Mode.Points);
        mesh.setBuffer(Type.Position, 3, positions);
        mesh.setBuffer(Type.TexCoord, 2, texCoords);
        mesh.setBuffer(Type.Color,    4, colors);
        mesh.updateBound();

        Material mat = new Material(assetManager, "kepplr/shaders/Star.j3md");

        attached = new Geometry("stars", mesh);
        attached.setMaterial(mat);
        farNode.attachChild(attached);

        logger.debug("StarFieldRenderer: {} stars, 1 draw call", count);
    }

    /** Detach the currently attached star geometry from the scene graph. */
    void detach() {
        if (attached != null) {
            attached.removeFromParent();
            attached = null;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
