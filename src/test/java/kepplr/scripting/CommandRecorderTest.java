package kepplr.scripting;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.camera.CameraFrame;
import kepplr.commands.SimulationCommands;
import kepplr.render.RenderQuality;
import kepplr.render.vector.VectorType;
import kepplr.render.vector.VectorTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CommandRecorder}. */
@DisplayName("CommandRecorder")
class CommandRecorderTest {

    private NoOpCommands delegate;
    private CommandRecorder recorder;

    @BeforeEach
    void setUp() {
        delegate = new NoOpCommands();
        recorder = new CommandRecorder(delegate);
    }

    // ── Passthrough ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Passthrough when not recording")
    class Passthrough {

        @Test
        @DisplayName("selectBody delegates to underlying commands")
        void selectBodyDelegates() {
            recorder.selectBody(399);
            assertEquals("selectBody", delegate.lastMethod);
            assertEquals(399, delegate.lastIntArg);
        }

        @Test
        @DisplayName("getScript returns empty header when not recording")
        void emptyScript() {
            recorder.selectBody(399);
            String script = recorder.getScript();
            assertTrue(script.startsWith("// KEPPLR recorded script"), "Should have header");
            assertFalse(script.contains("selectBody"), "Should not record when not recording");
        }
    }

    // ── Recording ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Recording captures commands")
    class Recording {

        @Test
        @DisplayName("Commands are recorded when recording is active")
        void recordsCommands() {
            recorder.startRecording();
            recorder.selectBody(399);
            recorder.setTimeRate(100.0);
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(script.contains("kepplr.selectBody(399)"), "Should contain selectBody: " + script);
            assertTrue(script.contains("kepplr.setTimeRate(100.0)"), "Should contain setTimeRate: " + script);
        }

        @Test
        @DisplayName("waitWall is inserted between commands based on elapsed time")
        void waitWallBetweenCommands() throws InterruptedException {
            recorder.startRecording();
            recorder.selectBody(399);
            Thread.sleep(150);
            recorder.centerBody(301);
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(script.contains("kepplr.waitWall("), "Should contain waitWall: " + script);
        }

        @Test
        @DisplayName("isRecording reflects state")
        void isRecording() {
            assertFalse(recorder.isRecording());
            recorder.startRecording();
            assertTrue(recorder.isRecording());
            recorder.stopRecording();
            assertFalse(recorder.isRecording());
        }

        @Test
        @DisplayName("startRecording clears previous recording")
        void startClearsPrevious() {
            recorder.startRecording();
            recorder.selectBody(399);
            recorder.stopRecording();

            recorder.startRecording();
            recorder.centerBody(301);
            recorder.stopRecording();

            String script = recorder.getScript();
            assertFalse(script.contains("selectBody"), "Previous recording should be cleared: " + script);
            assertTrue(script.contains("centerBody"), "New recording should be present: " + script);
        }

        @Test
        @DisplayName("setPaused is recorded correctly")
        void setPausedRecorded() {
            recorder.startRecording();
            recorder.setPaused(true);
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(script.contains("kepplr.setPaused(true)"), "Should contain setPaused: " + script);
        }

        @Test
        @DisplayName("setUTC is recorded with quoted string")
        void setUtcRecorded() {
            recorder.startRecording();
            recorder.setUTC("2015 Jul 14 07:59:00");
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(
                    script.contains("kepplr.setUTC(\"2015 Jul 14 07:59:00\")"), "Should contain quoted UTC: " + script);
        }

        @Test
        @DisplayName("setCameraFrame is recorded with enum name")
        void setCameraFrameRecorded() {
            recorder.startRecording();
            recorder.setCameraFrame(CameraFrame.SYNODIC);
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(script.contains("CameraFrame.SYNODIC"), "Should contain CameraFrame enum: " + script);
        }

        @Test
        @DisplayName("cancelTransition is recorded")
        void cancelTransitionRecorded() {
            recorder.startRecording();
            recorder.cancelTransition();
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(script.contains("kepplr.cancelTransition()"), "Should contain cancelTransition: " + script);
        }

        @Test
        @DisplayName("loadConfiguration is recorded verbatim with quoted path")
        void loadConfigurationRecorded() {
            recorder.startRecording();
            recorder.loadConfiguration("/some/path/config.properties");
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(
                    script.contains("kepplr.loadConfiguration(\"/some/path/config.properties\")"),
                    "Should contain loadConfiguration with quoted path: " + script);
        }
    }

    // ── Coalescing ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Instant camera command coalescing")
    class Coalescing {

        @Test
        @DisplayName("Multiple instant tilt commands are coalesced into one")
        void tiltCoalesced() {
            recorder.startRecording();
            recorder.tilt(5.0, 0);
            recorder.tilt(3.0, 0);
            recorder.tilt(2.0, 0);
            // Flush by sending a non-camera command
            recorder.setPaused(true);
            recorder.stopRecording();

            String script = recorder.getScript();
            // Should have one coalesced tilt (5+3+2 = 10) not three separate tilt calls
            long tiltCount =
                    script.lines().filter(l -> l.contains("kepplr.tilt(")).count();
            assertEquals(1, tiltCount, "Should have exactly one coalesced tilt: " + script);
            assertTrue(script.contains("10.0000"), "Coalesced tilt should sum to 10: " + script);
        }

        @Test
        @DisplayName("Multiple instant zoom commands are coalesced by multiplication")
        void zoomCoalesced() {
            recorder.startRecording();
            recorder.zoom(0.5, 0);
            recorder.zoom(0.5, 0);
            // Flush
            recorder.setPaused(true);
            recorder.stopRecording();

            String script = recorder.getScript();
            long zoomCount =
                    script.lines().filter(l -> l.contains("kepplr.zoom(")).count();
            assertEquals(1, zoomCount, "Should have exactly one coalesced zoom: " + script);
            assertTrue(script.contains("0.25"), "Coalesced zoom should be 0.5*0.5=0.25: " + script);
        }

        @Test
        @DisplayName("Different command types flush the previous coalesced command")
        void differentTypeFlushed() {
            recorder.startRecording();
            recorder.tilt(5.0, 0);
            recorder.yaw(10.0, 0); // different type → flushes tilt
            // Flush yaw
            recorder.setPaused(true);
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(script.contains("kepplr.tilt("), "Should contain tilt: " + script);
            assertTrue(script.contains("kepplr.yaw("), "Should contain yaw: " + script);
        }

        @Test
        @DisplayName("Non-instant camera commands are recorded verbatim, not coalesced")
        void nonInstantVerbatim() {
            recorder.startRecording();
            recorder.tilt(10.0, 2.0); // duration > 0
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(script.contains("kepplr.tilt(10.0, 2.0)"), "Non-instant tilt should be verbatim: " + script);
        }

        @Test
        @DisplayName("Orbit coalescing sums both degree arguments")
        void orbitCoalesced() {
            recorder.startRecording();
            recorder.orbit(10.0, 5.0, 0);
            recorder.orbit(20.0, 3.0, 0);
            // Flush
            recorder.setPaused(true);
            recorder.stopRecording();

            String script = recorder.getScript();
            long orbitCount =
                    script.lines().filter(l -> l.contains("kepplr.orbit(")).count();
            assertEquals(1, orbitCount, "Should have one coalesced orbit: " + script);
            assertTrue(script.contains("30.0000"), "rightDeg should sum to 30: " + script);
            assertTrue(script.contains("8.0000"), "upDeg should sum to 8: " + script);
        }
    }

    // ── Cinematic command coalescing (Step 24) ──────────────────────────────────────

    @Nested
    @DisplayName("Cinematic camera command coalescing")
    class CinematicCoalescing {

        @Test
        @DisplayName("Multiple instant truck commands coalesce by summation")
        void truckCoalesced() {
            recorder.startRecording();
            recorder.truck(100.0, 0);
            recorder.truck(200.0, 0);
            recorder.setPaused(true); // flush
            recorder.stopRecording();

            String script = recorder.getScript();
            long count = script.lines().filter(l -> l.contains("kepplr.truck(")).count();
            assertEquals(1, count, "Should have one coalesced truck: " + script);
            assertTrue(script.contains("300.0000"), "Coalesced truck should sum to 300: " + script);
        }

        @Test
        @DisplayName("Multiple instant crane commands coalesce by summation")
        void craneCoalesced() {
            recorder.startRecording();
            recorder.crane(50.0, 0);
            recorder.crane(75.0, 0);
            recorder.setPaused(true);
            recorder.stopRecording();

            String script = recorder.getScript();
            long count = script.lines().filter(l -> l.contains("kepplr.crane(")).count();
            assertEquals(1, count, "Should have one coalesced crane: " + script);
            assertTrue(script.contains("125.0000"), "Coalesced crane should sum to 125: " + script);
        }

        @Test
        @DisplayName("Multiple instant dolly commands coalesce by summation")
        void dollyCoalesced() {
            recorder.startRecording();
            recorder.dolly(1000.0, 0);
            recorder.dolly(-500.0, 0);
            recorder.setPaused(true);
            recorder.stopRecording();

            String script = recorder.getScript();
            long count = script.lines().filter(l -> l.contains("kepplr.dolly(")).count();
            assertEquals(1, count, "Should have one coalesced dolly: " + script);
            assertTrue(script.contains("500.0000"), "Coalesced dolly should sum to 500: " + script);
        }

        @Test
        @DisplayName("truck and crane are NOT coalesced together — different types")
        void truckAndCraneNotCoalesced() {
            recorder.startRecording();
            recorder.truck(100.0, 0);
            recorder.crane(50.0, 0); // different type flushes truck
            recorder.setPaused(true);
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(script.contains("kepplr.truck("), "Should contain truck: " + script);
            assertTrue(script.contains("kepplr.crane("), "Should contain crane: " + script);
        }

        @Test
        @DisplayName("Non-instant cinematic commands are recorded verbatim")
        void nonInstantVerbatim() {
            recorder.startRecording();
            recorder.truck(500.0, 3.0);
            recorder.crane(200.0, 2.0);
            recorder.dolly(1000.0, 5.0);
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(script.contains("kepplr.truck(500.0, 3.0)"), "truck verbatim: " + script);
            assertTrue(script.contains("kepplr.crane(200.0, 2.0)"), "crane verbatim: " + script);
            assertTrue(script.contains("kepplr.dolly(1000.0, 5.0)"), "dolly verbatim: " + script);
        }
    }

    // ── VectorType serialization ──────────────────────────────────────────────────

    @Nested
    @DisplayName("VectorType serialization in setVectorVisible")
    class VectorTypeSerialization {

        @Test
        @DisplayName("velocity() produces valid Groovy expression")
        void velocitySerialized() {
            recorder.startRecording();
            recorder.setVectorVisible(399, VectorTypes.velocity(), true);
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(script.contains("VectorTypes.velocity()"), "Should contain VectorTypes.velocity(): " + script);
            assertFalse(script.contains("/*"), "Should not contain placeholder comment: " + script);
        }

        @Test
        @DisplayName("bodyAxisX() produces valid Groovy expression")
        void bodyAxisXSerialized() {
            recorder.startRecording();
            recorder.setVectorVisible(399, VectorTypes.bodyAxisX(), true);
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(script.contains("VectorTypes.bodyAxisX()"), "Should contain VectorTypes.bodyAxisX(): " + script);
        }

        @Test
        @DisplayName("bodyAxisY() produces valid Groovy expression")
        void bodyAxisYSerialized() {
            recorder.startRecording();
            recorder.setVectorVisible(399, VectorTypes.bodyAxisY(), true);
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(script.contains("VectorTypes.bodyAxisY()"), "Should contain VectorTypes.bodyAxisY(): " + script);
        }

        @Test
        @DisplayName("bodyAxisZ() produces valid Groovy expression")
        void bodyAxisZSerialized() {
            recorder.startRecording();
            recorder.setVectorVisible(399, VectorTypes.bodyAxisZ(), true);
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(script.contains("VectorTypes.bodyAxisZ()"), "Should contain VectorTypes.bodyAxisZ(): " + script);
        }

        @Test
        @DisplayName("towardBody(10) produces valid Groovy expression with NAIF ID")
        void towardBodySerialized() {
            recorder.startRecording();
            recorder.setVectorVisible(399, VectorTypes.towardBody(10), true);
            recorder.stopRecording();

            String script = recorder.getScript();
            assertTrue(
                    script.contains("VectorTypes.towardBody(10)"),
                    "Should contain VectorTypes.towardBody(10): " + script);
        }
    }

    // ── Frustum overlay serialization (Step 22) ────────────────────────────────

    @Nested
    @DisplayName("Frustum overlay serialization")
    class FrustumOverlaySerialization {

        @Test
        @DisplayName("setFrustumVisible(int, true) serializes with NAIF code")
        void setFrustumVisibleIntSerialized() {
            recorder.startRecording();
            recorder.setFrustumVisible(-98300, true);
            recorder.stopRecording();
            String script = recorder.getScript();
            assertTrue(
                    script.contains("setFrustumVisible(-98300, true)"),
                    "Expected setFrustumVisible(-98300, true) in: " + script);
        }

        @Test
        @DisplayName("setFrustumVisible(String, false) serializes with quoted name")
        void setFrustumVisibleNameSerialized() {
            recorder.startRecording();
            recorder.setFrustumVisible("NH_LORRI", false);
            recorder.stopRecording();
            String script = recorder.getScript();
            assertTrue(
                    script.contains("setFrustumVisible(\"NH_LORRI\", false)"),
                    "Expected setFrustumVisible(\"NH_LORRI\", false) in: " + script);
        }

        @Test
        @DisplayName("setFrustumColor(int, r, g, b) serializes with NAIF code")
        void setFrustumColorIntSerialized() {
            recorder.startRecording();
            recorder.setFrustumColor(-98300, 255, 80, 20);
            recorder.stopRecording();
            String script = recorder.getScript();
            assertTrue(
                    script.contains("setFrustumColor(-98300, 255, 80, 20)"),
                    "Expected setFrustumColor(-98300, 255, 80, 20) in: " + script);
        }

        @Test
        @DisplayName("setFrustumColor(String, r, g, b) serializes with quoted name")
        void setFrustumColorNameSerialized() {
            recorder.startRecording();
            recorder.setFrustumColor("NH_LORRI", 25, 50, 255);
            recorder.stopRecording();
            String script = recorder.getScript();
            assertTrue(
                    script.contains("setFrustumColor(\"NH_LORRI\", 25, 50, 255)"),
                    "Expected setFrustumColor(\"NH_LORRI\", 25, 50, 255) in: " + script);
        }

        @Test
        @DisplayName("setFrustumColor(int, hex) serializes with quoted hex")
        void setFrustumColorHexSerialized() {
            recorder.startRecording();
            recorder.setFrustumColor(-98300, "#ff5014");
            recorder.stopRecording();
            String script = recorder.getScript();
            assertTrue(
                    script.contains("setFrustumColor(-98300, \"#ff5014\")"),
                    "Expected setFrustumColor(-98300, \"#ff5014\") in: " + script);
        }
    }

    // ── No-op delegate ──────────────────────────────────────────────────────────

    static class NoOpCommands implements SimulationCommands {
        String lastMethod;
        int lastIntArg;

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
        }

        @Override
        public void setTimeRate(double r) {
            lastMethod = "setTimeRate";
        }

        @Override
        public void setPaused(boolean p) {
            lastMethod = "setPaused";
        }

        @Override
        public void setET(double et) {
            lastMethod = "setET";
        }

        @Override
        public void setUTC(String s) {
            lastMethod = "setUTC";
        }

        @Override
        public void pointAt(int id, double dur) {
            lastMethod = "pointAt";
        }

        @Override
        public void goTo(int id, double rad, double dur) {
            lastMethod = "goTo";
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
        }

        @Override
        public void setCameraOrientation(double lx, double ly, double lz, double ux, double uy, double uz, double dur) {
            lastMethod = "setCameraOrientation";
        }

        @Override
        public void setSynodicFrame(int fid, int tid) {
            lastMethod = "setSynodicFrame";
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
        }

        @Override
        public void setTrailDuration(int id, double s) {
            lastMethod = "setTrailDuration";
        }

        @Override
        public void setTrailReferenceBody(int naifId, int referenceBodyId) {
            lastMethod = "setTrailReferenceBody";
        }

        @Override
        public void setVectorVisible(int id, VectorType t, boolean v) {
            lastMethod = "setVectorVisible";
        }

        @Override
        public void cancelTransition() {
            lastMethod = "cancelTransition";
        }

        @Override
        public void setFrustumVisible(int code, boolean v) {
            lastMethod = "setFrustumVisible";
        }

        @Override
        public void setFrustumVisible(String name, boolean v) {
            lastMethod = "setFrustumVisible";
        }

        @Override
        public void setFrustumPersistenceEnabled(int instrumentNaifCode, boolean enabled) {
            lastMethod = "setFrustumPersistenceEnabled";
        }

        @Override
        public void setFrustumPersistenceEnabled(String instrumentName, boolean enabled) {
            lastMethod = "setFrustumPersistenceEnabled";
        }

        @Override
        public void setFrustumColor(int instrumentNaifCode, int red, int green, int blue) {
            lastMethod = "setFrustumColor";
        }

        @Override
        public void setFrustumColor(String instrumentName, int red, int green, int blue) {
            lastMethod = "setFrustumColor";
        }

        @Override
        public void setFrustumColor(int instrumentNaifCode, String hexColor) {
            lastMethod = "setFrustumColor";
        }

        @Override
        public void setFrustumColor(String instrumentName, String hexColor) {
            lastMethod = "setFrustumColor";
        }

        @Override
        public void clearFrustumFootprints(int instrumentNaifCode) {
            lastMethod = "clearFrustumFootprints";
        }

        @Override
        public void clearFrustumFootprints(String instrumentName) {
            lastMethod = "clearFrustumFootprints";
        }

        @Override
        public void clearFrustumFootprints() {
            lastMethod = "clearFrustumFootprints";
        }

        @Override
        public void truck(double km, double dur) {
            lastMethod = "truck";
        }

        @Override
        public void crane(double km, double dur) {
            lastMethod = "crane";
        }

        @Override
        public void dolly(double km, double dur) {
            lastMethod = "dolly";
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
        }

        @Override
        public String getStateString() {
            lastMethod = "getStateString";
            return "AQAAAAAAAAAAAAAAAPA_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP8AAAAAAP_____________________AAAAAEZGAAA";
        }

        @Override
        public void setStateString(String s) {
            lastMethod = "setStateString";
        }
    }
}
