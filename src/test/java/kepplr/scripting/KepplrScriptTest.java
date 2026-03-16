package kepplr.scripting;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.camera.CameraFrame;
import kepplr.commands.SimulationCommands;
import kepplr.config.KEPPLRConfiguration;
import kepplr.render.RenderQuality;
import kepplr.render.vector.VectorType;
import kepplr.state.DefaultSimulationState;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KepplrScript}.
 *
 * <p>Uses a recording spy to verify delegation. SPICE test kernel is loaded for name resolution tests.
 */
@DisplayName("KepplrScript")
class KepplrScriptTest {

    private DefaultSimulationState state;
    private SpyCommands spy;
    private KepplrScript script;

    @BeforeEach
    void setUp() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTestTemplate();
        state = new DefaultSimulationState();
        spy = new SpyCommands();
        script = new KepplrScript(spy, state);
    }

    // ── Name resolution ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Name resolution (String overloads)")
    class NameResolution {

        @Test
        @DisplayName("selectBody(String) resolves Earth to 399")
        void selectBodyByName() {
            script.selectBody("Earth");
            assertEquals("selectBody", spy.lastMethod);
            assertEquals(399, spy.lastIntArg);
        }

        @Test
        @DisplayName("focusBody(String) resolves Moon to 301")
        void focusBodyByName() {
            script.focusBody("Moon");
            assertEquals("focusBody", spy.lastMethod);
            assertEquals(301, spy.lastIntArg);
        }

        @Test
        @DisplayName("targetBody(String) resolves Sun to 10")
        void targetBodyByName() {
            script.targetBody("Sun");
            assertEquals("targetBody", spy.lastMethod);
            assertEquals(10, spy.lastIntArg);
        }

        @Test
        @DisplayName("pointAt(String, double) resolves name")
        void pointAtByName() {
            script.pointAt("Earth", 2.0);
            assertEquals("pointAt", spy.lastMethod);
            assertEquals(399, spy.lastIntArg);
            assertEquals(2.0, spy.lastDoubleArg, 1e-9);
        }

        @Test
        @DisplayName("goTo(String, double, double) resolves name")
        void goToByName() {
            script.goTo("Earth", 5.0, 3.0);
            assertEquals("goTo", spy.lastMethod);
            assertEquals(399, spy.lastIntArg);
        }

        @Test
        @DisplayName("setSynodicFrame(String, String) resolves both names")
        void setSynodicFrameByName() {
            script.setSynodicFrame("Earth", "Moon");
            assertEquals("setSynodicFrame", spy.lastMethod);
            assertEquals(399, spy.lastIntArg);
            assertEquals(301, spy.lastIntArg2);
        }

        @Test
        @DisplayName("setLabelVisible(String, boolean) resolves name")
        void setLabelVisibleByName() {
            script.setLabelVisible("Earth", true);
            assertEquals("setLabelVisible", spy.lastMethod);
            assertEquals(399, spy.lastIntArg);
        }

        @Test
        @DisplayName("setTrailVisible(String, boolean) resolves name")
        void setTrailVisibleByName() {
            script.setTrailVisible("Earth", true);
            assertEquals("setTrailVisible", spy.lastMethod);
            assertEquals(399, spy.lastIntArg);
        }

        @Test
        @DisplayName("setTrailDuration(String, double) resolves name")
        void setTrailDurationByName() {
            script.setTrailDuration("Earth", 86400.0);
            assertEquals("setTrailDuration", spy.lastMethod);
            assertEquals(399, spy.lastIntArg);
        }

        @Test
        @DisplayName("setCameraPosition(x, y, z, String, dur) resolves name")
        void setCameraPositionByName() {
            script.setCameraPosition(0, 0, 10000, "Moon", 2.0);
            assertEquals("setCameraPosition5", spy.lastMethod);
            assertEquals(301, spy.lastIntArg);
        }

        @Test
        @DisplayName("Unresolvable name throws IllegalArgumentException")
        void unresolvableNameThrows() {
            assertThrows(IllegalArgumentException.class, () -> script.selectBody("Nonexistent"));
        }

        @Test
        @DisplayName("Name resolution is case-insensitive")
        void caseInsensitive() {
            script.selectBody("earth");
            assertEquals(399, spy.lastIntArg);
            script.selectBody("EARTH");
            assertEquals(399, spy.lastIntArg);
        }
    }

    // ── Direct delegation ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Direct delegation (int overloads)")
    class DirectDelegation {

        @Test
        @DisplayName("selectBody(int) delegates")
        void selectBodyInt() {
            script.selectBody(399);
            assertEquals("selectBody", spy.lastMethod);
            assertEquals(399, spy.lastIntArg);
        }

        @Test
        @DisplayName("setTimeRate delegates")
        void setTimeRate() {
            script.setTimeRate(3600.0);
            assertEquals("setTimeRate", spy.lastMethod);
            assertEquals(3600.0, spy.lastDoubleArg, 1e-9);
        }

        @Test
        @DisplayName("setPaused delegates")
        void setPaused() {
            script.setPaused(true);
            assertEquals("setPaused", spy.lastMethod);
            assertTrue(spy.lastBoolArg);
        }

        @Test
        @DisplayName("setCameraFrame delegates")
        void setCameraFrame() {
            script.setCameraFrame(CameraFrame.SYNODIC);
            assertEquals("setCameraFrame", spy.lastMethod);
        }

        @Test
        @DisplayName("cancelTransition delegates")
        void cancelTransition() {
            script.cancelTransition();
            assertEquals("cancelTransition", spy.lastMethod);
        }

        @Test
        @DisplayName("zoom delegates")
        void zoom() {
            script.zoom(2.0, 1.0);
            assertEquals("zoom", spy.lastMethod);
        }

        @Test
        @DisplayName("orbit delegates")
        void orbit() {
            script.orbit(45.0, 10.0, 2.0);
            assertEquals("orbit", spy.lastMethod);
        }

        @Test
        @DisplayName("tilt delegates")
        void tilt() {
            script.tilt(10.0, 0.5);
            assertEquals("tilt", spy.lastMethod);
        }

        @Test
        @DisplayName("yaw delegates")
        void yaw() {
            script.yaw(30.0, 1.0);
            assertEquals("yaw", spy.lastMethod);
        }

        @Test
        @DisplayName("roll delegates")
        void roll() {
            script.roll(90.0, 1.0);
            assertEquals("roll", spy.lastMethod);
        }
    }

    // ── Timing primitives ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Timing primitives")
    class TimingPrimitives {

        @Test
        @DisplayName("waitWall blocks for approximately the specified time")
        void waitWallBasic() throws InterruptedException {
            long start = System.nanoTime();
            script.waitWall(0.1);
            long elapsed = System.nanoTime() - start;
            assertTrue(elapsed >= 90_000_000L, "Should wait at least ~100ms; elapsed=" + elapsed);
        }

        @Test
        @DisplayName("waitWall with zero or negative returns immediately")
        void waitWallZero() throws InterruptedException {
            long start = System.nanoTime();
            script.waitWall(0);
            script.waitWall(-1.0);
            long elapsed = System.nanoTime() - start;
            assertTrue(elapsed < 50_000_000L, "Should return immediately for zero/negative");
        }

        @Test
        @DisplayName("waitUntilSim returns immediately if target ET already passed (forward time)")
        void waitUntilSimAlreadyPassed() throws InterruptedException {
            state.setCurrentEt(1000.0);
            long start = System.nanoTime();
            script.waitUntilSim(500.0); // target is in the past, time rate >= 0
            long elapsed = System.nanoTime() - start;
            assertTrue(elapsed < 10_000_000L, "Should return immediately when target already passed");
        }

        @Test
        @DisplayName("waitSim returns immediately for zero seconds")
        void waitSimZero() throws InterruptedException {
            long start = System.nanoTime();
            script.waitSim(0);
            long elapsed = System.nanoTime() - start;
            assertTrue(elapsed < 10_000_000L, "waitSim(0) should return immediately");
        }
    }

    // ── Spy implementation ──────────────────────────────────────────────────────

    /** Minimal recording spy for verifying delegation. Records the last method name and selected arguments. */
    static class SpyCommands implements SimulationCommands {
        String lastMethod;
        int lastIntArg;
        int lastIntArg2;
        double lastDoubleArg;
        boolean lastBoolArg;

        @Override
        public void selectBody(int naifId) {
            lastMethod = "selectBody";
            lastIntArg = naifId;
        }

        @Override
        public void focusBody(int naifId) {
            lastMethod = "focusBody";
            lastIntArg = naifId;
        }

        @Override
        public void targetBody(int naifId) {
            lastMethod = "targetBody";
            lastIntArg = naifId;
        }

        @Override
        public void setTimeRate(double r) {
            lastMethod = "setTimeRate";
            lastDoubleArg = r;
        }

        @Override
        public void setPaused(boolean p) {
            lastMethod = "setPaused";
            lastBoolArg = p;
        }

        @Override
        public void setET(double et) {
            lastMethod = "setET";
            lastDoubleArg = et;
        }

        @Override
        public void setUTC(String s) {
            lastMethod = "setUTC";
        }

        @Override
        public void pointAt(int id, double dur) {
            lastMethod = "pointAt";
            lastIntArg = id;
            lastDoubleArg = dur;
        }

        @Override
        public void goTo(int id, double rad, double dur) {
            lastMethod = "goTo";
            lastIntArg = id;
        }

        @Override
        public void zoom(double f, double dur) {
            lastMethod = "zoom";
        }

        @Override
        public void setFov(double deg, double dur) {
            lastMethod = "setFov";
        }

        @Override
        public void orbit(double r, double u, double dur) {
            lastMethod = "orbit";
        }

        @Override
        public void tilt(double deg, double dur) {
            lastMethod = "tilt";
        }

        @Override
        public void yaw(double deg, double dur) {
            lastMethod = "yaw";
        }

        @Override
        public void roll(double deg, double dur) {
            lastMethod = "roll";
        }

        @Override
        public void setCameraPosition(double x, double y, double z, double dur) {
            lastMethod = "setCameraPosition3";
        }

        @Override
        public void setCameraPosition(double x, double y, double z, int id, double dur) {
            lastMethod = "setCameraPosition5";
            lastIntArg = id;
        }

        @Override
        public void setCameraLookDirection(
                double lx, double ly, double lz, double ux, double uy, double uz, double dur) {
            lastMethod = "setCameraLookDirection";
        }

        @Override
        public void setSynodicFrame(int fid, int tid) {
            lastMethod = "setSynodicFrame";
            lastIntArg = fid;
            lastIntArg2 = tid;
        }

        @Override
        public void setCameraFrame(CameraFrame f) {
            lastMethod = "setCameraFrame";
        }

        @Override
        public void setRenderQuality(RenderQuality q) {
            lastMethod = "setRenderQuality";
        }

        @Override
        public void setLabelVisible(int id, boolean v) {
            lastMethod = "setLabelVisible";
            lastIntArg = id;
        }

        @Override
        public void setHudTimeVisible(boolean v) {
            lastMethod = "setHudTimeVisible";
        }

        @Override
        public void setHudInfoVisible(boolean v) {
            lastMethod = "setHudInfoVisible";
        }

        @Override
        public void setTrailVisible(int id, boolean v) {
            lastMethod = "setTrailVisible";
            lastIntArg = id;
        }

        @Override
        public void setTrailDuration(int id, double s) {
            lastMethod = "setTrailDuration";
            lastIntArg = id;
        }

        @Override
        public void setVectorVisible(int id, VectorType t, boolean v) {
            lastMethod = "setVectorVisible";
            lastIntArg = id;
        }

        @Override
        public void cancelTransition() {
            lastMethod = "cancelTransition";
        }
    }
}
