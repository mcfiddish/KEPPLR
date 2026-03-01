package kepplr.ephemeris.spice;

import com.google.common.io.Resources;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.mechanics.*;
import picante.mechanics.providers.aberrated.AberratedEphemerisProvider;
import picante.mechanics.providers.lockable.LockableEphemerisProvider;
import picante.mechanics.providers.reference.ReferenceEphemerisProvider;
import picante.spice.MetakernelReader;
import picante.spice.SpiceEnvironment;
import picante.spice.SpiceEnvironmentBuilder;
import picante.spice.adapters.SpiceEphemerisID;
import picante.spice.adapters.SpiceFrameID;
import picante.spice.kernel.KernelInstantiationException;
import picante.spice.kernelpool.KernelPool;
import picante.spice.kernelpool.UnwritableKernelPool;
import picante.spice.provided.EphemerisNames;
import picante.spice.provided.FrameNames;
import picante.time.TimeConversion;

/**
 * Container class with a {@link SpiceEnvironment}, {@link AberratedEphemerisProvider}, and
 * {@link UnwritableKernelPool}.
 *
 * @author nairah1
 */
public class SpiceBundle {

    private static final Logger logger = LogManager.getLogger();

    private SpiceEnvironment spiceEnv;
    private AberratedEphemerisProvider abProvider;
    private UnwritableKernelPool kernelPool;
    private TimeConversion timeConversion;

    private List<File> loadedKernels;
    private Map<String, EphemerisID> objectNameBindings;
    private Map<Integer, EphemerisID> objectIDBindings;
    private Map<EphemerisID, String> objectNameReverseBindings;
    private Map<EphemerisID, Integer> objectIDReverseBindings;
    private Map<String, FrameID> frameNameBindings;
    private Map<Integer, FrameID> frameIDBindings;
    private Map<EphemerisID, FrameID> bodyFixedFrames;

    public static final class Builder {
        private final List<File> kernels;
        private final KernelPool kernelPool;
        private final List<PositionVectorFunction> additionalEphemerisSources;
        private final List<FrameTransformFunction> additionalFrameSources;
        private final Map<FrameID, EphemerisID> additionalFrameCenters;
        private boolean lockable;
        private SpiceEnvironmentBuilder builder;
        private final Map<String, EphemerisID> objectNameBindings;
        private final Map<Integer, EphemerisID> objectIDBindings;
        private Map<EphemerisID, String> objectNameReverseBindings;
        private Map<EphemerisID, Integer> objectIDReverseBindings;
        private final Map<String, FrameID> frameNameBindings;
        private final Map<Integer, FrameID> frameIDBindings;

        /**
         * Add these kernels to the kernel list
         *
         * @param kernels kernels to add
         * @return builder
         */
        public Builder addKernelList(List<File> kernels) {
            this.kernels.addAll(kernels);
            return this;
        }

        /**
         * Add this kernel pool to the existing kernel pool
         *
         * @param kernelPool kernel pool to add
         * @return builder
         */
        public Builder addKernelPool(KernelPool kernelPool) {
            this.kernelPool.load(kernelPool);
            return this;
        }

        /**
         * Add the kernels and kernel pool variables in this list of metakernels to the existing kernel list and kernel
         * pool
         *
         * @param metakernels metakernels to add
         * @return builder
         */
        public Builder addMetakernels(List<String> metakernels) {
            List<File> kernelPaths = new ArrayList<>();
            KernelPool kernelPool = new KernelPool();

            for (String mk : metakernels) {
                MetakernelReader mkReader = new MetakernelReader(mk);
                if (mkReader.isGood()) {
                    kernelPaths.addAll(mkReader.getKernelsToLoad());
                    kernelPool.load(mkReader.getKernelPool());
                    if (mkReader.hasWarnings()) {
                        for (String s : mkReader.getWarnLog()) logger.warn(s.trim());
                    }
                } else {
                    for (String s : mkReader.getErrLog()) logger.warn(s.trim());
                    logger.warn("Did not load {}", mk);
                }
            }

            addKernelList(kernelPaths);
            addKernelPool(kernelPool);
            return this;
        }

        /**
         * Add these ephemerisSources to the {@link EphemerisAndFrameProvider} after the SPICE kernels have been loaded.
         *
         * @param funcs ephemeris sources to add
         * @return builder
         */
        public Builder addEphemerisSources(List<PositionVectorFunction> funcs) {
            additionalEphemerisSources.addAll(funcs);
            return this;
        }

        /**
         * Add these frameSources to the {@link EphemerisAndFrameProvider} after the SPICE kernels have been loaded.
         *
         * @param funcs frame sources to add
         * @return builder
         */
        public Builder addFrameSources(List<FrameTransformFunction> funcs) {
            additionalFrameSources.addAll(funcs);
            return this;
        }

        /**
         * Add these FrameID -> EphemerisID mappings.
         *
         * @param map frame centers to add
         * @return builder
         */
        public Builder addFrameCenters(Map<FrameID, EphemerisID> map) {
            additionalFrameCenters.putAll(map);
            return this;
        }

        /**
         * @param lockable If true, use a {@link LockableEphemerisProvider} rather than the default
         *     {@link ReferenceEphemerisProvider}.
         * @return builder
         */
        public Builder setLockable(boolean lockable) {
            this.lockable = lockable;
            return this;
        }

        public Builder setSpiceEnvironmentBuilder(SpiceEnvironmentBuilder builder) {
            this.builder = builder;
            return this;
        }

        public Builder() {
            this.kernels = new ArrayList<>();
            this.kernelPool = new KernelPool();
            this.additionalEphemerisSources = new ArrayList<>();
            this.additionalFrameSources = new ArrayList<>();
            this.additionalFrameCenters = new LinkedHashMap<>();
            lockable = false;
            builder = new SpiceEnvironmentBuilder();
            objectNameBindings = new HashMap<>();
            objectIDBindings = new HashMap<>();
            objectIDReverseBindings = new HashMap<>();
            objectNameReverseBindings = new HashMap<>();
            frameNameBindings = new HashMap<>();
            frameIDBindings = new HashMap<>();
        }

        public SpiceBundle build() {
            try {
                for (File kernel : kernels) {
                    try {

                        if (kernel.exists()) {
                            builder.load(kernel.getCanonicalPath(), kernel);
                        } else {
                            // try loading this path as a resource
                            URL resource = SpiceBundle.class.getResource(kernel.getPath());
                            if (resource == null) {
                                logger.warn("Cannot read kernel {}", kernel.getPath());
                                continue;
                            }
                            builder.load(kernel.getCanonicalPath(), Resources.asByteSource(resource));
                        }

                    } catch (KernelInstantiationException e) {
                        logger.warn(e.getLocalizedMessage());
                        logger.warn("Using forgiving loader for unsupported kernel format: {}.", kernel.getPath());
                        builder.forgivingLoad(kernel.getCanonicalPath(), kernel);
                    }
                }
            } catch (KernelInstantiationException | IOException e) {
                logger.warn(e.getLocalizedMessage(), e);
            }

            // now bind name/code pairs to ephemeris IDs
            KernelPool mkPool = new KernelPool(kernelPool);
            SpiceEnvironment env = builder.build();
            UnwritableKernelPool envPool = env.getPool();
            KernelPool fullPool = new KernelPool();
            fullPool.load(envPool);
            fullPool.load(mkPool);

            // map of SPICE codes to ephemeris objects
            Map<Integer, EphemerisID> boundIds = new HashMap<>();

            // add built in objects
            EphemerisNames builtInIds = new EphemerisNames();
            for (Integer key : builtInIds.getStandardBindings().keySet()) {
                EphemerisID value = builtInIds.getStandardBindings().get(key);
                boundIds.put(key, value);
                objectNameBindings.put(value.getName().toUpperCase(), value);
                objectIDBindings.put(key, value);
                objectNameReverseBindings.put(value, value.getName().toUpperCase());
                objectIDReverseBindings.put(value, key);
            }

            // add built in frames
            FrameNames builtInFrames = new FrameNames();
            for (Integer key : builtInFrames.getStandardBindings().keySet()) {
                FrameID value = builtInFrames.getStandardBindings().get(key);
                frameNameBindings.put(value.getName().toUpperCase(), value);
                frameIDBindings.put(key, value);
            }

            for (PositionVectorFunction f : env.getEphemerisSources()) {
                // initialize with known objects in SPK files
                EphemerisID id = f.getTargetID();
                if (id instanceof SpiceEphemerisID spiceID) {
                    boundIds.put(spiceID.getIDCode(), spiceID);
                }
            }

            if (envPool.hasKeyword("NAIF_BODY_NAME") && envPool.hasKeyword("NAIF_BODY_CODE")) {
                List<String> names = envPool.getStrings("NAIF_BODY_NAME");
                List<Integer> codes = envPool.getIntegers("NAIF_BODY_CODE");

                if (names.size() != codes.size())
                    logger.warn(
                            "NAIF_BODY_CODE has {} entries while NAIF_BODY_NAME has {} entries.  Will not bind any of these ids.",
                            codes.size(),
                            names.size());
                else {
                    for (int i = 0; i < codes.size(); i++) {
                        int code = codes.get(i);
                        String name = names.get(i);
                        SpiceEphemerisID spiceID = new SpiceEphemerisID(code, name);
                        objectNameBindings.put(name.toUpperCase(), spiceID);
                        objectIDBindings.put(code, spiceID);
                        objectNameReverseBindings.put(spiceID, name.toUpperCase());
                        objectIDReverseBindings.put(spiceID, code);
                        // builder.bindEphemerisID(name, spiceID);
                        new SpkCodeBinder(code, name, spiceID).configure(builder);
                        logger.debug("From text kernel: binding name {} to code {}", name, code);
                        boundIds.put(code, spiceID);
                    }
                }
            }

            // bind SPICE ids defined in metakernels
            if (mkPool.hasKeyword("NAIF_BODY_NAME") && mkPool.hasKeyword("NAIF_BODY_CODE")) {
                List<String> names = mkPool.getStrings("NAIF_BODY_NAME");
                List<Integer> codes = mkPool.getIntegers("NAIF_BODY_CODE");

                if (names.size() != codes.size())
                    logger.warn(
                            "NAIF_BODY_CODE has {} entries while NAIF_BODY_NAME has {} entries.  Will not bind any of these ids.",
                            codes.size(),
                            names.size());
                else {
                    for (int i = 0; i < codes.size(); i++) {
                        int code = codes.get(i);
                        String name = names.get(i);
                        SpiceEphemerisID spiceID = new SpiceEphemerisID(code, name);
                        objectNameBindings.put(name.toUpperCase(), spiceID);
                        objectIDBindings.put(code, spiceID);
                        objectNameReverseBindings.put(spiceID, name.toUpperCase());
                        objectIDReverseBindings.put(spiceID, code);
                        // builder.bindEphemerisID(name, spiceID);
                        new SpkCodeBinder(code, name, spiceID).configure(builder);
                        logger.debug("From metakernel: binding name {} to code {}", name, code);
                        boundIds.put(code, spiceID);
                    }
                }
            }

            // now populate frame lookup maps
            Set<String> keywords = fullPool.getKeywords();
            for (String keyword : keywords) {
                if (keyword.startsWith("FRAME_") && keyword.endsWith("_NAME")) {
                    String[] parts = keyword.split("_");
                    int id = Integer.parseInt(parts[1]);

                    String spiceName = fullPool.getStrings(keyword).getFirst();
                    String frameKeyword = String.format("FRAME_%s", spiceName);
                    if (!fullPool.hasKeyword(frameKeyword)) {
                        logger.warn("Kernel pool does not contain keyword {}", frameKeyword);
                        continue;
                    }
                    Integer spiceFrameCode =
                            fullPool.getDoubles(frameKeyword).getFirst().intValue();

                    if (spiceFrameCode != id) {
                        logger.warn(
                                "Expected keyword {} to be {}, found {} instead.  Skipping this one.",
                                frameKeyword,
                                id,
                                spiceFrameCode);
                        continue;
                    }

                    FrameID frameID = new SpiceFrameID(spiceFrameCode);
                    frameNameBindings.put(spiceName, frameID);
                    frameIDBindings.put(spiceFrameCode, frameID);
                    builder.bindFrameID(spiceName, frameID);

                    // The FRAME_XXX_CENTER can have a numeric or string value
                    String centerKey = String.format("FRAME_%d_CENTER", spiceFrameCode);

                    EphemerisID spiceID;
                    String centerCodeString;
                    if (fullPool.isStringValued(centerKey)) {
                        String centerCode = fullPool.getStrings(centerKey).getFirst();
                        spiceID = objectNameBindings.get(centerCode);
                        centerCodeString = centerCode;
                    } else {
                        int centerCode = 0;
                        if (fullPool.isDoubleValued(centerKey)) {
                            centerCode =
                                    fullPool.getDoubles(centerKey).getFirst().intValue();
                        } else if (fullPool.isIntegerValued(centerKey)) {
                            centerCode = fullPool.getIntegers(centerKey).getFirst();
                        }
                        spiceID = boundIds.get(centerCode);
                        centerCodeString = String.format("%d", centerCode);
                    }

                    if (spiceID == null)
                        logger.warn(
                                "Unknown ephemeris object specified by FRAME_{}_CENTER ({}).",
                                spiceFrameCode,
                                centerCodeString);
                    else {
                        additionalFrameCenters.put(frameID, spiceID);
                        logger.debug("Binding SPICE frame {} to frame code {}", frameID.getName(), spiceFrameCode);
                    }
                }
            }

            // populate body fixed map
            keywords = fullPool.getKeywords();
            Map<EphemerisID, FrameID> bodyFixedFrames = new HashMap<>();
            for (EphemerisID body : objectNameBindings.values()) {
                FrameID iauFrame = frameNameBindings.get(
                        String.format("IAU_%s", body.getName().toUpperCase()));
                if (iauFrame != null) bodyFixedFrames.put(body, iauFrame);
            }
            for (String keyword : keywords) {
                if (keyword.startsWith("OBJECT_") && keyword.endsWith("_FRAME")) {
                    String[] parts = keyword.split("_");
                    EphemerisID thisBody;
                    try {
                        Integer idCode = Integer.parseInt(parts[1]);
                        thisBody = objectIDBindings.get(idCode);
                    } catch (NumberFormatException e) {
                        thisBody = objectNameBindings.get(parts[1].toUpperCase());
                    }
                    if (thisBody != null) {
                        FrameID thisFrame;
                        if (fullPool.isIntegerValued(keyword))
                            thisFrame = frameIDBindings.get(
                                    fullPool.getIntegers(keyword).getFirst());
                        else
                            thisFrame = frameNameBindings.get(
                                    fullPool.getStrings(keyword).getFirst().toUpperCase());
                        if (thisFrame != null) bodyFixedFrames.put(thisBody, thisFrame);
                    }
                }
            }

            env = builder.build();
            List<PositionVectorFunction> envEphSources = new ArrayList<>(env.getEphemerisSources());
            envEphSources.addAll(additionalEphemerisSources);
            List<FrameTransformFunction> envFrameSources = new ArrayList<>(env.getFrameSources());
            envFrameSources.addAll(additionalFrameSources);

            EphemerisAndFrameProvider provider = lockable
                    ? new LockableEphemerisProvider(envEphSources, envFrameSources)
                    : new ReferenceEphemerisProvider(envEphSources, envFrameSources);

            SpiceBundle bundle = new SpiceBundle();
            bundle.spiceEnv = env;
            Map<FrameID, EphemerisID> frameCenters = new LinkedHashMap<>(env.getFrameCenterMap());
            frameCenters.putAll(additionalFrameCenters);

            bundle.abProvider = AberratedEphemerisProvider.createSingleIteration(provider, frameCenters);
            KernelPool kp = new KernelPool();
            kp.load(env.getPool());
            kp.load(kernelPool);
            bundle.kernelPool = new UnwritableKernelPool(kp);
            bundle.loadedKernels = Collections.unmodifiableList(kernels);
            bundle.objectNameBindings = Collections.unmodifiableMap(objectNameBindings);
            bundle.objectIDBindings = Collections.unmodifiableMap(objectIDBindings);
            bundle.objectNameReverseBindings = Collections.unmodifiableMap(objectNameReverseBindings);
            bundle.objectIDReverseBindings = Collections.unmodifiableMap(objectIDReverseBindings);
            bundle.frameNameBindings = Collections.unmodifiableMap(frameNameBindings);
            bundle.frameIDBindings = Collections.unmodifiableMap(frameIDBindings);
            bundle.bodyFixedFrames = Collections.unmodifiableMap(bodyFixedFrames);

            // initialize TimeSystems with this kernel pool
            bundle.timeConversion = new TimeConversion(env.getLSK());

            return bundle;
        }
    }

    private SpiceBundle() {}

    public SpiceEnvironment getSpiceEnv() {
        return spiceEnv;
    }

    public AberratedEphemerisProvider getAbProvider() {
        return abProvider;
    }

    /**
     * @return the kernel pool, including key/value pairs supplied in metakernels to the builder, while
     *     {@link SpiceEnvironment#getPool()} does not.
     */
    public UnwritableKernelPool getKernelPool() {
        return kernelPool;
    }

    /** @return TimeConversion object that can be used to convert between time systems. */
    public TimeConversion getTimeConversion() {
        return timeConversion;
    }

    /**
     * Return the last file in the list of loaded kernels matching supplied expression.
     *
     * @param regex Regular expression, used in a {@link Pattern} to match filenames in list of loaded kernels.
     * @return file matching regex, or null if not found
     */
    public File findKernel(String regex) {
        Pattern p = Pattern.compile(regex);
        return getKernels().stream()
                .filter(f -> p.matcher(f.getPath()).matches())
                .reduce((first, second) -> second)
                .orElse(null);
    }

    /** @return list of kernels in the order they were loaded. */
    public List<File> getKernels() {
        return loadedKernels;
    }

    /**
     * Intended for use when writing a metakernel; split long paths into multiple strings.
     *
     * @param f path to split
     * @param wrap desired maximum length of line
     * @return a list of strings each shorter than wrap that when concatenated return the original path
     */
    private List<String> splitPath(File f, int wrap) {

        List<String> list = new ArrayList<>();
        String[] parts = f.getAbsolutePath().split(Pattern.quote(File.separator));

        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (sb.toString().length() + parts[i].length() > wrap) {
                sb.append(File.separator);
                list.add(sb.toString());
                sb = new StringBuilder(parts[i]);
            } else {
                sb.append(String.format("%s%s", File.separator, parts[i]));
            }
        }
        list.add(sb.toString());

        return list;
    }

    /**
     * Write a metakernel containing all kernels loaded in this bundle
     *
     * @param file file to write
     * @param comments test to place in header
     */
    public void writeSingleMetaKernel(File file, String comments) {
        try (PrintWriter pw = new PrintWriter(file)) {
            pw.println("KPL/MK");
            pw.println(comments);
            pw.println("\\begindata\n");
            pw.println("KERNELS_TO_LOAD = (");
            for (File f : loadedKernels) {

                List<String> parts = splitPath(f, 78);
                if (parts.size() == 1) pw.printf("'%s'\n", f.getAbsolutePath());
                else {
                    for (int i = 0; i < parts.size() - 1; i++) {
                        String part = parts.get(i);
                        pw.printf("'%s+'\n", part);
                    }
                    pw.printf("'%s'\n", parts.getLast());
                }
            }
            pw.println(")\n");

            // print out other _TO_LOAD variables.
            for (String keyword : kernelPool.getKeywords()) {
                if (keyword.endsWith("_TO_LOAD")) {
                    if (keyword.length() > 32)
                        logger.warn(
                                "Kernel variable {} has length {} (SPICE max is 32 characters)",
                                keyword,
                                keyword.length());
                    pw.printf("%s = (\n", keyword);
                    for (String value : kernelPool.getStrings(keyword)) {
                        File f = new File(value);
                        List<String> parts = splitPath(f, 78);
                        if (parts.size() == 1) pw.printf("'%s'\n", f.getAbsolutePath());
                        else {
                            for (int i = 0; i < parts.size() - 1; i++) {
                                String part = parts.get(i);
                                pw.printf("'%s+'\n", part);
                            }
                            pw.printf("'%s'\n", parts.getLast());
                        }
                    }
                    pw.println(")\n");
                }
            }
            pw.println("\\begintext");
        } catch (FileNotFoundException e) {
            logger.error(e.getLocalizedMessage(), e);
        }
    }

    /**
     * @param body body
     * @return NAIF ID code
     */
    public Optional<Integer> getObjectCode(EphemerisID body) {
        return objectIDReverseBindings.containsKey(body)
                ? Optional.of(objectIDReverseBindings.get(body))
                : Optional.empty();
    }

    /**
     * @param body body
     * @return Name supplied from SPICE. This may not be the same as {@link EphemerisID#getName()}.
     */
    public Optional<String> getObjectName(EphemerisID body) {

        boolean containsKey = objectNameReverseBindings.containsKey(body);
        //        logger.info("{}: is present {}, name {}", body.getName(), containsKey, containsKey ?
        // objectNameReverseBindings.get(body) : "Missing");

        return containsKey ? Optional.of(objectNameReverseBindings.get(body)) : Optional.empty();
    }

    /**
     * @param idCode NAIF id code
     * @return the {@link EphemerisID} from the pool of known objects with the given id or null if there is no such
     *     object
     */
    public EphemerisID getObject(int idCode) {
        EphemerisID object = objectIDBindings.get(idCode);
        if (object == null) {
            Optional<EphemerisID> optional = getAbProvider().getKnownObjects(new HashSet<>()).stream()
                    .filter(id -> id instanceof SpiceEphemerisID && ((SpiceEphemerisID) id).getIDCode() == idCode)
                    .findFirst();
            if (optional.isPresent()) object = optional.get();
            else logger.warn("No object with id {} has been defined", idCode);
        }
        return object;
    }

    /**
     * @param name Object name
     * @return the {@link EphemerisID} from the pool of known objects with the given name or null if there is no such
     *     object
     */
    public EphemerisID getObject(String name) {
        EphemerisID object = objectNameBindings.get(name.toUpperCase());
        if (object == null) {
            Optional<EphemerisID> optional = getAbProvider().getKnownObjects(new HashSet<>()).stream()
                    .filter(id -> name.equalsIgnoreCase(id.getName()))
                    .findFirst();
            if (optional.isPresent()) object = optional.get();
            else {
                try {
                    object = getObject(Integer.parseInt(name));
                } catch (NumberFormatException ignored) {
                }
                if (object == null) logger.warn("No object {} has been defined", name);
            }
        }

        return object;
    }

    /**
     * @param name frame name
     * @return the {@link FrameID} from the pool of known frames with the given name
     */
    public FrameID getFrame(String name) {
        FrameID frame = frameNameBindings.get(name);
        if (frame == null) {
            try {
                Optional<FrameID> optional = getAbProvider().getKnownFrames(new HashSet<>()).stream()
                        .filter(id -> name.equalsIgnoreCase(id.getName()))
                        .findFirst();
                if (optional.isPresent()) frame = optional.get();
            } catch (NoSuchElementException e) {
                try {
                    frame = getFrame(Integer.parseInt(name));
                } catch (NumberFormatException ignored) {
                }
                if (frame == null) logger.warn("No frame {} has been defined", name);
            }
        }
        return frame;
    }

    /**
     * Return the body fixed frame associated with this body. Normally this is the IAU frame. A kernel may define a body
     * fixed frame using the OBJECT_*_FRAME keyword. From the "frames" required reading:
     *
     * <pre>
     *    OBJECT_&lt;name or spk_id&gt;_FRAME =  '&lt;frame name&gt;'
     * or
     *    OBJECT_&lt;name or spk_id&gt;_FRAME =  &lt;frame ID code&gt;
     * </pre>
     *
     * @param body body
     * @return body fixed frame
     */
    public FrameID getBodyFixedFrame(EphemerisID body) {
        return bodyFixedFrames.get(body);
    }

    /**
     * @param idCode NAIF frame id
     * @return the {@link FrameID} from the pool of known frames with the given id
     */
    public FrameID getFrame(int idCode) {
        FrameID frame = frameIDBindings.get(idCode);
        if (frame == null) {
            try {
                frame = getAbProvider().getFrameProvider().getKnownFrames(new HashSet<>()).stream()
                        .filter(id -> id instanceof SpiceFrameID && ((SpiceFrameID) id).getIDCode() == idCode)
                        .toList()
                        .getFirst();
            } catch (NoSuchElementException e) {
                logger.warn("No frame with id {} has been defined", idCode);
            }
        }
        return frame;
    }
}
