package kepplr.camera;

import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.providers.aberrated.AberrationCorrection;

/**
 * Computes the synodic camera frame basis at a given epoch (REDESIGN.md §5).
 *
 * <p>No instance state. Ephemeris is acquired at point-of-use per §3.3.
 *
 * <h3>Frame axes (§5.1)</h3>
 *
 * <ul>
 *   <li><b>+X</b> = normalized geometric vector from focus body center → selected body center
 *   <li><b>+Z candidate</b> = J2000 +Z by default; Ecliptic J2000 +Z if degenerate (§5.2)
 *   <li><b>+Y</b> = normalize(Z_candidate × X) — perpendicular to both (right-handed)
 *   <li><b>+Z final</b> = normalize(X × Y) — re-derived for exact orthonormality
 * </ul>
 *
 * <h3>Degenerate case (§5.2)</h3>
 *
 * <p>If |+X × +Z_candidate| &lt; {@link KepplrConstants#SYNODIC_DEGENERATE_THRESHOLD_RAD}, the +X axis is too close to
 * J2000 +Z and the Ecliptic J2000 +Z is used as the secondary axis instead.
 *
 * <h3>Fallback</h3>
 *
 * <p>If either the focus or target body ID is {@code -1}, or the ephemeris cannot produce a valid focus→target vector,
 * {@link #compute} returns {@code null}. The caller must fall back to the inertial frame.
 */
public final class SynodicFrame {

    private static final Logger logger = LogManager.getLogger();

    /** Ecliptic north pole in J2000 coordinates: (0, −sin ε, cos ε) where ε = obliquity of ecliptic. */
    static final VectorIJK ECLIPTIC_J2000_Z = new VectorIJK(
            0.0,
            -Math.sin(KepplrConstants.ECLIPTIC_J2000_OBLIQUITY_RAD),
            Math.cos(KepplrConstants.ECLIPTIC_J2000_OBLIQUITY_RAD));

    private SynodicFrame() {}

    /**
     * Orthonormal basis of the synodic frame, expressed in J2000.
     *
     * <p>All three axes are unit vectors forming a right-handed coordinate system: {@code xAxis × yAxis = zAxis}.
     */
    public record Basis(VectorIJK xAxis, VectorIJK yAxis, VectorIJK zAxis) {}

    /**
     * Compute the synodic frame basis at the given ET.
     *
     * <p>Ephemeris is acquired at point-of-use (§3.3). The focus→target vector is computed geometrically (no aberration
     * correction), as the frame definition is a geometric construction.
     *
     * @param focusBodyId NAIF ID of the focus body, or -1 if none
     * @param targetBodyId NAIF ID of the selected body (the "other body"), or -1 if none
     * @param et current simulation epoch (TDB seconds past J2000)
     * @return orthonormal {@link Basis} in J2000, or {@code null} if either body is absent or the ephemeris query fails
     *     — caller must fall back to inertial frame
     */
    public static Basis compute(int focusBodyId, int targetBodyId, double et) {
        if (focusBodyId == -1 || targetBodyId == -1) {
            logger.warn(
                    "Synodic frame requires both focus ({}) and target ({}) — falling back to inertial",
                    focusBodyId,
                    targetBodyId);
            return null;
        }

        // Rule 3: acquire ephemeris at point-of-use; never store or pass it
        KEPPLREphemeris ephemeris = KEPPLRConfiguration.getInstance().getEphemeris();

        // +X = normalized geometric focus→target (no aberration correction — frame is a geometric construction)
        VectorIJK focusToTarget =
                ephemeris.getObserverToTargetJ2000(focusBodyId, targetBodyId, et, AberrationCorrection.NONE);
        if (focusToTarget == null) {
            logger.warn(
                    "Could not obtain focus→target vector for synodic frame (focus={}, target={}) — falling back to inertial",
                    focusBodyId,
                    targetBodyId);
            return null;
        }
        double xLen = focusToTarget.getLength();
        if (Double.isNaN(xLen) || !(xLen > 0.0)) {
            logger.warn(
                    "focus→target vector has zero or invalid length for synodic frame (focus={}) — falling back to inertial",
                    focusBodyId);
            return null;
        }
        VectorIJK xAxis = focusToTarget.createScaled(1. / xLen);

        return computeFromXAxis(xAxis);
    }

    /**
     * Build the synodic frame basis given a pre-normalized +X axis.
     *
     * <p>Package-private to allow unit tests to exercise the secondary-axis selection and orthonormalization logic
     * without going through the ephemeris.
     *
     * @param xAxis normalized +X axis in J2000 (must be a unit vector)
     * @return orthonormal {@link Basis}
     */
    static Basis computeFromXAxis(VectorIJK xAxis) {
        // Choose secondary axis: J2000 +Z unless degenerate (§5.2)
        VectorIJK zCandidate = new VectorIJK(0.0, 0.0, 1.0);
        VectorIJK xCrossZ = VectorIJK.cross(xAxis, zCandidate);
        if (xCrossZ.getLength() < KepplrConstants.SYNODIC_DEGENERATE_THRESHOLD_RAD) {
            logger.warn(
                    "Synodic +X is near-parallel to J2000 +Z (|X×Z|={}); switching to Ecliptic J2000 +Z (§5.2)",
                    xCrossZ.getLength());
            zCandidate = ECLIPTIC_J2000_Z;
        }

        // +Y = normalize(Z_candidate × X)  per §5.1
        VectorIJK zCrossX = VectorIJK.cross(zCandidate, xAxis);
        VectorIJK yAxis = zCrossX.createScaled(1. / zCrossX.getLength());

        // +Z = normalize(X × Y) — re-derived so the basis is exactly orthonormal
        VectorIJK xCrossY = VectorIJK.cross(xAxis, yAxis);
        VectorIJK zAxis = xCrossY.createScaled(1. / xCrossY.getLength());

        return new Basis(xAxis, yAxis, zAxis);
    }
}
