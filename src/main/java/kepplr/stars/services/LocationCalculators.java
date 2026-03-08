package kepplr.stars.services;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import kepplr.stars.Star;
import picante.math.vectorspace.UnwritableVectorIJK;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.CelestialBodies;
import picante.mechanics.StateVector;
import picante.mechanics.StateVectorFunction;
import picante.units.FundamentalPhysicalConstants;

/**
 * Class capturing utility methods, adapters and implementations of {@link LocationCalculator}.
 *
 * @author F.S.Turner
 */
public class LocationCalculators {

    /**
     * Creates a new {@link LocationCalculator} that applies a stellar aberration correction based on the state of the
     * supplied observer to the {@link Star#getLocation(double, VectorIJK)} method.
     *
     * <p>The {@link LocationCalculator#hasLocation(Star)} method will always return true for this calculator.
     *
     * @param observerRelativeToSSB a {@link StateVectorFunction} describing the position of an observer relative to
     *     {@link CelestialBodies#SOLAR_SYSTEM_BARYCENTER} in an inertial frame of the star catalog of interest.
     * @return newly created {@link LocationCalculator} that applies stellar aberration correction for the supplied
     *     observer's state.
     */
    public static LocationCalculator applyStellarAberrationCorrection(final StateVectorFunction observerRelativeToSSB) {

        checkNotNull(observerRelativeToSSB);

        return new LocationCalculator() {

            @Override
            public boolean hasLocation(@SuppressWarnings("unused") Star star) {
                return true;
            }

            @Override
            public VectorIJK evaluateLocation(Star star, double et, VectorIJK buffer) {
                VectorIJK uncorrectedPosition = star.getLocation(et, new VectorIJK());
                VectorIJK velocity =
                        observerRelativeToSSB.getState(et, new StateVector()).getVelocity();
                return evaluate(uncorrectedPosition, velocity, buffer);
            }
        };
    }

    public static LocationCalculator applyStellarAberrationCorrection(
            final LocationCalculator uncorrected, final StateVectorFunction observerRelativeToSSB) {

        checkNotNull(uncorrected);
        checkNotNull(observerRelativeToSSB);
        checkArgument(
                observerRelativeToSSB.getFrameID().isInertial(),
                "Stellar aberration corrections must be made in an inertial frame.");
        checkArgument(
                observerRelativeToSSB.getObserverID().equals(CelestialBodies.SOLAR_SYSTEM_BARYCENTER),
                "The observer's position must be relative to the solar system barycenter.");

        return new LocationCalculator() {

            @Override
            public boolean hasLocation(Star star) {
                return uncorrected.hasLocation(star);
            }

            @Override
            public VectorIJK evaluateLocation(Star star, double et, VectorIJK buffer) {
                VectorIJK uncorrectedLocation = uncorrected.evaluateLocation(star, et, new VectorIJK());
                VectorIJK velocity =
                        observerRelativeToSSB.getState(et, new StateVector()).getVelocity();
                return evaluate(uncorrectedLocation, velocity, buffer);
            }
        };
    }

    /**
     * The speed of light in kilometers per second. The standard definition of this constant is in meters per second, so
     * this just divides the value in the {@link FundamentalPhysicalConstants} by 1000.0.
     */
    private static final double SPEED_OF_LIGHT_IN_VAC =
            FundamentalPhysicalConstants.SPEED_OF_LIGHT_IN_VACUUM_M_per_SEC / (1000.0);

    /**
     * Computes the stellar aberration corrected position from an observer to an object of interest.
     *
     * @param posFromObserverToObject the position vector that points from an observer to the object being observed
     * @param velOfObserverWRTSSB the velocity vector of the observer relative to the solar system barycenter
     * @return the apparent position vector that points from an observer to the apparent position of the object being
     *     observed, where the deviation of this vector from the input vector differs do to stellar aberration
     */
    static VectorIJK evaluate(UnwritableVectorIJK posFromObserverToObject, UnwritableVectorIJK velOfObserverWRTSSB) {
        return evaluate(posFromObserverToObject, velOfObserverWRTSSB, new VectorIJK());
    }

    /**
     * Computes the steller aberration corrected position from an observer to an object of interest.
     *
     * @param posFromObserverToObject the position vector that points from an observer to the object being observed
     * @param velOfObserverWRTSSB the velocity vector of the observer relative to the solar system barycenter
     * @param buffer the apparent position vector that points from an observer to the apparent position of the object
     *     being observed, where the deviation of this vector from the input vector differs do to stellar aberration
     * @return the apparent position vector that points from an observer to the apparent position of the object being
     *     observed, where the deviation of this vector from the input vector differs do to stellar aberration
     */
    static VectorIJK evaluate(
            UnwritableVectorIJK posFromObserverToObject, UnwritableVectorIJK velOfObserverWRTSSB, VectorIJK buffer) {

        checkNotNull(posFromObserverToObject);
        checkNotNull(velOfObserverWRTSSB);
        checkNotNull(buffer);
        checkArgument(
                velOfObserverWRTSSB.getLength() < SPEED_OF_LIGHT_IN_VAC,
                "The speed (" + velOfObserverWRTSSB.getLength()
                        + " km/s) is greater than or equal to the speed of light (km/s)");

        /*
         * Create some buffers.
         */
        VectorIJK crossBuffer = new VectorIJK();
        VectorIJK scaleBuffer = new VectorIJK();
        VectorIJK unitizeBuffer = new VectorIJK();

        /*
         * Copy the position into the output buffer.
         */
        buffer.setTo(posFromObserverToObject);

        /*
         * Scale the velocity into a fraction of the speed of light.
         */
        scaleBuffer.setTo(velOfObserverWRTSSB);
        scaleBuffer.scale(1.0 / SPEED_OF_LIGHT_IN_VAC);

        /*
         * Unitize the position from the observer to the target.
         */
        if (!posFromObserverToObject.equals(VectorIJK.ZERO)) {
            unitizeBuffer.setToUnitized(posFromObserverToObject);
        }

        // This doesn't make the most sense, we need to construct the cross
        // product of the position and the
        VectorIJK.cross(unitizeBuffer, scaleBuffer, crossBuffer);

        // a x b = |a|b|sin(phi)
        double sinePhi = crossBuffer.getLength();

        // if the sine of phi is zero, then we need not perform the calculation
        if (sinePhi != 0) {

            double phi = Math.asin(sinePhi);

            VectorIJK.rotate(buffer, crossBuffer, phi, buffer);

            return buffer;
        } else {
            return buffer;
        }
    }
}
