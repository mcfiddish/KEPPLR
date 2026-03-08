package kepplr.stars;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import java.util.Iterator;
import picante.math.intervals.UnwritableInterval;

/**
 * Static utility methods pertaining to the creation and use of {@link StarTile}
 *
 * @author F.S.Turner
 */
public class StarTiles {

    private StarTiles() {}

    /**
     * Creates a tile that caches the provided list of stars with the given RA/DEC range.
     *
     * <p>Note: no checking is performed to validate the the supplied stars are within the provided range. It is assumed
     * that the caller has done this externally with whatever criteria they are using for tiling.
     *
     * @param raRange right ascension range of the tile
     * @param decRange declination range of the tile
     * @param stars the stars to cache
     * @return a newly created StarTile that holds copies of the ranges and stars.
     */
    public static <S extends Star> StarTile<S> createCachedTile(
            final UnwritableInterval raRange, final UnwritableInterval decRange, final Iterable<S> stars) {

        return new StarTile<S>() {

            private final UnwritableInterval rRange = UnwritableInterval.copyOf(raRange);
            private final UnwritableInterval dRange = UnwritableInterval.copyOf(decRange);
            private final ImmutableList<S> s = ImmutableList.copyOf(stars);

            @Override
            public UnwritableInterval getDeclinationRange() {
                return dRange;
            }

            @Override
            public UnwritableInterval getRightAscensionRange() {
                return rRange;
            }

            @Override
            public Iterator<S> iterator() {
                return s.iterator();
            }
        };
    }

    /**
     * Creates a filtered view of the supplied tile.
     *
     * @param tile the tile to filter
     * @param filter the filter to apply
     * @return a newly created tile that is a filtered view of the supplied tile
     */
    public static <S extends Star> StarTile<S> filter(final StarTile<S> tile, final Predicate<? super S> filter) {
        return new StarTile<S>() {

            @Override
            public UnwritableInterval getDeclinationRange() {
                return tile.getDeclinationRange();
            }

            @Override
            public UnwritableInterval getRightAscensionRange() {
                return tile.getRightAscensionRange();
            }

            @Override
            public Iterator<S> iterator() {
                return Iterators.filter(tile.iterator(), filter);
            }
        };
    }
}
