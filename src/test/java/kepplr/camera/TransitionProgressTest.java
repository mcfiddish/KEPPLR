package kepplr.camera;

import static org.junit.jupiter.api.Assertions.*;

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
 * Unit tests for transition state properties on {@link DefaultSimulationState} as advanced by
 * {@link TransitionController} (Step 18).
 *
 * <p>Uses the test SPICE kernel. Epoch: 2015 Jul 14 07:59:00 UTC.
 */
@DisplayName("TransitionProgress")
class TransitionProgressTest {

    private static final int EARTH = 399;

    private DefaultSimulationState state;
    private TransitionController controller;
    private Camera cam;
    private double[] camPos;
    private double testEt;
    private KEPPLREphemeris eph;

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
        cam.setLocation(Vector3f.ZERO);
        // Look along +X initially (not toward Earth)
        cam.lookAt(new Vector3f(1f, 0f, 0f), Vector3f.UNIT_Y);

        // Camera 50 000 km from Earth in +Z (well outside Earth's radius)
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        assertNotNull(earthPos, "Earth position must be available at test epoch");
        camPos = new double[] {earthPos.getI(), earthPos.getJ(), earthPos.getK() + 50_000.0};
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. transitionActiveProperty is true while active, false after completion
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("transitionActiveProperty is true while transition runs and false after it completes")
    void transitionActiveFollowsTransitionLifecycle() {
        // Before any transition
        assertFalse(state.transitionActiveProperty().get(), "Should be inactive before any transition");
        assertEquals(
                0.0, state.transitionProgressProperty().get(), 1e-9, "Progress should be 0.0 before any transition");

        // Start a 2-second slew
        controller.requestPointAt(EARTH, 2.0);
        controller.update(0.0f, cam, camPos); // processes request, creates transition

        assertTrue(state.transitionActiveProperty().get(), "Should be active immediately after creation");

        // Advance 1 second (half elapsed, not complete)
        controller.update(1.0f, cam, camPos);
        assertTrue(state.transitionActiveProperty().get(), "Should still be active at t=0.5");

        // Advance another 2 seconds (total > duration — transition completes)
        controller.update(2.0f, cam, camPos);
        assertFalse(state.transitionActiveProperty().get(), "Should be inactive after transition completes");
        assertEquals(
                0.0, state.transitionProgressProperty().get(), 1e-9, "Progress should reset to 0.0 after completion");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. transitionProgressProperty increases monotonically from 0 to 1
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("transitionProgressProperty increases monotonically from 0.0 to 1.0 across update() calls")
    void transitionProgressIsMonotonic() {
        controller.requestPointAt(EARTH, 4.0);
        controller.update(0.0f, cam, camPos); // creates transition, t = 0

        double prevProgress = state.transitionProgressProperty().get();
        assertEquals(0.0, prevProgress, 1e-9, "Progress should start at 0.0");

        // Advance in 0.5-second increments: 8 updates to cover 4 seconds
        for (int i = 0; i < 8; i++) {
            controller.update(0.5f, cam, camPos);
            double progress = state.transitionProgressProperty().get();

            if (state.transitionActiveProperty().get()) {
                // Still in progress: progress must be >= previous
                assertTrue(
                        progress >= prevProgress,
                        "Progress should be non-decreasing; prev=" + prevProgress + " current=" + progress);
                assertTrue(progress <= 1.0 + 1e-9, "Progress must not exceed 1.0; got " + progress);
                prevProgress = progress;
            }
        }

        // After 4 seconds, transition should be complete
        assertFalse(state.transitionActiveProperty().get(), "Transition should have completed");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Pending goTo begins immediately after pointAt completes
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("goTo begins immediately after pointAt completes when focusBody sequence is used")
    void goToStartsAfterPointAtCompletes() {
        // Set Earth as focus body (required for goTo body-position lookup to use correct naif)
        state.setFocusedBodyId(EARTH);

        // Compute Earth's mean radius for expected end distance
        var earthId = eph.getSpiceBundle().getObject(EARTH);
        var earthShape = eph.getShape(earthId);
        double meanRadius = (earthShape.getA() + earthShape.getB() + earthShape.getC()) / 3.0;
        double apparentRadiusDeg = KepplrConstants.DEFAULT_GOTO_APPARENT_RADIUS_DEG; // 10°
        double expectedEndDist = meanRadius / Math.tan(Math.toRadians(apparentRadiusDeg));

        // Simulate focusBody: enqueue pointAt then goTo
        controller.requestPointAt(EARTH, KepplrConstants.DEFAULT_SLEW_DURATION_SECONDS); // 3 s
        controller.requestGoTo(EARTH, apparentRadiusDeg, KepplrConstants.DEFAULT_GOTO_DURATION_SECONDS); // 3 s

        // First update: drains inbox, creates POINT_AT, queues GO_TO as pending
        controller.update(0.0f, cam, camPos);
        assertTrue(state.transitionActiveProperty().get(), "Should be active during pointAt");

        // Run pointAt to completion (3 seconds)
        controller.update(3.0f, cam, camPos);

        // After pointAt completes, goTo should have started automatically
        assertTrue(
                state.transitionActiveProperty().get(),
                "goTo should have started immediately after pointAt completed — transitionActive should be true");

        // Run goTo to completion (3 seconds)
        controller.update(3.0f, cam, camPos);

        assertFalse(state.transitionActiveProperty().get(), "Both transitions should be complete");

        // Verify camera is at expected end distance from Earth
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        double actualDist = Math.sqrt(Math.pow(camPos[0] - earthPos.getI(), 2)
                + Math.pow(camPos[1] - earthPos.getJ(), 2)
                + Math.pow(camPos[2] - earthPos.getK(), 2));

        assertEquals(
                expectedEndDist,
                actualDist,
                expectedEndDist * 0.02,
                "Camera should be at goTo end distance from Earth after both transitions complete");
    }
}
