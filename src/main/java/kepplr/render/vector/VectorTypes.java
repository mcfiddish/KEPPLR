package kepplr.render.vector;

import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.RotationMatrixIJK;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.StateVector;

/**
 * Static factory for built-in {@link VectorType} implementations.
 *
 * <p>All built-in implementations are package-private. Callers depend only on the {@link VectorType} interface and
 * these factory methods. Adding a new built-in vector type requires implementing {@link VectorType} (package-private)
 * and adding one factory method here — no other classes change.
 *
 * <h3>Ephemeris access (REDESIGN.md §3.3)</h3>
 *
 * <p>Every implementation acquires ephemeris at point-of-use via
 * {@code KEPPLRConfiguration.getInstance().getEphemeris()} and never stores or passes it as a field or parameter. The
 * {@code targetNaifId} accepted by {@link #towardBody(int)} is a body-identity parameter, not an ephemeris reference,
 * and does not violate Rule 3.
 */
public final class VectorTypes {

    private VectorTypes() {}

    /**
     * Returns a {@link VectorType} whose direction is the unit velocity vector of the origin body relative to its
     * parent body in J2000.
     *
     * <p>For natural satellites (NAIF IDs 100–999 not ending in 99, plus Pluto 999), the velocity is relative to the
     * system barycenter ({@code naifId / 100}). For all other bodies (planets, Sun, spacecraft), the velocity is
     * heliocentric. This matches the orbital trail direction.
     *
     * @return velocity direction strategy
     */
    public static VectorType velocity() {
        return new VelocityVectorType();
    }

    /**
     * Returns a {@link VectorType} whose direction is the body-fixed +X axis of the origin body expressed in J2000.
     *
     * <p>The body-fixed frame is provided by the PCK kernel loaded at startup. If orientation data is unavailable,
     * {@link VectorType#computeDirection} returns {@code null} and logs a warning.
     *
     * @return body-fixed +X axis strategy
     */
    public static VectorType bodyAxisX() {
        return new BodyAxisVectorType(0);
    }

    /**
     * Returns a {@link VectorType} whose direction is the body-fixed +Y axis of the origin body expressed in J2000.
     *
     * @return body-fixed +Y axis strategy
     */
    public static VectorType bodyAxisY() {
        return new BodyAxisVectorType(1);
    }

    /**
     * Returns a {@link VectorType} whose direction is the body-fixed +Z axis of the origin body expressed in J2000.
     *
     * @return body-fixed +Z axis strategy
     */
    public static VectorType bodyAxisZ() {
        return new BodyAxisVectorType(2);
    }

    /**
     * Returns a {@link VectorType} whose direction points from the origin body toward {@code targetNaifId}.
     *
     * <p>Both heliocentric positions use geometric (no-aberration) corrections. {@code towardBody(10)} gives the
     * direction toward the Sun; {@code towardBody(399)} gives the direction toward Earth. There is no separate
     * SUN_DIRECTION constant — it is always expressed as {@code towardBody(10)}.
     *
     * @param targetNaifId NAIF integer ID of the target body
     * @return toward-body direction strategy
     */
    public static VectorType towardBody(int targetNaifId) {
        return new TowardBodyVectorType(targetNaifId);
    }

    // ── Package-private implementations ───────────────────────────────────────────────────────

    /**
     * Computes the unit velocity direction of the origin body relative to its parent body at the given ET.
     *
     * <p>For satellites, velocity is relative to the system barycenter; for other bodies it is heliocentric.
     */
    static final class VelocityVectorType implements VectorType {

        private static final Logger logger = LogManager.getLogger(VelocityVectorType.class);

        @Override
        public VectorIJK computeDirection(int originNaifId, double et) {
            try {
                KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
                StateVector bodyState = eph.getHeliocentricStateJ2000(originNaifId, et);
                if (bodyState == null) {
                    logger.warn("velocity(): null state for NAIF {} at ET={}", originNaifId, et);
                    return null;
                }
                VectorIJK vel = bodyState.getVelocity();

                // Satellites: subtract parent barycenter velocity to get orbit-relative velocity
                if (isSatellite(originNaifId)) {
                    int barycenterId = originNaifId / 100;
                    StateVector parentState = eph.getHeliocentricStateJ2000(barycenterId, et);
                    if (parentState != null) {
                        VectorIJK parentVel = parentState.getVelocity();
                        vel = new VectorIJK(
                                vel.getI() - parentVel.getI(),
                                vel.getJ() - parentVel.getJ(),
                                vel.getK() - parentVel.getK());
                    }
                }

                double len = vel.getLength();
                if (!(len > 0.0)) {
                    logger.warn("velocity(): zero velocity for NAIF {} at ET={}", originNaifId, et);
                    return null;
                }
                return new VectorIJK(vel.getI() / len, vel.getJ() / len, vel.getK() / len);
            } catch (Exception e) {
                logger.warn("velocity(): failed for NAIF {} at ET={}: {}", originNaifId, et, e.getMessage());
                return null;
            }
        }

        /** Returns true if naifId is a natural satellite (100–999 not ending in 99) or Pluto (999). */
        private static boolean isSatellite(int naifId) {
            return (naifId >= 100 && naifId <= 999 && naifId % 100 != 99) || naifId == 999;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof VelocityVectorType;
        }

        @Override
        public int hashCode() {
            return VelocityVectorType.class.hashCode();
        }

        @Override
        public String toString() {
            return "velocity";
        }

        @Override
        public String toScript() {
            return "VectorTypes.velocity()";
        }
    }

    /**
     * Computes the body-fixed axis of the origin body expressed in J2000.
     *
     * <p>The J2000-to-body-fixed rotation matrix R maps J2000 vectors to body-fixed vectors. The body-fixed axis unit
     * vector in J2000 is {@code R^T * e_axis}, which equals row {@code axisIndex} of R (since R is orthogonal and the
     * rows of R are orthonormal — each row is already a unit vector).
     *
     * <ul>
     *   <li>{@code axisIndex=0} → body-fixed +X in J2000 = row 0 of R
     *   <li>{@code axisIndex=1} → body-fixed +Y in J2000 = row 1 of R
     *   <li>{@code axisIndex=2} → body-fixed +Z in J2000 = row 2 of R
     * </ul>
     */
    static final class BodyAxisVectorType implements VectorType {

        private static final Logger logger = LogManager.getLogger(BodyAxisVectorType.class);

        /** 0 = X, 1 = Y, 2 = Z. */
        private final int axisIndex;

        BodyAxisVectorType(int axisIndex) {
            this.axisIndex = axisIndex;
        }

        @Override
        public VectorIJK computeDirection(int originNaifId, double et) {
            try {
                KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
                if (!eph.hasBodyFixedFrame(originNaifId)) {
                    logger.warn("bodyAxis{}: no body-fixed frame for NAIF {}; skipping", axisLabel(), originNaifId);
                    return null;
                }
                RotationMatrixIJK rot = eph.getJ2000ToBodyFixedRotation(originNaifId, et);
                if (rot == null) {
                    logger.warn("bodyAxis{}: null rotation for NAIF {} at ET={}", axisLabel(), originNaifId, et);
                    return null;
                }
                // Body-fixed axis in J2000 = R^T * e_axis = row axisIndex of R.
                // (R is orthogonal → rows are unit vectors → no normalization needed.)
                return new VectorIJK(rot.get(axisIndex, 0), rot.get(axisIndex, 1), rot.get(axisIndex, 2));
            } catch (Exception e) {
                logger.warn(
                        "bodyAxis{}: failed for NAIF {} at ET={}: {}", axisLabel(), originNaifId, et, e.getMessage());
                return null;
            }
        }

        private String axisLabel() {
            return axisIndex == 0 ? "X" : axisIndex == 1 ? "Y" : "Z";
        }

        @Override
        public boolean usesOriginBodyRadius() {
            return true;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof BodyAxisVectorType that && this.axisIndex == that.axisIndex;
        }

        @Override
        public int hashCode() {
            return 31 * BodyAxisVectorType.class.hashCode() + axisIndex;
        }

        @Override
        public String toString() {
            return "bodyAxis" + axisLabel();
        }

        @Override
        public String toScript() {
            return "VectorTypes.bodyAxis" + axisLabel() + "()";
        }
    }

    /**
     * Computes the unit vector from the origin body toward a target body in heliocentric J2000.
     *
     * <p>Both positions are geometric (no aberration correction). The target body NAIF ID is a body-identity parameter
     * stored at construction time; it is not an ephemeris or configuration reference and does not violate REDESIGN.md
     * §3.3.
     */
    static final class TowardBodyVectorType implements VectorType {

        private static final Logger logger = LogManager.getLogger(TowardBodyVectorType.class);

        /** NAIF ID of the target body — a body-identity parameter, not an ephemeris reference. */
        private final int targetNaifId;

        TowardBodyVectorType(int targetNaifId) {
            this.targetNaifId = targetNaifId;
        }

        @Override
        public VectorIJK computeDirection(int originNaifId, double et) {
            try {
                KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
                VectorIJK originPos = eph.getHeliocentricPositionJ2000(originNaifId, et);
                VectorIJK targetPos = eph.getHeliocentricPositionJ2000(targetNaifId, et);
                if (originPos == null) {
                    logger.warn("towardBody({}): null origin pos for NAIF {} at ET={}", targetNaifId, originNaifId, et);
                    return null;
                }
                if (targetPos == null) {
                    logger.warn("towardBody({}): null target pos at ET={}", targetNaifId, et);
                    return null;
                }
                double dx = targetPos.getI() - originPos.getI();
                double dy = targetPos.getJ() - originPos.getJ();
                double dz = targetPos.getK() - originPos.getK();
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (!(len > 0.0)) {
                    logger.warn(
                            "towardBody({}): origin and target at same position for NAIF {} at ET={}",
                            targetNaifId,
                            originNaifId,
                            et);
                    return null;
                }
                return new VectorIJK(dx / len, dy / len, dz / len);
            } catch (Exception e) {
                logger.warn(
                        "towardBody({}): failed for NAIF {} at ET={}: {}",
                        targetNaifId,
                        originNaifId,
                        et,
                        e.getMessage());
                return null;
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TowardBodyVectorType that && this.targetNaifId == that.targetNaifId;
        }

        @Override
        public int hashCode() {
            return 31 * TowardBodyVectorType.class.hashCode() + targetNaifId;
        }

        @Override
        public String toString() {
            return "towardBody:" + targetNaifId;
        }

        @Override
        public String toScript() {
            return "VectorTypes.towardBody(" + targetNaifId + ")";
        }
    }
}
