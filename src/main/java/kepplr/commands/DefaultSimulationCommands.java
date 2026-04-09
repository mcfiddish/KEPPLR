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
import kepplr.state.StateSnapshot;
import kepplr.state.StateSnapshotCodec;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.VectorIJK;

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
 *   <li>{@code centerBody(id)}: sets selected, focused, and targeted; no camera change (§4.5)
 *   <li>{@code targetBody(id)}: sets selected and targeted; no camera change (§4.4)
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
     * Called on the caller's thread after {@link #loadConfiguration} completes (whether via the scene-rebuild latch or
     * timeout). Used to notify the UI to refresh the body tree and instruments menu. Set by {@code KepplrApp};
     * {@code null} in unit tests.
     */
    private Runnable postReloadCallback;

    /**
     * When {@code true}, {@link #setStateString} creates a {@link CountDownLatch} embedded in the
     * {@link DefaultSimulationState.PendingCameraRestore} and blocks until the JME render thread counts it down after
     * fully applying the restore. Set to {@code true} by {@code KepplrApp} after construction; left {@code false} in
     * unit tests (where no JME thread is running to count down the latch).
     */
    private boolean restoreSyncEnabled = false;

    /**
     * Accepts an output path and a {@link CountDownLatch}, enqueues a JME-thread framebuffer capture, and counts the
     * latch down when the PNG file has been written. Set by {@code KepplrApp} after construction; {@code null} in unit
     * tests.
     */
    private ScreenshotCallback screenshotCallback;

    /**
     * Accepts a frame count and a {@link CountDownLatch}, scheduling a JME-thread render fence that counts the latch
     * down after the requested number of frames have completed. Set by {@code KepplrApp} after construction;
     * {@code null} in unit tests.
     */
    private RenderFenceCallback renderFenceCallback;

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

    /** Center the interaction state on a body (§4.5). Implicitly selects and targets the same body. */
    @Override
    public void centerBody(int naifId) {
        state.setSelectedBodyId(naifId);
        state.setFocusedBodyId(naifId);
        state.setTargetedBodyId(naifId);
    }

    /** Target a body (§4.4). Implicitly selects the body, without moving the camera. */
    @Override
    public void targetBody(int naifId) {
        state.setSelectedBodyId(naifId);
        state.setTargetedBodyId(naifId);
    }

    /**
     * Initiate a {@code pointAt} slew (Step 18).
     *
     * <p>Also updates interaction state so the target and selected bodies match the explicit camera target.
     */
    @Override
    public void pointAt(int naifId, double durationSeconds) {
        state.setSelectedBodyId(naifId);
        state.setTargetedBodyId(naifId);
        transitionController.requestPointAt(naifId, durationSeconds);
    }

    /**
     * Initiate a {@code goTo} camera move (Step 18).
     *
     * <p>Also updates interaction state so the selected, focused, and targeted bodies all match the explicit approach
     * target. Always queues a matching {@code pointAt} first so the approach path is consistent across scripts, UI
     * actions, and input gestures.
     */
    @Override
    public void goTo(int naifId, double apparentRadiusDeg, double durationSeconds) {
        state.setSelectedBodyId(naifId);
        state.setFocusedBodyId(naifId);
        state.setTargetedBodyId(naifId);
        transitionController.requestPointAt(naifId, KepplrConstants.DEFAULT_SLEW_DURATION_SECONDS);
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
        state.setSynodicFrameSelectedId(targetNaifId);
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
     * interaction state for focus/selected.
     */
    @Override
    public void setCameraFrame(CameraFrame frame) {
        if (frame == CameraFrame.SYNODIC) {
            state.setSynodicFrameFocusId(state.focusedBodyIdProperty().get());
            state.setSynodicFrameSelectedId(state.selectedBodyIdProperty().get());
        } else {
            state.setSynodicFrameFocusId(-1);
            state.setSynodicFrameSelectedId(-1);
        }
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
    public void setTrailReferenceBody(int naifId, int referenceBodyId) {
        state.setTrailReferenceBody(naifId, referenceBodyId);
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

    @Override
    public void setFrustumPersistenceEnabled(int instrumentNaifCode, boolean enabled) {
        state.setFrustumPersistenceEnabled(instrumentNaifCode, enabled);
    }

    @Override
    public void setFrustumPersistenceEnabled(String instrumentName, boolean enabled) {
        setFrustumPersistenceEnabled(BodyLookupService.resolve(instrumentName), enabled);
    }

    @Override
    public void clearFrustumFootprints(int instrumentNaifCode) {
        state.requestClearFrustumFootprints(instrumentNaifCode);
    }

    @Override
    public void clearFrustumFootprints(String instrumentName) {
        clearFrustumFootprints(BodyLookupService.resolve(instrumentName));
    }

    @Override
    public void clearFrustumFootprints() {
        state.requestClearAllFrustumFootprints();
    }

    // ── State snapshot (Step 26) ──────────────────────────────────────────────

    @Override
    public String getStateString() {
        double et = state.currentEtProperty().get();
        double[] camPos = state.cameraPositionJ2000Property().get();
        float[] camOrient = state.cameraOrientationJ2000Property().get();
        int focusId = state.focusedBodyIdProperty().get();

        // Store camera position relative to the focused body so that restoration places the camera
        // at the same apparent position near the body, even when time has advanced between capture
        // and restore (the camera is parked in J2000 after a goTo; the body moves, but the offset
        // from the body at the snapshot ET is preserved).
        double[] posToEncode;
        boolean relativeToFocus = false;
        double[] safeCamPos = camPos != null ? camPos : new double[3];

        if (focusId != -1) {
            VectorIJK bodyPos =
                    KEPPLRConfiguration.getInstance().getEphemeris().getHeliocentricPositionJ2000(focusId, et);
            if (bodyPos != null) {
                posToEncode = new double[] {
                    safeCamPos[0] - bodyPos.getI(), safeCamPos[1] - bodyPos.getJ(), safeCamPos[2] - bodyPos.getK()
                };
                relativeToFocus = true;
            } else {
                posToEncode = safeCamPos.clone();
            }
        } else {
            posToEncode = safeCamPos.clone();
        }

        float[] safeOrient = camOrient != null ? camOrient.clone() : new float[] {0f, 0f, 0f, 1f};

        StateSnapshot snap = new StateSnapshot(
                et,
                state.timeRateProperty().get(),
                state.pausedProperty().get(),
                posToEncode,
                safeOrient,
                state.cameraFrameProperty().get(),
                focusId,
                state.targetedBodyIdProperty().get(),
                state.selectedBodyIdProperty().get(),
                state.fovDegProperty().get(),
                relativeToFocus);

        return StateSnapshotCodec.encode(snap);
    }

    @Override
    public void setStateString(String stateString) {
        StateSnapshot snap = StateSnapshotCodec.decode(stateString);

        // Restore body selections
        state.setSelectedBodyId(snap.selectedBodyId());
        state.setFocusedBodyId(snap.focusedBodyId());
        state.setTargetedBodyId(snap.targetedBodyId());

        // Restore camera frame
        state.setCameraFrame(snap.cameraFrame());

        // Restore time state via clock (atomic anchor replacement, no ET jump artifacts).
        // Paused flag is intentionally NOT restored: callers that explicitly paused the simulation
        // (e.g. a scripted slideshow) should not have their pause state overridden by the snapshot.
        clock.setET(snap.et());
        clock.setTimeRate(snap.timeRate());

        // Cancel any in-progress transitions before restoring camera
        transitionController.requestCancel();

        // Resolve camera position: body-relative offset → absolute J2000
        double[] camPosAbsolute;
        double followDist = -1;
        if (snap.camPosRelativeToFocus() && snap.focusedBodyId() != -1) {
            VectorIJK bodyPos = KEPPLRConfiguration.getInstance()
                    .getEphemeris()
                    .getHeliocentricPositionJ2000(snap.focusedBodyId(), snap.et());
            if (bodyPos != null) {
                double[] off = snap.camPosJ2000();
                camPosAbsolute =
                        new double[] {bodyPos.getI() + off[0], bodyPos.getJ() + off[1], bodyPos.getK() + off[2]};
                // Preserve the follow distance so body-following is re-established after restore
                followDist = Math.sqrt(off[0] * off[0] + off[1] * off[1] + off[2] * off[2]);
            } else {
                // Focused body unavailable at snap ET — use the stored offset as-is
                camPosAbsolute = snap.camPosJ2000().clone();
            }
        } else {
            camPosAbsolute = snap.camPosJ2000().clone();
        }

        // Embed a latch in the restore record so the JME thread can signal completion.
        // The latch travels with the record: the JME thread counts it down at the end of the
        // simpleUpdate() that consumes the restore, after all state properties are written.
        // This eliminates the race where a separately-stored latch could be counted down in a frame
        // that ran before PendingCameraRestore was even posted.
        CountDownLatch restoreDone = restoreSyncEnabled ? new CountDownLatch(1) : null;

        // Post camera restore for the JME thread (position, orientation, FOV, latch)
        state.setPendingCameraRestore(new DefaultSimulationState.PendingCameraRestore(
                camPosAbsolute, snap.camOrientJ2000().clone(), snap.fovDeg(), restoreDone));

        // Re-establish body-following so the camera continues to track the focused body after restore.
        // Queued after requestCancel so the follow state is set once cancellation is processed.
        if (followDist > 0) {
            transitionController.requestFollow(snap.focusedBodyId(), followDist);
        }

        // Block the script thread until the JME render thread has consumed the restore and updated
        // all state properties (currentEtProperty, cameraBodyFixedSphericalProperty, etc.).
        if (restoreDone != null) {
            try {
                boolean done = restoreDone.await(KepplrConstants.CONFIG_RELOAD_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (!done) {
                    logger.warn(
                            "setStateString: camera restore did not complete within {} s",
                            KepplrConstants.CONFIG_RELOAD_TIMEOUT_SEC);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("setStateString: interrupted while waiting for camera restore");
            }
        }
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
     * Callback interface for a JME-thread render fence.
     *
     * <p>Accepts a frame count and a {@link CountDownLatch}. The implementation must count the latch down after the
     * requested number of full JME update/render frames have completed.
     */
    @FunctionalInterface
    public interface RenderFenceCallback {
        void awaitFrames(int frameCount, CountDownLatch latch);
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
     * Set the callback that waits for JME update/render frames.
     *
     * <p>Called by {@code KepplrApp} after construction; may be left {@code null} in unit tests.
     *
     * @param callback the render-fence callback; may be null
     */
    public void setRenderFenceCallback(RenderFenceCallback callback) {
        this.renderFenceCallback = callback;
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

    /**
     * Wait for a number of fully rendered JME frames.
     *
     * <p>Blocks the calling thread until the requested number of frames have completed. If the callback is null (unit
     * tests), this method is a no-op.
     */
    @Override
    public void waitRenderFrames(int frameCount) {
        if (frameCount <= 0) return;
        if (renderFenceCallback == null) {
            logger.warn("waitRenderFrames: no render fence callback set (unit test mode)");
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        renderFenceCallback.awaitFrames(frameCount, latch);
        try {
            boolean done = latch.await(KepplrConstants.CONFIG_RELOAD_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!done) {
                logger.warn(
                        "waitRenderFrames: fence did not complete within {} s",
                        KepplrConstants.CONFIG_RELOAD_TIMEOUT_SEC);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("waitRenderFrames: interrupted while waiting for fence");
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
     * Enable synchronous camera restore in {@link #setStateString}.
     *
     * <p>When enabled, {@code setStateString} embeds a {@link CountDownLatch} in the
     * {@link DefaultSimulationState.PendingCameraRestore} and blocks the calling thread until the JME render thread
     * counts it down after fully applying the restore. This guarantees that all state properties (ET, camera position,
     * body-fixed spherical, orientation, FOV) reflect the restored snapshot before the script thread resumes.
     *
     * <p>Disabled by default so that unit tests — which have no JME thread to count down the latch — are unaffected.
     * Called by {@code KepplrApp} after construction.
     *
     * @param enabled {@code true} to block until the JME thread applies the restore; {@code false} (default) for
     *     fire-and-forget (tests only)
     */
    public void setRestoreSyncEnabled(boolean enabled) {
        this.restoreSyncEnabled = enabled;
    }

    /**
     * Set the callback invoked after {@link #loadConfiguration} completes.
     *
     * <p>Called on whichever thread invoked {@code loadConfiguration} (script thread, background thread, etc.). The
     * implementation must be thread-safe. In practice this callback sets a volatile flag that the JavaFX
     * {@code AnimationTimer} polls to refresh the body tree on the FX thread.
     *
     * @param callback the post-reload notification; may be null
     */
    public void setPostReloadCallback(Runnable callback) {
        this.postReloadCallback = callback;
    }

    /**
     * Reload the configuration from {@code path} and block until the first full {@code simpleUpdate()} with the new
     * configuration completes (Step 27).
     *
     * <p>This ensures that body positions are computed for the new scene before the script thread resumes, so
     * subsequent commands like {@code centerBody()} can immediately queue transitions against valid body positions.
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

        if (postReloadCallback != null) {
            postReloadCallback.run();
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

    @Override
    public void setBodyVisible(int naifId, boolean visible) {
        state.setBodyVisible(naifId, visible);
    }
}
