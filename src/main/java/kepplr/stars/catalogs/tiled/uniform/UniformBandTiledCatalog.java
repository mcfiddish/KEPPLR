package kepplr.stars.catalogs.tiled.uniform;

import com.google.common.collect.ImmutableList;
import kepplr.stars.Star;
import kepplr.stars.StarTile;
import kepplr.stars.TileSet;
import kepplr.stars.TiledStarCatalog;
import picante.math.vectorspace.UnwritableVectorIJK;

/**
 * Implementation of the {@link TiledStarCatalog} interface that utilizes a uniform banded tiling scheme.
 *
 * @author F.S.Turner
 * @param <S> type of the star for which the catalog is built
 */
public class UniformBandTiledCatalog<S extends Star> implements TiledStarCatalog<S>, TileReceiver<TileSet<? super S>> {

    private final UniformTileLocator<TileSet<? super S>> locator;

    /**
     * While this may look like absolute chicken scratch, it effectively should be read: ImmutableList of producer of
     * ImmutableList of producer of StarTile of producer of S, which is the bare minimum of what is required by this
     * class to provide tiles to a user.
     */
    private final ImmutableList<? extends ImmutableList<? extends StarTile<? extends S>>> tiles;

    /**
     * Constructs a uniform banded tiled catalog.
     *
     * @param tiles an array of array of tiles of type S.
     * @param locator a tile locator compatible with tiles
     */
    public UniformBandTiledCatalog(
            ImmutableList<? extends ImmutableList<? extends StarTile<? extends S>>> tiles,
            UniformTileLocator<TileSet<? super S>> locator) {
        this.locator = locator;
        this.tiles = tiles;
    }

    @Override
    public void lookup(UnwritableVectorIJK location, double coneHalfAngle, TileSet<? super S> result) {
        result.clearTiles();
        locator.query(location, coneHalfAngle, this, result);
    }

    /**
     * {@inheritDoc} This method exists to allow the tiled catalog to take advantage of a generic sky tiling algorithm.
     */
    @Override
    public void add(int band, int tile, TileSet<? super S> recipient) {
        recipient.add(tiles.get(band).get(tile));
    }
}
