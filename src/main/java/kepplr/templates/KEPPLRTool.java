package kepplr.templates;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import kepplr.util.AppVersion;
import kepplr.util.Log4j2Configurator;
import kepplr.util.WrapUtil;
import org.apache.commons.cli.*;
import org.apache.commons.cli.help.TextHelpAppendable;
import org.apache.logging.log4j.Level;

public interface KEPPLRTool {

    /** @return One line description of this tool */
    String shortDescription();

    /**
     * @param options command line options
     * @param header String to print before argument list
     * @param footer String to print after argument list
     * @return Complete description of this tool.
     */
    default String fullDescription(Options options, String header, String footer) {

        // Show required options first, followed by non-required.
        Comparator<Option> customComparator = (o1, o2) -> {
            if (o1.isRequired() && !o2.isRequired()) return -1;
            if (!o1.isRequired() && o2.isRequired()) return 1;
            return o1.getKey().compareToIgnoreCase(o2.getKey());
        };

        StringBuilder help = new StringBuilder(AppVersion.getFullString()).append("\n\n");
        TextHelpAppendable tha = new TextHelpAppendable(help);
        tha.setLeftPad(0);
        tha.setIndent(0);
        org.apache.commons.cli.help.HelpFormatter formatter = org.apache.commons.cli.help.HelpFormatter.builder()
                .setComparator(customComparator)
                .setHelpAppendable(tha)
                .setShowSince(false)
                .get();

        try {
            // formatter.printHelp does not preserve newlines in header or footer
            help.append(WrapUtil.wrapPreservingBlankLines(header, tha.getMaxWidth()));
            formatter.printHelp(
                    String.format("%s [options]", this.getClass().getSimpleName()),
                    "",
                    formatter.sort(options),
                    "",
                    false);
            help.append(WrapUtil.wrapPreservingBlankLines(footer, tha.getMaxWidth()));
            return help.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param options command line options
     * @return Complete description of this tool.
     */
    default String fullDescription(Options options) {
        return fullDescription(options, "", "");
    }

    /**
     * @param args arguments to parse
     * @param options set of options accepted by the program
     * @return command line formed by parsing arguments
     */
    default CommandLine parseArgs(String[] args, Options options) {
        // if no arguments, print the usage and exit
        if (args.length == 0) {
            System.out.println(fullDescription(options));
            System.exit(0);
        }

        // if -shortDescription is specified, print short description and exit.
        for (String arg : args) {
            if (arg.equals("-shortDescription")) {
                System.out.println(shortDescription());
                System.exit(0);
            }
        }

        // parse the arguments
        CommandLine cl = null;
        try {
            cl = new DefaultParser().parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            System.out.printf(
                    "\nRun %s without arguments for usage description.",
                    getClass().getSimpleName());
            System.exit(0);
        }

        return cl;
    }

    /** @return default options */
    static Options defineOptions() {
        Options options = new Options();
        options.addOption(Option.builder("logFile")
                .hasArg()
                .desc("If present, save screen output to log file.")
                .get());
        StringBuilder sb = new StringBuilder();
        for (Level l : Level.values()) sb.append(String.format("%s ", l.name()));
        options.addOption(Option.builder("logLevel")
                .hasArg()
                .desc("If present, print messages above selected priority.  Valid values are "
                        + sb.toString().trim()
                        + ".  Default is INFO.")
                .get());
        return options;
    }

    /** Labels for startup messages. The enum order is the order in which they are printed. */
    enum MessageLabel {
        START("Start"),
        ARGUMENTS("arguments");
        public final String label;

        MessageLabel(String label) {
            this.label = label;
        }
    }

    /**
     * Generate startup messages. This is returned as a map. For example:
     *
     * <table>
     *     <tr>
     *         <th>Key</th>
     *         <th>Value</th>
     *     </tr>
     *     <tr>
     *         <td>
     *             Start
     *         </td>
     *         <td>
     *             KEPPLRSimulator [KEPPLRTools version 25.01.28-b868ef6M] on nairah1-ml1
     *         </td>
     *     </tr>
     *     <tr>
     *         <td>arguments:</td>
     *         <td>-spice /project/sis/users/nairah1/MMX/spice/meganeLCp.mk -startTime 2026 JUN 20 00:00:00 -stopTime 2026 JUN 20 08:00:00 -delta 180 -outputCSV tmp.csv -numThreads 1 -obj /project/sis/users/nairah1/MMX/obj/Phobos-Ernst-800.obj -dbName tmp.db</td>
     *     </tr>
     * </table>
     *
     * @param cl Command line object
     * @return standard startup messages
     */
    default Map<MessageLabel, String> startupMessages(CommandLine cl) {

        Map<MessageLabel, String> startupMessages = new LinkedHashMap<>();

        String hostname = "unknown host";
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ignored) {
        }

        Log4j2Configurator lc = Log4j2Configurator.getInstance();
        if (cl.hasOption("logLevel"))
            lc.setLevel(
                    Level.valueOf(cl.getOptionValue("logLevel").toUpperCase().trim()));

        if (cl.hasOption("logFile")) lc.addFile(cl.getOptionValue("logFile"));

        StringBuilder sb = new StringBuilder(
                String.format("%s [%s] on %s", getClass().getSimpleName(), AppVersion.getVersionString(), hostname));
        startupMessages.put(MessageLabel.START, sb.toString());
        sb = new StringBuilder();

        for (Option option : cl.getOptions()) {
            sb.append("-").append(option.getOpt()).append(" ");
            if (option.hasArgs()) {
                for (String arg : option.getValues()) sb.append(arg).append(" ");
            } else if (option.hasArg()) {
                sb.append(option.getValue()).append(" ");
            }
        }
        for (String arg : cl.getArgs()) {
            sb.append(arg).append(" ");
        }

        startupMessages.put(MessageLabel.ARGUMENTS, sb.toString());

        return startupMessages;
    }
}
