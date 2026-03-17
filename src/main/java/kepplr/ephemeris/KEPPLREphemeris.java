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
 * Primary high-level access point for ephemeris and frame data (REDESIGN.md §1.1).
 *
 * <p>This class centralizes body positions, frame transforms, time conversions, and aberration-corrected states on top
 * of the Picante SPICE-compatible ephemeris provider.
 *
 * <h3>Threading</h3>
 *
 * <p>Instances are typically accessed via {@code KEPPLRConfiguration.getInstance().getEphemeris()}, which returns a
 * thread-local instance. Callers should avoid storing or passing references across threads (REDESIGN.md §3.3).
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
     * Creates a new ephemeris service backed by the supplied metakernels.
     *
     * <p>In addition to the provided metakernel list, this constructor loads the default leap-seconds and planetary
     * constants kernels required by the application. It then initializes the known-body set, available body shapes,
     * J2000-to-body-fixed transforms, instrument definitions, and configured spacecraft metadata.
     *
     * @param metakernels metakernel paths to load into the backing {@link SpiceBundle}
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
                else {
                    spacecraftMap.put(
                            id,
                            ImmutableSpacecraft.builder()
                                    .id(id)
                                    .code(sb.naifID())
                                    .frameID(frame)
                                    .shapeModel(sb.shapeModel())
                                    .build());
                    stateTransformMap.put(
                            frame,
                            spiceBundle
                                    .getAbProvider()
                                    .createStateTransformFunction(CelestialFrames.J2000, frame, Coverage.ALL_TIME));
                }
            }
        });
        spacecraftMap = Collections.unmodifiableMap(spacecraftMap);
        stateTransformMap = Collections.unmodifiableMap(stateTransformMap);
    }

    /**
     * Returns the configured spacecraft metadata for a specific ephemeris object.
     *
     * <p>This lookup only includes spacecraft declared in the active {@link KEPPLRConfiguration}. Bodies that exist in
     * the loaded SPICE kernels but are not configured as spacecraft will return {@code null}.
     *
     * @param id spacecraft ephemeris identifier
     * @return spacecraft metadata for {@code id}, or {@code null} if the identifier is not configured as a spacecraft
     */
    public Spacecraft getSpacecraft(EphemerisID id) {
        return spacecraftMap.get(id);
    }

    /**
     * Returns all configured spacecraft metadata objects.
     *
     * <p>The returned collection is backed by an unmodifiable map and reflects the spacecraft entries resolved during
     * construction from {@link KEPPLRConfiguration}.
     *
     * @return unmodifiable collection of configured spacecraft
     */
    public Collection<Spacecraft> getSpacecraft() {
        return spacecraftMap.values();
    }

    /**
     * Returns the instrument definitions discovered from the loaded kernel pool.
     *
     * <p>Each instrument includes its NAIF code, field-of-view definition, and resolved center body when those data are
     * available from SPICE kernel metadata.
     *
     * @return unmodifiable set of known instruments
     */
    public Set<Instrument> getInstruments() {
        return instruments;
    }

    /**
     * Returns a transform function from J2000 to a named destination frame.
     *
     * <p>This overload first resolves the frame name through the backing {@link SpiceBundle} and then delegates to
     * {@link #j2000ToFrame(FrameID)}.
     *
     * @param frame destination frame name
     * @return function that transforms a state from J2000 into the resolved frame
     */
    public StateTransformFunction j2000ToFrame(String frame) {
        return j2000ToFrame(spiceBundle.getFrame(frame));
    }

    /**
     * Returns a transform function from J2000 to a destination frame.
     *
     * <p>The transform is created on first use and cached in {@code additionalStateTransformMap} for reuse on
     * subsequent calls.
     *
     * @param frame destination frame identifier
     * @return function that transforms a state from J2000 into {@code frame}
     */
    public StateTransformFunction j2000ToFrame(FrameID frame) {
        return additionalStateTransformMap.computeIfAbsent(frame, frameID -> spiceBundle
                .getAbProvider()
                .createStateTransformFunction(CelestialFrames.J2000, frame, Coverage.ALL_TIME));
    }

    /**
     * Determines whether a NAIF object code has a resolvable body-fixed frame transform from J2000.
     *
     * @param id NAIF body code
     * @return {@code true} if a body-fixed frame exists and a J2000-to-body-fixed transform was created
     */
    public boolean hasBodyFixedFrame(int id) {
        EphemerisID body = spiceBundle.getObject(id);
        return hasBodyFixedFrame(body);
    }

    /**
     * Determines whether a body has a resolvable body-fixed frame transform from J2000.
     *
     * @param id body identifier
     * @return {@code true} if a body-fixed frame exists and a J2000-to-body-fixed transform was created
     */
    public boolean hasBodyFixedFrame(EphemerisID id) {
        FrameID bodyFixed =
                spacecraftMap.containsKey(id) ? spacecraftMap.get(id).frameID() : spiceBundle.getBodyFixedFrame(id);
        return stateTransformMap.containsKey(bodyFixed);
    }

    /**
     * Evaluates the J2000-to-body-fixed rotation for a body at a specific ephemeris time.
     *
     * @param id body identifier
     * @param et ephemeris time in TDB seconds past J2000
     * @return rotation matrix from J2000 into the body's fixed frame
     * @throws NullPointerException if no body-fixed transform is available for {@code id}
     */
    public RotationMatrixIJK getJ2000ToBodyFixedRotation(EphemerisID id, double et) {
        return getJ2000ToBodyFixed(id).getTransform(et);
    }

    /**
     * Evaluates the J2000-to-body-fixed rotation for a NAIF body code at a specific ephemeris time.
     *
     * @param id NAIF body code
     * @param et ephemeris time in TDB seconds past J2000
     * @return rotation matrix from J2000 into the body's fixed frame
     * @throws NullPointerException if no body-fixed transform is available for {@code id}
     */
    public RotationMatrixIJK getJ2000ToBodyFixedRotation(int id, double et) {
        EphemerisID body = spiceBundle.getObject(id);
        return getJ2000ToBodyFixed(body).getTransform(et);
    }

    /**
     * Returns the J2000-to-body-fixed transform for a body.
     *
     * @param body body identifier
     * @return transform from J2000 into the body's body-fixed frame, or {@code null} if no body-fixed frame is known
     */
    public StateTransformFunction getJ2000ToBodyFixed(EphemerisID body) {
        FrameID bodyFixed = spacecraftMap.containsKey(body)
                ? spacecraftMap.get(body).frameID()
                : spiceBundle.getBodyFixedFrame(body);
        //        if (bodyFixed == null) logger.warn("Could not find body fixed frame for {}", body);
        return stateTransformMap.get(bodyFixed);
    }

    /**
     * Returns the heliocentric J2000 position for a NAIF body code.
     *
     * @param bodyId NAIF body code
     * @param et ephemeris time in TDB seconds past J2000
     * @return vector from the Sun to the body in J2000, or {@code null} if the body cannot be resolved or evaluated
     */
    public VectorIJK getHeliocentricPositionJ2000(int bodyId, double et) {
        EphemerisID body = spiceBundle.getObject(bodyId);
        return getHeliocentricPositionJ2000(body, et);
    }

    /**
     * Returns the heliocentric J2000 position for a body.
     *
     * @param body body identifier
     * @param et ephemeris time in TDB seconds past J2000
     * @return vector from the Sun to {@code body} in J2000; returns the zero vector for the Sun itself, or {@code null}
     *     if the body is {@code null}, missing from the loaded kernels, or cannot be evaluated
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
     * Returns the heliocentric J2000 state for a NAIF body code.
     *
     * @param bodyId NAIF body code
     * @param et ephemeris time in TDB seconds past J2000
     * @return state from the Sun to the body in J2000, or {@code null} if the body cannot be resolved or evaluated
     */
    public StateVector getHeliocentricStateJ2000(int bodyId, double et) {
        EphemerisID body = spiceBundle.getObject(bodyId);
        return getHeliocentricStateJ2000(body, et);
    }

    /**
     * Returns the heliocentric J2000 state for a body.
     *
     * @param body body identifier
     * @param et ephemeris time in TDB seconds past J2000
     * @return state from the Sun to {@code body} in J2000; returns a zero state for the Sun itself, or {@code null} if
     *     the body is {@code null}, missing from the loaded kernels, or cannot be evaluated
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
     * Returns the observer-to-target J2000 position for NAIF body codes.
     *
     * @param observerId observer NAIF body code
     * @param targetId target NAIF body code
     * @param et ephemeris time in TDB seconds past J2000
     * @param abCorr aberration correction
     * @return vector from the observer to the target in J2000, or {@code null} if either body cannot be resolved or the
     *     state cannot be evaluated
     */
    public VectorIJK getObserverToTargetJ2000(int observerId, int targetId, double et, AberrationCorrection abCorr) {
        EphemerisID observer = spiceBundle.getObject(observerId);
        EphemerisID target = spiceBundle.getObject(targetId);
        return getObserverToTargetJ2000(observer, target, et, abCorr);
    }

    /**
     * Returns the observer-to-target J2000 position.
     *
     * @param observer observer body
     * @param target target body
     * @param et ephemeris time in TDB seconds past J2000
     * @param abCorr aberration correction
     * @return vector from {@code observer} to {@code target} in J2000, or {@code null} if either argument is
     *     {@code null}, no function can be created, or the state cannot be evaluated
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
     * Computes the one-way light time for an observer-to-target vector.
     *
     * @param observerToTargetJ2000 observer-to-target vector in km
     * @return light time in seconds, or {@code 0} if the vector is {@code null}, zero-length, non-finite, or the speed
     *     of light constant is unavailable
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
     * Resolves the J2000-to-body-fixed rotation at the appropriate evaluation time.
     *
     * @param bodyId NAIF body code
     * @param et ephemeris time in TDB seconds past J2000
     * @param lightTimeSecondsOrNull light time in seconds to subtract from {@code et}, or {@code null} to evaluate at
     *     {@code et}
     * @return rotation matrix from J2000 into the body's fixed frame, or {@code null} if the body cannot be resolved or
     *     no body-fixed transform exists
     */
    public RotationMatrixIJK getJ2000ToBodyFixedAtEvalTime(int bodyId, double et, Double lightTimeSecondsOrNull) {
        EphemerisID body = spiceBundle.getObject(bodyId);
        return getJ2000ToBodyFixedAtEvalTime(body, et, lightTimeSecondsOrNull);
    }

    /**
     * Resolves the J2000-to-body-fixed rotation at the appropriate evaluation time.
     *
     * @param body body identifier
     * @param et ephemeris time in TDB seconds past J2000
     * @param lightTimeSecondsOrNull light time in seconds to subtract from {@code et}, or {@code null} to evaluate at
     *     {@code et}
     * @return rotation matrix from J2000 into the body's fixed frame, or {@code null} if {@code body} is {@code null}
     *     or no body-fixed transform exists
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
     * Transforms a J2000 position into body-fixed coordinates using the appropriate evaluation time.
     *
     * @param bodyId NAIF body code
     * @param et ephemeris time in TDB seconds past J2000
     * @param posJ2000 position in J2000 km
     * @param observerMode true when observer-centered rendering is active
     * @param lightTimeSecondsOrNull light time in seconds to subtract from {@code et} when {@code observerMode} is
     *     {@code true}; ignored otherwise
     * @return position in body-fixed kilometers, or {@code null} if the body cannot be resolved, {@code posJ2000} is
     *     {@code null}, or no body-fixed transform exists
     */
    public VectorIJK toBodyFixedPosition(
            int bodyId, double et, VectorIJK posJ2000, boolean observerMode, Double lightTimeSecondsOrNull) {
        EphemerisID body = spiceBundle.getObject(bodyId);
        return toBodyFixedPosition(body, et, posJ2000, observerMode, lightTimeSecondsOrNull);
    }

    /**
     * Transforms a J2000 position into body-fixed coordinates using the appropriate evaluation time.
     *
     * @param body body identifier
     * @param et ephemeris time in TDB seconds past J2000
     * @param posJ2000 position in J2000 km
     * @param observerMode true when observer-centered rendering is active
     * @param lightTimeSecondsOrNull light time in seconds to subtract from {@code et} when {@code observerMode} is
     *     {@code true}; ignored otherwise
     * @return position in body-fixed kilometers, or {@code null} if {@code body} or {@code posJ2000} is {@code null},
     *     or no body-fixed transform exists
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

    /**
     * Returns the complete set of ephemeris objects known to the loaded aberrated ephemeris provider.
     *
     * <p>This includes bodies made available by the loaded kernels, not just configured spacecraft or bodies with shape
     * models.
     *
     * @return unmodifiable set of known ephemeris identifiers
     */
    public Set<EphemerisID> getKnownBodies() {
        return knownBodies;
    }

    /**
     * Returns the triaxial ellipsoid shape model for a body.
     *
     * @param body body identifier
     * @return body's ellipsoid shape model, or {@code null} if the loaded kernels do not provide radii for the body
     */
    public Ellipsoid getShape(EphemerisID body) {
        return shapeMap.get(body);
    }

    /**
     * Returns the underlying SPICE bundle used by this ephemeris service.
     *
     * <p>This exposes lower-level SPICE and Picante access for callers that need capabilities not wrapped directly by
     * {@code KEPPLREphemeris}.
     *
     * @return backing SPICE bundle for this ephemeris instance
     */
    public SpiceBundle getSpiceBundle() {
        return spiceBundle;
    }

    /**
     * Returns a function that evaluates the sub-solar point on a body's reference ellipsoid.
     *
     * <p>The returned function computes the point where the ray from the body toward the Sun intersects the body's
     * shape model, expressed in the body's fixed frame.
     *
     * @param body body identifier
     * @return function that evaluates the sub-solar point in body-fixed Cartesian coordinates
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
     * Returns the cached Sun-to-body state function in J2000.
     *
     * @param body body identifier
     * @return function that evaluates the state vector from the Sun to {@code body} in J2000 with
     *     {@link AberrationCorrection#NONE}, or {@code null} if no such function was initialized
     */
    public StateVectorFunction getSunToBodyJ2000(EphemerisID body) {
        return sunToBodyMap.get(body);
    }

    /**
     * Creates an observer-to-target state function in J2000.
     *
     * @param observer observer body
     * @param target target body
     * @param abCorr aberration correction, typically {@link AberrationCorrection#NONE} or
     *     {@link AberrationCorrection#LT_S}
     * @return function that evaluates the state vector from {@code observer} to {@code target} in J2000
     */
    public StateVectorFunction getObserverToTargetJ2000(
            EphemerisID observer, EphemerisID target, AberrationCorrection abCorr) {
        return spiceBundle
                .getAbProvider()
                .createAberratedStateVectorFunction(target, observer, CelestialFrames.J2000, Coverage.ALL_TIME, abCorr);
    }
}
