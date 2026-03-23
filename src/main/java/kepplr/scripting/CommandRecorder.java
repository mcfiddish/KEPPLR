package kepplr.scripting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import kepplr.camera.CameraFrame;
import kepplr.commands.SimulationCommands;
import kepplr.render.RenderQuality;
import kepplr.render.vector.VectorType;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Decorator that records {@link SimulationCommands} calls and exports them as a runnable Groovy script (Step 20).
 *
 * <p>Wraps any {@code SimulationCommands} implementation. When recording is active, every method call is captured with
 * its arguments and wall-clock timestamp. When recording stops, {@link #getScript()} serializes the log as a Groovy
 * script with {@code kepplr.waitWall(seconds)} calls inserted between commands based on elapsed wall time.
 *
 * <h3>Instant camera command coalescing</h3>
 *
 * <p>Instant camera commands ({@code durationSeconds == 0}) from mouse and keyboard navigation fire many times per
 * second. Instead of recording verbatim, a coalescing strategy merges them:
 *
 * <ul>
 *   <li>{@code orbit}, {@code tilt}, {@code roll}, {@code yaw} — degree arguments are summed
 *   <li>{@code zoom} — factors are multiplied
 *   <li>{@code setFov}, {@code setCameraPosition}, {@code setCameraOrientation} — last value wins
 * </ul>
 *
 * <p>The coalescing window is {@value kepplr.util.KepplrConstants#RECORDER_COALESCE_WINDOW_MS} ms. A coalesced command
 * is flushed when the window expires, a different command type arrives, or a non-instant command arrives.
 *
 * <h3>Example output</h3>
 *
 * <pre>{@code
 * // KEPPLR recorded script
 * kepplr.focusBody(399)
 * kepplr.waitWall(2.500)
 * kepplr.setTimeRate(100.0)
 * kepplr.waitWall(5.000)
 * kepplr.orbit(45.2, 12.0, 0.0)
 * }</pre>
 */
public final class CommandRecorder implements SimulationCommands {

    private static final Logger logger = LogManager.getLogger();

    private final SimulationCommands delegate;

    // ── Recording state (synchronized on `this`) ────────────────────────────────

    private boolean recording = false;
    private final List<RecordedCommand> recordings = new ArrayList<>();
    private long lastRecordNanos;

    // ── Coalescing state (synchronized on `this`) ───────────────────────────────

    private String coalesceType = null;
    private double[] coalesceArgs = null;
    private long coalesceWindowStartNanos;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kepplr-recorder-coalesce");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> coalesceFuture = null;

    private record RecordedCommand(String groovyLine, long wallNanos) {}

    /** @param delegate the underlying commands implementation to wrap; must not be null */
    public CommandRecorder(SimulationCommands delegate) {
        this.delegate = delegate;
    }

    // ── Recording control ───────────────────────────────────────────────────────

    /**
     * Start recording. Clears any previous recording.
     *
     * <p>Example: {@code recorder.startRecording()}
     */
    public synchronized void startRecording() {
        recordings.clear();
        coalesceType = null;
        coalesceArgs = null;
        if (coalesceFuture != null) {
            coalesceFuture.cancel(false);
            coalesceFuture = null;
        }
        lastRecordNanos = System.nanoTime();
        recording = true;
        logger.info("Recording started");
    }

    /**
     * Stop recording. Flushes any pending coalesced command.
     *
     * <p>Example: {@code recorder.stopRecording()}
     */
    public synchronized void stopRecording() {
        flushCoalesced();
        recording = false;
        logger.info("Recording stopped ({} commands)", recordings.size());
    }

    /**
     * Returns {@code true} if recording is active.
     *
     * <p>Example: {@code if (recorder.isRecording()) \{ ... \}}
     *
     * @return {@code true} if recording
     */
    public synchronized boolean isRecording() {
        return recording;
    }

    /**
     * Export the recorded commands as a Groovy script string.
     *
     * <p>Each command is emitted as a method call on the {@code kepplr} API object. {@code kepplr.waitWall(seconds)}
     * calls are inserted between commands based on elapsed wall time. NAIF ID arguments are emitted as integers.
     *
     * <p>Example: {@code String script = recorder.getScript()}
     *
     * @return the Groovy script string
     */
    public synchronized String getScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("// KEPPLR recorded script\n");

        long prevNanos = -1;
        for (RecordedCommand cmd : recordings) {
            if (prevNanos >= 0) {
                double deltaSec = (cmd.wallNanos - prevNanos) / 1_000_000_000.0;
                if (deltaSec > 0.05) {
                    sb.append(String.format("kepplr.waitWall(%.3f)\n", deltaSec));
                }
            }
            sb.append(cmd.groovyLine).append('\n');
            prevNanos = cmd.wallNanos;
        }

        return sb.toString();
    }

    // ── Coalescing helpers ──────────────────────────────────────────────────────

    private synchronized void recordCommand(String groovyLine) {
        if (!recording) return;
        flushCoalesced();
        long now = System.nanoTime();
        recordings.add(new RecordedCommand(groovyLine, now));
        lastRecordNanos = now;
    }

    /**
     * Record an instant camera command with coalescing. Same-type commands within the coalescing window are merged.
     *
     * @param type command type identifier (e.g., "orbit", "zoom")
     * @param args the command arguments
     * @param mergeMode how to merge: SUM, MULTIPLY, or LAST_WINS
     */
    private synchronized void recordInstantCamera(String type, double[] args, MergeMode mergeMode) {
        if (!recording) return;

        if (coalesceType != null && !coalesceType.equals(type)) {
            flushCoalesced();
        }

        long now = System.nanoTime();

        if (coalesceType == null) {
            // Start new coalescing window
            coalesceType = type;
            coalesceArgs = args.clone();
            coalesceWindowStartNanos = now;
        } else {
            // Merge into existing window
            switch (mergeMode) {
                case SUM -> {
                    for (int i = 0; i < args.length; i++) coalesceArgs[i] += args[i];
                }
                case MULTIPLY -> {
                    for (int i = 0; i < args.length; i++) coalesceArgs[i] *= args[i];
                }
                case LAST_WINS -> coalesceArgs = args.clone();
            }
        }

        // Schedule or reschedule the flush timer
        if (coalesceFuture != null) {
            coalesceFuture.cancel(false);
        }
        coalesceFuture = scheduler.schedule(
                this::timerFlush, KepplrConstants.RECORDER_COALESCE_WINDOW_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void timerFlush() {
        flushCoalesced();
    }

    /** Flush the coalesced command if any. Must be called under synchronized(this). */
    private void flushCoalesced() {
        if (coalesceType == null) return;

        String line = buildCoalescedLine(coalesceType, coalesceArgs);
        long now = System.nanoTime();
        recordings.add(new RecordedCommand(line, now));
        lastRecordNanos = now;

        coalesceType = null;
        coalesceArgs = null;
        if (coalesceFuture != null) {
            coalesceFuture.cancel(false);
            coalesceFuture = null;
        }
    }

    private static String buildCoalescedLine(String type, double[] args) {
        return switch (type) {
            case "orbit" -> String.format("kepplr.orbit(%.4f, %.4f, 0.0)", args[0], args[1]);
            case "tilt" -> String.format("kepplr.tilt(%.4f, 0.0)", args[0]);
            case "yaw" -> String.format("kepplr.yaw(%.4f, 0.0)", args[0]);
            case "roll" -> String.format("kepplr.roll(%.4f, 0.0)", args[0]);
            case "zoom" -> String.format("kepplr.zoom(%.6f, 0.0)", args[0]);
            case "setFov" -> String.format("kepplr.setFov(%.4f, 0.0)", args[0]);
            case "setCameraPosition3" ->
                String.format("kepplr.setCameraPosition(%.4f, %.4f, %.4f, 0.0)", args[0], args[1], args[2]);
            case "setCameraPosition5" ->
                String.format(
                        "kepplr.setCameraPosition(%.4f, %.4f, %.4f, %d, 0.0)",
                        args[0], args[1], args[2], (int) args[3]);
            case "setCameraOrientation" ->
                String.format(
                        "kepplr.setCameraOrientation(%.4f, %.4f, %.4f, %.4f, %.4f, %.4f, 0.0)",
                        args[0], args[1], args[2], args[3], args[4], args[5]);
            case "truck" -> String.format("kepplr.truck(%.4f, 0.0)", args[0]);
            case "crane" -> String.format("kepplr.crane(%.4f, 0.0)", args[0]);
            case "dolly" -> String.format("kepplr.dolly(%.4f, 0.0)", args[0]);
            default -> "// unknown coalesced command: " + type;
        };
    }

    private enum MergeMode {
        SUM,
        MULTIPLY,
        LAST_WINS
    }

    // ── Formatting helpers ──────────────────────────────────────────────────────

    private static String fmt(double d) {
        // Always emit as a Groovy double literal to preserve type
        String s = Double.toString(d);
        // Double.toString already appends ".0" for whole numbers; just return as-is
        return s;
    }

    private static String fmtBool(boolean b) {
        return b ? "true" : "false";
    }

    // ── SimulationCommands delegation ───────────────────────────────────────────

    @Override
    public void selectBody(int naifId) {
        recordCommand("kepplr.selectBody(" + naifId + ")");
        delegate.selectBody(naifId);
    }

    @Override
    public void focusBody(int naifId) {
        recordCommand("kepplr.focusBody(" + naifId + ")");
        delegate.focusBody(naifId);
    }

    @Override
    public void targetBody(int naifId) {
        recordCommand("kepplr.targetBody(" + naifId + ")");
        delegate.targetBody(naifId);
    }

    @Override
    public void setTimeRate(double simSecondsPerWallSecond) {
        recordCommand("kepplr.setTimeRate(" + fmt(simSecondsPerWallSecond) + ")");
        delegate.setTimeRate(simSecondsPerWallSecond);
    }

    @Override
    public void setPaused(boolean paused) {
        recordCommand("kepplr.setPaused(" + fmtBool(paused) + ")");
        delegate.setPaused(paused);
    }

    @Override
    public void setET(double et) {
        recordCommand("kepplr.setET(" + fmt(et) + ")");
        delegate.setET(et);
    }

    @Override
    public void setUTC(String utcString) {
        recordCommand("kepplr.setUTC(\"" + utcString + "\")");
        delegate.setUTC(utcString);
    }

    @Override
    public void pointAt(int naifId, double durationSeconds) {
        recordCommand("kepplr.pointAt(" + naifId + ", " + fmt(durationSeconds) + ")");
        delegate.pointAt(naifId, durationSeconds);
    }

    @Override
    public void goTo(int naifId, double apparentRadiusDeg, double durationSeconds) {
        recordCommand("kepplr.goTo(" + naifId + ", " + fmt(apparentRadiusDeg) + ", " + fmt(durationSeconds) + ")");
        delegate.goTo(naifId, apparentRadiusDeg, durationSeconds);
    }

    @Override
    public void zoom(double factor, double durationSeconds) {
        if (durationSeconds <= 0) {
            recordInstantCamera("zoom", new double[] {factor}, MergeMode.MULTIPLY);
        } else {
            recordCommand("kepplr.zoom(" + fmt(factor) + ", " + fmt(durationSeconds) + ")");
        }
        delegate.zoom(factor, durationSeconds);
    }

    @Override
    public void setFov(double degrees, double durationSeconds) {
        if (durationSeconds <= 0) {
            recordInstantCamera("setFov", new double[] {degrees}, MergeMode.LAST_WINS);
        } else {
            recordCommand("kepplr.setFov(" + fmt(degrees) + ", " + fmt(durationSeconds) + ")");
        }
        delegate.setFov(degrees, durationSeconds);
    }

    @Override
    public void orbit(double rightDegrees, double upDegrees, double durationSeconds) {
        if (durationSeconds <= 0) {
            recordInstantCamera("orbit", new double[] {rightDegrees, upDegrees}, MergeMode.SUM);
        } else {
            recordCommand(
                    "kepplr.orbit(" + fmt(rightDegrees) + ", " + fmt(upDegrees) + ", " + fmt(durationSeconds) + ")");
        }
        delegate.orbit(rightDegrees, upDegrees, durationSeconds);
    }

    @Override
    public void tilt(double degrees, double durationSeconds) {
        if (durationSeconds <= 0) {
            recordInstantCamera("tilt", new double[] {degrees}, MergeMode.SUM);
        } else {
            recordCommand("kepplr.tilt(" + fmt(degrees) + ", " + fmt(durationSeconds) + ")");
        }
        delegate.tilt(degrees, durationSeconds);
    }

    @Override
    public void yaw(double degrees, double durationSeconds) {
        if (durationSeconds <= 0) {
            recordInstantCamera("yaw", new double[] {degrees}, MergeMode.SUM);
        } else {
            recordCommand("kepplr.yaw(" + fmt(degrees) + ", " + fmt(durationSeconds) + ")");
        }
        delegate.yaw(degrees, durationSeconds);
    }

    @Override
    public void roll(double degrees, double durationSeconds) {
        if (durationSeconds <= 0) {
            recordInstantCamera("roll", new double[] {degrees}, MergeMode.SUM);
        } else {
            recordCommand("kepplr.roll(" + fmt(degrees) + ", " + fmt(durationSeconds) + ")");
        }
        delegate.roll(degrees, durationSeconds);
    }

    @Override
    public void setCameraPosition(double x, double y, double z, double durationSeconds) {
        if (durationSeconds <= 0) {
            recordInstantCamera("setCameraPosition3", new double[] {x, y, z}, MergeMode.LAST_WINS);
        } else {
            recordCommand("kepplr.setCameraPosition(" + fmt(x) + ", " + fmt(y) + ", " + fmt(z) + ", "
                    + fmt(durationSeconds) + ")");
        }
        delegate.setCameraPosition(x, y, z, durationSeconds);
    }

    @Override
    public void setCameraPosition(double x, double y, double z, int originNaifId, double durationSeconds) {
        if (durationSeconds <= 0) {
            recordInstantCamera("setCameraPosition5", new double[] {x, y, z, originNaifId}, MergeMode.LAST_WINS);
        } else {
            recordCommand("kepplr.setCameraPosition(" + fmt(x) + ", " + fmt(y) + ", " + fmt(z) + ", " + originNaifId
                    + ", " + fmt(durationSeconds) + ")");
        }
        delegate.setCameraPosition(x, y, z, originNaifId, durationSeconds);
    }

    @Override
    public void setCameraOrientation(
            double lookX, double lookY, double lookZ, double upX, double upY, double upZ, double durationSeconds) {
        if (durationSeconds <= 0) {
            recordInstantCamera(
                    "setCameraOrientation", new double[] {lookX, lookY, lookZ, upX, upY, upZ}, MergeMode.LAST_WINS);
        } else {
            recordCommand("kepplr.setCameraOrientation(" + fmt(lookX) + ", " + fmt(lookY) + ", " + fmt(lookZ) + ", "
                    + fmt(upX) + ", " + fmt(upY) + ", " + fmt(upZ) + ", " + fmt(durationSeconds) + ")");
        }
        delegate.setCameraOrientation(lookX, lookY, lookZ, upX, upY, upZ, durationSeconds);
    }

    // ── Cinematic camera commands (Step 24) ──

    @Override
    public void truck(double km, double durationSeconds) {
        if (durationSeconds <= 0) {
            recordInstantCamera("truck", new double[] {km}, MergeMode.SUM);
        } else {
            recordCommand("kepplr.truck(" + fmt(km) + ", " + fmt(durationSeconds) + ")");
        }
        delegate.truck(km, durationSeconds);
    }

    @Override
    public void crane(double km, double durationSeconds) {
        if (durationSeconds <= 0) {
            recordInstantCamera("crane", new double[] {km}, MergeMode.SUM);
        } else {
            recordCommand("kepplr.crane(" + fmt(km) + ", " + fmt(durationSeconds) + ")");
        }
        delegate.crane(km, durationSeconds);
    }

    @Override
    public void dolly(double km, double durationSeconds) {
        if (durationSeconds <= 0) {
            recordInstantCamera("dolly", new double[] {km}, MergeMode.SUM);
        } else {
            recordCommand("kepplr.dolly(" + fmt(km) + ", " + fmt(durationSeconds) + ")");
        }
        delegate.dolly(km, durationSeconds);
    }

    @Override
    public void setSynodicFrame(int focusNaifId, int targetNaifId) {
        recordCommand("kepplr.setSynodicFrame(" + focusNaifId + ", " + targetNaifId + ")");
        delegate.setSynodicFrame(focusNaifId, targetNaifId);
    }

    @Override
    public void setCameraFrame(CameraFrame frame) {
        recordCommand("kepplr.setCameraFrame(CameraFrame." + frame.name() + ")");
        delegate.setCameraFrame(frame);
    }

    @Override
    public void setRenderQuality(RenderQuality quality) {
        recordCommand("kepplr.setRenderQuality(RenderQuality." + quality.name() + ")");
        delegate.setRenderQuality(quality);
    }

    @Override
    public void setLabelVisible(int naifId, boolean visible) {
        recordCommand("kepplr.setLabelVisible(" + naifId + ", " + fmtBool(visible) + ")");
        delegate.setLabelVisible(naifId, visible);
    }

    @Override
    public void setHudTimeVisible(boolean visible) {
        recordCommand("kepplr.setHudTimeVisible(" + fmtBool(visible) + ")");
        delegate.setHudTimeVisible(visible);
    }

    @Override
    public void setHudInfoVisible(boolean visible) {
        recordCommand("kepplr.setHudInfoVisible(" + fmtBool(visible) + ")");
        delegate.setHudInfoVisible(visible);
    }

    @Override
    public void setTrailVisible(int naifId, boolean visible) {
        recordCommand("kepplr.setTrailVisible(" + naifId + ", " + fmtBool(visible) + ")");
        delegate.setTrailVisible(naifId, visible);
    }

    @Override
    public void setTrailDuration(int naifId, double seconds) {
        recordCommand("kepplr.setTrailDuration(" + naifId + ", " + fmt(seconds) + ")");
        delegate.setTrailDuration(naifId, seconds);
    }

    @Override
    public void setVectorVisible(int naifId, VectorType type, boolean visible) {
        recordCommand("kepplr.setVectorVisible(" + naifId + ", " + type.toScript() + ", " + fmtBool(visible) + ")");
        delegate.setVectorVisible(naifId, type, visible);
    }

    @Override
    public void cancelTransition() {
        recordCommand("kepplr.cancelTransition()");
        delegate.cancelTransition();
    }

    // ── Instrument frustum overlays (Step 22) ──

    @Override
    public void setFrustumVisible(int instrumentNaifCode, boolean visible) {
        recordCommand("kepplr.setFrustumVisible(" + instrumentNaifCode + ", " + fmtBool(visible) + ")");
        delegate.setFrustumVisible(instrumentNaifCode, visible);
    }

    @Override
    public void setFrustumVisible(String instrumentName, boolean visible) {
        recordCommand("kepplr.setFrustumVisible(\"" + instrumentName + "\", " + fmtBool(visible) + ")");
        delegate.setFrustumVisible(instrumentName, visible);
    }

    // ── Screenshot capture (Step 25) ──

    @Override
    public void saveScreenshot(String outputPath) {
        recordCommand("kepplr.saveScreenshot(\"" + outputPath + "\")");
        delegate.saveScreenshot(outputPath);
    }

    // ── Configuration reload (Step 27) ──

    @Override
    public void loadConfiguration(String path) {
        recordCommand("kepplr.loadConfiguration(\"" + path + "\")");
        delegate.loadConfiguration(path);
    }

    // ── HUD message (Step 28) ──

    @Override
    public void displayMessage(String text, double durationSeconds) {
        recordCommand("kepplr.displayMessage(\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\", "
                + fmt(durationSeconds) + ")");
        delegate.displayMessage(text, durationSeconds);
    }

    // ── Window resize (Step 28) ──

    @Override
    public void setWindowSize(int width, int height) {
        recordCommand("kepplr.setWindowSize(" + width + ", " + height + ")");
        delegate.setWindowSize(width, height);
    }
}
