package kepplr.stars;

import com.google.common.base.Predicate;

/**
 * Service interface defining a collection of stars.
 *
 * @author F.S.Turner
 * @param <S> The class type of the stars supported by the catalog.
 */
public interface StarCatalog<S extends Star> extends Iterable<S> {

    /**
     * Creates an {@link Iterable} of stars subject to the supplied filter.
     *
     * @param filter the filter to apply in the iteration. If the filter does not apply to a particular star, then it is
     *     left out of the iteration.
     * @return The filter incorporated iterable. Note, it is left up to the implementor to decide if this iterable
     *     treats the filter as a view or if it internally caches results. The implementation should specify what, if
     *     any, caching it performs.
     */
    Iterable<S> filter(Predicate<? super S> filter);

    /**
     * Retrieves a star with the supplied ID.
     *
     * @param id the ID of interest
     * @return the star associated with id
     * @throws StarCatalogLookupException if id is not available in the supplied catalog
     */
    S getStar(String id);
}
