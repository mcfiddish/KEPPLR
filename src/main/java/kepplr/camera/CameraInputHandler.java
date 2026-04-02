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
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import kepplr.commands.SimulationCommands;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.state.DefaultSimulationState;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * JMonkeyEngine input handler for camera navigation.
 *
 * <p>Mouse drag (rotate and orbit) uses {@link RawInputListener} to obtain raw pixel deltas directly from JME's input
 * queue. This works correctly whether the cursor is visible or captured (GLFW cursor-disabled mode is not required).
 * {@link AnalogListener} is kept for scroll-wheel zoom. Keyboard uses {@link ActionListener}.
 *
 * <p>All navigation actions delegate to {@link SimulationCommands} with {@code durationSeconds = 0} (instant snap), so
 * that camera actions are scriptable through the same interface (Step 19c). The input handler retains responsibility
 * for detecting gestures and computing delta values — it does not retain responsibility for applying them to the
 * camera.
 *
 * <p>All callbacks fire on the JME render thread (CLAUDE.md Rule 4). Focus body and ephemeris are acquired via
 * {@link KEPPLRConfiguration#getInstance()} at point of use (CLAUDE.md Rule 3).
 *
 * <p>Input bindings:
 *
 * <ul>
 *   <li>Left drag — rotate camera in place (tilt + yaw; always active)
 *   <li>Right drag — orbit camera around focus body (no-op if no focus)
 *   <li>Scroll wheel — zoom (no-op if no focus)
 *   <li>Up/Down arrow — tilt camera (pitch in place)
 *   <li>Left/Right arrow — roll camera (roll in place)
 *   <li>Shift + Up/Down arrow — orbit around screen-right axis
 *   <li>Shift + Left/Right arrow — orbit around screen-up axis
 *   <li>PgUp/PgDn — zoom in/out
 *   <li>G — goTo focused body (camera transition)
 *   <li>F — toggle camera frame between SYNODIC and INERTIAL (§4.6)
 *   <li>T — target selected body
 *   <li>Space — pause/resume simulation
 *   <li>{@code [} / {@code ]} — decrease / increase time rate
 *   <li>Left click on body — select body
 *   <li>Double-click on body — focus body
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

    /**
     * SimulationCommands for keyboard shortcuts, mouse picking, and delegated navigation. Set via
     * {@link #setSimulationCommands}.
     */
    private SimulationCommands commands;

    /** Frustum layer root nodes for mouse pick ray collision. Set via {@link #setPickNodes}. */
    private Node[] pickNodes;

    // Mouse button drag state — set/cleared by RawInputListener.onMouseButtonEvent
    private boolean leftDragging = false;
    private boolean rightDragging = false;

    // Mouse click detection — distinguishes clicks from drags, detects double-clicks
    private float mouseDownX = -1;
    private float mouseDownY = -1;
    private long lastClickTimeNanos = 0;
    private int lastClickNaifId = -1;

    /**
     * Set to {@code true} whenever a navigation action fires (mouse drag, scroll, keyboard). Reset by
     * {@link #consumeManualNavigation()}. Used by {@code KepplrApp.simpleUpdate()} to cancel any active camera
     * transition when the user takes manual control (Step 18).
     */
    private boolean manualNavigationThisFrame = false;

    // Keyboard shift modifier
    private boolean shiftDown = false;

    // Accumulated mouse deltas from RawInputListener.onMouseMotionEvent, flushed in update()
    private int rawMouseDx = 0;
    private int rawMouseDy = 0;

    // Focus-tracking state — updated every frame to keep camera centred on the focus body (§4.5)
    private int prevFocusId = -1;
    private double[] prevFocusPos = null;

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
     * Set the simulation commands interface for keyboard shortcuts, mouse picking, and delegated navigation.
     *
     * @param commands the commands interface; null disables shortcut/pick/delegation handling
     */
    public void setSimulationCommands(SimulationCommands commands) {
        this.commands = commands;
    }

    /**
     * Set the frustum layer root nodes for mouse pick ray collision.
     *
     * @param nearNode near frustum root node
     * @param midNode mid frustum root node
     * @param farNode far frustum root node
     */
    public void setPickNodes(Node nearNode, Node midNode, Node farNode) {
        this.pickNodes = new Node[] {nearNode, midNode, farNode};
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

        // ActionListener only for shift modifier (toggle state)
        inputManager.addListener((ActionListener) this, SHIFT_LEFT, SHIFT_RIGHT);

        // AnalogListener for scroll and all keyboard navigation (fires every frame while held)
        inputManager.addListener(
                (AnalogListener) this,
                SCROLL_UP,
                SCROLL_DOWN,
                TILT_UP,
                TILT_DOWN,
                ROLL_LEFT,
                ROLL_RIGHT,
                ORBIT_UP,
                ORBIT_DOWN,
                ORBIT_LEFT,
                ORBIT_RIGHT,
                ZOOM_IN,
                ZOOM_OUT);

        // Raw mouse listener: provides raw pixel deltas and button events regardless of cursor mode
        inputManager.addRawInputListener(this);

        logger.debug("CameraInputHandler registered");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ActionListener — shift modifier only
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (name.equals(SHIFT_LEFT) || name.equals(SHIFT_RIGHT)) {
            shiftDown = isPressed;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AnalogListener — scroll wheel and all keyboard navigation
    //
    // For keyboard keys, JME fires onAnalog every frame while the key is held
    // with value ≈ tpf (seconds since last frame).  Multiplying by a per-second
    // rate gives smooth, frame-rate-independent motion.
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onAnalog(String name, float value, float tpf) {
        if (commands == null) return;

        switch (name) {
            case SCROLL_UP -> {
                commands.zoom(KepplrConstants.CAMERA_ZOOM_FACTOR_PER_STEP, 0);
                manualNavigationThisFrame = true;
            }
            case SCROLL_DOWN -> {
                commands.zoom(1.0 / KepplrConstants.CAMERA_ZOOM_FACTOR_PER_STEP, 0);
                manualNavigationThisFrame = true;
            }

            case TILT_UP -> {
                if (!shiftDown) {
                    double degrees = Math.toDegrees(value * KepplrConstants.CAMERA_KEYBOARD_ROTATE_RATE_RAD_PER_SEC);
                    commands.tilt(degrees, 0);
                    manualNavigationThisFrame = true;
                }
            }
            case TILT_DOWN -> {
                if (!shiftDown) {
                    double degrees = -Math.toDegrees(value * KepplrConstants.CAMERA_KEYBOARD_ROTATE_RATE_RAD_PER_SEC);
                    commands.tilt(degrees, 0);
                    manualNavigationThisFrame = true;
                }
            }
            case ROLL_LEFT -> {
                if (!shiftDown) {
                    double degrees = -Math.toDegrees(value * KepplrConstants.CAMERA_KEYBOARD_ROTATE_RATE_RAD_PER_SEC);
                    commands.roll(degrees, 0);
                    manualNavigationThisFrame = true;
                }
            }
            case ROLL_RIGHT -> {
                if (!shiftDown) {
                    double degrees = Math.toDegrees(value * KepplrConstants.CAMERA_KEYBOARD_ROTATE_RATE_RAD_PER_SEC);
                    commands.roll(degrees, 0);
                    manualNavigationThisFrame = true;
                }
            }

            case ORBIT_UP -> {
                if (shiftDown) {
                    double upDeg = Math.toDegrees(value * KepplrConstants.CAMERA_KEYBOARD_ORBIT_RATE_RAD_PER_SEC);
                    commands.orbit(0, upDeg, 0);
                    manualNavigationThisFrame = true;
                }
            }
            case ORBIT_DOWN -> {
                if (shiftDown) {
                    double upDeg = -Math.toDegrees(value * KepplrConstants.CAMERA_KEYBOARD_ORBIT_RATE_RAD_PER_SEC);
                    commands.orbit(0, upDeg, 0);
                    manualNavigationThisFrame = true;
                }
            }
            case ORBIT_LEFT -> {
                if (shiftDown) {
                    double rightDeg = Math.toDegrees(value * KepplrConstants.CAMERA_KEYBOARD_ORBIT_RATE_RAD_PER_SEC);
                    commands.orbit(rightDeg, 0, 0);
                    manualNavigationThisFrame = true;
                }
            }
            case ORBIT_RIGHT -> {
                if (shiftDown) {
                    double rightDeg = -Math.toDegrees(value * KepplrConstants.CAMERA_KEYBOARD_ORBIT_RATE_RAD_PER_SEC);
                    commands.orbit(rightDeg, 0, 0);
                    manualNavigationThisFrame = true;
                }
            }

            case ZOOM_IN -> {
                double steps = value * KepplrConstants.CAMERA_KEYBOARD_ZOOM_RATE_STEPS_PER_SEC;
                commands.zoom(Math.pow(KepplrConstants.CAMERA_ZOOM_FACTOR_PER_STEP, steps), 0);
                manualNavigationThisFrame = true;
            }
            case ZOOM_OUT -> {
                double steps = -value * KepplrConstants.CAMERA_KEYBOARD_ZOOM_RATE_STEPS_PER_SEC;
                commands.zoom(Math.pow(KepplrConstants.CAMERA_ZOOM_FACTOR_PER_STEP, steps), 0);
                manualNavigationThisFrame = true;
            }
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
        if (evt.getButtonIndex() == MouseInput.BUTTON_LEFT) {
            if (evt.isPressed()) {
                leftDragging = true;
                mouseDownX = evt.getX();
                mouseDownY = evt.getY();
            } else {
                leftDragging = false;
                // Check if this was a click (not a drag) based on distance moved
                float dx = evt.getX() - mouseDownX;
                float dy = evt.getY() - mouseDownY;
                if (Math.sqrt(dx * dx + dy * dy) < KepplrConstants.MOUSE_CLICK_DRAG_THRESHOLD_PX) {
                    handleClick(evt.getX(), evt.getY());
                }
            }
        } else if (evt.getButtonIndex() == MouseInput.BUTTON_RIGHT) {
            rightDragging = evt.isPressed();
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
    public void onKeyEvent(KeyInputEvent evt) {
        if (!evt.isPressed() || commands == null) return;

        switch (evt.getKeyCode()) {
            case KeyInput.KEY_SPACE ->
                commands.setPaused(!state.pausedProperty().get());
            case KeyInput.KEY_LBRACKET -> {
                // [ — decrease time rate
                double currentRate = state.timeRateProperty().get();
                commands.setTimeRate(currentRate / KepplrConstants.TIME_RATE_KEYBOARD_FACTOR);
            }
            case KeyInput.KEY_RBRACKET -> {
                // ] — increase time rate
                double currentRate = state.timeRateProperty().get();
                commands.setTimeRate(currentRate * KepplrConstants.TIME_RATE_KEYBOARD_FACTOR);
            }
            default -> {}
        }
    }

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
        applyFocusTracking();

        if (rawMouseDx == 0 && rawMouseDy == 0) return;

        int dx = rawMouseDx;
        int dy = rawMouseDy;
        rawMouseDx = 0;
        rawMouseDy = 0;

        if (commands == null) return;

        if (leftDragging) {
            // Left drag: tilt + yaw in place.  dx → yaw (around screenUp), dy → tilt (around screenRight)
            double yawDeg = Math.toDegrees(dx * KepplrConstants.CAMERA_MOUSE_ROTATE_SENSITIVITY);
            double tiltDeg = Math.toDegrees(-(dy * KepplrConstants.CAMERA_MOUSE_ROTATE_SENSITIVITY));
            commands.tilt(tiltDeg, 0);
            commands.yaw(yawDeg, 0);
            manualNavigationThisFrame = true;
        } else if (rightDragging) {
            // Right drag: orbit around focus.  dx → orbit around screenUp, dy → around screenRight
            double rightDeg = Math.toDegrees(-(dx * KepplrConstants.CAMERA_MOUSE_ORBIT_SENSITIVITY));
            double upDeg = Math.toDegrees(dy * KepplrConstants.CAMERA_MOUSE_ORBIT_SENSITIVITY);
            commands.orbit(rightDeg, upDeg, 0);
            manualNavigationThisFrame = true;
        }
    }

    /**
     * Resets the focus-tracking anchor so that {@link #applyFocusTracking()} skips the delta on the next frame.
     *
     * <p>Called from {@code KepplrApp.simpleUpdate()} immediately after consuming a {@code PendingCameraRestore}: the
     * ET jump between the previous frame and the restored ET would otherwise cause {@code applyFocusTracking()} to
     * apply a large spurious displacement (the focus body's motion over the elapsed simulation time) to the freshly
     * restored camera position.
     */
    public void resetFocusTrackingAnchor() {
        prevFocusPos = null;
    }

    /**
     * Returns {@code true} if any manual navigation action fired since the last call to this method, then resets the
     * flag to {@code false}.
     *
     * <p>Called from {@code KepplrApp.simpleUpdate()} after {@link #update()} to detect whether the user has taken
     * manual control and any active camera transition should be cancelled (Step 18).
     */
    public boolean consumeManualNavigation() {
        boolean result = manualNavigationThisFrame;
        manualNavigationThisFrame = false;
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mouse picking — ray cast against visible body nodes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Handle a left-click at the given screen coordinates. Casts a pick ray against the frustum layer nodes to find a
     * body. Single click → selectBody; double-click → focusBody.
     */
    private void handleClick(float screenX, float screenY) {
        if (commands == null) return;

        int hitNaifId = pickBody(screenX, screenY);
        if (hitNaifId == -1) return;

        long now = System.nanoTime();
        if (lastClickNaifId == hitNaifId
                && (now - lastClickTimeNanos) < KepplrConstants.MOUSE_DOUBLE_CLICK_THRESHOLD_NS) {
            // Double-click on same body → focus
            commands.focusBody(hitNaifId);
            lastClickTimeNanos = 0;
            lastClickNaifId = -1;
        } else {
            // Single click → select
            commands.selectBody(hitNaifId);
            lastClickTimeNanos = now;
            lastClickNaifId = hitNaifId;
        }
    }

    /**
     * Screen-space pick: project every visible body to screen space, find candidates within their effective pick
     * radius, and return the one with the largest actual screen radius.
     *
     * <ol>
     *   <li>Project each body center to screen space; compute actual screen radius from body radius and distance.
     *   <li>Effective pick radius = {@code max(actualScreenRadius, PICK_MIN_SCREEN_RADIUS_PX)}.
     *   <li>A body is a candidate if {@code screenDist ≤ effectivePickRadius}.
     *   <li>Among candidates, the one with the largest actual screen radius wins — this correctly selects Jupiter over
     *       a nearby satellite when both are candidates.
     *   <li>If no candidates exist, returns -1 (caller does nothing).
     * </ol>
     *
     * @return the NAIF ID of the best candidate body, or -1 if no body was hit
     */
    private int pickBody(float screenX, float screenY) {
        if (pickNodes == null) return -1;

        int bestNaifId = -1;
        double bestApparentPx = -1;

        int viewportHeight = cam.getHeight();
        double tanHalfFov = Math.tan(Math.toRadians(cam.getFov()) / 2.0);
        double halfHeight = viewportHeight / 2.0;

        for (Node layerNode : pickNodes) {
            if (layerNode == null) continue;
            for (com.jme3.scene.Spatial child : layerNode.getChildren()) {
                Integer naifId = child.getUserData("naifId");
                if (naifId == null) continue;
                Double bodyRadiusKm = child.getUserData("bodyRadiusKm");
                if (bodyRadiusKm == null) bodyRadiusKm = 0.0;

                // Step 1: project body center to screen space
                Vector3f worldPos = child.getWorldTranslation();
                // Behind-camera check: use dot product with camera direction, NOT screen.z.
                // In multi-frustum, cam is the far camera — its clip-space z is invalid for
                // bodies in the near/mid layers (closer than the far camera's near plane).
                // Screen X,Y from getScreenCoordinates are still valid for direction-based projection.
                if (cam.getDirection().dot(worldPos) <= 0f) {
                    continue;
                }
                Vector3f screen = cam.getScreenCoordinates(worldPos);

                // Step 1: compute actual screen radius in pixels
                double dist = worldPos.length();
                double actualScreenRadius =
                        (dist > 0 && bodyRadiusKm > 0) ? (bodyRadiusKm / dist) * halfHeight / tanHalfFov : 0.0;

                // Step 2: effective pick radius — actual size, but at least PICK_MIN_SCREEN_RADIUS_PX
                double effectivePickRadius = Math.max(actualScreenRadius, KepplrConstants.PICK_MIN_SCREEN_RADIUS_PX);

                // Step 3: screen-space distance from click to body center
                double ddx = screenX - screen.x;
                double ddy = screenY - screen.y;
                double screenDist = Math.sqrt(ddx * ddx + ddy * ddy);

                if (screenDist > effectivePickRadius) continue;

                // Step 4: among candidates, largest actual screen radius wins
                if (actualScreenRadius > bestApparentPx) {
                    bestApparentPx = actualScreenRadius;
                    bestNaifId = naifId;
                }
            }
        }
        return bestNaifId;
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

    /**
     * Apply per-frame focus tracking (§4.5): shift {@code cameraHelioJ2000} by the delta between the focus body's
     * current and previous heliocentric position so the camera stays centred on the focus body as it moves along its
     * orbit.
     *
     * <p>Resets the tracking anchor when the focus body changes or is cleared.
     */
    private void applyFocusTracking() {
        int focusId = state.focusedBodyIdProperty().get();

        if (focusId != prevFocusId) {
            // Focus body changed (or was just set / cleared) — reset anchor
            prevFocusId = focusId;
            prevFocusPos = null;
        }

        if (focusId == -1) return;

        double[] currentPos = getFocusBodyPos(focusId);
        if (currentPos == null) {
            prevFocusPos = null;
            return;
        }

        if (prevFocusPos != null) {
            double ddx = currentPos[0] - prevFocusPos[0];
            double ddy = currentPos[1] - prevFocusPos[1];
            double ddz = currentPos[2] - prevFocusPos[2];
            cameraHelioJ2000[0] += ddx;
            cameraHelioJ2000[1] += ddy;
            cameraHelioJ2000[2] += ddz;
            state.setCameraPositionJ2000(cameraHelioJ2000);
        }

        prevFocusPos = currentPos;
    }
}
