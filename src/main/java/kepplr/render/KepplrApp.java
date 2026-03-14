package kepplr.render;

import com.jme3.app.LostFocusBehavior;
import com.jme3.app.SimpleApplication;
import com.jme3.light.AmbientLight;
import com.jme3.light.PointLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.system.AppSettings;
import java.time.Instant;
import java.util.List;
import javafx.application.Platform;
import kepplr.camera.BodyFixedFrame;
import kepplr.camera.CameraFrame;
import kepplr.camera.CameraInputHandler;
import kepplr.camera.SynodicFrameApplier;
import kepplr.camera.TransitionController;
import kepplr.commands.DefaultSimulationCommands;
import kepplr.config.KEPPLRConfiguration;
import kepplr.core.SimulationClock;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.render.body.BodySceneManager;
import kepplr.render.frustum.FrustumLayer;
import kepplr.render.trail.TrailManager;
import kepplr.render.vector.VectorDefinition;
import kepplr.render.vector.VectorManager;
import kepplr.render.vector.VectorTypes;
import kepplr.stars.catalogs.yaleBSC.YaleBrightStarCatalog;
import kepplr.state.BodyInView;
import kepplr.state.DefaultSimulationState;
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
    private static final float CAMERA_OFFSET_KM = 15000f;

    /** Camera heliocentric J2000 position in km. Scene positions are {@code helioPos − this}, cast to float for JME. */
    private final double[] cameraHelioJ2000 = new double[3];

    // ── Simulation model ──────────────────────────────────────────────────────────────────────
    private DefaultSimulationState simulationState;
    private SimulationClock simulationClock;
    private TransitionController transitionController;
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

    // ── Sun halo ───────────────────────────────────────────────────────────────────────────────
    private SunHaloRenderer sunHaloRenderer;

    @Override
    public void simpleInitApp() {
        setLostFocusBehavior(LostFocusBehavior.Disabled);
        setDisplayFps(true);
        setDisplayStatView(false);
        flyCam.setEnabled(false);

        // ── Simulation clock and commands ─────────────────────────────────────────────────────
        double startET = KEPPLRConfiguration.getInstance().getTimeConversion().instantToTDB(Instant.now());
        simulationState = new DefaultSimulationState();
        simulationClock = new SimulationClock(simulationState, startET);
        transitionController = new TransitionController(simulationState);

        DefaultSimulationCommands commands =
                new DefaultSimulationCommands(simulationState, simulationClock, transitionController);
        SimulationStateFxBridge bridge = new SimulationStateFxBridge(simulationState);
        // On macOS, JavaFX is started here (after GLFW has claimed NSApplication) rather than in
        // main().  See main() comment for the full rationale.
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            Platform.startup(() -> {});
        }
        Platform.runLater(() -> new KepplrStatusWindow(bridge, commands).show());

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

        // ── Trail manager ─────────────────────────────────────────────────────────────────────
        trailManager = new TrailManager(nearNode, midNode, farNode, assetManager, simulationState);
        trailManager.enableTrail(focusBodyId);

        // ── Vector overlay manager — enable overlays for Step 13 visual confirmation ──────────
        vectorManager = new VectorManager(nearNode, midNode, farNode, assetManager);
        vectorManager.enableVector(new VectorDefinition(
                "velocity direction", VectorTypes.velocity(), focusBodyId, com.jme3.math.ColorRGBA.Cyan, 1.0));
        vectorManager.enableVector(new VectorDefinition(
                "Sun direction",
                VectorTypes.towardBody(KepplrConstants.SUN_NAIF_ID),
                focusBodyId,
                com.jme3.math.ColorRGBA.Yellow,
                1.0));

        // ── Star field ────────────────────────────────────────────────────────────────────────
        YaleBrightStarCatalog bsc =
                YaleBrightStarCatalog.loadFromResource("/resources/kepplr/stars/catalogs/yaleBSC/ybsc5.gz");
        starFieldManager = new StarFieldManager(farNode, assetManager, simulationState);
        starFieldManager.setCatalog(bsc);

        // ── Sun halo ──────────────────────────────────────────────────────────────────────────
        sunHaloRenderer = new SunHaloRenderer(farNode, midNode, nearNode, assetManager, simulationState);
        sunHaloRenderer.init();

        // ── HUD and camera input ──────────────────────────────────────────────────────────────
        hud = new KepplrHud(guiNode, assetManager, cam);
        cameraInputHandler = new CameraInputHandler(cam, cameraHelioJ2000, simulationState);
        cameraInputHandler.register(inputManager);
    }

    @Override
    public void simpleUpdate(float tpf) {
        simulationClock.advance();
        cameraInputHandler.update();

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
            int focusId = simulationState.focusedBodyIdProperty().get();
            int targetId = simulationState.targetedBodyIdProperty().get();
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

        // Sync slave cameras to master orientation (position is always ZERO in floating-origin)
        midCam.setLocation(cam.getLocation());
        midCam.setRotation(cam.getRotation());
        nearCam.setLocation(cam.getLocation());
        nearCam.setRotation(cam.getRotation());

        // Update Sun light position in all three layers (Sun helio pos = origin; scene pos = −cam)
        Vector3f sunScenePos = sunScenePosition();
        sunLightFar.setPosition(sunScenePos);
        sunLightMid.setPosition(sunScenePos);
        sunLightNear.setPosition(sunScenePos);

        double currentEt = simulationState.currentEtProperty().get();
        List<BodyInView> inView = bodySceneManager.update(currentEt, cameraHelioJ2000, cam);
        trailManager.update(currentEt, cameraHelioJ2000);
        vectorManager.update(
                currentEt,
                cameraHelioJ2000,
                cam,
                simulationState.focusedBodyIdProperty().get());
        starFieldManager.update(currentEt, cameraHelioJ2000);
        sunHaloRenderer.update(cameraHelioJ2000, cam, tpf);
        simulationState.setBodiesInView(inView);
        hud.update(currentEt);

        // JME calls updateGeometricState() only on rootNode and guiNode (SimpleApplication source).
        // Our frustum layer roots are custom viewport scenes and must be updated manually;
        // otherwise checkCulling() raises IllegalStateException during the render pass.
        farNode.updateGeometricState();
        midNode.updateGeometricState();
        nearNode.updateGeometricState();
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

    public static void main(String[] args) {
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

        KEPPLRConfiguration.getTemplate();

        KepplrApp app = new KepplrApp();
        AppSettings settings = new AppSettings(true);
        settings.setTitle("KEPPLR");
        settings.setWidth(1280);
        settings.setHeight(720);
        settings.setRenderer(AppSettings.LWJGL_OPENGL33);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }
}
