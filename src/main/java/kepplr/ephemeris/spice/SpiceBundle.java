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
 * Bundles the SPICE environment and the derived lookup utilities used by the application.
 *
 * <p>A {@code SpiceBundle} owns the loaded {@link SpiceEnvironment}, the corresponding
 * {@link AberratedEphemerisProvider}, a merged {@link UnwritableKernelPool}, time conversion support, and lookup maps
 * for bodies, frames, and body-fixed frames.
 *
 * <p>Instances are created through {@link Builder}, which loads kernels and metakernels, merges kernel-pool
 * assignments, and initializes the ID/name bindings needed by higher-level ephemeris code.
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

    /**
     * Builder for constructing a {@link SpiceBundle} from SPICE kernels, metakernels, and optional additional frame or
     * ephemeris sources.
     */
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
         * Appends kernels to the list that will be loaded into the bundle.
         *
         * @param kernels kernel files to add
         * @return this builder
         */
        public Builder addKernelList(List<File> kernels) {
            this.kernels.addAll(kernels);
            return this;
        }

        /**
         * Merges kernel-pool assignments into the builder's existing pool.
         *
         * <p>These values are combined with assignments parsed from any metakernels and with the kernel pool generated
         * by the loaded SPICE environment.
         *
         * @param kernelPool kernel pool assignments to add
         * @return this builder
         */
        public Builder addKernelPool(KernelPool kernelPool) {
            this.kernelPool.load(kernelPool);
            return this;
        }

        /**
         * Parses metakernels and merges their referenced kernels and kernel-pool assignments into the builder state.
         *
         * <p>Unreadable metakernels are logged and skipped. Successfully parsed metakernels contribute both the
         * referenced kernel files and any inline kernel-pool variables.
         *
         * @param metakernels metakernel paths to read
         * @return this builder
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
         * Adds ephemeris sources that will be appended after the SPICE-derived sources are loaded.
         *
         * @param funcs additional position-vector sources
         * @return this builder
         */
        public Builder addEphemerisSources(List<PositionVectorFunction> funcs) {
            additionalEphemerisSources.addAll(funcs);
            return this;
        }

        /**
         * Adds frame sources that will be appended after the SPICE-derived frame sources are loaded.
         *
         * @param funcs additional frame-transform sources
         * @return this builder
         */
        public Builder addFrameSources(List<FrameTransformFunction> funcs) {
            additionalFrameSources.addAll(funcs);
            return this;
        }

        /**
         * Adds explicit frame-center mappings to supplement those supplied by the SPICE environment.
         *
         * @param map mappings from frame identifiers to their center bodies
         * @return this builder
         */
        public Builder addFrameCenters(Map<FrameID, EphemerisID> map) {
            additionalFrameCenters.putAll(map);
            return this;
        }

        /**
         * Configures whether the final ephemeris provider should be lockable.
         *
         * @param lockable if {@code true}, build a {@link LockableEphemerisProvider}; otherwise use
         *     {@link ReferenceEphemerisProvider}
         * @return this builder
         */
        public Builder setLockable(boolean lockable) {
            this.lockable = lockable;
            return this;
        }

        /**
         * Replaces the {@link SpiceEnvironmentBuilder} used to load kernels and create the underlying
         * {@link SpiceEnvironment}.
         *
         * @param builder environment builder to use for kernel loading and final environment creation
         * @return this builder
         */
        public Builder setSpiceEnvironmentBuilder(SpiceEnvironmentBuilder builder) {
            this.builder = builder;
            return this;
        }

        /**
         * Creates an empty builder with no kernels, no additional sources, and a fresh {@link SpiceEnvironmentBuilder}.
         */
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

        /**
         * Builds a {@link SpiceBundle} from the currently configured kernels, kernel-pool assignments, and supplemental
         * sources.
         *
         * <p>This method loads kernels into the configured {@link SpiceEnvironmentBuilder}, merges kernel pools, binds
         * body and frame names to identifiers, infers body-fixed frames, and creates the final
         * {@link AberratedEphemerisProvider}.
         *
         * @return fully initialized {@link SpiceBundle}
         */
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

    /**
     * Returns the underlying SPICE environment built from the loaded kernels.
     *
     * @return SPICE environment backing this bundle
     */
    public SpiceEnvironment getSpiceEnv() {
        return spiceEnv;
    }

    /**
     * Returns the aberrated ephemeris provider derived from the bundle's frame and ephemeris sources.
     *
     * @return aberrated ephemeris provider used for state and transform evaluation
     */
    public AberratedEphemerisProvider getAbProvider() {
        return abProvider;
    }

    /**
     * Returns the merged kernel pool for this bundle.
     *
     * <p>Unlike {@link SpiceEnvironment#getPool()}, this pool also includes key/value pairs supplied through
     * metakernels or explicit builder-provided kernel-pool assignments.
     *
     * @return merged read-only kernel pool
     */
    public UnwritableKernelPool getKernelPool() {
        return kernelPool;
    }

    /**
     * Returns the time conversion utility initialized from the bundle's loaded leap-seconds kernel.
     *
     * @return time conversion helper for supported SPICE time systems
     */
    public TimeConversion getTimeConversion() {
        return timeConversion;
    }

    /**
     * Returns the last loaded kernel whose path matches the supplied regular expression.
     *
     * @param regex regular expression used to match kernel paths
     * @return matching kernel file, or {@code null} if no loaded kernel matches
     */
    public File findKernel(String regex) {
        Pattern p = Pattern.compile(regex);
        return getKernels().stream()
                .filter(f -> p.matcher(f.getPath()).matches())
                .reduce((first, second) -> second)
                .orElse(null);
    }

    /**
     * Returns the kernel files in the order they were loaded into the bundle.
     *
     * @return unmodifiable list of loaded kernel files
     */
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
     * Writes a metakernel that references all kernel files loaded in this bundle.
     *
     * <p>Long file paths are split using SPICE continuation syntax so the output remains valid as a metakernel.
     *
     * @param file destination file to write
     * @param comments comment text to place near the top of the generated metakernel
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
     * Returns the NAIF body code associated with an ephemeris identifier.
     *
     * @param body body identifier
     * @return optional NAIF body code for {@code body}
     */
    public Optional<Integer> getObjectCode(EphemerisID body) {
        return objectIDReverseBindings.containsKey(body)
                ? Optional.of(objectIDReverseBindings.get(body))
                : Optional.empty();
    }

    /**
     * Returns the SPICE-defined object name associated with an ephemeris identifier.
     *
     * <p>This name may differ from {@link EphemerisID#getName()} when the identifier implementation uses a normalized
     * or synthetic name.
     *
     * @param body body identifier
     * @return optional SPICE object name for {@code body}
     */
    public Optional<String> getObjectName(EphemerisID body) {

        boolean containsKey = objectNameReverseBindings.containsKey(body);
        //        logger.info("{}: is present {}, name {}", body.getName(), containsKey, containsKey ?
        // objectNameReverseBindings.get(body) : "Missing");

        return containsKey ? Optional.of(objectNameReverseBindings.get(body)) : Optional.empty();
    }

    /**
     * Resolves an ephemeris object by NAIF body code.
     *
     * <p>The lookup first checks the bundle's cached bindings and then falls back to scanning the provider's known
     * objects for a matching {@link SpiceEphemerisID}.
     *
     * @param idCode NAIF body code
     * @return matching ephemeris identifier, or {@code null} if no object with that code is known
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
     * Resolves an ephemeris object by name.
     *
     * <p>The lookup is case-insensitive against the bundle's name bindings, then falls back to scanning known provider
     * objects, and finally treats a numeric string as a NAIF body code.
     *
     * @param name object name or numeric body code string
     * @return matching ephemeris identifier, or {@code null} if no object with that name or code is known
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
     * Resolves a frame by name.
     *
     * <p>The lookup first checks the bundle's cached frame-name bindings, then scans the provider's known frames, and
     * finally treats a numeric string as a frame ID code.
     *
     * @param name frame name or numeric frame code string
     * @return matching frame identifier, or {@code null} if no frame with that name or code is known
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
     * Returns the body-fixed frame associated with a body.
     *
     * <p>This is usually the corresponding IAU frame. Kernels may override that default using the
     * {@code OBJECT_*_FRAME} keyword described in the SPICE Frames Required Reading:
     *
     * <pre>
     *    OBJECT_&lt;name or spk_id&gt;_FRAME =  '&lt;frame name&gt;'
     * or
     *    OBJECT_&lt;name or spk_id&gt;_FRAME =  &lt;frame ID code&gt;
     * </pre>
     *
     * @param body body identifier
     * @return body-fixed frame for {@code body}, or {@code null} if none is known
     */
    public FrameID getBodyFixedFrame(EphemerisID body) {
        return bodyFixedFrames.get(body);
    }

    /**
     * Resolves a frame by numeric frame code.
     *
     * @param idCode NAIF frame code
     * @return matching frame identifier, or {@code null} if no frame with that code is known
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
