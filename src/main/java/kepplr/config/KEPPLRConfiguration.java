package kepplr.config;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.ephemeris.spice.SpiceBundle;
import kepplr.util.AppVersion;
import kepplr.util.Log4j2Configurator;
import kepplr.util.ResourceUtils;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.PropertiesConfigurationLayout;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.mechanics.EphemerisID;
import picante.spice.provided.EphemerisNames;
import picante.time.TimeConversion;
import picante.time.UTCEpoch;

/**
 * Singleton configuration and ephemeris access point (REDESIGN.md §3).
 *
 * <p><b>This is a user-owned class.</b> The skeleton below defines the required public API contract. The user will
 * replace this file with their own implementation.
 *
 * <h3>Singleton and threading rule (§3.2)</h3>
 *
 * <ul>
 *   <li>Instantiated once at startup.
 *   <li>Accessed via {@link #getInstance()}.
 *   <li>Holds a {@code ThreadLocal<KEPPLREphemeris>} so each thread gets its own ephemeris instance.
 * </ul>
 *
 * <h3>No-passing rule (§3.3)</h3>
 *
 * <p>Code must <b>never</b> store or pass references to {@code KEPPLRConfiguration} or {@code KEPPLREphemeris}. Always
 * acquire at point-of-use:
 *
 * <pre>{@code
 * KEPPLRConfiguration.getInstance().getEphemeris()
 * }</pre>
 */
public class KEPPLRConfiguration implements KEPPLRConfigBlock {

    private static final Logger logger = LogManager.getLogger();
    private static KEPPLRConfiguration instance = null;
    private KEPPLRConfigBlock config = null;
    private ThreadLocal<KEPPLREphemeris> ephemeris;
    private final Map<String, BodyBlock> bodyBlocks = new LinkedHashMap<>();
    private final Map<Integer, SpacecraftBlock> spacecraftBlocks = new LinkedHashMap<>();

    /** @return True if configuration has been loaded */
    public static boolean isLoaded() {
        return instance != null;
    }

    public static KEPPLRConfiguration getInstance() {
        if (!isLoaded()) {
            throw new IllegalStateException("KEPPLR configuration is not loaded");
        }
        return instance;
    }

    private static List<String> extractMiddleKey(PropertiesConfiguration pc, String firstKey) {
        Pattern p = Pattern.compile("^" + firstKey + "\\.([^.]+)\\.naifID$");

        Iterator<String> it = pc.getKeys();

        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false)
                .map(key -> {
                    Matcher m = p.matcher(key);
                    return m.matches() ? m.group(1) : null;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Extract the body name from all body.*.naifID keys
     *
     * @param pc configuration
     * @return list of bodies
     */
    private static List<String> bodies(PropertiesConfiguration pc) {
        return extractMiddleKey(pc, "body");
    }

    /**
     * Extract the spacecraft key from all spacecraft.*.naifID keys
     *
     * @param pc configuration
     * @return list of bodies
     */
    private static List<String> spacecraft(PropertiesConfiguration pc) {
        return extractMiddleKey(pc, "spacecraft");
    }

    /** @return a default configuration instance. */
    public static KEPPLRConfiguration getTemplate() {
        if (isLoaded()) {
            logger.warn("KEPPLRConfiguration has already been loaded.  Replacing with default values.");
        }

        // write resources to temporary folder
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        List<Path> resources = ResourceUtils.getResourcePaths("/resources");
        for (Path p : resources) {
            ResourceUtils.writeResourceToFile(
                    p.toString(), new File(tmpDir, StringUtils.stripStart(p.toString(), "/")), true);
        }

        // create default config with new location of resources folder
        KEPPLRConfigBlockFactory factory = new KEPPLRConfigBlockFactory();
        instance = new KEPPLRConfiguration();
        PropertiesConfiguration pc =
                factory.withResourcesFolder(factory.getTemplate(), new File(tmpDir, "resources").getAbsolutePath());
        instance.config = factory.fromConfig(pc);
        instance.ephemeris = new ThreadLocal<>();

        Map<String, Integer> nameBindings = new EphemerisNames().getMap();
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("sun", "#FFFF00");
        colors.put("earth", "#2952FF");
        colors.put("moon", "#B2B2B2");
        for (String s : colors.keySet()) {
            if (nameBindings.containsKey(s.toUpperCase())) {
                String key = s.toLowerCase().replaceAll("\\s+", "");
                String prefix = String.format("body.%s.", key);
                pc = new PropertiesConfiguration();
                pc.setProperty(prefix + "naifID", nameBindings.get(s.toUpperCase()));
                pc.setProperty(prefix + "name", s.toUpperCase());
                pc.setProperty(prefix + "hexColor", colors.get(s));
                pc.setProperty(prefix + "nightShade", s.equals("sun") ? "1" : "0.05");
                pc.setProperty(prefix + "textureMap", String.format("maps/%s.jpg", s.toLowerCase()));
                pc.setProperty(prefix + "centerLonDeg", "0");
                pc.setProperty(prefix + "shapeModel", "");
                BodyBlockFactory bbf = new BodyBlockFactory(prefix);
                instance.bodyBlocks.put(key, bbf.fromConfig(pc));
            }
        }

        nameBindings = Map.of("New Horizons", -98);
        for (String s : nameBindings.keySet()) {
            String key = s.toLowerCase().replaceAll("\\s+", "");
            String prefix = String.format("spacecraft.%s.", key);
            pc = new PropertiesConfiguration();
            int code = nameBindings.get(s);
            pc.setProperty(prefix + "naifID", code);
            pc.setProperty(prefix + "name", s);
            pc.setProperty(prefix + "frame", "NH_SPACECRAFT");
            pc.setProperty(prefix + "shapeModel", "spacecraft/new_horizons_dds.glb");
            pc.setProperty(prefix + "scale", "1.0");
            SpacecraftBlockFactory sbf = new SpacecraftBlockFactory(prefix);
            instance.spacecraftBlocks.put(code, sbf.fromConfig(pc));
        }

        Log4j2Configurator lc = Log4j2Configurator.getInstance();
        lc.setLevel(Level.valueOf(instance.config.logLevel().toUpperCase().trim()));
        lc.setPattern(instance.config.logFormat());

        return instance;
    }

    /** @return configuration used for unit tests */
    public static KEPPLRConfiguration getTestTemplate() {

        instance = null;

        KEPPLRConfiguration config = getTemplate();
        KEPPLRConfigBlockFactory factory = new KEPPLRConfigBlockFactory();

        PropertiesConfiguration pc = factory.toConfig(config.config);
        pc.setProperty("spice.metakernel", "src/test/resources/spice/kepplr_test.tm");

        Map<String, BodyBlock> bodyBlocks = new HashMap<>();
        for (String s : config.bodies()) {
            BodyBlock b = config.bodyBlock(s);
            bodyBlocks.put(s.toLowerCase(), b);
        }

        Map<Integer, SpacecraftBlock> spacecraftBlocks = new HashMap<>();
        for (Integer i : config.spacecraft()) {
            SpacecraftBlock sb = config.spacecraftBlock(i);
            spacecraftBlocks.put(i, sb);
        }

        instance = new KEPPLRConfiguration();
        instance.config = factory.fromConfig(pc);
        instance.ephemeris = new ThreadLocal<>();
        instance.bodyBlocks.putAll(bodyBlocks);
        instance.spacecraftBlocks.putAll(spacecraftBlocks);

        Log4j2Configurator lc = Log4j2Configurator.getInstance();
        lc.setLevel(Level.valueOf(instance.config.logLevel().toUpperCase().trim()));
        lc.setPattern(instance.config.logFormat());

        return instance;
    }

    /** @return current configuration as a PropertiesConfiguration */
    private PropertiesConfiguration getConfig() {
        PropertiesConfiguration pc = new KEPPLRConfigBlockFactory().toConfig(instance.config);

        for (String s : bodies()) {
            String prefix = String.format("body.%s.", s.toLowerCase());
            BodyBlockFactory bbf = new BodyBlockFactory(prefix);
            pc.append(bbf.toConfig(bodyBlock(s), pc.getLayout()));
        }

        for (Integer i : spacecraft()) {
            SpacecraftBlock sb = spacecraftBlock(i);
            String key = sb.name().toLowerCase().replaceAll("\\s+", "");
            String prefix = String.format("spacecraft.%s.", key);
            SpacecraftBlockFactory sbf = new SpacecraftBlockFactory(prefix);
            pc.append(sbf.toConfig(sb, pc.getLayout()));
        }

        return pc;
    }

    /**
     * Null out the present configuration and reload from an Apache PropertiesConfiguration.
     *
     * <p>The KEPPLRConfiguration is meant to be an immutable object. It still is when using this method, but it's a
     * different immutable object than what was originally loaded. This method is intended to load a configuration,
     * change some of the settings programmatically on startup, and then create a new configuration for use. Be careful!
     *
     * <p>Here's an example of changing a property and reloading:
     *
     * <pre>
     *
     * KEPPLRConfiguration kc = KEPPLRConfiguration.getTemplate();
     * PropertiesConfiguration pc = kc.getConfig();
     * pc.setProperty("mission.missionDurationTsols", 60);
     * kc = KEPPLRConfiguration.reload(pc);
     *
     * </pre>
     *
     * @param pc Configuration to load
     * @return newly constructed KEPPLRConfiguration
     */
    public static KEPPLRConfiguration reload(PropertiesConfiguration pc) {
        instance = null;
        return load(pc);
    }

    /**
     * Null out the present configuration and reload from a configuration file
     *
     * @param filename configuration file to load
     * @return new configuration
     */
    public static KEPPLRConfiguration reload(Path filename) {
        instance = null;
        return load(filename);
    }
    /**
     * @param pc properties configuration
     * @return configuration object
     */
    private static KEPPLRConfiguration load(PropertiesConfiguration pc) {

        instance = new KEPPLRConfiguration();
        instance.config = new KEPPLRConfigBlockFactory().fromConfig(pc);
        instance.ephemeris = new ThreadLocal<>();

        Log4j2Configurator lc = Log4j2Configurator.getInstance();
        lc.setLevel(Level.valueOf(instance.config.logLevel().toUpperCase().trim()));
        lc.setPattern(instance.config.logFormat());

        // if outputFolder is blank, set its value
        if (instance.config.outputFolder().trim().isEmpty()) {
            String outputRoot = instance.config.outputRoot();
            String outputFolder = getSuggestedFolder(outputRoot);
            PropertiesConfiguration new_pc = (PropertiesConfiguration) pc.clone();
            new_pc.setProperty("outputFolder", outputFolder);
            instance.config = new KEPPLRConfigBlockFactory().fromConfig(new_pc);
        }

        for (String s : bodies(pc)) {
            String prefix = String.format("body.%s.", s.toLowerCase());
            if (pc.containsKey(prefix + "naifID")) {
                BodyBlockFactory bbf = new BodyBlockFactory(prefix);
                instance.bodyBlocks.put(s.toLowerCase(), bbf.fromConfig(pc));
            }
        }

        for (String s : spacecraft(pc)) {
            String prefix = String.format("spacecraft.%s.", s.toLowerCase());
            if (pc.containsKey(prefix + "naifID")) {
                SpacecraftBlockFactory sbf = new SpacecraftBlockFactory(prefix);
                SpacecraftBlock sb = sbf.fromConfig(pc);
                instance.spacecraftBlocks.put(sb.naifID(), sb);
            }
        }

        return instance;
    }

    /**
     * @param pc properties configuration
     * @return configuration object with missing properties filled in with default values
     */
    private static KEPPLRConfiguration loadWithDefaults(PropertiesConfiguration pc) {

        KEPPLRConfiguration template = KEPPLRConfiguration.getTemplate();
        PropertiesConfiguration templateConfig = template.getConfig();

        for (Iterator<String> it = pc.getKeys(); it.hasNext(); ) {
            String key = it.next();
            if (templateConfig.containsKey(key)) {
                templateConfig.setProperty(key, pc.getProperty(key));
            } else {
                System.err.printf("Supplied configuration contains key %s which is no longer supported.\n", key);
            }
        }

        instance = null;

        instance = load(templateConfig);

        return instance;
    }

    /**
     * @param filename configuration file to load
     * @return the configuration from an Apache Commons Configuration file.
     */
    public static KEPPLRConfiguration load(Path filename) {
        if (!Files.exists(filename)) {
            System.err.println("Cannot load configuration file " + filename);
            Thread.dumpStack();
            System.exit(1);
        }
        if (isLoaded()) {
            throw new RuntimeException(String.format(
                    "KEPPLRConfiguration has already been loaded; "
                            + "cannot load from %s.  You may want to use reload() instead.",
                    filename));
        } else {
            try {
                PropertiesConfiguration pc = new Configurations().properties(filename.toFile());

                instance = load(pc);

            } catch (ConfigurationException e) {
                throw new RuntimeException("Cannot load configuration file " + filename);
            }
        }
        return instance;
    }

    public List<String> bodies() {
        return bodyBlocks.keySet().stream().map(String::toUpperCase).toList();
    }

    public List<Integer> spacecraft() {
        return spacecraftBlocks.keySet().stream().toList();
    }

    /**
     * @param naifId NAIF code
     * @return spacecraft block from configuration file
     */
    public SpacecraftBlock spacecraftBlock(Integer naifId) {
        return instance.spacecraftBlocks.get(naifId);
    }

    /**
     * @param body body name
     * @return body block from configuration file
     */
    public BodyBlock bodyBlock(String body) {

        BodyBlock bodyBlock = instance.bodyBlocks.get(body.toLowerCase());

        if (bodyBlock == null) {
            SpiceBundle bundle = getEphemeris().getSpiceBundle();
            EphemerisID id = bundle.getObject(body);
            Optional<Integer> code = bundle.getObjectCode(id);
            Optional<String> name = bundle.getObjectName(id);
            if (code.isPresent() && name.isPresent()) {
                String prefix = String.format("body.%s.", body.toLowerCase());
                BodyBlockFactory bbf = new BodyBlockFactory(prefix);
                bodyBlock = bbf.getTemplate();
                PropertiesConfiguration pc = bbf.toConfig(bodyBlock);
                bbf.withNaifID(pc, code.get());
                bbf.withName(pc, name.get());
                String mapPath = String.format("maps%s%s.jpg", File.separator, body.toLowerCase());
                File map = new File(config.resourcesFolder(), mapPath);
                if (map.exists()) bbf.withTextureMap(pc, mapPath);
                bodyBlock = bbf.fromConfig(pc);
            }
        }

        return bodyBlock;
    }

    /**
     * Create a fresh directory with the name YYYYMMDD_nnn
     *
     * @param outFolder parent of the directory to create
     * @return name of new directory
     */
    private static String getSuggestedFolder(String outFolder) {
        String result;

        TimeConversion tc = TimeConversion.createUsingInternalConstants();
        UTCEpoch utc = tc.tdbToUTC(tc.instantToTDB(Instant.now()));
        String dateStr = String.format("%04d%02d%02d", utc.getYear(), utc.getMonth(), utc.getDom());

        int i = 1;
        while (true) {
            Path p = Paths.get(outFolder, dateStr + "_" + String.format("%03d", i++));
            if (!Files.isDirectory(p)) {
                result = p.toString();
                break;
            }
        }

        return result;
    }

    /**
     * @return The {@link KEPPLREphemeris} object. If thread safety is required call this from each thread; don't pass
     *     it in or store a handle to it.
     */
    public KEPPLREphemeris getEphemeris() {
        if (ephemeris.get() == null) {
            SPICEBlock sb = config.spiceBlock();
            ephemeris.set(new KEPPLREphemeris(sb.metakernel()));
        }
        return ephemeris.get();
    }

    /**
     * This is a shortcut for getEphemeris().getSpiceBundle().getTimeConversion()
     *
     * @return time conversion object
     */
    public TimeConversion getTimeConversion() {
        return getEphemeris().getSpiceBundle().getTimeConversion();
    }

    /** @return {@link #outputFolder()}, creating it if it does not exist */
    public Path getOutputFolder() {
        Path p = Paths.get(outputFolder());
        if (!Files.isDirectory(p))
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                logger.error(e.getLocalizedMessage(), e);
            }

        return p;
    }

    /**
     * Prepend the resourcesFolder to the supplied path
     *
     * @param path path within resourcesFolder
     * @return Path with the resourcesFolder prepended
     */
    public Path getPathInResources(String path) {
        return Paths.get(resourcesFolder(), path);
    }

    /** @return current configuration */
    public PropertiesConfiguration toPropertiesConfiguration() {
        PropertiesConfiguration pc = getConfig();
        PropertiesConfigurationLayout layout = new PropertiesConfigurationLayout(pc.getLayout());
        String now = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                .withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault())
                .format(Instant.now());
        layout.setHeaderComment(String.format("Configuration for %s\nCreated %s", AppVersion.getFullString(), now));
        pc.setLayout(layout);
        return pc;
    }

    @Override
    public String toString() {
        StringWriter string = new StringWriter();
        try (PrintWriter pw = new PrintWriter(string)) {
            PropertiesConfiguration pc = toPropertiesConfiguration();
            pc.write(pw);
        } catch (ConfigurationException | IOException e) {
            throw new RuntimeException(e.getLocalizedMessage());
        }
        return string.toString();
    }

    @Override
    public String logLevel() {
        return config.logLevel();
    }

    @Override
    public String logFormat() {
        return config.logFormat();
    }

    @Override
    public String timeFormat() {
        return config.timeFormat();
    }

    @Override
    public String outputRoot() {
        return config.outputRoot();
    }

    @Override
    public String outputFolder() {
        return config.outputFolder();
    }

    @Override
    public String resourcesFolder() {
        return config.resourcesFolder();
    }

    @Override
    public SPICEBlock spiceBlock() {
        return config.spiceBlock();
    }
}
