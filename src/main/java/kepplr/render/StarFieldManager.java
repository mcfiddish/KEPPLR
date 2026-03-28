package kepplr.render;

import com.jme3.asset.AssetManager;
import com.jme3.scene.Node;
import kepplr.stars.Star;
import kepplr.stars.StarCatalog;
import kepplr.state.SimulationState;

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
    private final StarFieldRenderer renderer;

    /**
     * Simulation state; read each frame on the JME render thread for the active render quality (§9.4).
     *
     * <p>The magnitude cutoff is derived from {@code state.renderQualityProperty().get().starMagnitudeCutoff()} rather
     * than from a settable field, so quality changes propagate automatically without direct method calls into this
     * manager (CLAUDE.md Rule 2).
     */
    private final SimulationState state;

    /**
     * @param farNode far frustum root node; star geometry is attached here
     * @param assetManager JME asset manager for material creation
     * @param state simulation state; read each frame for render quality (§9.4)
     */
    public StarFieldManager(Node farNode, AssetManager assetManager, SimulationState state) {
        this.renderer = new StarFieldRenderer(farNode, assetManager);
        this.state = state;
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
     * Update the star field for the current simulation time.
     *
     * <p>The magnitude cutoff is read each frame from {@code state.renderQualityProperty().get()} so quality changes
     * propagate without requiring an external setter call (CLAUDE.md Rule 2 — state flows one direction).
     *
     * <p>If no catalog has been set, previously attached geometries are detached and no new ones are created.
     *
     * @param et current simulation ET (TDB seconds past J2000)
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km (reserved for future aberration correction;
     *     currently unused — stars are placed at fixed directions from the camera)
     */
    /** Detach all star geometry from the scene graph. */
    public void dispose() {
        renderer.detach();
    }

    public void update(double et, double[] cameraHelioJ2000) {
        if (catalog == null) {
            renderer.detach();
            return;
        }
        double cutoff = state.renderQualityProperty().get().starMagnitudeCutoff();
        renderer.update(catalog, cutoff, et);
    }
}
