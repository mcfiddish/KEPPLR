package kepplr.camera;

import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.providers.aberrated.AberrationCorrection;

/**
 * Static utility for computing the apparent pointing direction from an observer to a target body (REDESIGN.md §6).
 *
 * <p>No instance state. Ephemeris is acquired at point-of-use per §3.3.
 *
 * <h3>Light-time and aberration policy (§6.2)</h3>
 *
 * <ul>
 *   <li>When a focus body exists ({@code focusBodyId != -1}), applies {@code LT+S} aberration correction with the
 *       focus body center as the observer.
 *   <li>When no focus body exists, returns the geometric heliocentric direction (Sun as implicit origin, no
 *       correction).
 * </ul>
 *
 * <h3>Limitation (§6.3)</h3>
 *
 * <p>If the camera is very far from the focus body, LT+S correction may be inaccurate. No special fallback is
 * required beyond the policy above.
 */
public final class CameraPointing {

    private CameraPointing() {}

    /**
     * Compute the apparent unit direction from the observer to a target body.
     *
     * <p>When a focus body exists, applies LT+S aberration correction with the focus body center as the observer
     * (§6.2). When no focus body exists, returns the geometric heliocentric direction (Sun as implicit origin).
     *
     * @param focusBodyId NAIF ID of focus body, or -1 if none
     * @param targetBodyId NAIF ID of target body
     * @param et current simulation time (TDB seconds past J2000)
     * @return unit direction in J2000, or {@code null} if unavailable (e.g., unknown body, zero-length vector)
     */
    public static VectorIJK computePointAtDirection(int focusBodyId, int targetBodyId, double et) {
        // Degenerate case: pointing from a body to itself has no meaningful direction (zero vector).
        // Picante's LT+S aberration code throws UnsupportedOperationException on a zero-length unitize,
        // so we return null here before the query.
        if (focusBodyId != -1 && focusBodyId == targetBodyId) {
            return null;
        }

        // Rule 3: acquire ephemeris at point-of-use; never store or pass it
        KEPPLREphemeris ephemeris = KEPPLRConfiguration.getInstance().getEphemeris();

        VectorIJK raw;
        if (focusBodyId != -1) {
            // LT+S correction with focus body as observer (§6.2)
            raw = ephemeris.getObserverToTargetJ2000(focusBodyId, targetBodyId, et, AberrationCorrection.LT_S);
        } else {
            // No focus body — geometric heliocentric position from Sun (§6.2)
            raw = ephemeris.getHeliocentricPositionJ2000(targetBodyId, et);
        }

        if (raw == null) {
            return null;
        }
        double length = raw.getLength();
        if (Double.isNaN(length) || !(length > 0.0)) {
            return null;
        }
        return new VectorIJK(raw.getI() / length, raw.getJ() / length, raw.getK() / length);
    }
}
