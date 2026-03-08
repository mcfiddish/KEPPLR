package kepplr.stars;

import picante.math.intervals.UnwritableInterval;

/**
 * An interface describing a tile of stars which contains all the stars of interest in a particular region of the sky.
 *
 * <p>This class exists as an interface, rather than a concrete class backed by some data structure to hold stars of a
 * particular type, to give flexibility to the catalog implementors with regards to lazy loading of tile content. For
 * massive catalogs, it is unlikely that a particular user desires the entire catalog to be pulled into memory at once.
 * So this interface gives catalog builders a means to create tiles that utilize weak references, etc. Catalog
 * implementors who go down this path will likely want to have their catalogs implement the tile generator interface,
 * and notify consumers of that fact through their reported features.
 *
 * @author F.S.Turner
 * @param <S> the type of star captured within a tile
 */
public interface StarTile<S extends Star> extends Iterable<S> {

    /**
     * Retrieves the declination range covered by the tile. Note: implementors may opt to return a reference to an
     * internal field or create a new interval each time this method is invoked.
     *
     * @return an unwritable interval defining the declination range. Acceptable ranges must lie between [-PI/2, PI/2].
     */
    public UnwritableInterval getDeclinationRange();

    /**
     * Retrieves the right ascension range covered by the tile. Note implementors may opt to return a reference to an
     * internal field or create a new interval each time this method is invoked.
     *
     * @return an unwritable interval defining the right ascension range. Acceptable ranges must lie between [0, 2*PI].
     */
    public UnwritableInterval getRightAscensionRange();
}
