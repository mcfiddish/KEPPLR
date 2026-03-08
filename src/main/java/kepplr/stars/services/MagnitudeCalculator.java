package kepplr.stars.services;

import kepplr.stars.Star;

/**
 * Service interface for computing the magnitude of the provided star.
 *
 * <p>Catalogs should implement at least one provider of this interface, if they have magnitudes available. Note: not
 * all stars may have all magnitudes; so the {@link MagnitudeCalculator#hasMagnitude(Star)} method exists to provide a
 * query mechanism to the consumer.
 *
 * @author F.S.Turner
 */
public interface MagnitudeCalculator {

    /**
     * Does this magnitude calculator apply to the supplied star?
     *
     * @param star the star of interest
     * @return true if the calculator instance has a magnitude for the star, false otherwise. Note: if the supplied star
     *     is not of the appropriate type, this method will return false and <b>not</b> throw a
     *     {@link ClassCastException}.
     */
    boolean hasMagnitude(Star star);

    /**
     * Computes the magnitude of the supplied star.
     *
     * @param star the star for which a magnitude is sought
     * @return the corresponding magnitude of interest
     * @throws ClassCastException if the supplied star type is not supported by the implementation
     * @throws IllegalArgumentException if the supplied star is not supported by the calculator
     */
    double evaluateMagnitude(Star star);
}
