package kepplr.ephemeris;

import java.util.Set;
import picante.math.vectorspace.RotationMatrixIJK;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.EphemerisID;
import picante.mechanics.StateVector;
import picante.surfaces.Ellipsoid;

/**
 * Sole authority for all ephemeris and frame data (REDESIGN.md §1.1).
 *
 * <p>All body positions, frame transforms, time conversions, and aberration-corrected
 * states must be obtained through this interface. Implementations wrap the Picante
 * (SPICE-compatible) ephemeris provider.
 *
 * <h3>Threading</h3>
 * <p>Instances are accessed via {@code KEPPLRConfiguration.getInstance().getEphemeris()},
 * which returns a thread-local instance. Callers must <strong>never</strong> store or pass
 * a reference to this interface (REDESIGN.md §3.3).
 *
 * <h3>Units</h3>
 * <ul>
 *   <li>Distances: <b>kilometers (km)</b></li>
 *   <li>Velocities: <b>km/s</b></li>
 *   <li>Time: <b>seconds</b> (ET = TDB seconds past J2000)</li>
 *   <li>Angles: <b>radians</b> internally (§2.2)</li>
 * </ul>
 *
 * <h3>Coordinate convention</h3>
 * <p>The base inertial frame is <b>J2000</b> (equivalent to ICRF per §1.2).
 * All "J2000" methods return vectors and transforms in this frame.
 */
public interface KEPPLREphemeris {

    // =====================================================================
    // Heliocentric body state queries (§1.1, §1.3)
    // =====================================================================

    /**
     * Heliocentric J2000 position of a body.
     *
     * <p>The Sun itself returns the zero vector. Bodies without valid ephemeris
     * at the requested epoch return {@code null}.
     *
     * @param naifId NAIF ID of the body
     * @param et     ephemeris time (TDB seconds past J2000)
     * @return Sun-centered J2000 position in km, or {@code null} if unavailable
     */
    VectorIJK getHeliocentricPositionJ2000(int naifId, double et);

    /**
     * Heliocentric J2000 state (position + velocity) of a body.
     *
     * @param naifId NAIF ID of the body
     * @param et     ephemeris time (TDB seconds past J2000)
     * @return Sun-centered J2000 state in km and km/s, or {@code null} if unavailable
     */
    StateVector getHeliocentricStateJ2000(int naifId, double et);

    // =====================================================================
    // Observer-to-target queries with aberration correction (§1.1, §6)
    // =====================================================================

    /**
     * Observer-to-target position in J2000 with the specified aberration correction.
     *
     * <p>Returns the position of {@code targetId} as seen from {@code observerId}
     * at epoch {@code et}, applying the requested correction (§6.1: NONE or LT_S).
     *
     * @param observerId NAIF ID of the observer
     * @param targetId   NAIF ID of the target
     * @param et         ephemeris time (TDB seconds past J2000)
     * @param abCorr     aberration correction mode
     * @return observer-to-target J2000 position in km, or {@code null} if unavailable
     */
    VectorIJK getTargetPositionJ2000(int observerId, int targetId, double et, AberrationCorrection abCorr);

    /**
     * Observer-to-target state in J2000 with the specified aberration correction.
     *
     * @param observerId NAIF ID of the observer
     * @param targetId   NAIF ID of the target
     * @param et         ephemeris time (TDB seconds past J2000)
     * @param abCorr     aberration correction mode
     * @return observer-to-target J2000 state in km and km/s, or {@code null} if unavailable
     */
    StateVector getTargetStateJ2000(int observerId, int targetId, double et, AberrationCorrection abCorr);

    // =====================================================================
    // Frame transforms (§1.1, §7.2)
    // =====================================================================

    /**
     * J2000-to-body-fixed rotation matrix at the given epoch.
     *
     * <p>Used to orient body textures correctly (§7.2). Returns {@code null}
     * if the body has no PCK/orientation data (§12.3).
     *
     * @param naifId NAIF ID of the body
     * @param et     ephemeris time (TDB seconds past J2000)
     * @return rotation matrix from J2000 to body-fixed frame, or {@code null}
     */
    RotationMatrixIJK getJ2000ToBodyFixedRotation(int naifId, double et);

    /**
     * J2000-to-body-fixed rotation accounting for light-time delay.
     *
     * <p>When rendering from an observer's perspective, the body-fixed frame should
     * be evaluated at {@code et - lightTimeSec} to match the retarded position.
     * Pass {@code null} for geometric (non-observer) mode.
     *
     * @param naifId         NAIF ID of the body
     * @param et             ephemeris time (TDB seconds past J2000)
     * @param lightTimeSec   one-way light-time in seconds, or {@code null} for geometric
     * @return rotation matrix, or {@code null} if body has no orientation data
     */
    RotationMatrixIJK getJ2000ToBodyFixedRotation(int naifId, double et, Double lightTimeSec);

    /**
     * Whether a body-fixed frame is available for the given body.
     *
     * @param naifId NAIF ID of the body
     * @return {@code true} if J2000-to-body-fixed transforms can be computed
     */
    boolean hasBodyFixedFrame(int naifId);

    /**
     * J2000-to-named-frame rotation matrix at the given epoch.
     *
     * <p>Supports arbitrary SPICE frames by name (e.g., "IAU_EARTH", "ECLIPJ2000").
     *
     * @param frameName SPICE frame name
     * @param et        ephemeris time (TDB seconds past J2000)
     * @return rotation matrix from J2000 to the named frame
     * @throws IllegalArgumentException if the frame name is unknown
     */
    RotationMatrixIJK getJ2000ToFrameRotation(String frameName, double et);

    // =====================================================================
    // Time conversions (§1.1, §1.2)
    // =====================================================================

    /**
     * Convert a UTC string to ET (TDB seconds past J2000).
     *
     * @param utc UTC time string in any SPICE-recognized format
     * @return corresponding ephemeris time
     */
    double utcToEt(String utc);

    /**
     * Convert ET to a UTC string.
     *
     * @param et     ephemeris time (TDB seconds past J2000)
     * @param format SPICE-compatible format string (e.g., "C" for calendar)
     * @return formatted UTC string
     */
    String etToUtc(double et, String format);

    // =====================================================================
    // Light-time computation (§6)
    // =====================================================================

    /**
     * Compute one-way light-time in seconds for a position vector.
     *
     * @param positionKm position vector in km (typically observer-to-target)
     * @return light-time in seconds
     */
    double computeLightTimeSeconds(VectorIJK positionKm);

    // =====================================================================
    // Body metadata (§4.1, §7.2, §7.3)
    // =====================================================================

    /**
     * Set of all body NAIF IDs known to the loaded ephemeris.
     *
     * @return unmodifiable set of NAIF IDs
     */
    Set<Integer> getKnownBodyIds();

    /**
     * Set of all known body identifiers as Picante {@link EphemerisID} objects.
     *
     * @return unmodifiable set of ephemeris identifiers
     */
    Set<EphemerisID> getKnownBodies();

    /**
     * Triaxial ellipsoid shape for a body.
     *
     * <p>Returns {@code null} if no radii are available for the given body.
     *
     * @param naifId NAIF ID of the body
     * @return body shape as an ellipsoid, or {@code null}
     */
    Ellipsoid getBodyShape(int naifId);

    /**
     * Human-readable name for a NAIF ID.
     *
     * @param naifId NAIF ID
     * @return body name, or {@code null} if unknown
     */
    String getBodyName(int naifId);

    /**
     * NAIF ID for a body name.
     *
     * @param name body name (case-insensitive match recommended)
     * @return NAIF ID, or {@code null} if unknown
     */
    Integer getBodyId(String name);

    /**
     * Resolve a NAIF integer ID to a Picante {@link EphemerisID}.
     *
     * @param naifId NAIF ID
     * @return corresponding {@link EphemerisID}, or {@code null} if unknown
     */
    EphemerisID toEphemerisId(int naifId);
}
