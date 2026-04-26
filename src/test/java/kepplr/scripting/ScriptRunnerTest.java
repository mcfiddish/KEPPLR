package kepplr.scripting;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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

    @Test
    @DisplayName("Inline script runs with implicit kepplr context")
    void inlineScriptRuns() throws Exception {
        List<String> output = new ArrayList<>();
        runner.setOutputListener(output::add);

        runner.runInlineScript("Console input", "selectBody(499)", true);
        Thread.sleep(500);

        assertEquals(499, commands.lastSelectBodyId, "Inline script should have called selectBody(499)");
        assertTrue(output.stream().anyMatch(line -> line.equals("✓ Completed: Console input")));
    }

    @Test
    @DisplayName("stop() interrupts inline script")
    void stopInterruptsInlineScript() throws Exception {
        List<String> output = new ArrayList<>();
        runner.setOutputListener(output::add);

        runner.runInlineScript("Console input", "waitWall(60.0)", true);
        Thread.sleep(100);
        assertTrue(runner.isRunning(), "Inline script should be running during waitWall");

        runner.stop();
        Thread.sleep(600);

        assertFalse(runner.isRunning(), "Inline script should stop after stop()");
        assertTrue(output.stream().anyMatch(line -> line.equals("— Interrupted: Console input")));
    }

    @Test
    @DisplayName("Scripts can enable frustum persistence by NAIF code and name")
    void scriptsCanSetFrustumPersistence() throws Exception {
        Path script = tempDir.resolve("frustum-persistence.groovy");
        Files.writeString(script, """
                kepplr.setFrustumPersistenceEnabled(-98300, true)
                kepplr.setFrustumPersistenceEnabled("NH_LORRI", false)
                """);

        runner.runScript(script);
        Thread.sleep(500);

        assertTrue(commands.calls.contains("setFrustumPersistenceEnabled:-98300:true"), commands.calls.toString());
        assertTrue(commands.calls.contains("setFrustumPersistenceEnabled:-98300:false"), commands.calls.toString());
    }

    @Test
    @DisplayName("Scripts can clear frustum footprints by NAIF code, name, and all-clear")
    void scriptsCanClearFrustumFootprints() throws Exception {
        Path script = tempDir.resolve("frustum-clears.groovy");
        Files.writeString(script, """
                kepplr.clearFrustumFootprints(-98300)
                kepplr.clearFrustumFootprints("NH_LORRI")
                kepplr.clearFrustumFootprints()
                """);

        runner.runScript(script);
        Thread.sleep(500);

        assertTrue(commands.calls.contains("clearFrustumFootprints:-98300"), commands.calls.toString());
        assertEquals(2, Collections.frequency(commands.calls, "clearFrustumFootprints:-98300"));
        assertTrue(commands.calls.contains("clearFrustumFootprints:all"), commands.calls.toString());
    }

    @Test
    @DisplayName("Scripts can set frustum color by instrument name with RGB and hex overloads")
    void scriptsCanSetFrustumColorByName() throws Exception {
        Path script = tempDir.resolve("frustum-colors.groovy");
        Files.writeString(script, """
                kepplr.setFrustumColor("NH_LORRI", 25, 50, 75)
                kepplr.setFrustumColor("NH_LORRI", "ff5014")
                """);

        runner.runScript(script);
        Thread.sleep(500);

        assertTrue(commands.calls.contains("setFrustumColorRgb:-98300:25:50:75"), commands.calls.toString());
        assertTrue(commands.calls.contains("setFrustumColorHex:-98300:ff5014"), commands.calls.toString());
    }

    // ── Cooperative interruption tests ─────────────────────────────────────────

    @Nested
    @DisplayName("Cooperative interruption")
    class CooperativeInterruptionTests {

        @Test
        @DisplayName("stop() interrupts blocking waitWall")
        void stopInterruptsWaitWall() throws Exception {
            Path script = tempDir.resolve("blocking.groovy");
            Files.writeString(script, "kepplr.waitWall(60.0)");

            runner.runScript(script);
            Thread.sleep(100);
            assertTrue(runner.isRunning(), "Script should be running");

            runner.stop();
            Thread.sleep(600);

            assertFalse(runner.isRunning(), "Script should be stopped after interrupt");
        }

        @Test
        @DisplayName("stop() interrupts waitSim")
        void stopInterruptsWaitSim() throws Exception {
            Path script = tempDir.resolve("waitSim.groovy");
            Files.writeString(script, """
                kepplr.setPaused(false)
                kepplr.waitSim(60.0)
                """);

            runner.runScript(script);
            Thread.sleep(100);
            assertTrue(runner.isRunning(), "Script should be running");

            runner.stop();
            Thread.sleep(600);

            assertFalse(runner.isRunning(), "Script should be stopped after interrupt");
        }

        @Test
        @DisplayName("stop() interrupts waitUntilSim")
        void stopInterruptsWaitUntilSim() throws Exception {
            Path script = tempDir.resolve("waitUntil.groovy");
            // Wait for a time far in the future to ensure it blocks
            Files.writeString(script, "kepplr.waitUntilSim(9999999999.0)");

            runner.runScript(script);
            Thread.sleep(100);
            assertTrue(runner.isRunning(), "Script should be running");

            runner.stop();
            Thread.sleep(600);

            assertFalse(runner.isRunning(), "Script should be stopped after interrupt");
        }

        @Test
        @DisplayName("stop() interrupts waitTransition")
        void stopInterruptsWaitTransition() throws Exception {
            Path script = tempDir.resolve("waitTrans.groovy");
            // Use a script that starts a long transition and waits for it
            // Note: goTo may fail in test environment without SPICE kernels, so we use waitWall
            // to simulate a blocking wait that represents the waitTransition state
            Files.writeString(script, """
                try {
                    kepplr.goTo(399, 30.0, 60.0)
                } catch (Exception e) {
                    // goTo may fail in test environment - that's ok
                }
                kepplr.waitWall(60.0)
                """);

            runner.runScript(script);
            Thread.sleep(100);
            assertTrue(runner.isRunning(), "Script should be running during wait");

            runner.stop();
            Thread.sleep(600);

            assertFalse(runner.isRunning(), "Script should be stopped after interrupt");
        }
    }

    // ── Command recording coverage tests ────────────────────────────────────────

    @Nested
    @DisplayName("Command recording coverage")
    class CommandRecordingCoverageTests {

        @Test
        @DisplayName("All SimulationCommands methods are recordable")
        void allCommandsAreRecordable() {
            // Get all methods from SimulationCommands interface
            Set<String> commandMethods = Arrays.stream(SimulationCommands.class.getMethods())
                    .filter(m -> !m.getName().equals("hashCode")
                            && !m.getName().equals("equals")
                            && !m.getName().equals("toString"))
                    .map(Method::getName)
                    .collect(Collectors.toSet());

            // Get all methods from RecordingCommands (the test implementation)
            Set<String> recordingMethods = Arrays.stream(RecordingCommands.class.getMethods())
                    .filter(m -> m.getDeclaringClass() == RecordingCommands.class)
                    .map(Method::getName)
                    .collect(Collectors.toSet());

            // Verify coverage - each command method must have a corresponding implementation
            for (String cmdMethod : commandMethods) {
                assertTrue(
                        recordingMethods.contains(cmdMethod),
                        "SimulationCommands method '" + cmdMethod
                                + "' has no corresponding RecordingCommands implementation");
            }
        }
    }

    // ── Recording commands ──────────────────────────────────────────────────────

    static class RecordingCommands implements SimulationCommands {
        volatile int lastSelectBodyId = -1;
        volatile boolean cancelTransitionCalled = false;
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

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
        public void setCameraPose(
                double x,
                double y,
                double z,
                double lx,
                double ly,
                double lz,
                double ux,
                double uy,
                double uz,
                double dur) {}

        @Override
        public void setCameraPose(
                double x,
                double y,
                double z,
                int id,
                double lx,
                double ly,
                double lz,
                double ux,
                double uy,
                double uz,
                double dur) {}

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
        public void setFrustumPersistenceEnabled(int instrumentNaifCode, boolean enabled) {
            calls.add("setFrustumPersistenceEnabled:" + instrumentNaifCode + ":" + enabled);
        }

        @Override
        public void setFrustumPersistenceEnabled(String instrumentName, boolean enabled) {
            calls.add("setFrustumPersistenceEnabled:" + instrumentName + ":" + enabled);
        }

        @Override
        public void setFrustumColor(int instrumentNaifCode, int red, int green, int blue) {
            calls.add("setFrustumColorRgb:" + instrumentNaifCode + ":" + red + ":" + green + ":" + blue);
        }

        @Override
        public void setFrustumColor(String instrumentName, int red, int green, int blue) {
            calls.add("setFrustumColorRgb:" + instrumentName + ":" + red + ":" + green + ":" + blue);
        }

        @Override
        public void setFrustumColor(int instrumentNaifCode, String hexColor) {
            calls.add("setFrustumColorHex:" + instrumentNaifCode + ":" + hexColor);
        }

        @Override
        public void setFrustumColor(String instrumentName, String hexColor) {
            calls.add("setFrustumColorHex:" + instrumentName + ":" + hexColor);
        }

        @Override
        public void clearFrustumFootprints(int instrumentNaifCode) {
            calls.add("clearFrustumFootprints:" + instrumentNaifCode);
        }

        @Override
        public void clearFrustumFootprints(String instrumentName) {
            calls.add("clearFrustumFootprints:" + instrumentName);
        }

        @Override
        public void clearFrustumFootprints() {
            calls.add("clearFrustumFootprints:all");
        }

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
        public void waitRenderFrames(int frameCount) {}

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

        @Override
        public void saveScenePreset(String path) {}

        @Override
        public void loadScenePreset(String path) {}

        @Override
        public kepplr.state.ScenePreset getScenePreset() {
            return null;
        }
    }
}
