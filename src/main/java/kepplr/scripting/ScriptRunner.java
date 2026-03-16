package kepplr.scripting;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import kepplr.camera.CameraFrame;
import kepplr.commands.SimulationCommands;
import kepplr.render.RenderQuality;
import kepplr.render.vector.VectorTypes;
import kepplr.state.SimulationState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages the lifecycle of Groovy script execution (Step 20).
 *
 * <p>Scripts run on a dedicated daemon thread ({@code kepplr-groovy-script}), separate from both the JME render thread
 * and the JavaFX application thread. {@link SimulationCommands} methods are thread-safe (they post to the transition
 * controller's inbox), so no additional synchronization is needed.
 *
 * <p>If {@link #runScript} is called while a script is already running, the previous script thread is interrupted and
 * any active transition is cancelled before the new script starts.
 *
 * <h3>Example usage</h3>
 *
 * <pre>{@code
 * ScriptRunner runner = new ScriptRunner(commands, state);
 * runner.runScript(Path.of("my_script.groovy"));
 * // ...
 * runner.stop(); // interrupt if still running
 * }</pre>
 */
public final class ScriptRunner {

    private static final Logger logger = LogManager.getLogger();
    private static final String THREAD_NAME = "kepplr-groovy-script";

    private final SimulationCommands commands;
    private final SimulationState state;

    private final Object lock = new Object();
    private volatile Thread scriptThread;

    /**
     * @param commands simulation commands for the script API; must not be null
     * @param state simulation state for timing primitives; must not be null
     */
    public ScriptRunner(SimulationCommands commands, SimulationState state) {
        this.commands = commands;
        this.state = state;
    }

    /**
     * Run a Groovy script file.
     *
     * <p>If a script is already running, it is interrupted and any active transition is cancelled before the new script
     * starts. The script has access to a {@code kepplr} binding of type {@link KepplrScript}.
     *
     * <p>Example: {@code runner.runScript(Path.of("flyby.groovy"))}
     *
     * @param scriptFile path to the {@code .groovy} script file
     */
    public void runScript(Path scriptFile) {
        synchronized (lock) {
            stopInternal();

            Thread thread = new Thread(() -> executeScript(scriptFile), THREAD_NAME);
            thread.setDaemon(true);
            scriptThread = thread;
            thread.start();
            logger.info("Script started: {}", scriptFile);
        }
    }

    /**
     * Returns {@code true} if a script is currently running.
     *
     * <p>Example: {@code if (runner.isRunning()) \{ ... \}}
     *
     * @return {@code true} if the script thread is alive
     */
    public boolean isRunning() {
        Thread t = scriptThread;
        return t != null && t.isAlive();
    }

    /**
     * Stop the currently running script by interrupting its thread and cancelling any active transition.
     *
     * <p>Example: {@code runner.stop()}
     */
    public void stop() {
        synchronized (lock) {
            stopInternal();
        }
    }

    private void stopInternal() {
        Thread t = scriptThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
            commands.cancelTransition();
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) {
                logger.warn("Script thread did not terminate within 500ms after interrupt");
            }
        }
        scriptThread = null;
    }

    private void executeScript(Path scriptFile) {
        KepplrScript api = new KepplrScript(commands, state);
        try {
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("groovy");
            if (engine == null) {
                logger.error("Groovy script engine not available — check groovy-jsr223 dependency");
                return;
            }

            Bindings bindings = engine.createBindings();
            bindings.put("kepplr", api);
            bindings.put("VectorTypes", VectorTypes.class);
            bindings.put("CameraFrame", CameraFrame.class);
            bindings.put("RenderQuality", RenderQuality.class);

            try (BufferedReader reader = Files.newBufferedReader(scriptFile)) {
                engine.eval(reader, bindings);
            }
            logger.info("Script completed: {}", scriptFile);
        } catch (ScriptException e) {
            if (isInterruptCause(e)) {
                logger.info("Script interrupted: {}", scriptFile);
            } else {
                logger.error("Script failed: {}", scriptFile, e);
            }
        } catch (Exception e) {
            logger.error("Script failed: {}", scriptFile, e);
        }
    }

    private static boolean isInterruptCause(Throwable t) {
        while (t != null) {
            if (t instanceof InterruptedException) return true;
            t = t.getCause();
        }
        return false;
    }
}
