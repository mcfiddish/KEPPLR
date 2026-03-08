package kepplr.stars.catalogs.tiled.uniform;

/**
 * Interface designed to receive the results of a tile based query, one tile at a time. It is utilized by the
 * TileLocator to populate the results of a tile query.
 *
 * @see UniformTileLocator
 * @author F.S.Turner
 * @param <R> the type of the recipient of the tile. Note: In general this does not need to be provided. An
 *     implementation that does not require this, can simply declare this as Object and pass null in for recipient and
 *     ignore that argument safely enough.
 */
public interface TileReceiver<R> {

    /**
     * Adds the specified tile to the recipient of the results.
     *
     * @param band the band index of the tile of interest
     * @param tile the tile index of the tile of interest
     * @param recipient the recipient of the tile
     */
    public void add(int band, int tile, R recipient);
}
