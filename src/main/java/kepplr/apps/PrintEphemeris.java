package kepplr.apps;

import java.nio.file.Paths;
import java.util.*;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.ephemeris.spice.SpiceBundle;
import kepplr.templates.KEPPLRTool;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.*;
import picante.mechanics.providers.aberrated.AberrationCorrection;
import picante.time.TimeConversion;
import picante.units.FundamentalPhysicalConstants;

public class PrintEphemeris implements KEPPLRTool {

    private static final Logger logger = LogManager.getLogger();

    @Override
    public String shortDescription() {
        return "Print position of solar system bodies.";
    }

    @Override
    public String fullDescription(Options options) {
        String header = "";
        String footer = "This program prints the geometric position of all known bodies "
                + "relative to the Solar System Barycenter in J2000.";
        return KEPPLRTool.super.fullDescription(options, header, footer);
    }

    private static Options defineOptions() {
        Options options = KEPPLRTool.defineOptions();
        options.addOption(Option.builder("config")
                .hasArg()
                .required()
                .desc("Required.  Load configuration from file.  Use the DumpConfig "
                        + "utility to create a sample config file.")
                .get());
        options.addOption(Option.builder("date")
                .hasArgs()
                .required()
                .desc("Required.  Date for calculation.")
                .get());
        return options;
    }

    public static void main(String[] args) {
        KEPPLRTool defaultOBJ = new PrintEphemeris();

        Options options = defineOptions();

        CommandLine cl = defaultOBJ.parseArgs(args, options);

        Map<MessageLabel, String> startupMessages = defaultOBJ.startupMessages(cl);
        for (MessageLabel ml : startupMessages.keySet()) logger.info("{} {}", ml.label, startupMessages.get(ml));

        KEPPLRConfiguration config = KEPPLRConfiguration.load(Paths.get(cl.getOptionValue("config")));

        KEPPLREphemeris ephemeris = config.getEphemeris();

        SpiceBundle bundle = ephemeris.getSpiceBundle();

        TimeConversion tc = bundle.getTimeConversion();

        double tdb = tc.utcStringToTDB(String.join(" ", cl.getOptionValues("date")));

        Set<EphemerisID> ids = bundle.getAbProvider().getKnownObjects(new HashSet<>());

        // key is distance from SSB
        Map<EphemerisID, StateVector> svMap = new HashMap<>();
        NavigableMap<Double, EphemerisID> distMap = new TreeMap<>();
        for (EphemerisID id : ids) {

            if (id == CelestialBodies.SOLAR_SYSTEM_BARYCENTER) continue;

            StateVectorFunction svf = bundle.getAbProvider()
                    .createAberratedStateVectorFunction(
                            id,
                            CelestialBodies.SOLAR_SYSTEM_BARYCENTER,
                            CelestialFrames.J2000,
                            Coverage.ALL_TIME,
                            AberrationCorrection.NONE);

            StateVector sv = svf.getState(tdb);

            svMap.put(id, sv);
            distMap.put(sv.getPosition().getLength(), id);
        }

        double kmToAU = 1 / FundamentalPhysicalConstants.getInstance().getAUinKm();
        double kmsToAUday = kmToAU * 86400;

        System.out.println(tc.tdbToUTCString(tdb, "ISOC"));
        StringBuilder sb = new StringBuilder();
        sb.append("Name, ");
        sb.append("Distance (AU), ");
        sb.append("X (AU), ");
        sb.append("Y (AU), ");
        sb.append("Z (AU), ");
        sb.append("Vx (AU/day), ");
        sb.append("Vy (AU/day), ");
        sb.append("Vz (AU/day)");
        System.out.println(sb);
        for (double dist : distMap.keySet()) {
            EphemerisID id = distMap.get(dist);
            VectorIJK p = svMap.get(id).getPosition();
            VectorIJK v = svMap.get(id).getVelocity();

            sb = new StringBuilder();
            sb.append(id.getName()).append(", ");
            sb.append(String.format("%.3f", dist * kmToAU)).append(", ");
            sb.append(String.format("%.3f", p.getI() * kmToAU)).append(", ");
            sb.append(String.format("%.3f", p.getJ() * kmToAU)).append(", ");
            sb.append(String.format("%.3f", p.getK() * kmToAU)).append(", ");
            sb.append(String.format("%.3e", v.getI() * kmsToAUday)).append(", ");
            sb.append(String.format("%.3e", v.getJ() * kmsToAUday)).append(", ");
            sb.append(String.format("%.3e", v.getK() * kmsToAUday));

            System.out.println(sb);
        }

        logger.info("Finished");
    }
}
