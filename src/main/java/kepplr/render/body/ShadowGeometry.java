package kepplr.render.body;

/**
 * Pure-math analytic eclipse geometry for solar-system bodies (REDESIGN.md §9.3, hybrid Option C).
 *
 * <p>All computation is in consistent units (km). No JME types, no ephemeris access — this class is
 * intentionally pure so it can be unit-tested without a SPICE kernel.
 *
 * <h3>Shadow model</h3>
 *
 * <p>Each (receiver point, caster sphere) pair is evaluated using angular disk geometry:
 *
 * <ul>
 *   <li>{@code αSun = asin(sunRadius / distReceiverToSun)} — angular radius of the Sun as seen from the receiver.
 *   <li>{@code αOcc = asin(casterRadius / distReceiverToCaster)} — angular radius of the caster.
 *   <li>{@code θ = acos(dot(sunDir, casterDir))} — angular separation between Sun and caster as seen from receiver.
 *   <li>Full umbra: {@code αOcc − αSun > 0} and {@code θ < αOcc − αSun}.
 *   <li>Full penumbra (lit): {@code θ > αOcc + αSun}.
 *   <li>Partial penumbra: linear blend between the two limits.
 * </ul>
 *
 * <p>Multiple casters are combined by taking the maximum shadow fraction (worst shadow wins). This is conservative but
 * physically correct when casters do not overlap in angular space.
 *
 * <p>Algorithm derived from prototype: {@code SaturnShadowController / EclipseLighting150.frag} — reimplemented for
 * new architecture with generalised caster loop and pure-Java testability.
 */
public final class ShadowGeometry {

    private ShadowGeometry() {}

    /**
     * Returns {@code true} if the caster could geometrically cast a shadow on the receiver.
     *
     * <p>A caster cannot cast a shadow on a receiver if the caster is on the opposite side of the receiver from the
     * Sun (i.e., the Sun direction and caster direction from the receiver are in opposing hemispheres). This cheap dot
     * product check is the primary early-out used by {@link EclipseShadowManager}.
     *
     * @param receiverPos receiver surface point in km
     * @param sunPos Sun center in km
     * @param casterPos caster center in km
     * @return {@code true} if the caster is in the solar hemisphere of the receiver
     */
    public static boolean canCastShadow(double[] receiverPos, double[] sunPos, double[] casterPos) {
        double sx = sunPos[0] - receiverPos[0];
        double sy = sunPos[1] - receiverPos[1];
        double sz = sunPos[2] - receiverPos[2];
        double cx = casterPos[0] - receiverPos[0];
        double cy = casterPos[1] - receiverPos[1];
        double cz = casterPos[2] - receiverPos[2];
        // Positive dot product → caster and Sun are on the same side of the receiver
        return (sx * cx + sy * cy + sz * cz) > 0.0;
    }

    /**
     * Compute the lit fraction at a single receiver point due to a single caster.
     *
     * <p>Returns {@code 1.0} (fully lit) if the caster cannot shadow the receiver or if the geometry is degenerate.
     * Returns {@code 0.0} if the receiver is in full umbra. Returns a value in {@code (0, 1)} in the penumbra zone.
     *
     * @param receiverPos receiver surface point (km)
     * @param sunPos Sun center (km)
     * @param sunRadius Sun radius (km); used only when {@code extendedSource = true}
     * @param casterPos caster sphere center (km)
     * @param casterRadius caster radius (km)
     * @param extendedSource {@code true} = extended-source analytic penumbra (MEDIUM/HIGH quality); {@code false} =
     *     point-source binary shadow (LOW quality)
     * @return lit fraction in {@code [0.0, 1.0]}; {@code 0.0} = full umbra, {@code 1.0} = full sunlight
     */
    public static double computeLitFraction(
            double[] receiverPos,
            double[] sunPos,
            double sunRadius,
            double[] casterPos,
            double casterRadius,
            boolean extendedSource) {

        double toSunX = sunPos[0] - receiverPos[0];
        double toSunY = sunPos[1] - receiverPos[1];
        double toSunZ = sunPos[2] - receiverPos[2];
        double dSun = Math.sqrt(toSunX * toSunX + toSunY * toSunY + toSunZ * toSunZ);
        if (!(dSun > 0.0)) return 1.0;

        double toCasterX = casterPos[0] - receiverPos[0];
        double toCasterY = casterPos[1] - receiverPos[1];
        double toCasterZ = casterPos[2] - receiverPos[2];
        double dCaster = Math.sqrt(toCasterX * toCasterX + toCasterY * toCasterY + toCasterZ * toCasterZ);
        if (!(dCaster > 0.0)) return 1.0;

        double alphaOcc = Math.asin(Math.min(1.0, casterRadius / dCaster));
        if (!(alphaOcc > 0.0)) return 1.0;

        // Normalised directions
        double sunDirX = toSunX / dSun;
        double sunDirY = toSunY / dSun;
        double sunDirZ = toSunZ / dSun;
        double casterDirX = toCasterX / dCaster;
        double casterDirY = toCasterY / dCaster;
        double casterDirZ = toCasterZ / dCaster;
        double cosTheta = Math.max(-1.0, Math.min(1.0,
                sunDirX * casterDirX + sunDirY * casterDirY + sunDirZ * casterDirZ));
        double theta = Math.acos(cosTheta);

        if (!extendedSource) {
            // Point-source (LOW quality): binary test against occluder angular radius only
            return (theta < alphaOcc) ? 0.0 : 1.0;
        }

        // Extended-source: angular disk computation
        double alphaSun = Math.asin(Math.min(1.0, sunRadius / dSun));
        double umbraLimit = alphaOcc - alphaSun;     // positive iff a true umbra exists
        double penumbraEnd = alphaOcc + alphaSun;

        if (umbraLimit > 0.0 && theta < umbraLimit) {
            return 0.0; // full umbra
        }
        if (theta > penumbraEnd) {
            return 1.0; // full sunlight
        }
        // Penumbra: linear interpolation
        double penumbraStart = Math.abs(umbraLimit);
        double denom = penumbraEnd - penumbraStart;
        if (!(denom > 0.0)) return 1.0;
        return 1.0 - ((penumbraEnd - theta) / denom);
    }

    /**
     * Compute the combined lit fraction at a receiver point from multiple casters.
     *
     * <p>The maximum shadow fraction across all casters is used (worst-case combination). A receiver in simultaneous
     * shadow from two casters is no darker than the darker of the two individual shadows — this is conservative and
     * visually correct for non-overlapping casters.
     *
     * @param receiverPos receiver surface point (km)
     * @param sunPos Sun center (km)
     * @param sunRadius Sun radius (km)
     * @param casterPositions world-space caster centers (km); array length must be ≥ {@code casterCount}
     * @param casterRadii caster radii (km); parallel to {@code casterPositions}
     * @param casterCount number of valid entries in the arrays
     * @param extendedSource {@code true} = analytic penumbra; {@code false} = point-source binary
     * @return combined lit fraction in {@code [0.0, 1.0]}
     */
    public static double computeCombinedLitFraction(
            double[] receiverPos,
            double[] sunPos,
            double sunRadius,
            double[][] casterPositions,
            double[] casterRadii,
            int casterCount,
            boolean extendedSource) {

        double maxShadow = 0.0;
        for (int i = 0; i < casterCount; i++) {
            double litFrac = computeLitFraction(
                    receiverPos, sunPos, sunRadius, casterPositions[i], casterRadii[i], extendedSource);
            maxShadow = Math.max(maxShadow, 1.0 - litFrac);
        }
        return 1.0 - maxShadow;
    }
}
