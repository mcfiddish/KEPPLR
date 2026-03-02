package kepplr.apps;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import kepplr.config.KEPPLRConfiguration;
import kepplr.templates.KEPPLRTool;
import kepplr.util.Log4j2Configurator;
import kepplr.util.ResourceUtils;
import org.apache.commons.cli.Options;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This program writes out a sample configuration file. It takes a single argument, which is the name of the directory
 * to place sample files.
 *
 * @author nairah1
 */
public class DumpConfig implements KEPPLRTool {

    private static final Logger logger = LogManager.getLogger();

    @Override
    public String shortDescription() {
        return "Write a sample configuration file.";
    }

    public String fullDescription(Options options) {
        return """
                This program writes out sample configuration files.  It takes a single argument,
                which is the name of the directory that will contain the configuration files to
                be written.""";
    }

    /** @param path directory to contain template config file (named dragonfly.config) and resource files */
    public static void dumpTemplate(File path) {
        boolean exists = path.exists();
        if (!exists) exists = path.mkdirs();
        if (!exists) {
            logger.error("Cannot write to {}!", path);
            return;
        }

        KEPPLRConfiguration config = KEPPLRConfiguration.getTemplate();
        List<Path> resources = ResourceUtils.getResourcePaths("/resources");
        File configFile = new File(path, "KEPPLR.config");
        PropertiesConfiguration pc = config.toPropertiesConfiguration();
        pc.setProperty("resourcesFolder", "resources");

        try (PrintWriter pw = new PrintWriter(configFile)) {
            pc.write(pw);
        } catch (ConfigurationException | IOException e) {
            throw new RuntimeException(e);
        }
        for (Path p : resources) {
            ResourceUtils.writeResourceToFile(
                    p.toString(), new File(path, StringUtils.stripStart(p.toString(), "/")), false);
        }
    }

    /**
     * Dump the input config and resources into path
     *
     * @param dc input configuration
     * @param path directory to contain config file (named KEPPLR.config) and resource files
     */
    public static void dump(KEPPLRConfiguration dc, File path) {
        boolean exists = path.exists();
        if (!exists) exists = path.mkdirs();
        if (!exists) {
            logger.error("Cannot write to {}!", path);
            return;
        }

        File resourceDir = new File(dc.resourcesFolder());
        File config = new File(path, "KEPPLR.config");
        try {
            try (PrintWriter pw = new PrintWriter(config)) {
                pw.print(dc);
            }
            // this is hokey, but read in the global config file and replace the resourcesFolder value
            List<String> linesIn = FileUtils.readLines(config, Charset.defaultCharset());
            List<String> linesOut = new ArrayList<>();
            for (String line : linesIn) {
                if (line.startsWith("resourcesFolder = ")) {
                    line = String.format(
                            "resourcesFolder = %s%s%s", path.getPath(), File.separator, dc.resourcesFolder());
                }
                linesOut.add(line);
            }
            try (PrintWriter pw = new PrintWriter(config)) {
                for (String line : linesOut) {
                    pw.println(line);
                }
            }

            FileUtils.copyDirectoryToDirectory(resourceDir, path);
        } catch (IOException e) {
            logger.warn(e.getLocalizedMessage());
        }
    }

    public static void main(String[] args) {
        // if no arguments, print the usage and exit
        if (args.length == 0) {
            System.out.println(new DumpConfig().fullDescription(null));
            System.exit(0);
        }

        // if -shortDescription is specified, print short description and exit.
        for (String arg : args) {
            if (arg.equals("-shortDescription")) {
                System.out.println(new DumpConfig().shortDescription());
                System.exit(0);
            }
        }

        Log4j2Configurator.getInstance();
        File path = new File(args[0]);
        dumpTemplate(path);

        System.out.println("Wrote config files to " + path.getAbsolutePath());
    }
}
