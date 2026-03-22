package kepplr.render.trail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.ephemeris.OsculatingElements;
import kepplr.ephemeris.spice.SpiceBundle;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.CelestialFrames;
import picante.mechanics.Coverage;
import picante.mechanics.EphemerisID;
import picante.mechanics.StateVector;
import picante.mechanics.StateVectorFunction;
import picante.mechanics.providers.aberrated.AberrationCorrection;

/**
 * Samples heliocentric J2000 trail positions from SPICE and computes orbital periods.
 *
 * <p>All methods are static; this class is not instantiated. Ephemeris access is always via
 * {@code KEPPLRConfiguration.getInstance().getEphemeris()} at point-of-use (REDESIGN.md §3.3).
 *
 * <h3>Period determination (REDESIGN.md §7.5)</h3>
 *
 * <ul>
 *   <li><b>Natural satellites</b> (NAIF IDs 100–999 not ending in 99): state relative to the system barycenter
 *       ({@code naifId / 100}) using {@code BODY{barycenterId}_GM}.
 *   <li><b>Planets and all other bodies</b>: heliocentric state using {@code BODY10_GM} (Sun).
 *   <li><b>Fallback</b>: {@link KepplrConstants#TRAIL_DEFAULT_DURATION_SEC} (30 days) when the period cannot be
 *       determined (hyperbolic/parabolic orbit, null state, or exception).
 * </ul>
 */
public final class TrailSampler {

    private static final Logger logger = LogManager.getLogger();

    private TrailSampler() {}

    /**
     * Compute the trail duration in seconds for the given body at the given ET.
     *
     * <p>Uses the OsculatingElements approach (see {@code OsculatingElementsTest} for the authoritative pattern). For
     * natural satellites uses the barycenter-relative state and barycenter GM; for planets uses heliocentric state and
     * the Sun's GM.
     *
     * @param naifId NAIF integer ID of the body
     * @param et ephemeris time (TDB seconds past J2000)
     * @return orbital period in seconds, or {@link KepplrConstants#TRAIL_DEFAULT_DURATION_SEC} if undetermined
     */
    public static double computeTrailDurationSec(int naifId, double et) {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        SpiceBundle spiceBundle = eph.getSpiceBundle();

        // Satellite path: IDs 100–999 not ending in 99, plus Pluto (999) orbiting barycenter 9
        boolean isSatellite =
                (naifId >= 100 && naifId <= 999 && naifId % 100 != 99) || naifId == KepplrConstants.PLUTO_NAIF_ID;

        if (isSatellite) {
            try {
                int barycenterId = naifId / 100;
                EphemerisID satellite = spiceBundle.getObject(naifId);
                Double gm = lookupGm(spiceBundle, barycenterId);
                if (satellite != null && gm != null) {
                    // Use the relative orbit (satellite vs. primary body) rather than the barycentric
                    // orbit.  For comparable-mass systems like Pluto-Charon the barycenter lies far from
                    // either body, so oscltx(state_relative_to_barycenter, system_GM) underestimates the
                    // period by (a_bary / a_rel)^1.5 — a factor of ~30 for Pluto.  The relative orbit
                    // gives the correct two-body period: T = 2π√(a_rel³ / GM_system).
                    int primaryId = barycenterId * 100 + 99;
                    EphemerisID center;
                    if (primaryId != naifId) {
                        // Standard satellite: compute relative orbit to primary (e.g. Moon→Earth)
                        center = spiceBundle.getObject(primaryId);
                    } else {
                        // Pluto case: satellite IS the primary. Use the largest companion (Charon = x01).
                        center = spiceBundle.getObject(barycenterId * 100 + 1);
                    }
                    if (center == null) {
                        center = spiceBundle.getObject(barycenterId);
                    }
                    if (center != null) {
                        StateVectorFunction svf = spiceBundle
                                .getAbProvider()
                                .createAberratedStateVectorFunction(
                                        satellite,
                                        center,
                                        CelestialFrames.J2000,
                                        Coverage.ALL_TIME,
                                        AberrationCorrection.NONE);
                        StateVector state = svf.getState(et);
                        OsculatingElements osc = OsculatingElements.oscltx(state, gm);
                        if (osc.hasDefinedPeriod()) {
                            return osc.tau();
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not compute satellite period for NAIF {}: {}", naifId, e.getMessage());
            }
        }

        // Heliocentric path: planets and all other bodies
        try {
            StateVector helioState = eph.getHeliocentricStateJ2000(naifId, et);
            Double sunGm = lookupGm(spiceBundle, 10);
            if (helioState != null && sunGm != null) {
                OsculatingElements osc = OsculatingElements.oscltx(helioState, sunGm);
                if (osc.hasDefinedPeriod()) {
                    return osc.tau();
                }
            }
        } catch (Exception e) {
            logger.warn("Could not compute heliocentric period for NAIF {}: {}", naifId, e.getMessage());
        }

        return KepplrConstants.TRAIL_DEFAULT_DURATION_SEC;
    }

    /**
     * Sample trail positions for a body, in heliocentric J2000 coordinates.
     *
     * <p>Uses adaptive arc-based sampling going <em>backward</em> from {@code centerEt} (the body's current position)
     * over one full {@code durationSec}. Segments near {@code centerEt} are spaced at
     * {@link KepplrConstants#TRAIL_MIN_ARC_DEG} of orbital arc, widening to {@link KepplrConstants#TRAIL_MAX_ARC_DEG}
     * at the oldest edge. The forward direction (times after {@code centerEt}) is not sampled.
     *
     * <p>The returned list is time-ordered oldest-first: {@code samples.get(0)} is at {@code centerEt − durationSec}
     * and {@code samples.get(last)} is at {@code centerEt}. {@link TrailRenderer} uses this ordering to assign
     * per-vertex alpha (newest = opaque, oldest = transparent).
     *
     * <h3>Satellite anchoring</h3>
     *
     * <p>For natural satellites (NAIF IDs 100–999 not ending in 99) the trail is anchored to the central body
     * (barycenter, {@code naifId / 100}) rather than traced in heliocentric space. Each sample is computed as:
     *
     * <pre>
     *   baryAnchor(centerEt) + (satHelio(sampleEt) − baryHelio(sampleEt))
     * </pre>
     *
     * <p>This produces an orbital arc centred on the barycenter's current position rather than a near-copy of the
     * barycenter's heliocentric trail. For planets and other bodies, sample positions are heliocentric J2000 positions
     * at each sample ET.
     *
     * @param naifId NAIF integer ID of the body
     * @param centerEt ET at the newest (body's current) end of the trail
     * @param durationSec total trail duration in seconds (= orbital period for period trails)
     * @param frame reference frame name (reserved; current implementation always uses J2000)
     * @return list of {@code double[4]} arrays — [x, y, z] in km (heliocentric J2000) and [3] = sample ET — oldest
     *     first; never null
     * @see #sample(int, double, double, String, int)
     */
    /**
     * Convenience overload using the default HIGH-quality sample cap
     * ({@link KepplrConstants#TRAIL_SAMPLES_PER_PERIOD}).
     *
     * @see #sample(int, double, double, String, int)
     */
    public static List<double[]> sample(int naifId, double centerEt, double durationSec, String frame) {
        return sample(naifId, centerEt, durationSec, frame, KepplrConstants.TRAIL_SAMPLES_PER_PERIOD);
    }

    /**
     * Sample the heliocentric J2000 trail for a body over one orbital period (or {@code durationSec}).
     *
     * @param naifId NAIF integer ID of the body
     * @param centerEt ET at the newest (body's current) end of the trail
     * @param durationSec total trail duration in seconds (= orbital period for period trails)
     * @param frame reference frame name (reserved; current implementation always uses J2000)
     * @param maxSamples maximum number of samples to collect (quality-tier cap)
     * @return list of {@code double[4]} arrays — [x, y, z] in km (heliocentric J2000) and [3] = sample ET — oldest
     *     first; never null
     */
    public static List<double[]> sample(int naifId, double centerEt, double durationSec, String frame, int maxSamples) {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();

        // Satellite path: IDs 100–999 not ending in 99, plus Pluto (999) orbiting barycenter 9
        boolean isSatellite =
                (naifId >= 100 && naifId <= 999 && naifId % 100 != 99) || naifId == KepplrConstants.PLUTO_NAIF_ID;

        // For satellites, anchor the orbital arc to the barycenter's position at centerEt.
        // baryAnchor[3] holds the barycenter's heliocentric J2000 position at centerEt.
        double[] baryAnchor = null;
        int barycenterId = -1;
        if (isSatellite) {
            barycenterId = naifId / 100;
            VectorIJK anchorPos = eph.getHeliocentricPositionJ2000(barycenterId, centerEt);
            if (anchorPos != null) {
                baryAnchor = new double[] {anchorPos.getI(), anchorPos.getJ(), anchorPos.getK()};
            }
        }

        double omega = 2.0 * Math.PI / durationSec;
        double minArcRad = Math.toRadians(KepplrConstants.TRAIL_MIN_ARC_DEG);
        double maxArcRad = Math.toRadians(KepplrConstants.TRAIL_MAX_ARC_DEG);

        // Single backward pass: centerEt → centerEt − durationSec
        // Collected newest-first, then reversed to yield oldest-first for TrailRenderer.
        List<double[]> backward = new ArrayList<>();
        double et = centerEt;
        while (et >= centerEt - durationSec && backward.size() < maxSamples) {
            double[] p = sampleOnePosition(naifId, barycenterId, baryAnchor, et, eph);
            if (p != null) backward.add(p);
            double f = Math.min((centerEt - et) / durationSec, 1.0);
            et -= (minArcRad + (maxArcRad - minArcRad) * f) / omega;
        }

        Collections.reverse(backward); // now oldest-first, newest-last
        return backward;
    }

    /**
     * Sample the heliocentric J2000 position for a single ET, handling both planet and satellite paths.
     *
     * <p>Package-private so {@link TrailManager} can call it each frame to keep the newest trail endpoint pinned to the
     * body's live position between full SPICE resamples.
     *
     * @return {@code double[4]} — [x, y, z] position in km and [3] = sample ET — or {@code null} if the ephemeris
     *     cannot resolve it
     */
    static double[] sampleOnePosition(
            int naifId, int barycenterId, double[] baryAnchor, double et, KEPPLREphemeris eph) {
        try {
            if (baryAnchor != null) {
                // Satellite: barycenter-relative position at et, anchored at centerEt
                VectorIJK satHelio = eph.getHeliocentricPositionJ2000(naifId, et);
                VectorIJK baryHelio = eph.getHeliocentricPositionJ2000(barycenterId, et);
                if (satHelio != null && baryHelio != null) {
                    return new double[] {
                        baryAnchor[0] + satHelio.getI() - baryHelio.getI(),
                        baryAnchor[1] + satHelio.getJ() - baryHelio.getJ(),
                        baryAnchor[2] + satHelio.getK() - baryHelio.getK(),
                        et
                    };
                }
            } else {
                VectorIJK pos = eph.getHeliocentricPositionJ2000(naifId, et);
                if (pos != null) {
                    return new double[] {pos.getI(), pos.getJ(), pos.getK(), et};
                }
            }
        } catch (Exception e) {
            logger.warn("Trail sample failed for NAIF {} at ET={}: {}", naifId, et, e.getMessage());
        }
        return null;
    }

    /**
     * Look up the gravitational parameter {@code BODY{naifId}_GM} from the kernel pool.
     *
     * @param spiceBundle bundle containing the kernel pool
     * @param naifId NAIF ID whose GM to look up
     * @return GM value in km³/s², or {@code null} if not found
     */
    private static Double lookupGm(SpiceBundle spiceBundle, int naifId) {
        try {
            var values = spiceBundle.getKernelPool().getDoubles("BODY" + naifId + "_GM");
            if (values != null && !values.isEmpty()) {
                return values.getFirst();
            }
        } catch (Exception e) {
            logger.warn("GM lookup failed for BODY{}_GM: {}", naifId, e.getMessage());
        }
        return null;
    }
}
