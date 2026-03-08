package kepplr.stars;

import picante.math.vectorspace.VectorIJK;

/**
 * Interface that an individual star must implement.
 *
 * <p>Implementation Notes:
 *
 * <p>Equals and Hashcode must be implemented properly to use these classes.
 *
 * <p>The {@link String} ID assigned to each star should be unique across the catalog and other catalogs. It is
 * recommended that each catalog is prefixed with a specific catalog identifier that is unique, but is not explicitly
 * required.
 *
 * @author F.S.Turner
 */
public interface Star {

    /**
     * Retrieve the identifier for the star
     *
     * @return a unique, catalog specific identifier for the star
     */
    public String getID();

    /**
     * Returns the magnitude for the star as configured when the catalog is built.
     *
     * @return the canonical magnitude for the star
     */
    public double getMagnitude();

    /**
     * Evaluates the location of the star as configured when the catalog is built.
     *
     * @param et TDB seconds past J2000.0 at which to evaluate the stars position
     * @param buffer buffer to receive the resultant position
     * @return reference to buffer for convenience
     */
    public VectorIJK getLocation(double et, VectorIJK buffer);

    /**
     * {@inheritDoc}
     *
     * <p>Implementors of this interface must define this method such that it is compatible with
     * {@link Star#hashCode()}. Generic data structures and services will store stars in maps, etc.
     */
    @Override
    public boolean equals(Object star);

    /**
     * {@inheritDoc}
     *
     * <p>Implementors of this interface must define this method such that it is compatible with
     * {@link Star#equals(Object)}. Generic data structures and services will store stars in maps, etc.
     */
    @Override
    public int hashCode();
}
