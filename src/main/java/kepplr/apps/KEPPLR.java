package kepplr.apps;

import java.nio.file.Paths;
import java.util.Map;
import kepplr.config.KEPPLRConfiguration;
import kepplr.render.KepplrApp;
import kepplr.templates.KEPPLRTool;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class KEPPLR implements KEPPLRTool {
    private static final Logger logger = LogManager.getLogger();

    /** This doesn't need to be private, or even declared, but you might want to if you have other constructors. */
    private KEPPLR() {}

    @Override
    public String shortDescription() {
        return "SHORT DESCRIPTION.";
    }

    @Override
    public String fullDescription(Options options) {
        String header = "TEXT APPEARING BEFORE COMMAND LINE OPTION SUMMARY";
        String footer = "\nTEXT APPENDED TO COMMAND LINE OPTION SUMMARY.\n";
        return KEPPLRTool.super.fullDescription(options, header, footer);
    }

    private static Options defineOptions() {
        Options options = KEPPLRTool.defineOptions();
        options.addOption(Option.builder("config")
                .hasArgs()
                .required()
                .desc("Configuration file to load")
                .get());
        return options;
    }

    public static void main(String[] args) {
        KEPPLRTool defaultOBJ = new KEPPLR();

        Options options = defineOptions();

        CommandLine cl = defaultOBJ.parseArgs(args, options);

        Map<MessageLabel, String> startupMessages = defaultOBJ.startupMessages(cl);
        for (MessageLabel ml : startupMessages.keySet()) logger.info("{} {}", ml.label, startupMessages.get(ml));

        KEPPLRConfiguration.load(Paths.get(cl.getOptionValue("config")));

        KepplrApp.run();

        logger.info("Finished");
    }
}
