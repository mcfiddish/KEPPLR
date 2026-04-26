package kepplr.scripting;

import java.io.BufferedReader;
import java.io.Writer;
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
 * <p><b>Security warning:</b> Scripts are trusted local code with full system access (filesystem, network, process
 * execution). Only run scripts you trust. See :doc:`../scripting` for details.
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
    private volatile ScriptOutputListener outputListener;

    /**
     * @param commands simulation commands for the script API; must not be null
     * @param state simulation state for timing primitives; must not be null
     */
    public ScriptRunner(SimulationCommands commands, SimulationState state) {
        this.commands = commands;
        this.state = state;
    }

    /**
     * Set a listener to receive script output and status events.
     *
     * <p>The listener is called on the script thread — implementations must marshal to the UI thread if needed.
     *
     * @param listener the listener, or null to disable output capture
     */
    public void setOutputListener(ScriptOutputListener listener) {
        this.outputListener = listener;
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

            Thread thread = new Thread(() -> executeScriptFile(scriptFile), THREAD_NAME);
            thread.setDaemon(true);
            scriptThread = thread;
            thread.start();
            logger.info("Script started: {}", scriptFile);
        }
    }

    /**
     * Run an inline Groovy snippet.
     *
     * <p>If a script is already running, it is interrupted and any active transition is cancelled before the new script
     * starts.
     *
     * @param displayName label used in output/status messages
     * @param scriptSource Groovy source to evaluate
     * @param wrapWithKepplrWith when true, evaluate inside {@code kepplr.with \{ ... \}} so the {@code kepplr.} prefix
     *     is optional
     */
    public void runInlineScript(String displayName, String scriptSource, boolean wrapWithKepplrWith) {
        synchronized (lock) {
            stopInternal();

            Thread thread =
                    new Thread(() -> executeInlineScript(displayName, scriptSource, wrapWithKepplrWith), THREAD_NAME);
            thread.setDaemon(true);
            scriptThread = thread;
            thread.start();
            logger.info("Inline script started: {}", displayName);
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

    private void executeScriptFile(Path scriptFile) {
        KepplrScript api = new KepplrScript(commands, state);
        emit("▶ Running: " + scriptFile.getFileName());
        try {
            try (BufferedReader reader = Files.newBufferedReader(scriptFile)) {
                evalScript(api, engine -> engine.eval(reader, createBindings(engine, api)));
            }
            logger.info("Script completed: {}", scriptFile);
            emit("✓ Completed: " + scriptFile.getFileName());
        } catch (ScriptException e) {
            if (isInterruptCause(e)) {
                logger.info("Script interrupted: {}", scriptFile);
                emit("— Interrupted: " + scriptFile.getFileName());
            } else {
                logger.error("Script failed: {}", scriptFile, e);
                emit("✗ Error: " + extractMessage(e));
            }
        } catch (Exception e) {
            logger.error("Script failed: {}", scriptFile, e);
            emit("✗ Error: " + extractMessage(e));
        }
    }

    private void executeInlineScript(String displayName, String scriptSource, boolean wrapWithKepplrWith) {
        KepplrScript api = new KepplrScript(commands, state);
        emit("▶ Running: " + displayName);
        try {
            String source = wrapWithKepplrWith ? "kepplr.with {\n" + scriptSource + "\n}" : scriptSource;
            Object result = evalScript(api, engine -> engine.eval(source, createBindings(engine, api)));
            if (result != null) {
                emit("= " + result);
            }
            logger.info("Inline script completed: {}", displayName);
            emit("✓ Completed: " + displayName);
        } catch (ScriptException e) {
            if (isInterruptCause(e)) {
                logger.info("Inline script interrupted: {}", displayName);
                emit("— Interrupted: " + displayName);
            } else {
                logger.error("Inline script failed: {}", displayName, e);
                emit("✗ Error: " + extractMessage(e));
            }
        } catch (Exception e) {
            logger.error("Inline script failed: {}", displayName, e);
            emit("✗ Error: " + extractMessage(e));
        }
    }

    private Object evalScript(KepplrScript api, ScriptEvaluator evaluator) throws Exception {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("groovy");
        if (engine == null) {
            logger.error("Groovy script engine not available — check groovy-jsr223 dependency");
            emit("ERROR: Groovy engine not available");
            return null;
        }

        Writer outputWriter = new LineForwardingWriter(outputListener);
        engine.getContext().setWriter(outputWriter);
        engine.getContext().setErrorWriter(outputWriter);
        return evaluator.eval(engine);
    }

    private static Bindings createBindings(ScriptEngine engine, KepplrScript api) {
        Bindings bindings = engine.createBindings();
        bindings.put("kepplr", api);
        bindings.put("VectorTypes", VectorTypes.class);
        bindings.put("CameraFrame", CameraFrame.class);
        bindings.put("RenderQuality", RenderQuality.class);
        return bindings;
    }

    private void emit(String line) {
        ScriptOutputListener listener = outputListener;
        if (listener != null) {
            listener.onOutput(line);
        }
    }

    private static String extractMessage(Throwable t) {
        // ScriptException wraps the real cause — dig to the most useful message
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
    }

    private static boolean isInterruptCause(Throwable t) {
        while (t != null) {
            if (t instanceof InterruptedException) return true;
            t = t.getCause();
        }
        return false;
    }

    @FunctionalInterface
    private interface ScriptEvaluator {
        Object eval(ScriptEngine engine) throws Exception;
    }
}
