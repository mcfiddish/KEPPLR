package kepplr.render.trail;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.camera.SynodicFrame;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.VectorIJK;

@DisplayName("TrailManager")
public class TrailManagerTest {

    private static final int EARTH = 399;
    private static final int MOON = 301;

    /** Phobos NAIF ID — known-good SPK data at the test epoch, used for any drawing tests. */
    private static final int PHOBOS = 401;

    /** A body never added to any trail map. */
    private static final int NEVER_ENABLED = 999;

    /**
     * Create a TrailManager with null JME nodes. Valid for enable/disable tests that never call update() (which is the
     * only method that accesses the nodes or asset manager).
     */
    private TrailManager manager() {
        return new TrailManager(null, null, null, null, new kepplr.state.DefaultSimulationState());
    }

    @Test
    @DisplayName("enableTrail() adds the NAIF ID to the active set")
    void testEnableTrail() {
        TrailManager tm = manager();
        tm.enableTrail(PHOBOS);

        assertTrue(tm.getEnabledIds().contains(PHOBOS), "Enabled trail set should contain PHOBOS after enableTrail");
    }

    @Test
    @DisplayName("disableTrail() removes the NAIF ID from the active set")
    void testDisableTrail() {
        TrailManager tm = manager();
        tm.enableTrail(PHOBOS);
        tm.disableTrail(PHOBOS);

        assertFalse(
                tm.getEnabledIds().contains(PHOBOS), "Enabled trail set should not contain PHOBOS after disableTrail");
        assertTrue(tm.getEnabledIds().isEmpty());
    }

    @Test
    @DisplayName("disableTrail() on a body that was never enabled does not throw")
    void testDisableMissingBody() {
        TrailManager tm = manager();
        assertDoesNotThrow(
                () -> tm.disableTrail(NEVER_ENABLED), "disableTrail on a body that was never enabled must not throw");
        assertTrue(tm.getEnabledIds().isEmpty());
    }

    /**
     * Tests for the synodic trail projection math (§7.5).
     *
     * <p>These tests verify the coordinate transform used by {@code TrailManager} when the camera frame is SYNODIC.
     * They use SPICE directly via {@link KEPPLREphemeris} and {@link SynodicFrame} to confirm the invariants without
     * needing a live JME render loop (which would require non-null JME nodes).
     */
    @Nested
    @DisplayName("Synodic trail projection")
    class SynodicProjectionTest {

        private double et;
        private KEPPLREphemeris eph;

        @BeforeEach
        void setup() {
            TestHarness.resetSingleton();
            KEPPLRConfiguration.getTestTemplate();
            et = TestHarness.getTestEpoch();
            eph = KEPPLRConfiguration.getInstance().getEphemeris();
        }

        /**
         * In the Earth→Moon synodic frame, the Moon's position relative to Earth projects entirely onto the X axis.
         *
         * <p>By construction, the synodic X axis is {@code normalize(Moon − Earth)}, so {@code dP = Moon − Earth} has
         * {@code sx = |dP|} and {@code sy ≈ sz ≈ 0}.
         */
        @Test
        @DisplayName("Moon's synodic X coord equals Earth-Moon distance; Y and Z are zero")
        void moonSynodicCoordIsAlongX() {
            VectorIJK moonHelio = eph.getHeliocentricPositionJ2000(MOON, et);
            VectorIJK earthHelio = eph.getHeliocentricPositionJ2000(EARTH, et);
            assertNotNull(moonHelio);
            assertNotNull(earthHelio);

            double[] baryAnchor = {earthHelio.getI(), earthHelio.getJ(), earthHelio.getK()};
            // Moon sample in anchored form: baryAnchor + (Moon - Earth) = Moon
            double[] sample = {moonHelio.getI(), moonHelio.getJ(), moonHelio.getK(), et};

            SynodicFrame.Basis bi = SynodicFrame.compute(EARTH, MOON, et);
            assertNotNull(bi);

            double dPx = sample[0] - baryAnchor[0]; // = Moon.x - Earth.x
            double dPy = sample[1] - baryAnchor[1];
            double dPz = sample[2] - baryAnchor[2];
            VectorIJK x = bi.xAxis(), y = bi.yAxis(), z = bi.zAxis();
            double sx = dPx * x.getI() + dPy * x.getJ() + dPz * x.getK();
            double sy = dPx * y.getI() + dPy * y.getJ() + dPz * y.getK();
            double sz = dPx * z.getI() + dPy * z.getJ() + dPz * z.getK();

            double expectedDist = Math.sqrt(dPx * dPx + dPy * dPy + dPz * dPz);
            assertEquals(expectedDist, sx, 1e-6, "sx should equal |Moon - Earth|");
            assertEquals(0.0, sy, 1e-6, "sy should be zero (X axis is the focus→selected direction)");
            assertEquals(0.0, sz, 1e-6, "sz should be zero (X axis is the focus→selected direction)");
        }

        /**
         * Re-expressing synodic coordinates in J2000 using the same basis recovers the original J2000 position.
         *
         * <p>At the current epoch, B_i = B_now, so the round-trip {@code J2000 → synodic → J2000} must be the identity.
         */
        @Test
        @DisplayName("Round-trip synodic→J2000 recovers original position")
        void roundTripSynodicToJ2000() {
            VectorIJK moonHelio = eph.getHeliocentricPositionJ2000(MOON, et);
            VectorIJK earthHelio = eph.getHeliocentricPositionJ2000(EARTH, et);
            assertNotNull(moonHelio);
            assertNotNull(earthHelio);

            double[] baryAnchor = {earthHelio.getI(), earthHelio.getJ(), earthHelio.getK()};
            double[] sample = {moonHelio.getI(), moonHelio.getJ(), moonHelio.getK(), et};

            SynodicFrame.Basis bNow = SynodicFrame.compute(EARTH, MOON, et);
            assertNotNull(bNow);

            double dPx = sample[0] - baryAnchor[0];
            double dPy = sample[1] - baryAnchor[1];
            double dPz = sample[2] - baryAnchor[2];
            VectorIJK x = bNow.xAxis(), y = bNow.yAxis(), z = bNow.zAxis();
            double sx = dPx * x.getI() + dPy * x.getJ() + dPz * x.getK();
            double sy = dPx * y.getI() + dPy * y.getJ() + dPz * y.getK();
            double sz = dPx * z.getI() + dPy * z.getJ() + dPz * z.getK();

            // Re-express in J2000 using the same (current) basis
            double px = earthHelio.getI() + sx * x.getI() + sy * y.getI() + sz * z.getI();
            double py = earthHelio.getJ() + sx * x.getJ() + sy * y.getJ() + sz * z.getJ();
            double pz = earthHelio.getK() + sx * x.getK() + sy * y.getK() + sz * z.getK();

            assertEquals(moonHelio.getI(), px, 1e-3, "Round-trip X should recover Moon's J2000 X");
            assertEquals(moonHelio.getJ(), py, 1e-3, "Round-trip Y should recover Moon's J2000 Y");
            assertEquals(moonHelio.getK(), pz, 1e-3, "Round-trip Z should recover Moon's J2000 Z");
        }
    }
}
