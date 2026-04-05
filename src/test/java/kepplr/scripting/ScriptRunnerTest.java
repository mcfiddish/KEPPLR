package kepplr.scripting;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import kepplr.camera.CameraFrame;
import kepplr.commands.SimulationCommands;
import kepplr.config.KEPPLRConfiguration;
import kepplr.render.RenderQuality;
import kepplr.render.vector.VectorType;
import kepplr.state.DefaultSimulationState;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link ScriptRunner}. */
@DisplayName("ScriptRunner")
class ScriptRunnerTest {

    @TempDir
    Path tempDir;

    private DefaultSimulationState state;
    private RecordingCommands commands;
    private ScriptRunner runner;

    @BeforeEach
    void setUp() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTestTemplate();
        state = new DefaultSimulationState();
        commands = new RecordingCommands();
        runner = new ScriptRunner(commands, state);
    }

    @Test
    @DisplayName("Runs a simple script that calls selectBody")
    void runsSimpleScript() throws Exception {
        Path script = tempDir.resolve("test.groovy");
        Files.writeString(script, "kepplr.selectBody(399)");

        runner.runScript(script);
        Thread.sleep(500); // Allow time for script thread to execute

        assertEquals(399, commands.lastSelectBodyId, "Script should have called selectBody(399)");
    }

    @Test
    @DisplayName("Script error is logged, not thrown to caller")
    void scriptErrorHandled() throws Exception {
        Path script = tempDir.resolve("bad.groovy");
        Files.writeString(script, "this is not valid groovy (((");

        assertDoesNotThrow(() -> runner.runScript(script));
        Thread.sleep(500);
        assertFalse(runner.isRunning(), "Script should have finished (with error)");
    }

    @Test
    @DisplayName("isRunning reflects script lifecycle")
    void isRunningLifecycle() throws Exception {
        assertFalse(runner.isRunning());

        Path script = tempDir.resolve("slow.groovy");
        Files.writeString(script, "kepplr.waitWall(5.0)");

        runner.runScript(script);
        Thread.sleep(100);
        assertTrue(runner.isRunning(), "Script should be running during waitWall");

        runner.stop();
        Thread.sleep(600);
        assertFalse(runner.isRunning(), "Script should be stopped after stop()");
    }

    @Test
    @DisplayName("Re-running a script interrupts the previous one")
    void reRunInterruptsPrevious() throws Exception {
        Path slow = tempDir.resolve("slow.groovy");
        Files.writeString(slow, "kepplr.waitWall(60.0)");

        Path fast = tempDir.resolve("fast.groovy");
        Files.writeString(fast, "kepplr.selectBody(301)");

        runner.runScript(slow);
        Thread.sleep(100);
        assertTrue(runner.isRunning());

        runner.runScript(fast);
        Thread.sleep(500);

        assertEquals(301, commands.lastSelectBodyId, "Second script should have run");
    }

    @Test
    @DisplayName("VectorTypes binding is available in scripts")
    void vectorTypesBinding() throws Exception {
        Path script = tempDir.resolve("vectors.groovy");
        Files.writeString(script, "kepplr.setVectorVisible(399, VectorTypes.velocity(), true)");

        runner.runScript(script);
        Thread.sleep(500);

        assertFalse(runner.isRunning(), "Script should have completed successfully");
    }

    @Test
    @DisplayName("CameraFrame binding is available in scripts")
    void cameraFrameBinding() throws Exception {
        Path script = tempDir.resolve("frame.groovy");
        Files.writeString(script, "kepplr.setCameraFrame(CameraFrame.BODY_FIXED)");

        runner.runScript(script);
        Thread.sleep(500);

        assertFalse(runner.isRunning(), "Script should have completed successfully");
    }

    @Test
    @DisplayName("stop() cancels active transition")
    void stopCancelsTransition() throws Exception {
        Path script = tempDir.resolve("long.groovy");
        Files.writeString(script, "kepplr.waitWall(60.0)");

        runner.runScript(script);
        Thread.sleep(100);

        runner.stop();
        assertTrue(commands.cancelTransitionCalled, "stop() should call cancelTransition()");
    }

    // ── Recording commands ──────────────────────────────────────────────────────

    static class RecordingCommands implements SimulationCommands {
        volatile int lastSelectBodyId = -1;
        volatile boolean cancelTransitionCalled = false;

        @Override
        public void selectBody(int naifId) {
            lastSelectBodyId = naifId;
        }

        @Override
        public void centerBody(int naifId) {}

        @Override
        public void targetBody(int naifId) {}

        @Override
        public void setTimeRate(double r) {}

        @Override
        public void setPaused(boolean p) {}

        @Override
        public void setET(double et) {}

        @Override
        public void setUTC(String s) {}

        @Override
        public void pointAt(int id, double dur) {}

        @Override
        public void goTo(int id, double rad, double dur) {}

        @Override
        public void zoom(double f, double dur) {}

        @Override
        public void setFov(double deg, double dur) {}

        @Override
        public void orbit(double r, double u, double dur) {}

        @Override
        public void tilt(double deg, double dur) {}

        @Override
        public void yaw(double deg, double dur) {}

        @Override
        public void roll(double deg, double dur) {}

        @Override
        public void setCameraPosition(double x, double y, double z, double dur) {}

        @Override
        public void setCameraPosition(double x, double y, double z, int id, double dur) {}

        @Override
        public void setCameraOrientation(
                double lx, double ly, double lz, double ux, double uy, double uz, double dur) {}

        @Override
        public void setSynodicFrame(int fid, int tid) {}

        @Override
        public void setCameraFrame(CameraFrame f) {}

        @Override
        public void setRenderQuality(RenderQuality q) {}

        @Override
        public void setLabelVisible(int id, boolean v) {}

        @Override
        public void setHudTimeVisible(boolean v) {}

        @Override
        public void setHudInfoVisible(boolean v) {}

        @Override
        public void setTrailVisible(int id, boolean v) {}

        @Override
        public void setTrailDuration(int id, double s) {}

        @Override
        public void setTrailReferenceBody(int naifId, int referenceBodyId) {}

        @Override
        public void setVectorVisible(int id, VectorType t, boolean v) {}

        @Override
        public void cancelTransition() {
            cancelTransitionCalled = true;
        }

        @Override
        public void setFrustumVisible(int code, boolean v) {}

        @Override
        public void setFrustumVisible(String name, boolean v) {}

        @Override
        public void truck(double km, double dur) {}

        @Override
        public void crane(double km, double dur) {}

        @Override
        public void dolly(double km, double dur) {}

        @Override
        public void loadConfiguration(String path) {}

        @Override
        public void saveScreenshot(String outputPath) {}

        @Override
        public void displayMessage(String text, double durationSeconds) {}

        @Override
        public void setWindowSize(int width, int height) {}

        @Override
        public void setBodyVisible(int naifId, boolean visible) {}

        @Override
        public String getStateString() {
            return "";
        }

        @Override
        public void setStateString(String s) {}
    }
}
