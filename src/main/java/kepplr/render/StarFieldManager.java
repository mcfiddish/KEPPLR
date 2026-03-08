package kepplr.render;

import com.jme3.asset.AssetManager;
import com.jme3.scene.Node;
import kepplr.stars.Star;
import kepplr.stars.StarCatalog;
import kepplr.util.KepplrConstants;

/**
 * Manages the star field rendered by {@link StarFieldRenderer}.
 *
 * <p>Accepts a {@link StarCatalog} and delegates geometry construction to {@link StarFieldRenderer} on every
 * {@link #update} call. Geometry is rebuilt every frame because star scene positions are expressed in camera-relative
 * coordinates (floating origin). At ~9 096 stars split across ~10 magnitude buckets the per-frame cost is negligible.
 *
 * <p>A null catalog is valid; the star field is simply hidden until a catalog is provided.
 *
 * <p>All methods must be called on the JME render thread (CLAUDE.md Rule 4).
 */
public class StarFieldManager {

    private StarCatalog<? extends Star> catalog;
    private double magnitudeCutoff = KepplrConstants.STAR_DEFAULT_MAGNITUDE_CUTOFF;
    private final StarFieldRenderer renderer;

    /**
     * @param farNode far frustum root node; star geometry is attached here
     * @param assetManager JME asset manager for material creation
     */
    public StarFieldManager(Node farNode, AssetManager assetManager) {
        this.renderer = new StarFieldRenderer(farNode, assetManager);
    }

    /**
     * Set the star catalog to render.
     *
     * <p>Pass {@code null} to hide the star field.
     *
     * @param catalog catalog providing {@link Star} instances, or null to clear
     */
    public void setCatalog(StarCatalog<? extends Star> catalog) {
        this.catalog = catalog;
    }

    /**
     * Set the visual magnitude cutoff.
     *
     * <p>Stars with {@code vmag > cutoff} are excluded. NaN-magnitude stars are always excluded regardless of this
     * value.
     *
     * @param cutoff visual magnitude cutoff (inclusive upper bound)
     */
    public void setMagnitudeCutoff(double cutoff) {
        this.magnitudeCutoff = cutoff;
    }

    /**
     * Update the star field for the current simulation time.
     *
     * <p>If no catalog has been set, previously attached geometries are detached and no new ones are created.
     *
     * @param et current simulation ET (TDB seconds past J2000)
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km (reserved for future aberration correction;
     *     currently unused — stars are placed at fixed directions from the camera)
     */
    public void update(double et, double[] cameraHelioJ2000) {
        if (catalog == null) {
            renderer.detach();
            return;
        }
        renderer.update(catalog, magnitudeCutoff, et);
    }
}
