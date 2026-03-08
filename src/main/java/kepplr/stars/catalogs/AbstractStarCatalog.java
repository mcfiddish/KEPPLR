package kepplr.stars.catalogs;

import com.google.common.base.Predicates;
import java.util.Iterator;
import kepplr.stars.Star;
import kepplr.stars.StarCatalog;

/**
 * Abstract class that provides any of the methods on the {@link StarCatalog} interface that could be implemented with
 * the more primitive methods.
 *
 * @author F.S.Turner
 * @param <S> Star class of interest
 */
public abstract class AbstractStarCatalog<S extends Star> implements StarCatalog<S> {

    @Override
    public Iterator<S> iterator() {
        return filter(Predicates.alwaysTrue()).iterator();
    }
}
