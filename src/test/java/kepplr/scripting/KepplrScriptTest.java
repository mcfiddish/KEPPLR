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
        @DisplayName("centerBody(String) resolves Moon to 301")
        void centerBodyByName() {
            script.centerBody("Moon");
            assertEquals("centerBody", spy.lastMethod);
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

    // ── Instrument frustum overlays (Step 22) ──────────────────────────────────

    @Nested
    @DisplayName("Instrument frustum overlay delegation")
    class FrustumOverlayDelegation {

        @Test
        @DisplayName("setFrustumVisible(int, boolean) delegates to commands")
        void setFrustumVisibleInt() {
            script.setFrustumVisible(-98300, true);
            assertEquals("setFrustumVisible", spy.lastMethod);
        }

        @Test
        @DisplayName("setFrustumVisible(String, boolean) resolves via BodyLookupService")
        void setFrustumVisibleStringDelegatesToResolve() {
            // NH_LORRI is in the test kernel and resolves to -98300.
            // Expect delegation (not throw) — same pattern as setLabelVisible(String).
            script.setFrustumVisible("NH_LORRI", false);
            assertEquals("setFrustumVisible", spy.lastMethod);
            assertEquals(-98300, spy.lastIntArg);
            assertFalse(spy.lastBoolArg);
        }

        @Test
        @DisplayName("setFrustumPersistenceEnabled(int, boolean) delegates to commands")
        void setFrustumPersistenceEnabledInt() {
            script.setFrustumPersistenceEnabled(-98300, true);
            assertEquals("setFrustumPersistenceEnabled", spy.lastMethod);
            assertEquals(-98300, spy.lastIntArg);
            assertTrue(spy.lastBoolArg);
        }

        @Test
        @DisplayName("setFrustumPersistenceEnabled(String, boolean) resolves via BodyLookupService")
        void setFrustumPersistenceEnabledString() {
            script.setFrustumPersistenceEnabled("NH_LORRI", false);
            assertEquals("setFrustumPersistenceEnabled", spy.lastMethod);
            assertEquals(-98300, spy.lastIntArg);
            assertFalse(spy.lastBoolArg);
        }

        @Test
        @DisplayName("setFrustumColor(int, r, g, b) delegates to commands")
        void setFrustumColorInt() {
            script.setFrustumColor(-98300, 255, 80, 20);
            assertEquals("setFrustumColor", spy.lastMethod);
            assertEquals(-98300, spy.lastIntArg);
            assertEquals(255, spy.lastColorRed);
        }

        @Test
        @DisplayName("setFrustumColor(int, hex) delegates to commands")
        void setFrustumColorHex() {
            script.setFrustumColor(-98300, "#ff5014");
            assertEquals("setFrustumColor", spy.lastMethod);
            assertEquals(-98300, spy.lastIntArg);
            assertEquals("#ff5014", spy.lastStringArg);
        }

        @Test
        @DisplayName("setFrustumColor(String, r, g, b) resolves via BodyLookupService")
        void setFrustumColorStringRgb() {
            script.setFrustumColor("NH_LORRI", 25, 50, 75);
            assertEquals("setFrustumColor", spy.lastMethod);
            assertEquals(-98300, spy.lastIntArg);
            assertEquals(25, spy.lastColorRed);
        }

        @Test
        @DisplayName("setFrustumColor(String, hex) resolves via BodyLookupService")
        void setFrustumColorStringHex() {
            script.setFrustumColor("NH_LORRI", "ff5014");
            assertEquals("setFrustumColor", spy.lastMethod);
            assertEquals(-98300, spy.lastIntArg);
            assertEquals("ff5014", spy.lastStringArg);
        }

        @Test
        @DisplayName("clearFrustumFootprints(int) delegates to commands")
        void clearFrustumFootprintsInt() {
            script.clearFrustumFootprints(-98300);
            assertEquals("clearFrustumFootprints", spy.lastMethod);
            assertEquals(-98300, spy.lastIntArg);
        }

        @Test
        @DisplayName("clearFrustumFootprints(String) resolves via BodyLookupService")
        void clearFrustumFootprintsString() {
            script.clearFrustumFootprints("NH_LORRI");
            assertEquals("clearFrustumFootprints", spy.lastMethod);
            assertEquals(-98300, spy.lastIntArg);
        }

        @Test
        @DisplayName("clearFrustumFootprints() delegates to commands")
        void clearFrustumFootprintsAll() {
            script.clearFrustumFootprints();
            assertEquals("clearFrustumFootprints", spy.lastMethod);
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
        @DisplayName("waitRenderFrames delegates to commands")
        void waitRenderFramesDelegates() {
            script.waitRenderFrames(2);
            assertEquals("waitRenderFrames", spy.lastMethod);
            assertEquals(2, spy.lastIntArg);
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
        String lastStringArg;
        int lastIntArg;
        int lastIntArg2;
        int lastColorRed;
        double lastDoubleArg;
        boolean lastBoolArg;

        @Override
        public void selectBody(int naifId) {
            lastMethod = "selectBody";
            lastIntArg = naifId;
        }

        @Override
        public void centerBody(int naifId) {
            lastMethod = "centerBody";
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
        public void setCameraOrientation(double lx, double ly, double lz, double ux, double uy, double uz, double dur) {
            lastMethod = "setCameraOrientation";
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
        public void setTrailReferenceBody(int naifId, int referenceBodyId) {
            lastMethod = "setTrailReferenceBody";
            lastIntArg = naifId;
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

        @Override
        public void setFrustumVisible(int code, boolean v) {
            lastMethod = "setFrustumVisible";
            lastIntArg = code;
            lastBoolArg = v;
        }

        @Override
        public void setFrustumVisible(String name, boolean v) {
            lastMethod = "setFrustumVisible";
            lastStringArg = name;
            lastBoolArg = v;
        }

        @Override
        public void setFrustumPersistenceEnabled(int code, boolean enabled) {
            lastMethod = "setFrustumPersistenceEnabled";
            lastIntArg = code;
            lastBoolArg = enabled;
        }

        @Override
        public void setFrustumPersistenceEnabled(String name, boolean enabled) {
            lastMethod = "setFrustumPersistenceEnabled";
            lastStringArg = name;
            lastBoolArg = enabled;
        }

        @Override
        public void setFrustumColor(int instrumentNaifCode, int red, int green, int blue) {
            lastMethod = "setFrustumColor";
            lastIntArg = instrumentNaifCode;
            lastColorRed = red;
        }

        @Override
        public void setFrustumColor(String instrumentName, int red, int green, int blue) {
            lastMethod = "setFrustumColor";
            lastStringArg = instrumentName;
            lastColorRed = red;
        }

        @Override
        public void setFrustumColor(int instrumentNaifCode, String hexColor) {
            lastMethod = "setFrustumColor";
            lastIntArg = instrumentNaifCode;
            lastStringArg = hexColor;
        }

        @Override
        public void setFrustumColor(String instrumentName, String hexColor) {
            lastMethod = "setFrustumColor";
            lastStringArg = hexColor;
        }

        @Override
        public void clearFrustumFootprints(int instrumentNaifCode) {
            lastMethod = "clearFrustumFootprints";
            lastIntArg = instrumentNaifCode;
        }

        @Override
        public void clearFrustumFootprints(String instrumentName) {
            lastMethod = "clearFrustumFootprints";
            lastStringArg = instrumentName;
        }

        @Override
        public void clearFrustumFootprints() {
            lastMethod = "clearFrustumFootprints";
        }

        @Override
        public void truck(double km, double dur) {
            lastMethod = "truck";
            lastDoubleArg = km;
        }

        @Override
        public void crane(double km, double dur) {
            lastMethod = "crane";
            lastDoubleArg = km;
        }

        @Override
        public void dolly(double km, double dur) {
            lastMethod = "dolly";
            lastDoubleArg = km;
        }

        @Override
        public void loadConfiguration(String path) {
            lastMethod = "loadConfiguration";
        }

        @Override
        public void saveScreenshot(String outputPath) {
            lastMethod = "saveScreenshot";
        }

        @Override
        public void waitRenderFrames(int frameCount) {
            lastMethod = "waitRenderFrames";
            lastIntArg = frameCount;
        }

        @Override
        public void displayMessage(String text, double durationSeconds) {
            lastMethod = "displayMessage";
        }

        @Override
        public void setWindowSize(int width, int height) {
            lastMethod = "setWindowSize";
        }

        @Override
        public void setBodyVisible(int naifId, boolean visible) {
            lastMethod = "setBodyVisible";
            lastIntArg = naifId;
            lastBoolArg = visible;
        }

        @Override
        public String getStateString() {
            lastMethod = "getStateString";
            return "";
        }

        @Override
        public void setStateString(String s) {
            lastMethod = "setStateString";
        }
    }
}
