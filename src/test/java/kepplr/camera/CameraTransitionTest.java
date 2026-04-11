package kepplr.camera;

import static org.junit.jupiter.api.Assertions.*;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.state.DefaultSimulationState;
import kepplr.testsupport.TestHarness;
import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.VectorIJK;

/**
 * Unit tests for {@link TransitionController} and {@link CameraTransition} (Step 18).
 *
 * <p>Uses the test SPICE kernel (Earth and Moon). Epoch: 2015 Jul 14 07:59:00 UTC. JME math classes ({@link Camera},
 * {@link Quaternion}) work headlessly — no GL context required.
 */
@DisplayName("CameraTransition")
class CameraTransitionTest {

    private static final int EARTH = 399;
    private static final int MOON = 301;
    private static final int INVALID_NAIF = -999999;

    private DefaultSimulationState state;
    private TransitionController controller;
    private Camera cam;
    private double[] camPos;
    private KEPPLREphemeris eph;
    private double testEt;

    @BeforeEach
    void setUp() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTestTemplate();
        eph = KEPPLRConfiguration.getInstance().getEphemeris();
        testEt = TestHarness.getTestEpoch();

        state = new DefaultSimulationState();
        state.setCurrentEt(testEt);
        controller = new TransitionController(state);

        cam = new Camera(800, 600);
        cam.setFrustumPerspective(45f, 800f / 600f, 0.001f, 1e15f);
        cam.setLocation(Vector3f.ZERO);
        // Look along +X initially (away from Earth which is roughly in the ecliptic plane)
        cam.lookAt(new Vector3f(1f, 0f, 0f), Vector3f.UNIT_Y);

        // Place camera 15 000 km from Earth in J2000 +Z
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        assertNotNull(earthPos, "Earth position must be available at test epoch");
        camPos = new double[] {earthPos.getI(), earthPos.getJ(), earthPos.getK() + 15_000.0};
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Zero-duration POINT_AT completes on first update()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POINT_AT with zero duration snaps camera to end orientation on first update()")
    void pointAtZeroDurationSnapsOnFirstUpdate() {
        // Camera is looking along +X; Earth is below the camera (+Z offset), so direction to Earth ≈ -Z
        controller.requestPointAt(EARTH, KepplrConstants.CAMERA_TRANSITION_INSTANT_THRESHOLD_SEC);

        // update() drains the inbox and applies the instant snap
        boolean completed = controller.update(0.016f, cam, camPos);

        // Instant snap: no active transition (no animation started), but camera is reoriented
        assertFalse(controller.isActive(), "No transition should be active after instant snap");
        // The camera should now be looking toward Earth (approximately -Z in J2000, since Earth is below)
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        float ex = (float) (earthPos.getI() - camPos[0]);
        float ey = (float) (earthPos.getJ() - camPos[1]);
        float ez = (float) (earthPos.getK() - camPos[2]);
        Vector3f expectedDir = new Vector3f(ex, ey, ez).normalizeLocal();
        float dot = cam.getDirection().dot(expectedDir);
        assertTrue(dot > 0.999f, "Camera should be pointing at Earth after instant snap; dot=" + dot);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. POINT_AT slerp at t = 0.5 produces orientation halfway between start and end
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POINT_AT slerp at t=0.5 produces orientation halfway between start and end")
    void pointAtSlerpAtHalfwayPoint() {
        Quaternion startQ = cam.getRotation().clone();

        // Start a 2-second slew toward Earth
        controller.requestPointAt(EARTH, 2.0);

        // First update: processes inbox, creates transition, no time elapsed yet → t = 0.0
        // The cam orientation is set to slerp(start, end, 0.0) = start
        controller.update(0.0f, cam, camPos);

        // Capture end orientation by peeking at the camera if we ran it to completion instantly
        // Instead, compute it analytically
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        float dx = (float) (earthPos.getI() - camPos[0]);
        float dy = (float) (earthPos.getJ() - camPos[1]);
        float dz = (float) (earthPos.getK() - camPos[2]);
        Vector3f dir = new Vector3f(dx, dy, dz).normalizeLocal();
        Quaternion endQ = TransitionController.buildLookAtQuaternion(dir, cam.getUp());

        // Advance by 1.0 s (half of 2.0 s duration) → t = 0.5
        controller.update(1.0f, cam, camPos);

        // Expected halfway quaternion
        Quaternion expectedHalfway = new Quaternion();
        expectedHalfway.slerp(startQ, endQ, 0.5f);
        expectedHalfway.normalizeLocal();

        // Check that the camera orientation matches the expected halfway quaternion
        Quaternion actual = cam.getRotation();
        // Slerp correctness: dot of the two quaternions should be very close to 1
        // (account for possible sign flip in quaternion representation)
        float qdot = Math.abs(actual.dot(expectedHalfway));
        assertTrue(qdot > 0.999f, "Camera quaternion at t=0.5 should match slerp(start, end, 0.5); qdot=" + qdot);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. GO_TO end distance formula: endDist = bodyRadius / tan(apparentRadiusDeg)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GO_TO end distance equals bodyRadius / tan(apparentRadiusDeg) for Earth at 10 degrees")
    void goToEndDistanceFormula() {
        // Compute Earth's mean radius from ephemeris
        var earthId = eph.getSpiceBundle().getObject(EARTH);
        assertNotNull(earthId, "Earth must be defined in the test kernel");
        var earthShape = eph.getShape(earthId);
        assertNotNull(earthShape, "Earth shape must be available");
        double meanRadius = (earthShape.getA() + earthShape.getB() + earthShape.getC()) / 3.0;

        double apparentRadiusDeg = 10.0;
        double expectedEndDist = meanRadius / Math.tan(Math.toRadians(apparentRadiusDeg));

        // Start a focused sequence: point at Earth first, then goTo
        // For goTo-only testing, we start a 0-duration pointAt then the goTo
        controller.requestPointAt(EARTH, 0.0); // instant snap (clears any pending)
        controller.update(0.0f, cam, camPos); // drains inbox, applies snap

        // Now start a goTo — should start immediately (no active pointAt after instant snap)
        controller.requestGoTo(EARTH, apparentRadiusDeg, 4.0);
        controller.update(0.0f, cam, camPos); // processes goTo request, creates transition

        // Advance to completion (t = 1.0) to reach the end distance
        controller.update(4.0f, cam, camPos); // tpf = 4.0 s completes the 4-second goTo

        // Camera should now be at endDist from Earth
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        double actualDist = Math.sqrt(Math.pow(camPos[0] - earthPos.getI(), 2)
                + Math.pow(camPos[1] - earthPos.getJ(), 2)
                + Math.pow(camPos[2] - earthPos.getK(), 2));

        assertEquals(
                expectedEndDist,
                actualDist,
                expectedEndDist * 0.01,
                "Final camera distance from Earth should match endDist = bodyRadius / tan(apparentRadiusDeg)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. GO_TO linear interpolation at t = 0.5 produces midpoint distance
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GO_TO linear interpolation at t=0.5 produces midpoint distance from Earth")
    void goToLinearInterpolationAtHalfwayPoint() {
        var earthId = eph.getSpiceBundle().getObject(EARTH);
        var earthShape = eph.getShape(earthId);
        double meanRadius = (earthShape.getA() + earthShape.getB() + earthShape.getC()) / 3.0;
        double apparentRadiusDeg = 10.0;
        double endDist = meanRadius / Math.tan(Math.toRadians(apparentRadiusDeg));

        // Compute start distance
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        double startDist = Math.sqrt(Math.pow(camPos[0] - earthPos.getI(), 2)
                + Math.pow(camPos[1] - earthPos.getJ(), 2)
                + Math.pow(camPos[2] - earthPos.getK(), 2));

        // Instant pointAt to Earth so the camera is aimed correctly for goTo
        controller.requestPointAt(EARTH, 0.0);
        controller.update(0.0f, cam, camPos);

        // Start 4-second goTo
        controller.requestGoTo(EARTH, apparentRadiusDeg, 4.0);
        controller.update(0.0f, cam, camPos); // creates GO_TO transition

        // Advance 2.0 s (t = 0.5)
        controller.update(2.0f, cam, camPos);

        double midDist = Math.sqrt(Math.pow(camPos[0] - earthPos.getI(), 2)
                + Math.pow(camPos[1] - earthPos.getJ(), 2)
                + Math.pow(camPos[2] - earthPos.getK(), 2));

        double expectedMid = startDist + (endDist - startDist) * 0.5;
        assertEquals(
                expectedMid,
                midDist,
                expectedMid * 0.01,
                "At t=0.5 the camera distance should be the midpoint between start and end distances");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. New startPointAt while transition is active cancels the previous one
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Issuing a new requestPointAt while POINT_AT is active cancels the previous transition")
    void newPointAtCancelsPreviousPointAt() {
        // Start slewing toward Earth (3-second slew)
        controller.requestPointAt(EARTH, 3.0);
        controller.update(0.0f, cam, camPos); // creates POINT_AT for Earth
        assertTrue(controller.isActive(), "Transition should be active");

        // Capture direction toward Earth for verification later
        VectorIJK moonPos = eph.getHeliocentricPositionJ2000(MOON, testEt);
        assertNotNull(moonPos, "Moon position must be available at test epoch");

        // Issue a new pointAt toward Moon before Earth slew completes
        controller.requestPointAt(MOON, 3.0);
        controller.update(0.0f, cam, camPos); // cancels Earth slew, starts Moon slew

        assertTrue(controller.isActive(), "New Moon slew should be active");

        // Run the Moon slew to completion
        controller.update(3.0f, cam, camPos);

        // Camera should point toward Moon, not Earth
        float mx = (float) (moonPos.getI() - camPos[0]);
        float my = (float) (moonPos.getJ() - camPos[1]);
        float mz = (float) (moonPos.getK() - camPos[2]);
        Vector3f moonDir = new Vector3f(mx, my, mz).normalizeLocal();
        float dotMoon = cam.getDirection().dot(moonDir);
        assertTrue(dotMoon > 0.99f, "Camera should be pointing at Moon after second slew; dot=" + dotMoon);

        assertFalse(controller.isActive(), "No transition should be active after Moon slew completes");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Target body with no valid ephemeris does not throw and leaves isActive() false
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POINT_AT to a body with no ephemeris does not throw and leaves no active transition")
    void invalidBodyDoesNotThrowAndLeavesNoTransition() {
        assertDoesNotThrow(
                () -> {
                    controller.requestPointAt(INVALID_NAIF, 3.0);
                    controller.update(0.016f, cam, camPos);
                },
                "requestPointAt with invalid NAIF must not throw");

        assertFalse(controller.isActive(), "No transition should be active for invalid NAIF");
        assertFalse(state.transitionActiveProperty().get(), "transitionActive state should be false");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 19c: Camera scripting transitions
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ZOOM with zero duration snaps camera to new distance from focused body")
    void zoomInstantSnap() {
        state.setFocusedBodyId(EARTH);
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        double startDist = Math.sqrt(Math.pow(camPos[0] - earthPos.getI(), 2)
                + Math.pow(camPos[1] - earthPos.getJ(), 2)
                + Math.pow(camPos[2] - earthPos.getK(), 2));

        controller.requestZoom(0.5, 0); // halve distance
        controller.update(0.016f, cam, camPos);

        double endDist = Math.sqrt(Math.pow(camPos[0] - earthPos.getI(), 2)
                + Math.pow(camPos[1] - earthPos.getJ(), 2)
                + Math.pow(camPos[2] - earthPos.getK(), 2));

        assertEquals(startDist * 0.5, endDist, startDist * 0.02, "After zoom(0.5, 0), distance should be halved");
        assertFalse(controller.isActive(), "No transition should be active after instant zoom");
    }

    @Test
    @DisplayName("ZOOM with no focus body is a no-op")
    void zoomNoFocusIsNoOp() {
        double[] before = camPos.clone();
        controller.requestZoom(0.5, 0);
        controller.update(0.016f, cam, camPos);

        assertArrayEquals(before, camPos, 1e-10, "Camera position should not change when no focus body");
    }

    @Test
    @DisplayName("TILT with zero duration rotates camera pitch")
    void tiltInstantSnap() {
        Quaternion before = cam.getRotation().clone();
        controller.requestTilt(10.0, 0);
        controller.update(0.016f, cam, camPos);

        Quaternion after = cam.getRotation();
        float dot = Math.abs(before.dot(after));
        assertTrue(dot < 0.999f, "Camera orientation should change after tilt; dot=" + dot);
    }

    @Test
    @DisplayName("YAW with zero duration rotates camera yaw")
    void yawInstantSnap() {
        Quaternion before = cam.getRotation().clone();
        controller.requestYaw(10.0, 0);
        controller.update(0.016f, cam, camPos);

        Quaternion after = cam.getRotation();
        float dot = Math.abs(before.dot(after));
        assertTrue(dot < 0.999f, "Camera orientation should change after yaw; dot=" + dot);
    }

    @Test
    @DisplayName("ROLL with zero duration rotates camera roll")
    void rollInstantSnap() {
        Quaternion before = cam.getRotation().clone();
        controller.requestRoll(45.0, 0);
        controller.update(0.016f, cam, camPos);

        Quaternion after = cam.getRotation();
        float dot = Math.abs(before.dot(after));
        assertTrue(dot < 0.999f, "Camera orientation should change after roll; dot=" + dot);
    }

    @Test
    @DisplayName("ORBIT with zero duration moves camera around focused body")
    void orbitInstantSnap() {
        state.setFocusedBodyId(EARTH);
        double[] before = camPos.clone();

        controller.requestOrbit(45.0, 0.0, 0);
        controller.update(0.016f, cam, camPos);

        // Position should have changed
        boolean moved = Math.abs(camPos[0] - before[0]) > 1e-6
                || Math.abs(camPos[1] - before[1]) > 1e-6
                || Math.abs(camPos[2] - before[2]) > 1e-6;
        assertTrue(moved, "Camera position should change after orbit");

        // Distance from Earth should be preserved
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        double distBefore = Math.sqrt(Math.pow(before[0] - earthPos.getI(), 2)
                + Math.pow(before[1] - earthPos.getJ(), 2)
                + Math.pow(before[2] - earthPos.getK(), 2));
        double distAfter = Math.sqrt(Math.pow(camPos[0] - earthPos.getI(), 2)
                + Math.pow(camPos[1] - earthPos.getJ(), 2)
                + Math.pow(camPos[2] - earthPos.getK(), 2));
        assertEquals(
                distBefore, distAfter, distBefore * 0.001, "Distance from focus body should be preserved during orbit");
    }

    @Test
    @DisplayName("CAMERA_POSITION with zero duration moves camera to explicit offset from origin body")
    void cameraPositionInstantSnap() {
        state.setFocusedBodyId(EARTH);
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);

        controller.requestCameraPosition(0, 0, 50000, EARTH, 0);
        controller.update(0.016f, cam, camPos);

        assertEquals(earthPos.getI(), camPos[0], 1.0, "Camera X should be at Earth X");
        assertEquals(earthPos.getJ(), camPos[1], 1.0, "Camera Y should be at Earth Y");
        assertEquals(earthPos.getK() + 50000.0, camPos[2], 1.0, "Camera Z should be 50000 km above Earth");
    }

    @Test
    @DisplayName("CAMERA_LOOK_DIRECTION with zero duration snaps camera orientation")
    void cameraLookDirectionInstantSnap() {
        controller.requestCameraOrientation(0, 0, 1, 0, 1, 0, 0); // look +Z, up +Y
        controller.update(0.016f, cam, camPos);

        Vector3f dir = cam.getDirection();
        assertTrue(dir.z > 0.99f, "Camera should look along +Z; dir.z=" + dir.z);
    }

    @Test
    @DisplayName("CAMERA_POSE with zero duration moves camera and snaps orientation")
    void cameraPoseInstantSnap() {
        state.setFocusedBodyId(EARTH);
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);

        controller.requestCameraPose(0, 0, 50000, EARTH, 0, 0, 1, 0, 1, 0, 0);
        controller.update(0.016f, cam, camPos);

        assertEquals(earthPos.getI(), camPos[0], 1.0, "Camera X should be at Earth X");
        assertEquals(earthPos.getJ(), camPos[1], 1.0, "Camera Y should be at Earth Y");
        assertEquals(earthPos.getK() + 50000.0, camPos[2], 1.0, "Camera Z should be 50000 km above Earth");

        Vector3f dir = cam.getDirection();
        assertTrue(dir.z > 0.99f, "Camera should look along +Z; dir.z=" + dir.z);
        assertFalse(controller.isActive(), "Instant pose should not leave an active transition");
    }

    @Test
    @DisplayName("Animated CAMERA_POSE moves and rotates as one transition")
    void cameraPoseAnimatedTransition() {
        state.setFocusedBodyId(EARTH);
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        double[] before = camPos.clone();

        controller.requestCameraPose(0, 0, 50000, EARTH, 0, 0, 1, 0, 1, 0, 2.0);
        controller.update(0.0f, cam, camPos);
        assertTrue(controller.isActive(), "Pose transition should be active");

        controller.update(1.0f, cam, camPos);
        assertTrue(controller.isActive(), "Pose transition should still be active halfway");
        boolean movedHalfway = Math.abs(camPos[2] - before[2]) > 1e-6;
        assertTrue(movedHalfway, "Camera position should change during pose transition");

        controller.update(1.0f, cam, camPos);
        assertFalse(controller.isActive(), "Pose transition should complete");
        assertEquals(earthPos.getK() + 50000.0, camPos[2], 1.0, "Camera Z should reach target");
        assertTrue(cam.getDirection().z > 0.99f, "Camera should reach target look direction");
    }

    @Test
    @DisplayName("Animated TILT transition interpolates over duration")
    void tiltAnimatedTransition() {
        Quaternion before = cam.getRotation().clone();

        controller.requestTilt(30.0, 2.0);
        controller.update(0.0f, cam, camPos); // creates transition
        assertTrue(controller.isActive(), "Tilt transition should be active");

        // Advance halfway
        controller.update(1.0f, cam, camPos);
        assertTrue(controller.isActive(), "Tilt transition should still be active at t=0.5");

        // Complete
        controller.update(1.0f, cam, camPos);
        assertFalse(controller.isActive(), "Tilt transition should complete at t=1.0");

        // Orientation should be different from start
        float dot = Math.abs(before.dot(cam.getRotation()));
        assertTrue(dot < 0.999f, "Camera orientation should differ after 30-degree tilt");
    }

    @Test
    @DisplayName("New camera command cancels active transition")
    void newCommandCancelsActiveTransition() {
        controller.requestTilt(30.0, 5.0);
        controller.update(0.0f, cam, camPos);
        assertTrue(controller.isActive(), "Tilt transition should be active");

        // New roll request should cancel the tilt
        controller.requestRoll(10.0, 0);
        controller.update(0.016f, cam, camPos);
        assertFalse(controller.isActive(), "Active transition should be cancelled by new request");
    }

    @Test
    @DisplayName("FOV instant snap sets camera FOV")
    void fovInstantSnap() {
        float before = cam.getFov();
        controller.requestFov(60.0, 0);
        controller.update(0.016f, cam, camPos);

        assertEquals(60.0f, cam.getFov(), 0.01f, "Camera FOV should be 60 after instant snap");
    }

    @Test
    @DisplayName("FOV is clamped to [FOV_MIN_DEG, FOV_MAX_DEG]")
    void fovIsClamped() {
        controller.requestFov(1e-9, 0);
        controller.update(0.016f, cam, camPos);
        assertEquals((float) KepplrConstants.FOV_MIN_DEG, cam.getFov(), 0.01f, "FOV should be clamped to minimum");

        controller.requestFov(200.0, 0);
        controller.update(0.016f, cam, camPos);
        assertEquals((float) KepplrConstants.FOV_MAX_DEG, cam.getFov(), 0.01f, "FOV should be clamped to maximum");
    }
}
