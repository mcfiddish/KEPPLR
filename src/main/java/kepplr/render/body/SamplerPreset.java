package kepplr.render.body;

import com.jme3.texture.Texture;

/**
 * Texture sampler presets applied to GLB shape model textures after loading.
 *
 * <p>Each preset defines the min filter, mag filter, and anisotropic filtering level applied to every texture found in
 * a loaded GLB Spatial. Presets are applied immediately after {@code assetManager.loadModel()} returns.
 *
 * <p>Algorithm derived from prototype: {@code GlbModelViewer.SamplerPreset} — reimplemented for new architecture.
 */
public enum SamplerPreset {

    /**
     * Default quality preset: trilinear min filter, bilinear mag filter, anisotropy 8.
     *
     * <p>Used for all shape models in normal rendering. Provides good texture quality at an acceptable performance
     * cost.
     */
    QUALITY_DEFAULT("QualityDefault", Texture.MinFilter.Trilinear, Texture.MagFilter.Bilinear, 8),

    /**
     * Debug preset: bilinear min filter with no mipmaps, anisotropy disabled.
     *
     * <p>Useful for diagnosing mipmap-related artifacts.
     */
    NO_MIPMAPS_DEBUG("NoMipmapsDebug", Texture.MinFilter.BilinearNoMipMaps, Texture.MagFilter.Bilinear, 0),

    /**
     * Debug preset: nearest-neighbor filtering, anisotropy disabled.
     *
     * <p>Useful for inspecting raw texel boundaries.
     */
    NEAREST_DEBUG("NearestDebug", Texture.MinFilter.NearestNoMipMaps, Texture.MagFilter.Nearest, 0);

    final String label;
    final Texture.MinFilter minFilter;
    final Texture.MagFilter magFilter;
    final int anisotropy;

    SamplerPreset(String label, Texture.MinFilter minFilter, Texture.MagFilter magFilter, int anisotropy) {
        this.label = label;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.anisotropy = anisotropy;
    }
}
