package kepplr.stars;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import kepplr.stars.catalogs.tiled.uniform.UniformBandTiledCatalog;
import kepplr.stars.catalogs.tiled.uniform.UniformBandedTiling;
import kepplr.stars.catalogs.tiled.uniform.UniformBandedTilingFactory;
import kepplr.stars.catalogs.tiled.uniform.UniformTileLocator;
import kepplr.stars.services.LocationCalculator;
import picante.math.coords.CoordConverters;
import picante.math.coords.LatitudinalVector;
import picante.math.vectorspace.UnwritableVectorIJK;
import picante.math.vectorspace.VectorIJK;

/**
 * Static utility methods pertaining to the manipulation and creation of tiled star catalogs.
 *
 * @author F.S.Turner
 */
public class TiledStarCatalogs {

    /** Static utility method collection, can't be constructed. */
    private TiledStarCatalogs() {}

    /**
     * Creates a memory based tiled catalog from a star catalog. All stars are read in from the catalog and references
     * to them are stored in the appropriate tiles.
     *
     * @param <S> the type of star utilized in the catalog
     * @param catalog the star catalog of interest
     * @param tileSize the size of the tile, in radians
     * @param referenceTime the time to pass into the {@link Star#getLocation(double, VectorIJK)} method when organizing
     *     the stars into their various tiles
     * @return a newly constructed tiled star catalog that retains hard references to each of the stars stored in
     *     catalog
     */
    public static <S extends Star> UniformBandTiledCatalog<S> createMemoryTiledCatalog(
            StarCatalog<S> catalog, LocationCalculator positionCalculator, double tileSize, double referenceTime) {
        return createMemoryTiledCatalog(catalog, Predicates.alwaysTrue(), positionCalculator, tileSize, referenceTime);
    }

    /**
     * Creates a memory based tiled catalog from a star catalog. All stars are read in from the catalog and references
     * to them are stored in the appropriate tiles if they are accepted by the supplied filter.
     *
     * @param <S> the type of star utilized in the catalog.
     * @param catalog the star catalog of interest
     * @param filter the star filter used to select or reject stars provided by catalog.
     * @param tileSize the size of the tile, in radians
     * @param referenceTime the time to pass into the {@link Star#getLocation(double, VectorIJK)} method when organizing
     *     the stars into their various tiles
     * @return a newly constructed tiled star catalog that retains hard references to each of the stars stored in
     *     catalog that are accepted by the filter.
     */
    public static <S extends Star> UniformBandTiledCatalog<S> createMemoryTiledCatalog(
            StarCatalog<S> catalog,
            Predicate<? super S> filter,
            LocationCalculator positionCalculator,
            double tileSize,
            double referenceTime) {

        /*
         * Create the tiling with the supplied tile size, and the array of array of tiles.
         */
        UniformBandedTiling tiling = UniformBandedTilingFactory.createSinusoidalTiling(tileSize);
        ArrayList<ArrayList<LinkedList<S>>> tiles = Lists.newArrayListWithCapacity(tiling.getNumberOfBands());

        /*
         * Build each individual tile, adding it to the tiles list.
         */
        for (int b = 0; b < tiling.getNumberOfBands(); b++) {
            ArrayList<LinkedList<S>> tileList = Lists.newArrayListWithCapacity(tiling.getNumberOfTiles(b));
            for (int t = 0; t < tiling.getNumberOfTiles(b); t++) {
                LinkedList<S> tile = Lists.newLinkedList();
                tileList.add(tile);
            }
            tiles.add(tileList);
        }

        Iterator<? extends S> iterator = catalog.filter(filter).iterator();

        VectorIJK vector = new VectorIJK();

        while (iterator.hasNext()) {
            S star = iterator.next();
            positionCalculator.evaluateLocation(star, referenceTime, vector);
            LatitudinalVector coord = CoordConverters.convertToLatitudinal(vector);
            double ra = coord.getLongitude();
            double dec = coord.getLatitude();
            int band = tiling.getBand(dec);
            int tile = tiling.getTile(band, ra);

            tiles.get(band).get(tile).add(star);
        }

        UniformTileLocator<TileSet<? super S>> locator = new UniformTileLocator<TileSet<? super S>>(tiling);

        /*
         * Convert the array lists of lists into immutable lists.
         */
        ImmutableList.Builder<ImmutableList<StarTile<S>>> builder = ImmutableList.builder();

        for (int b = 0; b < tiling.getNumberOfBands(); b++) {
            ImmutableList.Builder<StarTile<S>> listBuilder = ImmutableList.builder();
            for (int t = 0; t < tiling.getNumberOfTiles(b); t++) {
                listBuilder.add(StarTiles.createCachedTile(
                        tiling.getRightAscensionRange(b, t),
                        tiling.getDeclinationRange(b),
                        tiles.get(b).get(t)));
            }
            builder.add(listBuilder.build());
        }

        return new UniformBandTiledCatalog<S>(builder.build(), locator);
    }

    /**
     * Concatenates two tiled star catalogs together.
     *
     * @param a the first catalog
     * @param b the second catalog
     * @return a newly created TiledStarCatalog instance that queries both catalogs a and b and returns the tiles that
     *     satisfy the query from both catalogs.
     */
    public static <S extends Star> TiledStarCatalog<S> concat(
            final TiledStarCatalog<? extends S> a, final TiledStarCatalog<? extends S> b) {
        return new TiledStarCatalog<S>() {

            @Override
            public void lookup(UnwritableVectorIJK location, double coneHalfAngle, TileSet<? super S> result) {

                /*
                 * We need this to be thread-safe, so create the tile set we will actually be putting tiles
                 * into for each query. This will only be used temporarily to hold onto the tiles until we
                 * can place the resultant tiles into the supplied result buffer.
                 */
                TileSet<S> buffer = new TileSet<S>();
                a.lookup(location, coneHalfAngle, buffer);
                for (StarTile<? extends S> tile : buffer.getTiles()) {
                    result.add(tile);
                }
                b.lookup(location, coneHalfAngle, buffer);
                for (StarTile<? extends S> tile : buffer.getTiles()) {
                    result.add(tile);
                }
            }
        };
    }

    // public static <S extends Star> TiledStarCatalog<S> concat(
    // final TiledStarCatalog<? extends S>... catalogs) {
    // return new TiledStarCatalog<S>() {
    //
    // @Override
    // public void lookup(UnwritableVectorIJK location,
    // double coneHalfAngle, TileSet<? super S> result) {
    // TileSet<S> buffer = new TileSet<S>();
    //
    // for (int i = 0; i < catalogs.length; i++) {
    // catalogs[i].lookup(location, coneHalfAngle, buffer);
    // for (StarTile<? extends S> tile : buffer.getTiles()) {
    // result.add(tile);
    // }
    // }
    //
    // }
    // };
    // }

}
