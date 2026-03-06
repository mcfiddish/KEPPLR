package kepplr.camera;

import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.RawInputListener;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.input.event.JoyAxisEvent;
import com.jme3.input.event.JoyButtonEvent;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.input.event.MouseMotionEvent;
import com.jme3.input.event.TouchEvent;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.state.DefaultSimulationState;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.mechanics.EphemerisID;
import picante.surfaces.Ellipsoid;

/**
 * JMonkeyEngine input handler for camera navigation.
 *
 * <p>Mouse drag (rotate and orbit) uses {@link RawInputListener} to obtain raw pixel deltas directly from JME's input
 * queue. This works correctly whether the cursor is visible or captured (GLFW cursor-disabled mode is not required).
 * {@link AnalogListener} is kept for scroll-wheel zoom. Keyboard uses {@link ActionListener}.
 *
 * <p>All callbacks fire on the JME render thread (CLAUDE.md Rule 4). Focus body and ephemeris are acquired via
 * {@link KEPPLRConfiguration#getInstance()} at point of use (CLAUDE.md Rule 3).
 *
 * <p>Input bindings:
 *
 * <ul>
 *   <li>Left drag — rotate camera in place (always active)
 *   <li>Right drag — orbit camera around focus body (no-op if no focus)
 *   <li>Scroll wheel — zoom (no-op if no focus)
 *   <li>Up/Down arrow — tilt camera (pitch in place)
 *   <li>Left/Right arrow — roll camera (roll in place)
 *   <li>Shift + Up/Down arrow — orbit around screen-right axis
 *   <li>Shift + Left/Right arrow — orbit around screen-up axis
 *   <li>PgUp/PgDn — zoom in/out
 * </ul>
 */
public final class CameraInputHandler implements ActionListener, AnalogListener, RawInputListener {

    private static final Logger logger = LogManager.getLogger();

    // ── Mapping names for keyboard and scroll ─────────────────────────────────

    private static final String SCROLL_UP = "CAM_SCROLL_UP";
    private static final String SCROLL_DOWN = "CAM_SCROLL_DOWN";
    private static final String TILT_UP = "CAM_TILT_UP";
    private static final String TILT_DOWN = "CAM_TILT_DOWN";
    private static final String ROLL_LEFT = "CAM_ROLL_LEFT";
    private static final String ROLL_RIGHT = "CAM_ROLL_RIGHT";
    private static final String ORBIT_UP = "CAM_ORBIT_UP";
    private static final String ORBIT_DOWN = "CAM_ORBIT_DOWN";
    private static final String ORBIT_LEFT = "CAM_ORBIT_LEFT";
    private static final String ORBIT_RIGHT = "CAM_ORBIT_RIGHT";
    private static final String ZOOM_IN = "CAM_ZOOM_IN";
    private static final String ZOOM_OUT = "CAM_ZOOM_OUT";
    private static final String SHIFT_LEFT = "CAM_SHIFT_LEFT";
    private static final String SHIFT_RIGHT = "CAM_SHIFT_RIGHT";

    // ── State ──────────────────────────────────────────────────────────────────

    private final Camera cam;
    private final double[] cameraHelioJ2000;
    private final DefaultSimulationState state;

    // Mouse button drag state — set/cleared by RawInputListener.onMouseButtonEvent
    private boolean leftDragging = false;
    private boolean rightDragging = false;

    // Keyboard shift modifier
    private boolean shiftDown = false;

    // Accumulated mouse deltas from RawInputListener.onMouseMotionEvent, flushed in update()
    private int rawMouseDx = 0;
    private int rawMouseDy = 0;

    /**
     * @param cam JME camera — position and orientation are updated directly on the render thread
     * @param cameraHelioJ2000 heliocentric J2000 camera position array (length 3, km); mutated in place
     * @param state simulation state — focus body ID is read here; camera position is written back
     */
    public CameraInputHandler(Camera cam, double[] cameraHelioJ2000, DefaultSimulationState state) {
        this.cam = cam;
        this.cameraHelioJ2000 = cameraHelioJ2000;
        this.state = state;
    }

    /**
     * Register all input mappings with JME's input manager.
     *
     * <p>Call once from {@code simpleInitApp()} after the input manager is ready.
     */
    public void register(InputManager inputManager) {
        // Scroll wheel (AnalogListener — works in all cursor modes)
        inputManager.addMapping(SCROLL_UP, new MouseAxisTrigger(MouseInput.AXIS_WHEEL, false));
        inputManager.addMapping(SCROLL_DOWN, new MouseAxisTrigger(MouseInput.AXIS_WHEEL, true));

        // Arrow keys — tilt and roll without shift (ActionListener for discrete increment)
        inputManager.addMapping(TILT_UP, new KeyTrigger(KeyInput.KEY_UP));
        inputManager.addMapping(TILT_DOWN, new KeyTrigger(KeyInput.KEY_DOWN));
        inputManager.addMapping(ROLL_LEFT, new KeyTrigger(KeyInput.KEY_LEFT));
        inputManager.addMapping(ROLL_RIGHT, new KeyTrigger(KeyInput.KEY_RIGHT));

        // Arrow keys — orbit with shift (same physical keys; dispatched by shiftDown flag)
        inputManager.addMapping(ORBIT_UP, new KeyTrigger(KeyInput.KEY_UP));
        inputManager.addMapping(ORBIT_DOWN, new KeyTrigger(KeyInput.KEY_DOWN));
        inputManager.addMapping(ORBIT_LEFT, new KeyTrigger(KeyInput.KEY_LEFT));
        inputManager.addMapping(ORBIT_RIGHT, new KeyTrigger(KeyInput.KEY_RIGHT));

        // PgUp/PgDn — zoom
        inputManager.addMapping(ZOOM_IN, new KeyTrigger(KeyInput.KEY_PGUP));
        inputManager.addMapping(ZOOM_OUT, new KeyTrigger(KeyInput.KEY_PGDN));

        // Shift keys — track modifier state
        inputManager.addMapping(SHIFT_LEFT, new KeyTrigger(KeyInput.KEY_LSHIFT));
        inputManager.addMapping(SHIFT_RIGHT, new KeyTrigger(KeyInput.KEY_RSHIFT));

        inputManager.addListener(
                (ActionListener) this,
                TILT_UP,
                TILT_DOWN,
                ROLL_LEFT,
                ROLL_RIGHT,
                ORBIT_UP,
                ORBIT_DOWN,
                ORBIT_LEFT,
                ORBIT_RIGHT,
                ZOOM_IN,
                ZOOM_OUT,
                SHIFT_LEFT,
                SHIFT_RIGHT);
        inputManager.addListener((AnalogListener) this, SCROLL_UP, SCROLL_DOWN);

        // Raw mouse listener: provides raw pixel deltas and button events regardless of cursor mode
        inputManager.addRawInputListener(this);

        logger.debug("CameraInputHandler registered");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ActionListener — discrete keyboard press events
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        switch (name) {
            case SHIFT_LEFT, SHIFT_RIGHT -> shiftDown = isPressed;

            case TILT_UP -> {
                if (isPressed && !shiftDown) applyTilt(-(float) KepplrConstants.CAMERA_ROTATE_INCREMENT_RAD);
            }
            case TILT_DOWN -> {
                if (isPressed && !shiftDown) applyTilt((float) KepplrConstants.CAMERA_ROTATE_INCREMENT_RAD);
            }
            case ROLL_LEFT -> {
                if (isPressed && !shiftDown) applyRoll(-(float) KepplrConstants.CAMERA_ROTATE_INCREMENT_RAD);
            }
            case ROLL_RIGHT -> {
                if (isPressed && !shiftDown) applyRoll((float) KepplrConstants.CAMERA_ROTATE_INCREMENT_RAD);
            }
            case ORBIT_UP -> {
                if (isPressed && shiftDown) applyOrbit((float) KepplrConstants.CAMERA_ORBIT_INCREMENT_RAD, 0f);
            }
            case ORBIT_DOWN -> {
                if (isPressed && shiftDown) applyOrbit(-(float) KepplrConstants.CAMERA_ORBIT_INCREMENT_RAD, 0f);
            }
            case ORBIT_LEFT -> {
                if (isPressed && shiftDown) applyOrbit(0f, (float) KepplrConstants.CAMERA_ORBIT_INCREMENT_RAD);
            }
            case ORBIT_RIGHT -> {
                if (isPressed && shiftDown) applyOrbit(0f, -(float) KepplrConstants.CAMERA_ORBIT_INCREMENT_RAD);
            }
            case ZOOM_IN -> {
                if (isPressed) applyZoom(1); // steps=1 → factor^1 < 1 → distance decreases
            }
            case ZOOM_OUT -> {
                if (isPressed) applyZoom(-1); // steps=-1 → factor^-1 > 1 → distance increases
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AnalogListener — scroll wheel only
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onAnalog(String name, float value, float tpf) {
        switch (name) {
            case SCROLL_UP -> applyZoom(1);
            case SCROLL_DOWN -> applyZoom(-1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RawInputListener — mouse button and motion events
    //
    // These fire on the JME render thread before simpleUpdate(), providing raw
    // pixel deltas directly without cursor-capture requirements.
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onMouseButtonEvent(MouseButtonEvent evt) {
        switch (evt.getButtonIndex()) {
            case MouseInput.BUTTON_LEFT -> leftDragging = evt.isPressed();
            case MouseInput.BUTTON_RIGHT -> rightDragging = evt.isPressed();
        }
    }

    @Override
    public void onMouseMotionEvent(MouseMotionEvent evt) {
        // Accumulate raw pixel deltas; flushed in update() which is called from simpleUpdate()
        rawMouseDx += evt.getDX();
        rawMouseDy += evt.getDY();
    }

    // Unused RawInputListener callbacks
    @Override
    public void beginInput() {}

    @Override
    public void endInput() {}

    @Override
    public void onJoyAxisEvent(JoyAxisEvent evt) {}

    @Override
    public void onJoyButtonEvent(JoyButtonEvent evt) {}

    @Override
    public void onKeyEvent(KeyInputEvent evt) {}

    @Override
    public void onTouchEvent(TouchEvent evt) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Per-frame flush — called from KepplrApp.simpleUpdate() each frame
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Flush accumulated mouse deltas for this frame. Must be called once per frame from
     * {@code KepplrApp.simpleUpdate()} after all input events have been processed.
     */
    public void update() {
        if (rawMouseDx == 0 && rawMouseDy == 0) return;

        int dx = rawMouseDx;
        int dy = rawMouseDy;
        rawMouseDx = 0;
        rawMouseDy = 0;

        if (leftDragging) {
            // Left drag: rotate in place.  dx → yaw (around screenUp), dy → pitch (around screenRight)
            float deltaUp = (float) (dx * KepplrConstants.CAMERA_MOUSE_ROTATE_SENSITIVITY);
            float deltaRight = -(float) (dy * KepplrConstants.CAMERA_MOUSE_ROTATE_SENSITIVITY);
            applyRotateInPlace(deltaRight, deltaUp);
        } else if (rightDragging) {
            // Right drag: orbit around focus.  dx → orbit around screenUp, dy → around screenRight
            float deltaUp = -(float) (dx * KepplrConstants.CAMERA_MOUSE_ORBIT_SENSITIVITY);
            float deltaRight = (float) (dy * KepplrConstants.CAMERA_MOUSE_ORBIT_SENSITIVITY);
            applyOrbit(deltaRight, deltaUp);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Navigation operations
    // ─────────────────────────────────────────────────────────────────────────

    /** Pitch (tilt): rotate around the camera's current screen-right axis. */
    private void applyTilt(float deltaRight) {
        applyRotateInPlace(deltaRight, 0f);
    }

    /** Roll: rotate around the camera's current look direction (forward axis). */
    private void applyRoll(float delta) {
        Vector3f lookDir = cam.getDirection().normalize();
        Quaternion qRoll = new Quaternion().fromAngleNormalAxis(delta, lookDir);
        Quaternion newOrientation = qRoll.mult(cam.getRotation()).normalizeLocal();
        cam.setAxes(newOrientation);
    }

    private void applyRotateInPlace(float deltaRight, float deltaUp) {
        Vector3f screenRight = cam.getLeft().negate();
        Vector3f screenUp = cam.getUp();
        Quaternion newOrientation =
                CameraNavigator.rotateInPlace(cam.getRotation(), screenRight, screenUp, deltaRight, deltaUp);
        cam.setAxes(newOrientation);
    }

    private void applyOrbit(float deltaRight, float deltaUp) {
        int focusId = state.focusedBodyIdProperty().get();
        if (focusId == -1) return;

        double[] focusPos = getFocusBodyPos(focusId);
        if (focusPos == null) return;

        Vector3f screenRight = cam.getLeft().negate();
        Vector3f screenUp = cam.getUp();

        CameraNavigator.OrbitResult result = CameraNavigator.orbit(
                cameraHelioJ2000, cam.getRotation(), focusPos, screenRight, screenUp, deltaRight, deltaUp);

        cameraHelioJ2000[0] = result.position()[0];
        cameraHelioJ2000[1] = result.position()[1];
        cameraHelioJ2000[2] = result.position()[2];
        cam.setAxes(result.orientation());
        state.setCameraPositionJ2000(cameraHelioJ2000);
    }

    private void applyZoom(int steps) {
        int focusId = state.focusedBodyIdProperty().get();
        if (focusId == -1) return;

        double[] focusPos = getFocusBodyPos(focusId);
        if (focusPos == null) return;

        double minDist = getFocusBodyMinDist(focusId);
        double[] newPos = CameraNavigator.zoom(
                cameraHelioJ2000,
                focusPos,
                steps,
                minDist,
                KepplrConstants.FRUSTUM_FAR_MAX_KM,
                KepplrConstants.CAMERA_ZOOM_FACTOR_PER_STEP);

        cameraHelioJ2000[0] = newPos[0];
        cameraHelioJ2000[1] = newPos[1];
        cameraHelioJ2000[2] = newPos[2];
        state.setCameraPositionJ2000(cameraHelioJ2000);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers — ephemeris/config acquired at point of use (Rule 3)
    // ─────────────────────────────────────────────────────────────────────────

    private double[] getFocusBodyPos(int naifId) {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        double et = state.currentEtProperty().get();
        picante.math.vectorspace.VectorIJK pos = eph.getHeliocentricPositionJ2000(naifId, et);
        if (pos == null) return null;
        return new double[] {pos.getI(), pos.getJ(), pos.getK()};
    }

    private double getFocusBodyMinDist(int naifId) {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        EphemerisID id = eph.getSpiceBundle().getObject(naifId);
        if (id != null) {
            Ellipsoid shape = eph.getShape(id);
            if (shape != null) {
                double meanRadius = (shape.getA() + shape.getB() + shape.getC()) / 3.0;
                return meanRadius * KepplrConstants.CAMERA_ZOOM_BODY_RADIUS_FACTOR;
            }
        }
        return KepplrConstants.CAMERA_ZOOM_FALLBACK_MIN_KM;
    }
}
