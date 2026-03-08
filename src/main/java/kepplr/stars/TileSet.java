package kepplr.stars;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

/**
 * A class that captures the results of a query to a <code>TiledStarCatalog</code> for stars.
 *
 * <p>This class exists to allow the user of a <code>TiledStarCatalog</code> to have control over the allocation of
 * memory to capture the results of the query. This set supports the ability to provide an additional layer of filtering
 * beyond whatever filters may be enabled in the tiled catalog itself. However, requests for the actual set of tiles
 * returned by the catalog will not have this additional filter applied. Consider it as retrieving the raw results of
 * the query.
 *
 * <p>There are two standard usages of this class with regards to generics. First, querying a catalog of a specific type
 * of stars:
 *
 * <pre>
 * // Assuming we already have a TiledStarCatalog declared thusly:
 * TiledStarCatalog&lt;MyStar&gt; catalog;
 *
 * // Queries can be made in this way:
 * TileSet&lt;MyStar&gt; set = new TileSet&lt;MyStar&gt;();
 * catalog.lookup(myVector, myHalfAngle, set);
 * </pre>
 *
 * <p>Or if we have a catalog of unknown type:
 *
 * <p>
 *
 * <pre>
 * // Assuming we already have a TiledStarCatalog declared thusly:
 * TiledStarCatalog&lt;?&gt; catalog;
 *
 * // Queries can be made in this way:
 * TileSet&lt;Star&gt; set = new TileSet&lt;Star&gt;();
 * catalog.lookup(myVector, myHalfAngle, set);
 * </pre>
 *
 * @author F.S.Turner
 * @param <S> the type of star captured by the <code>TileSet</code>
 * @see TiledStarCatalog
 */
public class TileSet<S extends Star> implements Iterable<S> {

    /** The set of tiles containing the results of the last successful query to a tiled catalog. */
    private final Set<StarTile<? extends S>> tileset;

    private final Predicate<? super S> filter;

    /** Constructs a basic tile set with no additional filtering. */
    public TileSet() {
        /*
         * Can not use a TreeSet here, because the star tiles are not required to implement Comparable.
         */
        this.tileset = Sets.newHashSet();

        /*
         * Set the filter to null, as this is a simple way to indicate that no filtering is to be
         * performed.
         */
        this.filter = null;
    }

    /**
     * Constructs a tile set that applies the supplied filter when iterating over the tiled catalog query results.
     *
     * @param filter the filter to apply to stars returned by the query
     */
    public TileSet(Predicate<? super S> filter) {
        /*
         * Can not use a TreeSet here, because the star tiles are not required to implement Comparable.
         */
        this.tileset = Sets.newHashSet();
        this.filter = filter;
    }

    /**
     * Retrieves an unmodifiable reference to the internal set of tiles containing the results of the last query.
     *
     * <p>TODO: This method should probably be removed, is it really necessary??
     *
     * @return the <b>raw</b>, unfiltered tiles
     */
    public Set<StarTile<? extends S>> getTiles() {
        return Collections.unmodifiableSet(tileset);
    }

    /**
     * Method used by a tiled star catalog to add star tiles to the set.
     *
     * @param tile the tile to add to the set.
     */
    public void add(StarTile<? extends S> tile) {
        tileset.add(tile);
    }

    /** Method used by a tiled star catalog to clear the existing tiles out of the set. */
    public void clearTiles() {
        tileset.clear();
    }

    /**
     * Creates an iterator over the entire set of tiles, applying any filter supplied to the constructor of the tile
     * set.
     */
    @Override
    public Iterator<S> iterator() {
        if (filter == null) {
            return Iterables.concat(tileset).iterator();
        }
        return Iterators.filter(Iterables.concat(tileset).iterator(), filter);
    }
}
