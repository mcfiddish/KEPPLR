package kepplr.ephemeris;

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.*;
import kepplr.config.KEPPLRConfiguration;
import kepplr.config.SpacecraftBlock;
import kepplr.ephemeris.spice.SpiceBundle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.functions.VectorIJKFunction;
import picante.math.vectorspace.RotationMatrixIJK;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.*;
import picante.mechanics.providers.aberrated.AberrationCorrection;
import picante.mechanics.providers.reference.ReferenceEphemerisLinkEvaluationException;
import picante.spice.fov.FOV;
import picante.spice.fov.FOVFactory;
import picante.surfaces.Ellipsoid;
import picante.surfaces.Surfaces;
import picante.time.TimeConversion;
import picante.units.FundamentalPhysicalConstants;

/**
 * Sole authority for all ephemeris and frame data (REDESIGN.md §1.1).
 *
 * <p>All body positions, frame transforms, time conversions, and aberration-corrected states must be obtained through
 * this interface. Implementations wrap the Picante (SPICE-compatible) ephemeris provider.
 *
 * <h3>Threading</h3>
 *
 * <p>Instances are accessed via {@code KEPPLRConfiguration.getInstance().getEphemeris()}, which returns a thread-local
 * instance. Callers must <strong>never</strong> store or pass a reference to this interface (REDESIGN.md §3.3).
 *
 * <h3>Units</h3>
 *
 * <ul>
 *   <li>Distances: <b>kilometers (km)</b>
 *   <li>Velocities: <b>km/s</b>
 *   <li>Time: <b>seconds</b> (ET = TDB seconds past J2000)
 *   <li>Angles: <b>radians</b> internally (§2.2)
 * </ul>
 *
 * <h3>Coordinate convention</h3>
 *
 * <p>The base inertial frame is <b>J2000</b> (equivalent to ICRF per §1.2). All "J2000" methods return vectors and
 * transforms in this frame.
 */
public class KEPPLREphemeris {
    private static final Logger logger = LogManager.getLogger();
    private final SpiceBundle spiceBundle;
    private final Set<EphemerisID> knownBodies;
    private Map<EphemerisID, Spacecraft> spacecraftMap;
    private NavigableSet<Instrument> instruments;

    private Map<EphemerisID, Ellipsoid> shapeMap;
    private Map<FrameID, StateTransformFunction> stateTransformMap;
    private Map<FrameID, StateTransformFunction> additionalStateTransformMap;
    private Map<EphemerisID, StateVectorFunction> sunToBodyMap;
    private final double speedOfLightKmPerSec =
            FundamentalPhysicalConstants.getInstance().getSpeedOfLightInKmPerSec();

    /**
     * Ephemeris object containing commonly used methods. It's usually best to access this using
     * {@link KEPPLRConfiguration#getEphemeris()}.
     *
     * @param metakernels metakernels to load
     */
    public KEPPLREphemeris(List<String> metakernels) {
        SpiceBundle.Builder builder = new SpiceBundle.Builder();
        builder.addKernelList(
                List.of(new File("/resources/spice/lsk/naif0012.tls"), new File("/resources/spice/pck/gm_de440.tpc")));
        this.spiceBundle = builder.addMetakernels(metakernels).build();

        this.knownBodies =
                Collections.unmodifiableSet(spiceBundle.getAbProvider().getKnownObjects(new HashSet<>()));

        shapeMap = new HashMap<>();
        for (EphemerisID body : this.knownBodies) {
            ImmutableList<Double> radii =
                    spiceBundle.getSpiceEnv().getBodyRadii().get(body);
            if (!radii.isEmpty())
                shapeMap.put(body, Surfaces.createEllipsoidalSurface(radii.get(0), radii.get(1), radii.get(2)));
        }
        shapeMap = Collections.unmodifiableMap(shapeMap);

        sunToBodyMap = new HashMap<>();
        for (EphemerisID body : this.knownBodies) {
            if (body.equals(CelestialBodies.SUN)) continue;

            sunToBodyMap.put(
                    body,
                    spiceBundle
                            .getAbProvider()
                            .createAberratedStateVectorFunction(
                                    body,
                                    CelestialBodies.SUN,
                                    CelestialFrames.J2000,
                                    Coverage.ALL_TIME,
                                    AberrationCorrection.NONE));
        }
        sunToBodyMap = Collections.unmodifiableMap(sunToBodyMap);

        stateTransformMap = new HashMap<>();
        for (EphemerisID body : this.knownBodies) {
            if (body.getName().endsWith("_BARYCENTER")) continue;
            FrameID bodyFixed = spiceBundle.getBodyFixedFrame(body);
            if (bodyFixed == null) continue;
            try {
                stateTransformMap.put(
                        bodyFixed,
                        spiceBundle
                                .getAbProvider()
                                .createStateTransformFunction(CelestialFrames.J2000, bodyFixed, Coverage.ALL_TIME));
            } catch (FrameSourceLinkException e) {
                logger.warn(e.getLocalizedMessage());
            }
        }
        stateTransformMap = Collections.unmodifiableMap(stateTransformMap);
        additionalStateTransformMap = new HashMap<>();

        instruments = new TreeSet<>();
        FOVFactory fovFactory = new FOVFactory(spiceBundle.getKernelPool());
        Set<String> keywords = spiceBundle.getKernelPool().getKeywords();
        for (String keyword : keywords) {
            if (keyword.startsWith("INS") && keyword.endsWith("_FOV_FRAME")) {
                String[] parts = keyword.split("_");
                int code = Integer.parseInt(parts[0].substring(3));
                EphemerisID ephemerisID = spiceBundle.getObject(code);
                FOV fov = fovFactory.create(code);
                FrameID frame = fov.getFrameID();
                EphemerisID center =
                        spiceBundle.getSpiceEnv().getFrameCenterMap().get(frame);
                if (center != null)
                    instruments.add(ImmutableInstrument.builder()
                            .id(ephemerisID)
                            .code(code)
                            .fov(fov)
                            .center(center)
                            .build());
            }
        }
        instruments = Collections.unmodifiableNavigableSet(instruments);

        spacecraftMap = new HashMap<>();
        KEPPLRConfiguration config = KEPPLRConfiguration.getInstance();
        config.spacecraft().forEach(i -> {
            SpacecraftBlock sb = config.spacecraftBlock(i);
            EphemerisID id = spiceBundle.getObject(sb.naifID());
            if (id != null) {
                FrameID frame =
                        sb.frame().isBlank() ? spiceBundle.getBodyFixedFrame(id) : spiceBundle.getFrame(sb.frame());
                if (frame == null) logger.warn("Could not find body fixed frame for {}", id.getName());
                else
                    spacecraftMap.put(
                            id,
                            ImmutableSpacecraft.builder()
                                    .id(id)
                                    .code(sb.naifID())
                                    .frameID(frame)
                                    .shapeModel(sb.shapeModel())
                                    .build());
            }
        });
        spacecraftMap = Collections.unmodifiableMap(spacecraftMap);
    }

    public Spacecraft getSpacecraft(EphemerisID id) {
        return spacecraftMap.get(id);
    }

    public Collection<Spacecraft> getSpacecraft() {
        return spacecraftMap.values();
    }

    public Set<Instrument> getInstruments() {
        return instruments;
    }

    /**
     * @param frame destination frame of the transform
     * @return function to transform a state vector in J2000 to the supplied frame
     */
    public StateTransformFunction j2000ToFrame(String frame) {
        return j2000ToFrame(spiceBundle.getFrame(frame));
    }

    /**
     * @param frame destination frame of the transform
     * @return function to transform a state vector in J2000 to the supplied frame
     */
    public StateTransformFunction j2000ToFrame(FrameID frame) {
        return additionalStateTransformMap.computeIfAbsent(frame, frameID -> spiceBundle
                .getAbProvider()
                .createStateTransformFunction(CelestialFrames.J2000, frame, Coverage.ALL_TIME));
    }

    public boolean hasBodyFixedFrame(int id) {
        EphemerisID body = spiceBundle.getObject(id);
        FrameID bodyFixed = spiceBundle.getBodyFixedFrame(body);
        return stateTransformMap.containsKey(bodyFixed);
    }

    public boolean hasBodyFixedFrame(EphemerisID id) {
        FrameID bodyFixed = spiceBundle.getBodyFixedFrame(id);
        return stateTransformMap.containsKey(bodyFixed);
    }

    public RotationMatrixIJK getJ2000ToBodyFixedRotation(EphemerisID id, double et) {
        return getJ2000ToBodyFixed(id).getTransform(et);
    }

    public RotationMatrixIJK getJ2000ToBodyFixedRotation(int id, double et) {
        EphemerisID body = spiceBundle.getObject(id);
        return getJ2000ToBodyFixed(body).getTransform(et);
    }

    /**
     * @param body body
     * @return {@link StateTransformFunction} to transform a state in J2000 to object's body fixed frame
     */
    public StateTransformFunction getJ2000ToBodyFixed(EphemerisID body) {
        FrameID bodyFixed = spiceBundle.getBodyFixedFrame(body);
        //        if (bodyFixed == null) logger.warn("Could not find body fixed frame for {}", body);
        return stateTransformMap.get(bodyFixed);
    }

    /**
     * Return a heliocentric J2000 position for a body NAIF ID.
     *
     * @param bodyId NAIF ID
     * @param et ephemeris time (TDB/ET)
     * @return Sun-centered J2000 position or null if unavailable
     */
    public VectorIJK getHeliocentricPositionJ2000(int bodyId, double et) {
        EphemerisID body = spiceBundle.getObject(bodyId);
        return getHeliocentricPositionJ2000(body, et);
    }

    /**
     * Return a heliocentric J2000 position for a body.
     *
     * @param body body identifier
     * @param et ephemeris time (TDB/ET)
     * @return Sun-centered J2000 position or null if unavailable
     */
    public VectorIJK getHeliocentricPositionJ2000(EphemerisID body, double et) {
        if (body == null) {
            return null;
        }
        if (CelestialBodies.SUN.equals(body)) {
            return new VectorIJK(0.0, 0.0, 0.0);
        }
        StateVectorFunction stateVector = getSunToBodyJ2000(body);
        if (stateVector == null) {
            return null;
        }
        try {
            return stateVector.getPosition(et);
        } catch (ReferenceEphemerisLinkEvaluationException e) {
            KEPPLRConfiguration config = KEPPLRConfiguration.getInstance();
            TimeConversion tc = config.getTimeConversion();
            logger.warn("Can't connect {} to SUN at {}", body.getName(), tc.tdbToUTCString(et, config.timeFormat()));
            return null;
        }
    }

    /**
     * Return a heliocentric J2000 position for a body NAIF ID.
     *
     * @param bodyId NAIF ID
     * @param et ephemeris time (TDB/ET)
     * @return Sun-centered J2000 position or null if unavailable
     */
    public StateVector getHeliocentricStateJ2000(int bodyId, double et) {
        EphemerisID body = spiceBundle.getObject(bodyId);
        return getHeliocentricStateJ2000(body, et);
    }

    /**
     * Return a heliocentric J2000 state for a body.
     *
     * @param body body identifier
     * @param et ephemeris time (TDB/ET)
     * @return Sun-centered J2000 position or null if unavailable
     */
    public StateVector getHeliocentricStateJ2000(EphemerisID body, double et) {
        if (body == null) {
            return null;
        }
        if (CelestialBodies.SUN.equals(body)) {
            return new StateVector();
        }
        StateVectorFunction stateVector = getSunToBodyJ2000(body);
        if (stateVector == null) {
            return null;
        }
        try {
            return stateVector.getState(et);
        } catch (ReferenceEphemerisLinkEvaluationException e) {
            KEPPLRConfiguration config = KEPPLRConfiguration.getInstance();
            TimeConversion tc = config.getTimeConversion();
            logger.warn("Can't connect {} to SUN at {}", body.getName(), tc.tdbToUTCString(et, config.timeFormat()));
            return null;
        }
    }

    /**
     * Return an observer-to-target J2000 position for NAIF IDs.
     *
     * @param observerId observer NAIF ID
     * @param targetId target NAIF ID
     * @param et ephemeris time (TDB/ET)
     * @param abCorr aberration correction
     * @return observer-to-target J2000 position or null if unavailable
     */
    public VectorIJK getObserverToTargetJ2000(int observerId, int targetId, double et, AberrationCorrection abCorr) {
        EphemerisID observer = spiceBundle.getObject(observerId);
        EphemerisID target = spiceBundle.getObject(targetId);
        return getObserverToTargetJ2000(observer, target, et, abCorr);
    }

    /**
     * Return an observer-to-target J2000 position.
     *
     * @param observer observer body
     * @param target target body
     * @param et ephemeris time (TDB/ET)
     * @param abCorr aberration correction
     * @return observer-to-target J2000 position or null if unavailable
     */
    public VectorIJK getObserverToTargetJ2000(
            EphemerisID observer, EphemerisID target, double et, AberrationCorrection abCorr) {
        if (observer == null || target == null) {
            return null;
        }
        StateVectorFunction function = getObserverToTargetJ2000(observer, target, abCorr);
        if (function == null) {
            return null;
        }
        try {
            return function.getPosition(et);
        } catch (ReferenceEphemerisLinkEvaluationException e) {
            KEPPLRConfiguration config = KEPPLRConfiguration.getInstance();
            TimeConversion tc = config.getTimeConversion();
            logger.warn(
                    "Can't connect {} to {} at {}",
                    target.getName(),
                    observer.getName(),
                    tc.tdbToUTCString(et, config.timeFormat()));
            return null;
        }
    }

    /**
     * Compute one-way light time in seconds for an observer-to-target vector.
     *
     * @param observerToTargetJ2000 observer-to-target vector in km
     * @return light time in seconds, or 0 if unavailable
     */
    public double computeLightTimeSeconds(VectorIJK observerToTargetJ2000) {
        if (observerToTargetJ2000 == null || speedOfLightKmPerSec <= 0.0) {
            return 0.0;
        }
        double length = observerToTargetJ2000.getLength();
        if (Double.isNaN(length) || !(length > 0.0)) return 0.0;

        return length / speedOfLightKmPerSec;
    }

    /**
     * Resolve the J2000-to-body-fixed rotation at the evaluation time.
     *
     * @param bodyId NAIF ID
     * @param et ephemeris time (TDB/ET)
     * @param lightTimeSecondsOrNull light time seconds for observer mode, or null
     * @return rotation matrix or null if unavailable
     */
    public RotationMatrixIJK getJ2000ToBodyFixedAtEvalTime(int bodyId, double et, Double lightTimeSecondsOrNull) {
        EphemerisID body = spiceBundle.getObject(bodyId);
        return getJ2000ToBodyFixedAtEvalTime(body, et, lightTimeSecondsOrNull);
    }

    /**
     * Resolve the J2000-to-body-fixed rotation at the evaluation time.
     *
     * @param body body identifier
     * @param et ephemeris time (TDB/ET)
     * @param lightTimeSecondsOrNull light time seconds for observer mode, or null
     * @return rotation matrix or null if unavailable
     */
    public RotationMatrixIJK getJ2000ToBodyFixedAtEvalTime(EphemerisID body, double et, Double lightTimeSecondsOrNull) {
        if (body == null) {
            return null;
        }
        StateTransformFunction j2bf = getJ2000ToBodyFixed(body);
        if (j2bf == null) {
            return null;
        }
        double evalEt = lightTimeSecondsOrNull != null ? et - lightTimeSecondsOrNull : et;
        return j2bf.getTransform(evalEt);
    }

    /**
     * Transform a J2000 position to body-fixed at the correct evaluation time.
     *
     * @param bodyId NAIF ID
     * @param et ephemeris time (TDB/ET)
     * @param posJ2000 position in J2000 km
     * @param observerMode true when observer-centered rendering is active
     * @param lightTimeSecondsOrNull light time seconds if observer mode is active
     * @return position in body-fixed km, or null if unavailable
     */
    public VectorIJK toBodyFixedPosition(
            int bodyId, double et, VectorIJK posJ2000, boolean observerMode, Double lightTimeSecondsOrNull) {
        EphemerisID body = spiceBundle.getObject(bodyId);
        return toBodyFixedPosition(body, et, posJ2000, observerMode, lightTimeSecondsOrNull);
    }

    /**
     * Transform a J2000 position to body-fixed at the correct evaluation time.
     *
     * @param body body identifier
     * @param et ephemeris time (TDB/ET)
     * @param posJ2000 position in J2000 km
     * @param observerMode true when observer-centered rendering is active
     * @param lightTimeSecondsOrNull light time seconds if observer mode is active
     * @return position in body-fixed km, or null if unavailable
     */
    public VectorIJK toBodyFixedPosition(
            EphemerisID body, double et, VectorIJK posJ2000, boolean observerMode, Double lightTimeSecondsOrNull) {
        if (body == null || posJ2000 == null) {
            return null;
        }
        StateTransformFunction j2bf = getJ2000ToBodyFixed(body);
        if (j2bf == null) {
            return null;
        }
        double evalEt = observerMode && lightTimeSecondsOrNull != null ? et - lightTimeSecondsOrNull : et;
        return j2bf.getTransform(evalEt).mxv(posJ2000);
    }

    public Set<EphemerisID> getKnownBodies() {
        return knownBodies;
    }

    /**
     * @param body body
     * @return triaxial ellipsoid for the body shape
     */
    public Ellipsoid getShape(EphemerisID body) {
        return shapeMap.get(body);
    }

    public SpiceBundle getSpiceBundle() {
        return spiceBundle;
    }

    /**
     * @param body body
     * @return Cartesian coordinates of subsolar point on body in body fixed coordinates
     */
    public VectorIJKFunction getSubSolarPoint(EphemerisID body) {
        FrameTransformFunction ftf = getJ2000ToBodyFixed(body);
        return (t, buffer) -> {
            RotationMatrixIJK rot = ftf.getTransform(t);
            VectorIJK source = rot.mxv(getSunToBodyJ2000(body).getPosition(t).negate());
            VectorIJK ray = source.createNegated();

            return shapeMap.get(body).compute(source, ray, buffer);
        };
    }

    /**
     * @param body body
     * @return body's state relative to Sun, in J2000, with {@link AberrationCorrection#NONE}
     */
    public StateVectorFunction getSunToBodyJ2000(EphemerisID body) {
        return sunToBodyMap.get(body);
    }

    /**
     * @param observer observer
     * @param target target
     * @param abCorr usually either {@link AberrationCorrection#NONE} or {@link AberrationCorrection#LT_S}
     * @return function to evaluate target state relative to observer in J2000
     */
    public StateVectorFunction getObserverToTargetJ2000(
            EphemerisID observer, EphemerisID target, AberrationCorrection abCorr) {
        return spiceBundle
                .getAbProvider()
                .createAberratedStateVectorFunction(target, observer, CelestialFrames.J2000, Coverage.ALL_TIME, abCorr);
    }
}
