package kepplr.stars.services;

import java.awt.*;
import kepplr.stars.Star;

/**
 * Service interface for estimating the color of the provided star.
 *
 * <p>This is an optional service, and may not be provided by catalogs in general.
 *
 * <p>There are many different reasons for providing an implementation of this interface. One may be to provide an
 * accurate model of the color of a given star. Others may be for display purposes and classification.
 *
 * @author F.S.Turner
 */
public interface ColorCalculator {

    /**
     * Does this color calculator apply to the supplied star?
     *
     * @param star the star of interest
     * @return true if the calculator instance has a color for the star, false otherwise. Note: if the supplied star is
     *     not of the appropriate type, this method will return false and <b>not</b> throw a {@link ClassCastException}.
     */
    boolean hasColor(Star star);

    /**
     * Computes the color of the supplied star.
     *
     * @param star the star for which a color is sought
     * @return the corresponding color
     * @throws ClassCastException if the supplied star type is not supported by the implementation
     * @throws IllegalArgumentException if the supplied star is not supported by the calculator
     */
    Color evaluateColor(Star star);

    /**
     * Is the color returned from this calculator an accurate representation of the actual color of the star?
     *
     * @param star the star of interest
     * @return true if the calculator instance has an accurate color for the star, false otherwise.
     * @throws ClassCastException if the supplied star type is not supported by the implementation
     * @throws IllegalArgumentException if the supplied star is not supported by the calculator
     */
    boolean isColorAccurate(Star star);
}
