package kepplr.ephemeris;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.spice.SpiceBundle;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picante.mechanics.*;
import picante.mechanics.providers.aberrated.AberrationCorrection;
import picante.time.TimeConversion;

public class OsculatingElementsTest {
    @BeforeEach
    void setup() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTemplate();
    }

    @Test
    void testOsculatingElements() {
        KEPPLRConfiguration config = KEPPLRConfiguration.getInstance();
        KEPPLREphemeris ephemeris = config.getEphemeris();
        SpiceBundle spiceBundle = ephemeris.getSpiceBundle();
        TimeConversion timeConversion = config.getTimeConversion();

        // OSCLTX test
        String utc = "Dec 25, 2007";
        double et = timeConversion.utcStringToTDB(utc);

        Optional<Integer> naifId = spiceBundle.getObjectCode(CelestialBodies.MARS);
        assertTrue(naifId.isPresent());
        assertEquals(499, (int) naifId.get());

        Double gm = ephemeris
                .getSpiceBundle()
                .getKernelPool()
                .getDoubles(String.format("BODY%d_GM", naifId.get()))
                .getFirst();
        assertNotNull(gm);

        // from gm_de440.tpc
        assertEquals(4.282837362069909E+04, gm);

        StateVectorFunction svf = spiceBundle
                .getAbProvider()
                .createAberratedStateVectorFunction(
                        CelestialBodies.PHOBOS,
                        CelestialBodies.MARS,
                        CelestialFrames.J2000,
                        Coverage.ALL_TIME,
                        AberrationCorrection.NONE);

        StateVector state = svf.getState(et);

        OsculatingElements osc = OsculatingElements.oscltx(state, et, gm);

        assertEquals(9232.5746716211, osc.rp(), 1.0);
        assertEquals(0.0156113904, osc.ecc(), 0.0001);
        assertEquals(38.1225231660, Math.toDegrees(osc.inc()), 0.01);
        assertEquals(47.0384055902, Math.toDegrees(osc.lnode()), 0.01);
        assertEquals(214.1546430017, Math.toDegrees(osc.argp()), 0.1);
        assertEquals(340.5048466068, Math.toDegrees(osc.m0()), 0.1);
        assertEquals(339.8966628076, Math.toDegrees(osc.nu()), 0.1);
        assertEquals(9378.9938051492, osc.a(), 0.0001);
        assertEquals(27577.0908930612, osc.tau(), 0.001);

        // Calculate the history of Phobos's orbital period at intervals
        //       of six months for a time interval of 10 years.

        List<Double> periods = List.of(
                27575.419249,
                27575.124052,
                27574.987749,
                27574.273163,
                27573.096137,
                27572.262064,
                27572.336386,
                27572.576986,
                27572.441912,
                27572.338535,
                27572.964737,
                27574.450440,
                27575.627595,
                27576.174100,
                27576.702123,
                27577.625008,
                27578.959155,
                27579.545076,
                27578.920610,
                27577.800624);

        et = timeConversion.utcStringToTDB("Jan 1, 2000 12:00:00");
        double step = 180 * 86400;

        for (int i = 0; i < 20; i++) {
            state = svf.getState(et + i * step);
            osc = OsculatingElements.oscltx(state, et, gm);
            //            System.out.printf("%2d) Expected %f, Actual %f, difference %f\n", i, periods.get(i),
            // osc.tau(), periods.get(i)-osc.tau());
            assertEquals(periods.get(i), osc.tau(), 0.0025);
        }
    }
}
