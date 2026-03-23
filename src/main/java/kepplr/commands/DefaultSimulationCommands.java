package kepplr.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import kepplr.camera.CameraFrame;
import kepplr.camera.TransitionController;
import kepplr.config.KEPPLRConfiguration;
import kepplr.core.SimulationClock;
import kepplr.ephemeris.BodyLookupService;
import kepplr.render.RenderQuality;
import kepplr.render.vector.VectorType;
import kepplr.state.DefaultSimulationState;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Concrete implementation of {@link SimulationCommands} that applies state-transition rules directly to a
 * {@link DefaultSimulationState} (REDESIGN.md §4, CLAUDE.md Rule 2).
 *
 * <p>Holds a reference to {@link DefaultSimulationState} (not the read-only {@link kepplr.state.SimulationState}
 * interface) so it can call the mutable setters. This keeps the one-direction state flow intact: the UI only sees the
 * read-only interface; commands write via this class.
 *
 * <h3>State-transition rules (§4.3–§4.6)</h3>
 *
 * <ul>
 *   <li>{@code selectBody(id)}: sets selected only; no camera change (§4.3)
 *   <li>{@code focusBody(id)}: sets selected, focused, and targeted (§4.5)
 *   <li>{@code targetBody(id)}: sets selected and targeted (§4.4)
 *   <li>{@code setTimeRate(r)}: delegates to {@link SimulationClock} — absolute, no-jump (§2.3)
 *   <li>{@code setPaused(b)}: delegates to {@link SimulationClock} — clamp/resume logic (§1.2)
 *   <li>{@code setET(et)}: delegates to {@link SimulationClock} (§1.2)
 *   <li>{@code setUTC(s)}: converts via Picante then delegates to {@link SimulationClock} (§1.2)
 * </ul>
 */
public final class DefaultSimulationCommands implements SimulationCommands {

    private static final Logger logger = LogManager.getLogger();

    private final DefaultSimulationState state;
    private final SimulationClock clock;
    private final TransitionController transitionController;

    /**
     * Accepts a {@link CountDownLatch} and enqueues a JME-thread scene rebuild, counting the latch down when the
     * rebuild finishes. Set by {@code KepplrApp} after construction; {@code null} in unit tests.
     */
    private Consumer<CountDownLatch> sceneRebuildCallback;

    /**
     * Accepts an output path and a {@link CountDownLatch}, enqueues a JME-thread framebuffer capture, and counts the
     * latch down when the PNG file has been written. Set by {@code KepplrApp} after construction; {@code null} in unit
     * tests.
     */
    private ScreenshotCallback screenshotCallback;

    /**
     * @param state mutable state object this instance will write to for interaction commands
     * @param clock simulation clock this instance will delegate time commands to
     * @param transitionController camera transition controller; receives {@code pointAt}/{@code goTo} requests
     */
    public DefaultSimulationCommands(
            DefaultSimulationState state, SimulationClock clock, TransitionController transitionController) {
        this.state = state;
        this.clock = clock;
        this.transitionController = transitionController;
    }

    /** Select a body for HUD display only (§4.3). Does not change focused or targeted state. */
    @Override
    public void selectBody(int naifId) {
        state.setSelectedBodyId(naifId);
    }

    /**
     * Focus the camera on a body (§4.5). Implicitly selects and targets the same body.
     *
     * <p>Initiates a {@code pointAt} slew followed by a {@code goTo} translation (Step 18). The {@code goTo} is queued
     * and begins automatically when the {@code pointAt} completes.
     */
    @Override
    public void focusBody(int naifId) {
        state.setSelectedBodyId(naifId);
        state.setFocusedBodyId(naifId);
        state.setTargetedBodyId(naifId);
        transitionController.requestPointAt(naifId, KepplrConstants.DEFAULT_SLEW_DURATION_SECONDS);
        transitionController.requestGoTo(
                naifId,
                KepplrConstants.DEFAULT_GOTO_APPARENT_RADIUS_DEG,
                KepplrConstants.DEFAULT_GOTO_DURATION_SECONDS);
    }

    /**
     * Target a body — "point at" (§4.4). Implicitly selects the body.
     *
     * <p>Initiates a {@code pointAt} slew (Step 18).
     */
    @Override
    public void targetBody(int naifId) {
        state.setSelectedBodyId(naifId);
        state.setTargetedBodyId(naifId);
        transitionController.requestPointAt(naifId, KepplrConstants.DEFAULT_SLEW_DURATION_SECONDS);
    }

    /** Initiate a {@code pointAt} slew (Step 18). Delegates to {@link TransitionController}. */
    @Override
    public void pointAt(int naifId, double durationSeconds) {
        transitionController.requestPointAt(naifId, durationSeconds);
    }

    /** Initiate a {@code goTo} translation (Step 18). Delegates to {@link TransitionController}. */
    @Override
    public void goTo(int naifId, double apparentRadiusDeg, double durationSeconds) {
        transitionController.requestGoTo(naifId, apparentRadiusDeg, durationSeconds);
    }

    // ── Camera navigation commands (Step 19c) ──

    @Override
    public void zoom(double factor, double durationSeconds) {
        transitionController.requestZoom(factor, durationSeconds);
    }

    @Override
    public void setFov(double degrees, double durationSeconds) {
        transitionController.requestFov(degrees, durationSeconds);
    }

    @Override
    public void orbit(double rightDegrees, double upDegrees, double durationSeconds) {
        transitionController.requestOrbit(rightDegrees, upDegrees, durationSeconds);
    }

    @Override
    public void tilt(double degrees, double durationSeconds) {
        transitionController.requestTilt(degrees, durationSeconds);
    }

    @Override
    public void yaw(double degrees, double durationSeconds) {
        transitionController.requestYaw(degrees, durationSeconds);
    }

    @Override
    public void roll(double degrees, double durationSeconds) {
        transitionController.requestRoll(degrees, durationSeconds);
    }

    @Override
    public void setCameraPosition(double x, double y, double z, double durationSeconds) {
        transitionController.requestCameraPosition(x, y, z, -1, durationSeconds);
    }

    @Override
    public void setCameraPosition(double x, double y, double z, int originNaifId, double durationSeconds) {
        transitionController.requestCameraPosition(x, y, z, originNaifId, durationSeconds);
    }

    @Override
    public void setCameraOrientation(
            double lookX, double lookY, double lookZ, double upX, double upY, double upZ, double durationSeconds) {
        transitionController.requestCameraOrientation(lookX, lookY, lookZ, upX, upY, upZ, durationSeconds);
    }

    // ── Cinematic camera commands (Step 24) ──

    @Override
    public void truck(double km, double durationSeconds) {
        transitionController.requestTruck(km, durationSeconds);
    }

    @Override
    public void crane(double km, double durationSeconds) {
        transitionController.requestCrane(km, durationSeconds);
    }

    @Override
    public void dolly(double km, double durationSeconds) {
        transitionController.requestDolly(km, durationSeconds);
    }

    @Override
    public void setSynodicFrame(int focusNaifId, int targetNaifId) {
        state.setSynodicFrameFocusId(focusNaifId);
        state.setSynodicFrameTargetId(targetNaifId);
        state.setCameraFrame(CameraFrame.SYNODIC);
    }

    /**
     * Set the simulation time rate as an absolute value (§2.3).
     *
     * <p>"3x" means {@code timeRate = 3.0}, <b>not</b> "multiply current rate by 3". Delegates to
     * {@link SimulationClock} which replaces the anchor atomically so no ET jump occurs.
     */
    @Override
    public void setTimeRate(double simSecondsPerWallSecond) {
        clock.setTimeRate(simSecondsPerWallSecond);
    }

    /** Pause or unpause the simulation clock (§1.2). Delegates to {@link SimulationClock}. */
    @Override
    public void setPaused(boolean paused) {
        clock.setPaused(paused);
    }

    /** Jump the simulation clock to the specified ET (§1.2). Delegates to {@link SimulationClock}. */
    @Override
    public void setET(double et) {
        clock.setET(et);
    }

    /**
     * Convert a UTC string to ET and jump the clock to that epoch (§1.2).
     *
     * <p>Delegates to {@link SimulationClock#setUTC(String)} which acquires ephemeris at point-of-use (CLAUDE.md Rule
     * 3).
     */
    @Override
    public void setUTC(String utcString) {
        clock.setUTC(utcString);
    }

    /**
     * Switch the active camera frame (§1.5). Clears synodic frame override IDs so the frame reverts to using
     * interaction state for focus/target.
     */
    @Override
    public void setCameraFrame(CameraFrame frame) {
        state.setSynodicFrameFocusId(-1);
        state.setSynodicFrameTargetId(-1);
        state.setCameraFrame(frame);
    }

    /** Set the render quality preset (§9.4). Takes effect on the next JME frame. */
    @Override
    public void setRenderQuality(RenderQuality quality) {
        state.setRenderQuality(quality);
    }

    // ── Overlay commands (Step 19b) ──

    @Override
    public void setLabelVisible(int naifId, boolean visible) {
        state.setLabelVisible(naifId, visible);
    }

    @Override
    public void setHudTimeVisible(boolean visible) {
        state.setHudTimeVisible(visible);
    }

    @Override
    public void setHudInfoVisible(boolean visible) {
        state.setHudInfoVisible(visible);
    }

    @Override
    public void setTrailVisible(int naifId, boolean visible) {
        state.setTrailVisible(naifId, visible);
    }

    @Override
    public void setTrailDuration(int naifId, double seconds) {
        state.setTrailDuration(naifId, seconds);
    }

    @Override
    public void setVectorVisible(int naifId, VectorType type, boolean visible) {
        state.setVectorVisible(naifId, type, visible);
    }

    // ── Transition control (Step 20) ──

    @Override
    public void cancelTransition() {
        transitionController.requestCancel();
    }

    // ── Instrument frustum overlays (Step 22) ──

    @Override
    public void setFrustumVisible(int instrumentNaifCode, boolean visible) {
        state.setFrustumVisible(instrumentNaifCode, visible);
    }

    @Override
    public void setFrustumVisible(String instrumentName, boolean visible) {
        setFrustumVisible(BodyLookupService.resolve(instrumentName), visible);
    }

    // ── Screenshot capture (Step 25) ──────────────────────────────────────────

    /**
     * Callback interface for JME-thread screenshot capture.
     *
     * <p>Accepts an output path and a {@link CountDownLatch}. The implementation enqueues a framebuffer capture on the
     * JME render thread and counts the latch down once the PNG file has been written.
     */
    @FunctionalInterface
    public interface ScreenshotCallback {
        void capture(String outputPath, CountDownLatch latch);
    }

    /**
     * Set the callback that enqueues a JME-thread framebuffer capture for use by {@link #saveScreenshot}.
     *
     * <p>Called by {@code KepplrApp} after construction; may be left {@code null} in unit tests.
     *
     * @param callback the screenshot callback; may be null
     */
    public void setScreenshotCallback(ScreenshotCallback callback) {
        this.screenshotCallback = callback;
    }

    /**
     * Capture the current JME framebuffer to a PNG file (Step 25).
     *
     * <p>Blocks the calling thread until the screenshot is written. If the callback is null (unit tests), this method
     * is a no-op.
     */
    @Override
    public void saveScreenshot(String outputPath) {
        if (screenshotCallback == null) {
            logger.warn("saveScreenshot: no screenshot callback set (unit test mode)");
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        screenshotCallback.capture(outputPath, latch);
        try {
            boolean done = latch.await(KepplrConstants.CONFIG_RELOAD_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!done) {
                logger.warn(
                        "saveScreenshot: capture did not complete within {} s",
                        KepplrConstants.CONFIG_RELOAD_TIMEOUT_SEC);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("saveScreenshot: interrupted while waiting for capture");
        }
    }

    // ── Configuration reload (Step 27) ───────────────────────────────────────

    /**
     * Set the callback that enqueues a JME-thread scene rebuild for use by {@link #loadConfiguration}.
     *
     * <p>The callback receives a {@link CountDownLatch} that it must count down to zero once the rebuild completes.
     * Called by {@code KepplrApp} after construction; may be left {@code null} in unit tests (in which case
     * {@code loadConfiguration} reloads the config but does not wait for any scene rebuild).
     *
     * @param callback consumer that enqueues the rebuild and counts down the latch; may be null
     */
    public void setSceneRebuildCallback(Consumer<CountDownLatch> callback) {
        this.sceneRebuildCallback = callback;
    }

    /**
     * Reload the configuration from {@code path} and block until the JME scene rebuild completes (Step 27).
     *
     * <p>If {@link KEPPLRConfiguration#reload} throws for any reason (file not found, parse error, etc.) the error is
     * logged and this method returns immediately — no rebuild is enqueued and the previous configuration remains
     * active.
     */
    @Override
    public void loadConfiguration(String path) {
        Path filePath = Path.of(path);
        if (!Files.exists(filePath)) {
            logger.error("Cannot load configuration: file not found: {}", path);
            return;
        }
        try {
            KEPPLRConfiguration.reload(filePath);
        } catch (Exception e) {
            logger.error("Failed to load configuration '{}': {}", path, e.getMessage());
            return;
        }

        if (sceneRebuildCallback == null) {
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        sceneRebuildCallback.accept(latch);
        try {
            boolean done = latch.await(KepplrConstants.CONFIG_RELOAD_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!done) {
                logger.warn(
                        "loadConfiguration: scene rebuild did not complete within {} s; continuing",
                        KepplrConstants.CONFIG_RELOAD_TIMEOUT_SEC);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("loadConfiguration: interrupted while waiting for scene rebuild");
        }
    }

    // ── HUD message (Step 28) ────────────────────────────────────────────────

    @Override
    public void displayMessage(String text, double durationSeconds) {
        state.setHudMessage(text, durationSeconds);
    }

    // ── Window resize (Step 28) ──────────────────────────────────────────────

    private BiConsumer<Integer, Integer> windowResizeCallback;

    /**
     * Set the callback that resizes the JME render window.
     *
     * @param callback accepts (width, height) in pixels; called by {@link #setWindowSize}
     */
    public void setWindowResizeCallback(BiConsumer<Integer, Integer> callback) {
        this.windowResizeCallback = callback;
    }

    @Override
    public void setWindowSize(int width, int height) {
        if (windowResizeCallback != null) {
            windowResizeCallback.accept(width, height);
        } else {
            logger.warn("setWindowSize: no resize callback set (unit test mode)");
        }
    }
}
