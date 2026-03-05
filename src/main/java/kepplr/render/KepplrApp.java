package kepplr.render;

import com.jme3.app.LostFocusBehavior;
import com.jme3.app.SimpleApplication;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.light.AmbientLight;
import com.jme3.light.PointLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix3f;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Sphere;
import com.jme3.system.AppSettings;
import com.jme3.texture.Texture;
import java.nio.file.Path;
import java.time.Instant;
import javafx.application.Platform;
import kepplr.commands.DefaultSimulationCommands;
import kepplr.config.BodyBlock;
import kepplr.config.KEPPLRConfiguration;
import kepplr.core.SimulationClock;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.state.DefaultSimulationState;
import kepplr.ui.KepplrStatusWindow;
import kepplr.ui.SimulationStateFxBridge;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;
import picante.math.vectorspace.RotationMatrixIJK;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.EphemerisID;
import picante.surfaces.Ellipsoid;

/**
 * Main JMonkeyEngine application for KEPPLR.
 *
 * <p>Renders the solar system using ephemeris-driven body positions in a floating-origin scene graph. World-space units
 * are kilometers (REDESIGN.md §2.1). Body positions are computed relative to the camera's heliocentric J2000 position
 * each frame, keeping scene graph coordinate values small regardless of true heliocentric distances.
 *
 * <p>This initial version renders Earth as a textured sphere at its correct heliocentric position for ET = 0.0 (J2000
 * epoch), with a fixed camera offset.
 */
public class KepplrApp extends SimpleApplication {

    private static final Logger logger = LogManager.getLogger();

    private static final int EARTH_NAIF_ID = 399;
    private static final float CAMERA_OFFSET_KM = 50_000f;

    /**
     * Camera heliocentric J2000 position in km, stored in double precision. Scene graph positions are computed as (body
     * helio pos - camera helio pos) and cast to float for JME.
     */
    private final double[] cameraHelioJ2000 = new double[3];

    private DefaultSimulationState simulationState;
    private SimulationClock simulationClock;
    private KepplrHud hud;

    /** Scene-graph node whose translation is updated each frame to Earth's camera-relative position. */
    private Node earthEphemerisNode;

    /** Scene-graph node whose rotation is updated each frame to the J2000 → IAU_EARTH frame transform. */
    private Node earthBodyFixedNode;

    @Override
    public void simpleInitApp() {
        setLostFocusBehavior(LostFocusBehavior.Disabled);
        setDisplayFps(true);
        setDisplayStatView(false);
        flyCam.setEnabled(false);
        viewPort.setBackgroundColor(ColorRGBA.Black);

        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();

        // Initialise time model: start at current wall time converted to TDB (§1.2)
        double startET = KEPPLRConfiguration.getInstance().getTimeConversion().instantToTDB(Instant.now());
        simulationState = new DefaultSimulationState();
        simulationClock = new SimulationClock(simulationState, startET);

        // Show JavaFX status window — Platform was already started in main() on the main thread
        DefaultSimulationCommands commands = new DefaultSimulationCommands(simulationState, simulationClock);
        SimulationStateFxBridge bridge = new SimulationStateFxBridge(simulationState);
        Platform.runLater(() -> new KepplrStatusWindow(bridge, commands).show());

        // Default camera target: focus on Earth at launch (§4.5)
        commands.focusBody(EARTH_NAIF_ID);

        VectorIJK earthHelioPos = eph.getHeliocentricPositionJ2000(EARTH_NAIF_ID, startET);
        if (earthHelioPos == null) {
            logger.error("Cannot resolve Earth (NAIF {}) at ET={}", EARTH_NAIF_ID, startET);
            stop();
            return;
        }

        // Camera: offset from Earth along J2000 +Z
        cameraHelioJ2000[0] = earthHelioPos.getI();
        cameraHelioJ2000[1] = earthHelioPos.getJ();
        cameraHelioJ2000[2] = earthHelioPos.getK() + CAMERA_OFFSET_KM;
        simulationState.setCameraPositionJ2000(cameraHelioJ2000);

        // Frustum: single viewport, mid-frustum range
        cam.setFrustumPerspective(
                45f, (float) cam.getWidth() / cam.getHeight(), (float) KepplrConstants.FRUSTUM_MID_MIN_KM, (float)
                        KepplrConstants.FRUSTUM_MID_MAX_KM);
        cam.setLocation(Vector3f.ZERO);
        cam.lookAt(toScenePosition(earthHelioPos), Vector3f.UNIT_Y);

        Node earthNode = createEarthNode(eph, earthHelioPos, startET); // sets earthEphemerisNode + earthBodyFixedNode
        rootNode.attachChild(earthNode);

        addLighting(eph);

        hud = new KepplrHud(guiNode, assetManager, cam);
    }

    @Override
    public void simpleUpdate(float tpf) {
        simulationClock.advance();
        double currentEt = simulationState.currentEtProperty().get();
        updateEarthSceneGraph(currentEt);
        hud.update(currentEt);
    }

    private void updateEarthSceneGraph(double et) {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        VectorIJK earthHelioPos = eph.getHeliocentricPositionJ2000(EARTH_NAIF_ID, et);
        if (earthHelioPos == null) {
            logger.warn("Cannot resolve Earth (NAIF {}) at ET={}; skipping frame update", EARTH_NAIF_ID, et);
            return;
        }
        earthEphemerisNode.setLocalTranslation(toScenePosition(earthHelioPos));
        RotationMatrixIJK rot = eph.getJ2000ToBodyFixedRotation(EARTH_NAIF_ID, et);
        if (rot != null) {
            earthBodyFixedNode.setLocalRotation(toJmeQuaternion(rot));
        }
    }

    private Node createEarthNode(KEPPLREphemeris eph, VectorIJK earthHelioPos, double et) {
        // Resolve Earth's physical radius for mesh scaling
        EphemerisID earthId = eph.getSpiceBundle().getObject(EARTH_NAIF_ID);
        Ellipsoid shape = eph.getShape(earthId);
        float radiusKm;
        if (shape != null) {
            radiusKm = (float) ((shape.getA() + shape.getB() + shape.getC()) / 3.0);
        } else {
            radiusKm = 6371f;
            logger.warn("No shape data for Earth; using default radius {} km", radiusKm);
        }

        // Sphere mesh — unit radius, scaled by setLocalScale
        int tess = KepplrConstants.BODY_SPHERE_TESSELLATION;
        Sphere mesh = new Sphere(tess, tess, 1f);
        mesh.setTextureMode(Sphere.TextureMode.Projected);
        mesh.updateBound();

        Geometry earthGeom = new Geometry("Earth", mesh);
        earthGeom.setLocalScale(radiusKm);
        earthGeom.setMaterial(createEarthMaterial());

        // Texture alignment: rotate so texture center longitude aligns in body-fixed frame
        BodyBlock earthBlock = KEPPLRConfiguration.getInstance().bodyBlock("earth");
        double centerLonRad = earthBlock.centerLon();
        Node textureAlignNode = new Node("Earth-texture-align");
        if (centerLonRad != 0.0) {
            Quaternion texRot = new Quaternion();
            texRot.fromAngleAxis((float) centerLonRad, Vector3f.UNIT_Z);
            textureAlignNode.setLocalRotation(texRot);
        }
        textureAlignNode.attachChild(earthGeom);

        // Body-fixed rotation: J2000 → IAU_EARTH
        Node bodyFixedNode = new Node("Earth-body-fixed");
        RotationMatrixIJK j2000ToBodyFixed = eph.getJ2000ToBodyFixedRotation(EARTH_NAIF_ID, et);
        if (j2000ToBodyFixed != null) {
            bodyFixedNode.setLocalRotation(toJmeQuaternion(j2000ToBodyFixed));
        } else {
            logger.warn("No body-fixed frame for Earth; texture orientation will be incorrect");
        }
        bodyFixedNode.attachChild(textureAlignNode);

        // Ephemeris node: positioned at Earth's camera-relative location
        // Store references so simpleUpdate() can reposition them each frame
        earthBodyFixedNode = bodyFixedNode;
        earthEphemerisNode = new Node("Earth-ephemeris");
        earthEphemerisNode.setLocalTranslation(toScenePosition(earthHelioPos));
        earthEphemerisNode.attachChild(bodyFixedNode);

        return earthEphemerisNode;
    }

    private Material createEarthMaterial() {
        Material mat = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        mat.setBoolean("UseMaterialColors", true);
        mat.setColor("Diffuse", ColorRGBA.White);
        mat.setColor("Ambient", new ColorRGBA(0.05f, 0.05f, 0.05f, 1f));

        BodyBlock earthBlock = KEPPLRConfiguration.getInstance().bodyBlock("earth");
        String texturePath = earthBlock.textureMap();
        if (texturePath != null && !texturePath.isBlank()) {
            Path resolved = KEPPLRConfiguration.getInstance().getPathInResources(texturePath);
            try {
                String parentDir = resolved.getParent().toString();
                assetManager.registerLocator(parentDir, FileLocator.class);
                Texture tex = assetManager.loadTexture(resolved.getFileName().toString());
                tex.setWrap(Texture.WrapMode.Repeat);
                mat.setTexture("DiffuseMap", tex);
            } catch (Exception e) {
                logger.warn("Could not load Earth texture {}: {}", resolved, e.getMessage());
                mat.setColor("Diffuse", ColorRGBA.Blue);
            }
        } else {
            mat.setColor("Diffuse", ColorRGBA.Blue);
        }
        return mat;
    }

    private void addLighting(KEPPLREphemeris eph) {
        // Dim ambient so night side is visible but dark
        AmbientLight ambient = new AmbientLight(new ColorRGBA(0.15f, 0.15f, 0.15f, 1f));
        rootNode.addLight(ambient);

        // Sun point light at Sun's scene position (Sun helio pos = origin)
        PointLight sunLight = new PointLight();
        sunLight.setColor(ColorRGBA.White.mult(2f));
        sunLight.setPosition(toScenePosition(new VectorIJK(0.0, 0.0, 0.0)));
        sunLight.setRadius(Float.MAX_VALUE);
        rootNode.addLight(sunLight);
    }

    /**
     * Convert a heliocentric J2000 position (km, double) to scene-graph coordinates (camera-relative, float). Floating
     * origin: scene position = helio position - camera helio position.
     */
    private Vector3f toScenePosition(VectorIJK helioPos) {
        return new Vector3f(
                (float) (helioPos.getI() - cameraHelioJ2000[0]),
                (float) (helioPos.getJ() - cameraHelioJ2000[1]),
                (float) (helioPos.getK() - cameraHelioJ2000[2]));
    }

    /** Convert a Picante RotationMatrixIJK to a JME Quaternion. */
    private static Quaternion toJmeQuaternion(RotationMatrixIJK rot) {
        Matrix3f m = new Matrix3f(
                (float) rot.get(0, 0),
                (float) rot.get(0, 1),
                (float) rot.get(0, 2),
                (float) rot.get(1, 0),
                (float) rot.get(1, 1),
                (float) rot.get(1, 2),
                (float) rot.get(2, 0),
                (float) rot.get(2, 1),
                (float) rot.get(2, 2));
        Quaternion q = new Quaternion();
        q.fromRotationMatrix(m);
        return q;
    }

    public static void main(String[] args) {
        // On Linux with both DISPLAY and WAYLAND_DISPLAY set, GLFW defaults to Wayland and loads
        // libdecor-gtk for window decorations.  If JavaFX then forces GTK to X11 mode, libdecor-gtk
        // fails to initialise and its fallback path segfaults (known libdecor bug).  Pinning GLFW to
        // X11 before glfwInit() is called avoids Wayland/libdecor entirely; XWayland provides the
        // X11 display.  This hint is a no-op on non-Linux platforms.
        GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);

        // Start the JavaFX application thread on the main thread.  GTK initialisation (which
        // Platform.startup triggers internally) must happen on the main thread on Linux; calling it
        // later from the JME render thread can cause GTK assertion failures and crashes.
        Platform.startup(() -> {});

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
