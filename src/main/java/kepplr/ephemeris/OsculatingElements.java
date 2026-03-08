package kepplr.ephemeris;

import java.util.Locale;
import picante.math.vectorspace.UnwritableVectorIJK;
import picante.mechanics.StateVector;

/**
 * Immutable extended osculating element set computed from an inertial Cartesian state.
 *
 * <p>This record corresponds to the extended SPICE {@code OSCLTX} element set:
 *
 * <pre>
 *   RP     perifocal distance
 *   ECC    eccentricity
 *   INC    inclination
 *   LNODE  longitude of ascending node
 *   ARGP   argument of periapsis
 *   M0     mean anomaly at epoch
 *   MU     gravitational parameter
 *   NU     true anomaly at epoch
 *   A      semi-major axis
 *   TAU    orbital period
 * </pre>
 *
 * <p>Expected units:
 *
 * <ul>
 *   <li>position in km
 *   <li>velocity in km/s
 *   <li>epoch in ephemeris seconds past J2000 (or another consistent time scale)
 *   <li>gravitational parameter in km^3/s^2
 *   <li>angles in radians
 * </ul>
 *
 * <p>Angle conventions:
 *
 * <ul>
 *   <li>{@code inc} is in the range {@code [0, pi]}
 *   <li>{@code lnode}, {@code argp}, {@code m0}, and {@code nu} are normalized to {@code [0, 2*pi)}
 * </ul>
 *
 * <p>Undefined-value conventions:
 *
 * <ul>
 *   <li>{@code a == 0} means semi-major axis was not safely computable
 *   <li>{@code tau == 0} means orbital period was not safely computable or not defined
 * </ul>
 *
 * <p>This implementation is intended to be numerically and physically consistent with SPICE {@code OSCLTX}, but it is
 * not a byte-for-byte clone of NAIF's implementation.
 *
 * @param rp perifocal distance
 * @param ecc eccentricity
 * @param inc inclination in radians
 * @param lnode longitude of ascending node in radians
 * @param argp argument of periapsis in radians
 * @param m0 mean anomaly at epoch in radians for elliptic orbits, hyperbolic anomaly expression for hyperbolic orbits,
 *     or Barker parameter expression for parabolic orbits, matching the traditional OSCLTX convention
 * @param mu gravitational parameter of the primary body
 * @param nu true anomaly at epoch in radians
 * @param a semi-major axis, or zero if not safely computable
 * @param tau orbital period, or zero if not safely computable or not defined
 */
public record OsculatingElements(
        double rp,
        double ecc,
        double inc,
        double lnode,
        double argp,
        double m0,
        double mu,
        double nu,
        double a,
        double tau) {

    /** Array index for perifocal distance in the SPICE {@code OSCLTX} ordering. */
    public static final int RP = 0;

    /** Array index for eccentricity in the SPICE {@code OSCLTX} ordering. */
    public static final int ECC = 1;

    /** Array index for inclination in the SPICE {@code OSCLTX} ordering. */
    public static final int INC = 2;

    /** Array index for longitude of ascending node in the SPICE {@code OSCLTX} ordering. */
    public static final int LNODE = 3;

    /** Array index for argument of periapsis in the SPICE {@code OSCLTX} ordering. */
    public static final int ARGP = 4;

    /** Array index for mean anomaly at epoch in the SPICE {@code OSCLTX} ordering. */
    public static final int M0 = 5;

    /**
     * Array index for epoch in the SPICE {@code OSCLTX} ordering. Epoch is not used anywhere in the calculation and is
     * always set to zero.
     */
    public static final int T0 = 6;

    /** Array index for gravitational parameter in the SPICE {@code OSCLTX} ordering. */
    public static final int MU = 7;

    /** Array index for true anomaly in the SPICE {@code OSCLTX} ordering. */
    public static final int NU = 8;

    /** Array index for semi-major axis in the SPICE {@code OSCLTX} ordering. */
    public static final int A = 9;

    /** Array index for orbital period in the SPICE {@code OSCLTX} ordering. */
    public static final int TAU = 10;

    /** Size of the extended element array returned by {@link #toArray()}. */
    public static final int OSCXSZ = 11;

    /**
     * Branch-stabilization tolerance used for nearly circular, equatorial, and parabolic cases.
     *
     * <p>This is not intended as a statement of physical uncertainty. It is only used to make discrete branch decisions
     * more stable in the presence of roundoff.
     */
    private static final double SINGULARITY_TOLERANCE = 1.0e-10;

    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double MARGIN = 200.0;
    private static final double LIMIT = Double.MAX_VALUE / MARGIN;

    /**
     * Compact constructor with basic invariant checks.
     *
     * @throws IllegalArgumentException if {@code mu <= 0}, {@code ecc < 0}, or {@code rp < 0}
     */
    public OsculatingElements {
        if (!(mu > 0.0)) {
            throw new IllegalArgumentException("Non-positive gravitational parameter: " + mu);
        }
        if (ecc < 0.0) {
            throw new IllegalArgumentException("Negative eccentricity: " + ecc);
        }
        if (rp < 0.0) {
            throw new IllegalArgumentException("Negative perifocal distance: " + rp);
        }
    }

    /**
     * Computes extended osculating elements from a Cartesian state.
     *
     * @param state inertial Cartesian state
     * @param mu gravitational parameter of the primary body
     * @return immutable extended osculating element set
     * @throws NullPointerException if {@code state} is null
     * @throws IllegalArgumentException if the input is degenerate or {@code mu <= 0}
     */
    public static OsculatingElements oscltx(StateVector state, double mu) {
        if (state == null) {
            throw new NullPointerException("state must not be null");
        }
        return oscltx(state.getPosition(), state.getVelocity(), mu);
    }

    /**
     * Computes extended osculating elements from Cartesian position and velocity.
     *
     * @param position inertial position vector
     * @param velocity inertial velocity vector
     * @param mu gravitational parameter of the primary body
     * @return immutable extended osculating element set
     * @throws NullPointerException if {@code position} or {@code velocity} is null
     * @throws IllegalArgumentException if the input is degenerate or {@code mu <= 0}
     */
    public static OsculatingElements oscltx(UnwritableVectorIJK position, UnwritableVectorIJK velocity, double mu) {

        if (position == null) {
            throw new NullPointerException("position must not be null");
        }
        if (velocity == null) {
            throw new NullPointerException("velocity must not be null");
        }
        if (!(mu > 0.0)) {
            throw new IllegalArgumentException("Non-positive gravitational parameter: " + mu);
        }

        final double rx = position.getI();
        final double ry = position.getJ();
        final double rz = position.getK();

        final double vx = velocity.getI();
        final double vy = velocity.getJ();
        final double vz = velocity.getK();

        final double rmag = norm(rx, ry, rz);
        final double vmag = norm(vx, vy, vz);

        if (rmag == 0.0) {
            throw new IllegalArgumentException("Degenerate case: zero position vector");
        }
        if (vmag == 0.0) {
            throw new IllegalArgumentException("Degenerate case: zero velocity vector");
        }

        // Specific angular momentum: h = r x v
        final double hx = ry * vz - rz * vy;
        final double hy = rz * vx - rx * vz;
        final double hz = rx * vy - ry * vx;

        final double hmag = norm(hx, hy, hz);
        if (hmag == 0.0) {
            throw new IllegalArgumentException("Degenerate case: zero angular momentum vector");
        }

        // Node vector: n = k x h
        final double nx = -hy;
        final double ny = hx;
        final double nmag = norm(nx, ny, 0.0);

        // Eccentricity vector:
        // e = [ ((v^2 - mu/r) r) - ((r.v) v) ] / mu
        final double rv = rx * vx + ry * vy + rz * vz;
        final double c1 = (vmag * vmag - mu / rmag) / mu;
        final double c2 = rv / mu;

        final double ex = c1 * rx - c2 * vx;
        final double ey = c1 * ry - c2 * vy;
        final double ez = c1 * rz - c2 * vz;

        double ecc = norm(ex, ey, ez);
        ecc = snapTo(ecc, 0.0, SINGULARITY_TOLERANCE);
        ecc = snapTo(ecc, 1.0, SINGULARITY_TOLERANCE);

        final double rp = (hmag * hmag) / (mu * (1.0 + ecc));
        final double inc = Math.acos(clamp(hz / hmag, -1.0, 1.0));

        final double lnode;
        if (isNearZero(inc) || isNearZero(inc - Math.PI)) {
            lnode = 0.0;
        } else {
            lnode = normalizeAngle(Math.atan2(ny, nx));
        }

        final double argp;
        if (ecc == 0.0) {
            argp = 0.0;
        } else if (nmag > 0.0) {
            final double cosArgp = clamp((nx * ex + ny * ey) / (nmag * ecc), -1.0, 1.0);
            double value = Math.acos(cosArgp);
            if (ez < 0.0) {
                value = TWO_PI - value;
            }
            argp = normalizeAngle(value);
        } else {
            // Equatorial non-circular orbit: use longitude of periapsis.
            argp = normalizeAngle(Math.atan2(ey, ex));
        }

        final double nu;
        if (ecc != 0.0) {
            final double er = ex * rx + ey * ry + ez * rz;
            final double cx = ey * rz - ez * ry;
            final double cy = ez * rx - ex * rz;
            final double cz = ex * ry - ey * rx;
            final double sinNu = (cx * hx + cy * hy + cz * hz) / (ecc * hmag * rmag);
            final double cosNu = er / (ecc * rmag);
            nu = normalizeAngle(Math.atan2(sinNu, cosNu));
        } else {
            // Circular orbit: use geometric surrogate angle.
            if (nmag > 0.0) {
                final double cosU = clamp((nx * rx + ny * ry) / (nmag * rmag), -1.0, 1.0);
                double u = Math.acos(cosU);
                if (rz < 0.0) {
                    u = TWO_PI - u;
                }
                nu = normalizeAngle(u);
            } else {
                nu = normalizeAngle(Math.atan2(ry, rx));
            }
        }

        final double m0;
        if (ecc == 0.0) {
            m0 = nu;
        } else if (ecc < 1.0) {
            final double sinNu2 = Math.sin(nu / 2.0);
            final double cosNu2 = Math.cos(nu / 2.0);
            final double eccentricAnomaly =
                    2.0 * Math.atan2(Math.sqrt(1.0 - ecc) * sinNu2, Math.sqrt(1.0 + ecc) * cosNu2);
            m0 = normalizeAngle(eccentricAnomaly - ecc * Math.sin(eccentricAnomaly));
        } else if (ecc > 1.0) {
            final double arg = Math.sqrt((ecc - 1.0) / (ecc + 1.0)) * Math.tan(nu / 2.0);
            final double hyperbolicAnomaly = 2.0 * atanh(arg);
            m0 = ecc * Math.sinh(hyperbolicAnomaly) - hyperbolicAnomaly;
        } else {
            // Parabolic Barker parameter.
            final double d = Math.tan(nu / 2.0);
            m0 = d + (d * d * d) / 3.0;
        }

        double a = 0.0;
        double tau = 0.0;

        if (ecc != 1.0 && rmag > (mu / LIMIT)) {
            final double specificEnergy = 0.5 * vmag * vmag - mu / rmag;

            if (Math.abs(specificEnergy) >= Math.abs(mu) / LIMIT) {
                a = -mu / (2.0 * specificEnergy);

                if (ecc < 1.0) {
                    final double b = Math.pow(LIMIT / TWO_PI, 2.0 / 3.0);
                    final double muCubr = Math.cbrt(mu);

                    final boolean canComputeTau = (mu >= 1.0) ? ((a / muCubr) < b) : (a < (b * muCubr));

                    if (canComputeTau) {
                        tau = TWO_PI * Math.pow(a / muCubr, 1.5);
                    }
                }
            }
        }

        return new OsculatingElements(rp, ecc, inc, lnode, argp, m0, mu, nu, a, tau);
    }

    /**
     * Returns these elements as a newly allocated array in SPICE {@code OSCLTX} order.
     *
     * @return array in the order {@code [RP, ECC, INC, LNODE, ARGP, M0, T0, MU, NU, A, TAU]}
     */
    public double[] toArray() {
        return new double[] {rp, ecc, inc, lnode, argp, m0, 0., mu, nu, a, tau};
    }

    /**
     * Returns the element value at a SPICE {@code OSCLTX} index.
     *
     * @param index one of the public index constants defined by this class
     * @return element value at the requested index
     * @throws IndexOutOfBoundsException if {@code index} is not in {@code [0, OSCXSZ)}
     */
    public double get(int index) {
        return switch (index) {
            case RP -> rp;
            case ECC -> ecc;
            case INC -> inc;
            case LNODE -> lnode;
            case ARGP -> argp;
            case M0 -> m0;
            case T0 -> 0.;
            case MU -> mu;
            case NU -> nu;
            case A -> a;
            case TAU -> tau;
            default -> throw new IndexOutOfBoundsException("index: " + index);
        };
    }

    /**
     * Returns whether the orbit is treated as circular under the class tolerance.
     *
     * @return {@code true} if eccentricity is effectively zero
     */
    public boolean isCircular() {
        return Math.abs(ecc) <= SINGULARITY_TOLERANCE;
    }

    /**
     * Returns whether the orbit is treated as equatorial under the class tolerance.
     *
     * @return {@code true} if inclination is effectively 0 or pi
     */
    public boolean isEquatorial() {
        return Math.abs(inc) <= SINGULARITY_TOLERANCE || Math.abs(inc - Math.PI) <= SINGULARITY_TOLERANCE;
    }

    /**
     * Returns whether the orbit is treated as elliptic under the class tolerance.
     *
     * <p>Circular orbits are a subset of elliptic orbits.
     *
     * @return {@code true} if {@code ecc < 1} by more than tolerance
     */
    public boolean isElliptic() {
        return ecc < 1.0 - SINGULARITY_TOLERANCE;
    }

    /**
     * Returns whether the orbit is treated as parabolic under the class tolerance.
     *
     * @return {@code true} if eccentricity is effectively 1
     */
    public boolean isParabolic() {
        return Math.abs(ecc - 1.0) <= SINGULARITY_TOLERANCE;
    }

    /**
     * Returns whether the orbit is treated as hyperbolic under the class tolerance.
     *
     * @return {@code true} if {@code ecc > 1} by more than tolerance
     */
    public boolean isHyperbolic() {
        return ecc > 1.0 + SINGULARITY_TOLERANCE;
    }

    /**
     * Returns whether the semi-major axis field is defined.
     *
     * <p>This mirrors the convention used by this implementation: {@code a == 0} means it was not safely computable.
     *
     * @return {@code true} if semi-major axis is defined
     */
    public boolean hasDefinedSemiMajorAxis() {
        return a != 0.0;
    }

    /**
     * Returns whether the orbital period field is defined.
     *
     * <p>This mirrors the convention used by this implementation: {@code tau == 0} means it was not safely computable
     * or not defined for the orbit class.
     *
     * @return {@code true} if orbital period is defined
     */
    public boolean hasDefinedPeriod() {
        return tau != 0.0;
    }

    /**
     * Returns the mean motion for elliptic orbits when semi-major axis is defined.
     *
     * <p>The returned value is {@code sqrt(mu / a^3)} in radians per second.
     *
     * @return mean motion in radians per second
     * @throws IllegalStateException if the orbit is not elliptic or semi-major axis is undefined
     */
    public double meanMotion() {
        if (!isElliptic()) {
            throw new IllegalStateException("Mean motion is only defined here for elliptic orbits");
        }
        if (!hasDefinedSemiMajorAxis()) {
            throw new IllegalStateException("Semi-major axis is not defined");
        }
        return Math.sqrt(mu / (a * a * a));
    }

    /**
     * Returns a diagnostic string with angles shown in both radians and degrees.
     *
     * <p>This is mainly useful during cross-checking against reference implementations.
     *
     * @return human-readable diagnostic string
     */
    public String toDebugString() {
        return String.format(
                Locale.ROOT,
                """
                OsculatingElements[
                  rp=%.16e km
                  ecc=%.16e
                  inc=%.16e rad (%.12f deg)
                  lnode=%.16e rad (%.12f deg)
                  argp=%.16e rad (%.12f deg)
                  m0=%.16e
                  t0=%.16e
                  mu=%.16e km^3/s^2
                  nu=%.16e rad (%.12f deg)
                  a=%.16e km
                  tau=%.16e s
                ]""",
                rp,
                ecc,
                inc,
                Math.toDegrees(inc),
                lnode,
                Math.toDegrees(lnode),
                argp,
                Math.toDegrees(argp),
                m0,
                0.,
                mu,
                nu,
                Math.toDegrees(nu),
                a,
                tau);
    }

    /**
     * Returns the Euclidean norm of a 3-vector.
     *
     * @param x x-component
     * @param y y-component
     * @param z z-component
     * @return Euclidean norm
     */
    private static double norm(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * Clamps a value into the closed interval {@code [lo, hi]}.
     *
     * <p>This protects inverse-trigonometric calls from small roundoff excursions.
     *
     * @param x value to clamp
     * @param lo lower bound
     * @param hi upper bound
     * @return clamped value
     */
    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    /**
     * Returns whether a value is effectively zero under the class tolerance.
     *
     * @param x value to test
     * @return {@code true} if the value is near zero
     */
    private static boolean isNearZero(double x) {
        return Math.abs(x) <= SINGULARITY_TOLERANCE;
    }

    /**
     * Normalizes an angle into the interval {@code [0, 2*pi)}.
     *
     * @param angle input angle in radians
     * @return normalized angle in radians
     */
    private static double normalizeAngle(double angle) {
        double a = angle % TWO_PI;
        if (a < 0.0) {
            a += TWO_PI;
        }
        return a;
    }

    /**
     * Snaps a value exactly to a target when it lies within the provided tolerance.
     *
     * @param value value to test
     * @param target target value
     * @param tolerance tolerance for snapping
     * @return {@code target} if within tolerance, otherwise {@code value}
     */
    private static double snapTo(double value, double target, double tolerance) {
        return Math.abs(value - target) <= tolerance ? target : value;
    }

    /**
     * Computes the inverse hyperbolic tangent.
     *
     * @param x input value
     * @return inverse hyperbolic tangent of {@code x}
     */
    private static double atanh(double x) {
        return 0.5 * Math.log((1.0 + x) / (1.0 - x));
    }
}
