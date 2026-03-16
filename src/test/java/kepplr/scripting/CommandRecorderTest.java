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
            recorder.focusBody(301);
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
            recorder.focusBody(301);
            recorder.stopRecording();

            String script = recorder.getScript();
            assertFalse(script.contains("selectBody"), "Previous recording should be cleared: " + script);
            assertTrue(script.contains("focusBody"), "New recording should be present: " + script);
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
        public void focusBody(int naifId) {
            lastMethod = "focusBody";
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
        public void setCameraLookDirection(
                double lx, double ly, double lz, double ux, double uy, double uz, double dur) {
            lastMethod = "setCameraLookDirection";
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
        public void setVectorVisible(int id, VectorType t, boolean v) {
            lastMethod = "setVectorVisible";
        }

        @Override
        public void cancelTransition() {
            lastMethod = "cancelTransition";
        }
    }
}
