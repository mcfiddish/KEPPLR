package kepplr.render;

import com.jme3.app.LostFocusBehavior;
import com.jme3.app.SimpleApplication;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.light.AmbientLight;
import com.jme3.light.PointLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.scene.plugins.gltf.GlbLoader;
import com.jme3.system.AppSettings;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.application.Platform;
import kepplr.camera.BodyFixedFrame;
import kepplr.camera.CameraFrame;
import kepplr.camera.CameraInputHandler;
import kepplr.camera.SynodicFrameApplier;
import kepplr.camera.TransitionController;
import kepplr.commands.DefaultSimulationCommands;
import kepplr.config.KEPPLRConfiguration;
import kepplr.core.SimulationClock;
import kepplr.ephemeris.BodyLookupService;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.render.body.BodySceneManager;
import kepplr.render.frustum.FrustumLayer;
import kepplr.render.label.LabelManager;
import kepplr.render.trail.TrailManager;
import kepplr.render.vector.VectorDefinition;
import kepplr.render.vector.VectorManager;
import kepplr.scripting.CommandRecorder;
import kepplr.scripting.ScriptRunner;
import kepplr.stars.catalogs.yaleBSC.YaleBrightStarCatalog;
import kepplr.state.BodyInView;
import kepplr.state.DefaultSimulationState;
import kepplr.state.DefaultSimulationState.VectorKey;
import kepplr.ui.KepplrStatusWindow;
import kepplr.ui.SimulationStateFxBridge;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;
import picante.math.vectorspace.RotationMatrixIJK;
import picante.math.vectorspace.VectorIJK;

/**
 * Main JMonkeyEngine application for KEPPLR.
 *
 * <p>Renders all known solar-system bodies and spacecraft using ephemeris-driven positions in a floating-origin scene
 * graph. World-space units are kilometers (REDESIGN.md §2.1). Body positions are computed relative to the camera's
 * heliocentric J2000 position each frame, keeping scene-graph coordinate values numerically small regardless of true
 * heliocentric distances.
 *
 * <h3>Multi-frustum rendering (§8)</h3>
 *
 * <p>Three camera/viewport pairs share the same position and orientation but have different near/far planes. They
 * render in far→mid→near order; far clears color and depth, mid and near clear depth only. Bodies are assigned to the
 * nearest frustum whose expanded range fully contains their bounding volume (§8.3).
 *
 * <h3>Sun light (§7.6)</h3>
 *
 * <p>A {@link PointLight} is used (not DirectionalLight) because at solar-system scale the Sun's direction varies
 * significantly between bodies — a directional light would be incorrect for bodies on opposite sides of the Solar
 * System. The PointLight position is updated every frame to track the Sun's scene-relative location under floating
 * origin (Sun helio pos = origin, so scene pos = −cameraHelioJ2000). One PointLight instance per frustum layer is
 * required because JME lights illuminate only the subtree they are attached to.
 *
 * <p>Note for future shadow step: the Sun's radius is accessible from its {@code BodySceneNode} fullGeom scale (set by
 * {@code BodyNodeFactory} from the PCK shape data). Retrieve it there when implementing analytic eclipse geometry (§9).
 */
public class KepplrApp extends SimpleApplication {

    private static final Logger logger = LogManager.getLogger();

    private static final int DEFAULT_FOCUS_BODY = 399;

    /** Width of the JavaFX control window in pixels (must match KepplrStatusWindow.WINDOW_WIDTH). */
    private static final double WINDOW_WIDTH_FX = 380.0;

    private static final float CAMERA_OFFSET_KM = 15000f;

    /** Camera heliocentric J2000 position in km. Scene positions are {@code helioPos − this}, cast to float for JME. */
    private final double[] cameraHelioJ2000 = new double[3];

    // ── Command-line startup options ────────────────────────────────────────────────────────────
    private String startupScript;
    private String startupState;

    // ── Simulation model ──────────────────────────────────────────────────────────────────────
    private DefaultSimulationState simulationState;
    private SimulationClock simulationClock;
    private TransitionController transitionController;
    private ScriptRunner scriptRunner;
    private CommandRecorder recorder;
    private KepplrHud hud;
    private CameraInputHandler cameraInputHandler;
    private final BodyFixedFrame bodyFixedFrame = new BodyFixedFrame();
    private final SynodicFrameApplier synodicFrameApplier = new SynodicFrameApplier();

    // ── Multi-frustum cameras and viewports (§8) ─────────────────────────────────────────────
    /** Mid camera — slave of {@code cam}, mid-range frustum planes. */
    private Camera midCam;
    /** Near camera — slave of {@code cam}, near-range frustum planes. */
    private Camera nearCam;

    // ── Frustum scene-graph roots ─────────────────────────────────────────────────────────────
    /** Root node for the far frustum layer; receives far-range bodies. */
    private Node farNode;
    /** Root node for the mid frustum layer; receives mid-range bodies. */
    private Node midNode;
    /** Root node for the near frustum layer; receives near-range bodies. */
    private Node nearNode;

    // ── Sun lights (one per frustum layer; position updated every frame) ─────────────────────
    private PointLight sunLightFar;
    private PointLight sunLightMid;
    private PointLight sunLightNear;

    // ── Body scene management ─────────────────────────────────────────────────────────────────
    private BodySceneManager bodySceneManager;

    // ── Trail management ──────────────────────────────────────────────────────────────────────
    private TrailManager trailManager;

    // ── Vector overlay management ──────────────────────────────────────────────────────────────
    private VectorManager vectorManager;

    // ── Star field management ──────────────────────────────────────────────────────────────────
    private StarFieldManager starFieldManager;

    // ── Label management ──────────────────────────────────────────────────────────────────────
    private LabelManager labelManager;

    // ── Sun halo ───────────────────────────────────────────────────────────────────────────────
    private SunHaloRenderer sunHaloRenderer;

    // ── Star catalog (retained for reconstruction on config reload) ────────────────────────────
    private YaleBrightStarCatalog starCatalog;

    // ── Instrument frustum overlays (Step 22) ─────────────────────────────────────────────────
    private InstrumentFrustumManager instrumentFrustumManager;

    // ── Overlay sync state (maps state → render managers each frame) ───────────────────────────
    /** Currently enabled trail IDs in TrailManager, kept in sync with state each frame. */
    private final Set<Integer> activeTrailIds = new HashSet<>();
    /** Currently enabled vector definitions in VectorManager, keyed by state VectorKey. */
    private final Map<VectorKey, VectorDefinition> activeVectorDefs = new HashMap<>();

    // ── JavaFX control window ─────────────────────────────────────────────────────────────────
    private volatile KepplrStatusWindow statusWindow;
    private boolean fxWindowPositioned;

    // ── Post-render screenshot capture (Step 25) ──────────────────────────────────────────────
    // Pending capture is set by the screenshot callback (from the capture/script thread) and
    // processed AFTER the render pass completes so the framebuffer reflects the current frame's
    // scene graph — not the previous frame's.
    private record PendingCapture(String outputPath, java.util.concurrent.CountDownLatch latch) {}

    private volatile PendingCapture pendingCapture;

    // ── Post-rebuild latch (Step 27 fix) ─────────────────────────────────────────────────────
    // When loadConfiguration() triggers a scene rebuild, the latch is stored here (by the
    // enqueued callable) and counted down at the END of simpleUpdate() — guaranteeing that the
    // first full update cycle with the new configuration has completed before the script thread
    // unblocks.  Without this, the script thread could queue transitions against bodies whose
    // positions have not yet been computed in the new scene.
    private volatile java.util.concurrent.CountDownLatch postRebuildLatch;

    @Override
    public void simpleInitApp() {
        setLostFocusBehavior(LostFocusBehavior.Disabled);
        setDisplayFps(false);
        setDisplayStatView(false);
        flyCam.setEnabled(false);

        // Ensure the native window has resize + maximize decorations. AppSettings.setResizable()
        // may not propagate to the already-created GLFW window, so set the attribute directly.
        long handle = getGlfwWindowHandle();
        if (handle != 0) {
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        }

        // Remove JME's default Escape→exit mapping (SimpleApplication binds KEY_ESCAPE to stop()).
        // Escape has no function in KEPPLR and must not close either window.
        inputManager.deleteMapping(INPUT_MAPPING_EXIT);

        // ── Asset manager: shape model support (§14.6.2) ─────────────────────────────────────
        // Register resourcesFolder() as a FileLocator so GLB paths from BodyBlock.shapeModel()
        // and SpacecraftBlock.shapeModel() (which are relative to that folder) resolve correctly.
        // Register GlbLoader for the "glb" extension (not registered by JME by default).
        // Both registrations are done once here, before BodySceneManager is constructed.
        assetManager.registerLocator(KEPPLRConfiguration.getInstance().resourcesFolder(), FileLocator.class);
        assetManager.registerLoader(GlbLoader.class, "glb");

        // ── Simulation clock and commands ─────────────────────────────────────────────────────
        double startET = KEPPLRConfiguration.getInstance().getTimeConversion().instantToTDB(Instant.now());
        simulationState = new DefaultSimulationState();
        simulationClock = new SimulationClock(simulationState, startET);
        transitionController = new TransitionController(simulationState);

        DefaultSimulationCommands commands =
                new DefaultSimulationCommands(simulationState, simulationClock, transitionController);
        // Wire the scene rebuild callback so loadConfiguration() can block the script thread
        // until the first full simpleUpdate() after rebuildBodyScene() completes.  The latch is
        // stored in postRebuildLatch and counted down at the end of simpleUpdate(), ensuring body
        // positions are computed before the script thread resumes.
        commands.setSceneRebuildCallback(latch -> enqueue(() -> {
            rebuildBodyScene();
            postRebuildLatch = latch;
            return null;
        }));
        // Wire the screenshot callback: store a pending capture that will be processed after the
        // render pass completes (see update() override). This ensures the framebuffer reflects the
        // current frame's scene graph, including focus-body tracking from simpleUpdate().
        commands.setScreenshotCallback((outputPath, latch) -> pendingCapture = new PendingCapture(outputPath, latch));
        commands.setWindowResizeCallback((w, h) -> enqueue(() -> {
            long glfwWindowHandle = getGlfwWindowHandle();
            if (glfwWindowHandle != 0) {
                GLFW.glfwSetWindowSize(glfwWindowHandle, w, h);
            }
        }));
        recorder = new CommandRecorder(commands);
        scriptRunner = new ScriptRunner(commands, simulationState);

        SimulationStateFxBridge bridge = new SimulationStateFxBridge(simulationState);
        // On macOS, JavaFX is started here (after GLFW has claimed NSApplication) rather than in
        // main().  See main() comment for the full rationale.
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            Platform.startup(() -> {});
        }
        // Capture a reference to this app for the FX-side shutdown callback
        KepplrApp appRef = this;
        Platform.runLater(() -> {
            statusWindow = new KepplrStatusWindow(bridge, recorder);
            statusWindow.setJmeShutdown(appRef::stop);
            statusWindow.setJmeResizeCallback((w, h) -> appRef.enqueue(() -> {
                long glfwWindowHandle = getGlfwWindowHandle();
                if (glfwWindowHandle != 0) {
                    GLFW.glfwSetWindowSize(glfwWindowHandle, w, h);
                }
            }));
            statusWindow.setConfigReloadCallback(() -> appRef.enqueue(appRef::rebuildBodyScene));
            statusWindow.setScriptRunner(scriptRunner);
            statusWindow.setCommandRecorder(recorder);
            statusWindow.show();
        });

        int focusBodyId = DEFAULT_FOCUS_BODY;
        commands.focusBody(focusBodyId);

        // ── Camera initial position: offset above Earth in J2000 +Z ──────────────────────────
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        VectorIJK focusHelioPos = eph.getHeliocentricPositionJ2000(focusBodyId, startET);
        if (focusHelioPos == null) {
            logger.error("cannot resolve NAIF {} position at ET={}; cannot start", focusBodyId, startET);
            stop();
            return;
        }
        cameraHelioJ2000[0] = focusHelioPos.getI();
        cameraHelioJ2000[1] = focusHelioPos.getJ();
        cameraHelioJ2000[2] = focusHelioPos.getK() + CAMERA_OFFSET_KM;
        simulationState.setCameraPositionJ2000(cameraHelioJ2000);

        // ── Multi-frustum setup (§8) ──────────────────────────────────────────────────────────
        float aspect = (float) cam.getWidth() / cam.getHeight();

        // Reuse the default viewPort as the FAR layer (rendered first: clears color + depth).
        // 'cam' drives all three layers; midCam and nearCam are synced from it every frame.
        cam.setFrustumPerspective(KepplrConstants.CAMERA_FOV_Y_DEG, aspect, (float) FrustumLayer.FAR.nearKm, (float)
                FrustumLayer.FAR.farKm);
        cam.setLocation(Vector3f.ZERO);
        cam.lookAt(toScenePosition(focusHelioPos), Vector3f.UNIT_Y);

        farNode = new Node("far");
        viewPort.detachScene(rootNode);
        viewPort.attachScene(farNode);
        viewPort.setBackgroundColor(ColorRGBA.Black);
        viewPort.setClearFlags(true, true, false);

        midCam = cam.clone();
        midCam.setFrustumPerspective(KepplrConstants.CAMERA_FOV_Y_DEG, aspect, (float) FrustumLayer.MID.nearKm, (float)
                FrustumLayer.MID.farKm);
        midNode = new Node("mid");
        ViewPort midVP = renderManager.createMainView("Mid", midCam);
        midVP.setClearFlags(false, true, false);
        midVP.attachScene(midNode);

        nearCam = cam.clone();
        nearCam.setFrustumPerspective(
                KepplrConstants.CAMERA_FOV_Y_DEG, aspect, (float) FrustumLayer.NEAR.nearKm, (float)
                        FrustumLayer.NEAR.farKm);
        nearNode = new Node("near");
        ViewPort nearVP = renderManager.createMainView("Near", nearCam);
        nearVP.setClearFlags(false, true, false);
        nearVP.attachScene(nearNode);

        // ── Lighting ──────────────────────────────────────────────────────────────────────────
        // Dim ambient on all three layer nodes so night sides are dark but not black.
        AmbientLight ambientFar = new AmbientLight(new ColorRGBA(0.15f, 0.15f, 0.15f, 1f));
        AmbientLight ambientMid = new AmbientLight(new ColorRGBA(0.15f, 0.15f, 0.15f, 1f));
        AmbientLight ambientNear = new AmbientLight(new ColorRGBA(0.15f, 0.15f, 0.15f, 1f));
        farNode.addLight(ambientFar);
        midNode.addLight(ambientMid);
        nearNode.addLight(ambientNear);

        // PointLight at the Sun's scene position, updated every frame (§7.6).
        // PointLight chosen over DirectionalLight: at solar-system scale the Sun's direction from
        // bodies on opposite sides of the Solar System can differ by 180°; a directional light
        // (infinite source) cannot represent this. PointLight.setRadius(MAX_VALUE) ensures no
        // distance attenuation across the scene.
        Vector3f sunScenePos = sunScenePosition();
        sunLightFar = sunPointLight(sunScenePos);
        sunLightMid = sunPointLight(sunScenePos);
        sunLightNear = sunPointLight(sunScenePos);
        farNode.addLight(sunLightFar);
        midNode.addLight(sunLightMid);
        nearNode.addLight(sunLightNear);

        // ── Body scene manager ────────────────────────────────────────────────────────────────
        bodySceneManager = new BodySceneManager(nearNode, midNode, farNode, assetManager, simulationState);
        transitionController.setBodySceneManager(bodySceneManager);

        // ── Trail manager ─────────────────────────────────────────────────────────────────────
        trailManager = new TrailManager(nearNode, midNode, farNode, assetManager, simulationState);

        // ── Vector overlay manager ────────────────────────────────────────────────────────────
        vectorManager = new VectorManager(nearNode, midNode, farNode, assetManager);

        // ── Star field ────────────────────────────────────────────────────────────────────────
        starCatalog = YaleBrightStarCatalog.loadFromResource("/kepplr/stars/catalogs/yaleBSC/ybsc5.gz");
        starFieldManager = new StarFieldManager(farNode, assetManager, simulationState);
        starFieldManager.setCatalog(starCatalog);

        // ── Sun halo ──────────────────────────────────────────────────────────────────────────
        sunHaloRenderer = new SunHaloRenderer(farNode, midNode, nearNode, assetManager, simulationState);
        sunHaloRenderer.init();

        // ── Instrument frustum overlays (Step 22) ────────────────────────────────────────────
        instrumentFrustumManager = new InstrumentFrustumManager(nearNode, midNode, farNode, assetManager);

        // ── HUD, labels, and camera input ────────────────────────────────────────────────────
        hud = new KepplrHud(guiNode, assetManager, cam);
        labelManager = new LabelManager(guiNode, assetManager);
        cameraInputHandler = new CameraInputHandler(cam, cameraHelioJ2000, simulationState);
        cameraInputHandler.setSimulationCommands(recorder);
        cameraInputHandler.setPickNodes(nearNode, midNode, farNode);
        cameraInputHandler.register(inputManager);

        // NOTE: Auto-focus JME window on cursor enter is deferred — macOS does not
        // honour glfwFocusWindow() or Cocoa makeKeyAndOrderFront: from a render loop.
        // Works on Linux. Users must click the JME window to give it focus on macOS.

        // ── Command-line startup actions (state before script, so script sees restored state) ──
        if (startupState != null) {
            try {
                recorder.setStateString(startupState);
                logger.info("Applied startup state string");
            } catch (IllegalArgumentException e) {
                logger.error("Invalid -state argument: {}", e.getMessage());
            }
        }
        if (startupScript != null) {
            scriptRunner.runScript(Path.of(startupScript));
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        // One-shot: position the JavaFX window to the left of the JME window on first frame
        if (!fxWindowPositioned) {
            fxWindowPositioned = true;
            var ctx = getContext();
            if (ctx instanceof com.jme3.system.lwjgl.LwjglWindow lwjglWindow) {
                int jmeX = lwjglWindow.getWindowXPosition();
                int jmeY = lwjglWindow.getWindowYPosition();
                double fxWidth = WINDOW_WIDTH_FX;
                double fxX = Math.max(0, jmeX - fxWidth);
                double fxY = jmeY;
                Platform.runLater(() -> {
                    if (statusWindow != null) {
                        statusWindow.setPosition(fxX, fxY);
                    }
                });
            }
        }

        simulationClock.advance();

        // Apply pending camera restore from setStateString() (Step 26)
        DefaultSimulationState.PendingCameraRestore restore = simulationState.consumePendingCameraRestore();
        if (restore != null) {
            cameraHelioJ2000[0] = restore.posJ2000()[0];
            cameraHelioJ2000[1] = restore.posJ2000()[1];
            cameraHelioJ2000[2] = restore.posJ2000()[2];
            cam.setRotation(new com.jme3.math.Quaternion(
                    restore.orientJ2000()[0], restore.orientJ2000()[1],
                    restore.orientJ2000()[2], restore.orientJ2000()[3]));
            cam.setFov((float)
                    Math.max(KepplrConstants.FOV_MIN_DEG, Math.min(restore.fovDeg(), KepplrConstants.FOV_MAX_DEG)));
        }

        try {
            cameraInputHandler.update();
        } catch (Exception e) {
            logger.warn(e.getMessage());
            return;
        }
        // Cancel any active transition when the user takes manual control (Step 18)
        if (cameraInputHandler.consumeManualNavigation() && transitionController.isActive()) {
            transitionController.cancel();
        }

        // Advance camera transition one frame (Step 18)
        transitionController.update(tpf, cam, cameraHelioJ2000);

        // Camera frame co-rotation: apply frame delta after translational tracking
        CameraFrame requestedFrame = simulationState.cameraFrameProperty().get();
        if (requestedFrame == CameraFrame.BODY_FIXED) {
            synodicFrameApplier.reset();
            int focusId = simulationState.focusedBodyIdProperty().get();
            double et = simulationState.currentEtProperty().get();
            BodyFixedFrame.ApplyResult bf = bodyFixedFrame.apply(cameraHelioJ2000, cam.getRotation(), focusId, et);
            cameraHelioJ2000[0] = bf.newCamHelioJ2000()[0];
            cameraHelioJ2000[1] = bf.newCamHelioJ2000()[1];
            cameraHelioJ2000[2] = bf.newCamHelioJ2000()[2];
            cam.setAxes(bf.newOrientation());
            simulationState.setActiveCameraFrame(bf.fallbackActive() ? CameraFrame.INERTIAL : CameraFrame.BODY_FIXED);
            simulationState.setCameraFrameFallbackActive(bf.fallbackActive());
        } else if (requestedFrame == CameraFrame.SYNODIC) {
            bodyFixedFrame.reset();
            // Step 19c: synodic frame override IDs take precedence over interaction state
            int synodicFocus = simulationState.synodicFrameFocusIdProperty().get();
            int synodicTarget = simulationState.synodicFrameTargetIdProperty().get();
            int focusId = (synodicFocus != -1)
                    ? synodicFocus
                    : simulationState.focusedBodyIdProperty().get();
            int targetId = (synodicTarget != -1)
                    ? synodicTarget
                    : simulationState.selectedBodyIdProperty().get();
            double et = simulationState.currentEtProperty().get();
            SynodicFrameApplier.ApplyResult sr =
                    synodicFrameApplier.apply(cameraHelioJ2000, cam.getRotation(), focusId, targetId, et);
            cameraHelioJ2000[0] = sr.newCamHelioJ2000()[0];
            cameraHelioJ2000[1] = sr.newCamHelioJ2000()[1];
            cameraHelioJ2000[2] = sr.newCamHelioJ2000()[2];
            cam.setAxes(sr.newOrientation());
            simulationState.setActiveCameraFrame(sr.fallbackActive() ? CameraFrame.INERTIAL : CameraFrame.SYNODIC);
            simulationState.setCameraFrameFallbackActive(sr.fallbackActive());
        } else {
            bodyFixedFrame.reset();
            synodicFrameApplier.reset();
            simulationState.setActiveCameraFrame(requestedFrame);
            simulationState.setCameraFrameFallbackActive(false);
        }

        // Clone so SimpleObjectProperty sees a new reference and fires listeners (in-place mutation
        // would leave the reference unchanged and suppress change notifications)
        simulationState.setCameraPositionJ2000(cameraHelioJ2000.clone());
        simulationState.setCameraBodyFixedSpherical(computeBodyFixedSpherical(
                simulationState.focusedBodyIdProperty().get(),
                cameraHelioJ2000,
                simulationState.currentEtProperty().get()));
        simulationState.setFovDeg(cam.getFov());
        com.jme3.math.Quaternion rot = cam.getRotation();
        simulationState.setCameraOrientationJ2000(new float[] {rot.getX(), rot.getY(), rot.getZ(), rot.getW()});

        // Sync slave cameras to master orientation, aspect ratio, and FOV (position is always ZERO
        // in floating-origin). FOV sync is required because TransitionController calls cam.setFov()
        // only on the master; midCam and nearCam retain their own near/far planes but must share
        // the same vertical FOV so all three layers project bodies consistently.
        midCam.setLocation(cam.getLocation());
        midCam.setRotation(cam.getRotation());
        nearCam.setLocation(cam.getLocation());
        nearCam.setRotation(cam.getRotation());
        float fov = cam.getFov();
        float aspect = (float) cam.getWidth() / cam.getHeight();
        midCam.setFrustumPerspective(fov, aspect, (float) FrustumLayer.MID.nearKm, (float) FrustumLayer.MID.farKm);
        nearCam.setFrustumPerspective(fov, aspect, (float) FrustumLayer.NEAR.nearKm, (float) FrustumLayer.NEAR.farKm);

        // Update Sun light position in all three layers (Sun helio pos = origin; scene pos = −cam)
        Vector3f sunScenePos = sunScenePosition();
        sunLightFar.setPosition(sunScenePos);
        sunLightMid.setPosition(sunScenePos);
        sunLightNear.setPosition(sunScenePos);

        double currentEt = simulationState.currentEtProperty().get();
        int focusedBodyId = simulationState.focusedBodyIdProperty().get();
        List<BodyInView> inView = bodySceneManager.update(currentEt, cameraHelioJ2000, cam);

        // ── Sync overlay state → render managers ──────────────────────────────────────────────
        syncTrails();
        syncVectors();
        syncLabels();
        syncFrustums();

        // ── HUD visibility and messages ──────────────────────────────────────────────────────
        hud.setTimeVisible(simulationState.hudTimeVisibleProperty().get());
        hud.setInfoVisible(simulationState.hudInfoVisibleProperty().get());
        var pendingMsg = simulationState.consumeHudMessage();
        if (pendingMsg != null) {
            hud.showMessage(pendingMsg.text(), pendingMsg.durationSeconds());
        }

        trailManager.update(currentEt, cameraHelioJ2000);
        vectorManager.update(
                currentEt, cameraHelioJ2000, cam, focusedBodyId, bodySceneManager::getEffectiveBodyRadiusKm);
        instrumentFrustumManager.update(currentEt, cameraHelioJ2000);
        starFieldManager.update(currentEt, cameraHelioJ2000);
        try {
            sunHaloRenderer.update(cameraHelioJ2000, cam, tpf);
        } catch (Exception e) {
            logger.warn(e.getMessage());
            return;
        }
        labelManager.update(cam, nearNode, midNode, farNode);
        simulationState.setBodiesInView(inView);
        int selectedBodyId = simulationState.selectedBodyIdProperty().get();
        hud.update(currentEt, selectedBodyId, cameraHelioJ2000);

        // JME calls updateGeometricState() only on rootNode and guiNode (SimpleApplication source).
        // Our frustum layer roots are custom viewport scenes and must be updated manually;
        // otherwise checkCulling() raises IllegalStateException during the render pass.
        farNode.updateGeometricState();
        midNode.updateGeometricState();
        nearNode.updateGeometricState();

        // Signal loadConfiguration() that the first full update with the new config is done
        java.util.concurrent.CountDownLatch rebuildLatch = postRebuildLatch;
        if (rebuildLatch != null) {
            postRebuildLatch = null;
            rebuildLatch.countDown();
        }
    }

    @Override
    public void update() {
        // Read pending capture BEFORE super.update() so that advance() (inside
        // super.update → simpleUpdate) reads the latest atomic anchor set by
        // setET() from the script thread.  The capture still happens AFTER the
        // render pass so the framebuffer contains the fully rendered scene.
        PendingCapture capture = pendingCapture;
        if (capture != null) {
            pendingCapture = null;
        }
        super.update(); // runs enqueued tasks → simpleUpdate() → render pass
        if (capture != null) {
            captureFramebufferToPng(capture.outputPath());
            capture.latch().countDown();
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        if (statusWindow != null) {
            // Sanctioned use: JME destroy() runs on JME thread; must marshal FX cleanup to FX thread
            Platform.runLater(() -> {
                statusWindow.close();
                Platform.exit();
            });
        }
    }

    // ── Overlay sync helpers ────────────────────────────────────────────────────────────────────

    /**
     * Sync label visibility from state to LabelManager. Iterates all label-visible entries and forwards each body's
     * enabled state.
     */
    private void syncLabels() {
        for (var entry : simulationState.getLabelVisibilityMap().entrySet()) {
            labelManager.setLabelVisible(entry.getKey(), entry.getValue().get());
        }
    }

    /**
     * Sync trail visibility from state to TrailManager. Enables/disables trails based on state, applying satellite
     * decluttering: suppress satellite trails when the satellite's screen position is within
     * {@link KepplrConstants#TRAIL_DECLUTTER_MIN_SEPARATION_PX} of its primary body.
     */
    private void syncTrails() {
        double et = simulationState.currentEtProperty().get();
        Set<Integer> wantEnabled = new HashSet<>();
        for (var entry : simulationState.getTrailVisibilityMap().entrySet()) {
            if (entry.getValue().get()) {
                int naifId = entry.getKey();
                if (isSatellite(naifId) && isSatelliteTooCloseToParent(naifId, et)) {
                    continue;
                }
                wantEnabled.add(naifId);
            }
        }

        // Enable new trails
        for (int id : wantEnabled) {
            if (activeTrailIds.add(id)) {
                trailManager.enableTrail(id);
            }
        }
        // Disable removed trails
        activeTrailIds.removeIf(id -> {
            if (!wantEnabled.contains(id)) {
                trailManager.disableTrail(id);
                return true;
            }
            return false;
        });
    }

    /**
     * Check if a satellite's screen position is too close to its primary body for trail decluttering. Projects both the
     * satellite and its primary (planet with id = hundreds*100 + 99) to screen coords.
     */
    private boolean isSatelliteTooCloseToParent(int naifId, double et) {
        try {
            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
            VectorIJK satPos = eph.getHeliocentricPositionJ2000(naifId, et);
            if (satPos == null) return false;
            // Primary planet: e.g. for Moon (301) → Earth (399), Pluto (999) → 999 (self, skip)
            int primaryId = (naifId / 100) * 100 + 99;
            if (primaryId == naifId) return false;
            VectorIJK primaryPos = eph.getHeliocentricPositionJ2000(primaryId, et);
            if (primaryPos == null) return false;

            Vector3f satScreen = cam.getScreenCoordinates(new Vector3f(
                    (float) (satPos.getI() - cameraHelioJ2000[0]),
                    (float) (satPos.getJ() - cameraHelioJ2000[1]),
                    (float) (satPos.getK() - cameraHelioJ2000[2])));
            Vector3f parentScreen = cam.getScreenCoordinates(new Vector3f(
                    (float) (primaryPos.getI() - cameraHelioJ2000[0]),
                    (float) (primaryPos.getJ() - cameraHelioJ2000[1]),
                    (float) (primaryPos.getK() - cameraHelioJ2000[2])));

            double dx = satScreen.x - parentScreen.x;
            double dy = satScreen.y - parentScreen.y;
            double minSep = KepplrConstants.TRAIL_DECLUTTER_MIN_SEPARATION_PX;
            return dx * dx + dy * dy < minSep * minSep;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sync vector visibility from state to VectorManager. Creates/destroys VectorDefinition instances as needed,
     * maintaining the identity-keyed mapping.
     */
    private void syncVectors() {
        Set<VectorKey> wantEnabled = new HashSet<>();
        for (var entry : simulationState.getVectorVisibilityMap().entrySet()) {
            if (entry.getValue().get()) {
                wantEnabled.add(entry.getKey());
            }
        }

        // Enable new vectors
        for (VectorKey key : wantEnabled) {
            if (!activeVectorDefs.containsKey(key)) {
                ColorRGBA color = resolveVectorColor(key);
                VectorDefinition def =
                        new VectorDefinition(key.type().toString(), key.type(), key.naifId(), color, 1.0);
                activeVectorDefs.put(key, def);
                vectorManager.enableVector(def);
            }
        }
        // Disable removed vectors
        activeVectorDefs.entrySet().removeIf(entry -> {
            if (!wantEnabled.contains(entry.getKey())) {
                vectorManager.disableVector(entry.getValue());
                return true;
            }
            return false;
        });
    }

    /**
     * Sync instrument frustum visibility from {@link DefaultSimulationState} to {@link InstrumentFrustumManager}. Calls
     * {@link InstrumentFrustumManager#setVisible} for every entry in the frustum visibility map each frame; the
     * manager's own visibility flag is idempotent so re-setting the same value is harmless.
     */
    private void syncFrustums() {
        for (var entry : simulationState.getFrustumVisibilityMap().entrySet()) {
            instrumentFrustumManager.setVisible(entry.getKey(), entry.getValue().get());
        }
    }

    /** Returns true if {@code naifId} identifies a natural satellite or Pluto (orbiting its barycenter). */
    private static boolean isSatellite(int naifId) {
        return (naifId >= 100 && naifId <= 999 && naifId % 100 != 99) || naifId == KepplrConstants.PLUTO_NAIF_ID;
    }

    /**
     * Resolve the color for a vector overlay. For towardBody vectors, uses the target body's color (e.g., Sun direction
     * → Sun's yellow). For other vector types, uses white.
     */
    private static ColorRGBA resolveVectorColor(VectorKey key) {
        String typeStr = key.type().toString();
        if (typeStr.startsWith("towardBody:")) {
            try {
                int targetNaifId = Integer.parseInt(typeStr.substring("towardBody:".length()));
                return resolveBodyColor(targetNaifId);
            } catch (NumberFormatException ignored) {
            }
        }
        return switch (typeStr) {
            case "bodyAxisX" -> ColorRGBA.Red;
            case "bodyAxisY" -> ColorRGBA.Green;
            case "bodyAxisZ" -> ColorRGBA.Blue;
            default -> ColorRGBA.White;
        };
    }

    /**
     * Resolve a body's configured color from its BodyBlock, falling back to white. Acquires configuration at
     * point-of-use (CLAUDE.md Rule 3).
     */
    private static ColorRGBA resolveBodyColor(int naifId) {
        try {
            String name = BodyLookupService.formatName(naifId);
            if (name != null && !name.startsWith("NAIF ") && !name.equals("—")) {
                java.awt.Color awtColor =
                        KEPPLRConfiguration.getInstance().bodyBlock(name).color();
                if (awtColor != null) {
                    return new ColorRGBA(
                            awtColor.getRed() / 255f, awtColor.getGreen() / 255f, awtColor.getBlue() / 255f, 1f);
                }
            }
        } catch (Exception ignored) {
        }
        return ColorRGBA.White;
    }

    // ── Config-driven scene rebuild ────────────────────────────────────────────────────────────

    /**
     * Rebuild all render managers after a configuration reload. Must run on the JME render thread.
     *
     * <p>A config reload is treated as an application restart: all managers that hold scene-graph state are disposed
     * and reconstructed from scratch. {@code activeTrailIds} and {@code activeVectorDefs} are cleared so the next
     * {@link #syncTrails}/{@link #syncVectors} call rebuilds them from the current state. Overlay visibility state is
     * also cleared so scripts start with a clean slate.
     */
    private void rebuildBodyScene() {
        // Tear down managers that hold persistent scene-graph state
        bodySceneManager.dispose();
        trailManager.dispose();
        vectorManager.dispose();
        starFieldManager.dispose();
        sunHaloRenderer.dispose();
        labelManager.dispose();
        activeTrailIds.clear();
        activeVectorDefs.clear();
        // Clear overlay visibility state so scripts start with a clean slate
        simulationState.clearOverlayState();

        // Clear the asset cache so GLB shape models and textures are reloaded from disk
        // rather than served from the stale cache left by the previous configuration.
        assetManager.clearCache();

        // Re-register the new resources folder before any manager that loads assets
        assetManager.registerLocator(KEPPLRConfiguration.getInstance().resourcesFolder(), FileLocator.class);

        // Reconstruct all managers
        bodySceneManager = new BodySceneManager(nearNode, midNode, farNode, assetManager, simulationState);
        transitionController.setBodySceneManager(bodySceneManager);
        instrumentFrustumManager.reload();
        trailManager = new TrailManager(nearNode, midNode, farNode, assetManager, simulationState);
        vectorManager = new VectorManager(nearNode, midNode, farNode, assetManager);
        starFieldManager = new StarFieldManager(farNode, assetManager, simulationState);
        starFieldManager.setCatalog(starCatalog);
        sunHaloRenderer = new SunHaloRenderer(farNode, midNode, nearNode, assetManager, simulationState);
        sunHaloRenderer.init();
        labelManager = new LabelManager(guiNode, assetManager);
        logger.info("All render managers rebuilt after configuration reload");
    }

    // ── Screenshot capture (Step 25) ──────────────────────────────────────────────────────────

    /**
     * Capture the current framebuffer to a PNG file on the JME render thread.
     *
     * <p>Reads the framebuffer of the default viewport (which composites all three frustum layers after rendering),
     * converts the pixel data to a {@code BufferedImage}, and writes it to the specified path.
     *
     * @param outputPath file system path for the output PNG file
     */
    private void captureFramebufferToPng(String outputPath) {
        int width = cam.getWidth();
        int height = cam.getHeight();

        java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(width * height * 4);
        renderManager.getRenderer().readFrameBuffer(null, buf);

        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // OpenGL reads bottom-to-top; BufferedImage is top-to-bottom
                int srcIdx = ((height - 1 - y) * width + x) * 4;
                int r = buf.get(srcIdx) & 0xFF;
                int g = buf.get(srcIdx + 1) & 0xFF;
                int b = buf.get(srcIdx + 2) & 0xFF;
                int a = buf.get(srcIdx + 3) & 0xFF;
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }

        try {
            java.nio.file.Path path = java.nio.file.Path.of(outputPath);
            if (path.getParent() != null) {
                java.nio.file.Files.createDirectories(path.getParent());
            }
            javax.imageio.ImageIO.write(img, "PNG", path.toFile());
            logger.info("Screenshot saved: {}", outputPath);
        } catch (java.io.IOException e) {
            logger.error("Failed to save screenshot '{}': {}", outputPath, e.getMessage());
        }
    }

    /**
     * Get the current viewport dimensions as {@code [width, height]}.
     *
     * <p>Used by {@link kepplr.core.CaptureService} to record dimensions in {@code capture_info.json}.
     *
     * @return {@code int[2]} containing width and height in pixels
     */
    int[] getViewportDimensions() {
        return new int[] {cam.getWidth(), cam.getHeight()};
    }

    // ── private helpers ───────────────────────────────────────────────────────────────────────

    /**
     * Compute the camera's position in the focused body's body-fixed frame as spherical coordinates.
     *
     * @param focusId NAIF ID of the focused body, or -1 for none
     * @param camHelioJ2000 heliocentric J2000 camera position in km
     * @param et current simulation ET
     * @return {@code [r_km, lat_deg, lon_deg]}, or {@code null} if unavailable
     */
    private double[] computeBodyFixedSpherical(int focusId, double[] camHelioJ2000, double et) {
        if (focusId == -1) return null;
        try {
            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
            VectorIJK focusPos = eph.getHeliocentricPositionJ2000(focusId, et);
            if (focusPos == null) return null;
            RotationMatrixIJK r = eph.getJ2000ToBodyFixedRotation(focusId, et);
            if (r == null) return null;
            // Offset in J2000
            double dx = camHelioJ2000[0] - focusPos.getI();
            double dy = camHelioJ2000[1] - focusPos.getJ();
            double dz = camHelioJ2000[2] - focusPos.getK();
            // Apply R (J2000→bodyFixed): bodyFixed = R * offset_J2000
            double bx = r.get(0, 0) * dx + r.get(0, 1) * dy + r.get(0, 2) * dz;
            double by = r.get(1, 0) * dx + r.get(1, 1) * dy + r.get(1, 2) * dz;
            double bz = r.get(2, 0) * dx + r.get(2, 1) * dy + r.get(2, 2) * dz;
            double rKm = Math.sqrt(bx * bx + by * by + bz * bz);
            if (rKm < 1e-9) return new double[] {0.0, 0.0, 0.0};
            double latDeg = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, bz / rKm))));
            double lonDeg = Math.toDegrees(Math.atan2(by, bx));
            return new double[] {rKm, latDeg, lonDeg};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Convert a heliocentric J2000 position (km, double) to scene-graph coordinates (camera-relative, float). Floating
     * origin: scene position = helio position − camera helio position.
     */
    private Vector3f toScenePosition(VectorIJK helioPos) {
        return new Vector3f(
                (float) (helioPos.getI() - cameraHelioJ2000[0]),
                (float) (helioPos.getJ() - cameraHelioJ2000[1]),
                (float) (helioPos.getK() - cameraHelioJ2000[2]));
    }

    /**
     * Scene-space position of the Sun (km). Under floating origin, the Sun is always at helio origin (0, 0, 0), so its
     * scene position is the negation of the camera's helio position.
     */
    private Vector3f sunScenePosition() {
        return new Vector3f((float) -cameraHelioJ2000[0], (float) -cameraHelioJ2000[1], (float) -cameraHelioJ2000[2]);
    }

    private static PointLight sunPointLight(Vector3f position) {
        PointLight light = new PointLight();
        light.setColor(ColorRGBA.White.mult(2f));
        light.setPosition(position);
        light.setRadius(Float.MAX_VALUE); // no distance attenuation
        return light;
    }

    /**
     * Get the GLFW window handle from the JME LWJGL3 context.
     *
     * @return the native window handle, or 0 if unavailable
     */
    private long getGlfwWindowHandle() {
        var ctx = getContext();
        if (ctx instanceof com.jme3.system.lwjgl.LwjglWindow lwjglWindow) {
            return lwjglWindow.getWindowHandle();
        }
        return 0;
    }

    public static void main(String[] args) {
        KEPPLRConfiguration.getTemplate();

        run(null, null);
    }

    /**
     * Launch the KEPPLR JME application.
     *
     * @param scriptPath path to a Groovy script to run on startup, or {@code null} to skip
     * @param stateString state string to restore on startup, or {@code null} to skip
     */
    public static void run(String scriptPath, String stateString) {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("linux")) {
            // On Linux with both DISPLAY and WAYLAND_DISPLAY set, GLFW defaults to Wayland and
            // loads libdecor-gtk for window decorations.  If JavaFX then forces GTK to X11 mode,
            // libdecor-gtk fails to initialise and its fallback path segfaults (known libdecor
            // bug).  Pinning GLFW to X11 before glfwInit() avoids Wayland/libdecor entirely;
            // XWayland provides the X11 display.
            //
            // IMPORTANT: GLFW 3.4 (LWJGL 3.3.5+) interprets GLFW_PLATFORM hints strictly —
            // requesting an unavailable platform causes glfwInit() to fail.  This hint must only
            // be set on Linux; on macOS it would select X11 (unavailable) and silently prevent
            // the JME window from ever appearing.
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);

            // GTK initialisation (triggered by Platform.startup) must happen on the main thread
            // on Linux.  Calling it later from the JME render thread causes GTK assertion failures
            // and crashes.
            Platform.startup(() -> {});
        }
        // On macOS, Platform.startup() is deferred to simpleInitApp() so that GLFW initialises
        // NSApplication first.  Calling Platform.startup() before glfwInit() on macOS lets JavaFX
        // install its own NSApplication delegate; when GLFW then also tries to configure
        // NSApplication the result is a conflict that silently prevents the JME window from
        // appearing.

        KepplrApp app = new KepplrApp();
        app.startupScript = scriptPath;
        app.startupState = stateString;
        AppSettings settings = new AppSettings(true);
        settings.setTitle("KEPPLR");
        settings.setWidth(1280);
        settings.setHeight(720);
        settings.setRenderer(AppSettings.LWJGL_OPENGL33);
        settings.setResizable(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }
}
