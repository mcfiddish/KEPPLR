package kepplr.stars.services;

import kepplr.stars.Star;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.CelestialFrames;

/**
 * Service interface for computing location of the provided star.
 *
 * <p>Catalogs should implement at least one provider of this interface that functions for all stars. Note: not all
 * stars may have proper motion location estimates available, etc; so there may be calculators that do not support all
 * stars in a catalog.
 *
 * <p>While not enforced with software, the frame in which the locations should be expressed is
 * {@link CelestialFrames#J2000}.
 *
 * @author F.S.Turner
 */
public interface LocationCalculator {

    /**
     * Does this location calculator apply to the supplied star?
     *
     * @param star the star of interest
     * @return true if the calculator instance can provide a location for the star, false otherwise. Note: if the
     *     supplied star is not of the appropriate type, this method will return false and <b>not</b> throw a
     *     {@link ClassCastException}.
     */
    boolean hasLocation(Star star);

    /**
     * Computes the position of the requested star in the supplied frame.
     *
     * @param star the star of interest.
     * @param et
     * @param buffer
     * @return
     * @throws ClassCastException if the supplied star type is not supported by the implementation
     * @throws IllegalArgumentException if the supplied star is not supported by the calculator
     */
    VectorIJK evaluateLocation(Star star, double et, VectorIJK buffer);
}
