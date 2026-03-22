package kepplr.camera;

import static org.junit.jupiter.api.Assertions.*;

import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.state.DefaultSimulationState;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.VectorIJK;

/**
 * Unit tests for Step 24: cinematic camera commands (truck/crane/dolly) and smoothstep easing.
 *
 * <p>Uses the test SPICE kernel. JME math classes work headlessly.
 */
@DisplayName("CinematicTransition (Step 24)")
class CinematicTransitionTest {

    private static final int EARTH = 399;

    private DefaultSimulationState state;
    private TransitionController controller;
    private Camera cam;
    private double[] camPos;

    @BeforeEach
    void setUp() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTestTemplate();
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        double testEt = TestHarness.getTestEpoch();

        state = new DefaultSimulationState();
        state.setCurrentEt(testEt);
        state.setFocusedBodyId(EARTH);
        controller = new TransitionController(state);

        cam = new Camera(800, 600);
        cam.setFrustumPerspective(45f, 800f / 600f, 0.001f, 1e15f);
        cam.setLocation(Vector3f.ZERO);
        cam.lookAt(new Vector3f(1f, 0f, 0f), Vector3f.UNIT_Y);

        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        camPos = new double[] {earthPos.getI(), earthPos.getJ(), earthPos.getK() + 15_000.0};
    }

    // ── Smoothstep easing ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Smoothstep easing function")
    class Smoothstep {

        @Test
        @DisplayName("smoothstep(0) = 0")
        void atZero() {
            assertEquals(0.0, TransitionController.smoothstep(0.0), 1e-15);
        }

        @Test
        @DisplayName("smoothstep(0.5) = 0.5")
        void atHalf() {
            assertEquals(0.5, TransitionController.smoothstep(0.5), 1e-15);
        }

        @Test
        @DisplayName("smoothstep(1) = 1")
        void atOne() {
            assertEquals(1.0, TransitionController.smoothstep(1.0), 1e-15);
        }

        @Test
        @DisplayName("smoothstep(0.25) < 0.25 (ease-in)")
        void easeIn() {
            double result = TransitionController.smoothstep(0.25);
            assertTrue(result < 0.25, "smoothstep(0.25) should be < 0.25 (ease-in): " + result);
            assertTrue(result > 0.0, "smoothstep(0.25) should be > 0: " + result);
        }

        @Test
        @DisplayName("smoothstep(0.75) > 0.75 (ease-out)")
        void easeOut() {
            double result = TransitionController.smoothstep(0.75);
            assertTrue(result > 0.75, "smoothstep(0.75) should be > 0.75 (ease-out): " + result);
            assertTrue(result < 1.0, "smoothstep(0.75) should be < 1.0: " + result);
        }
    }

    // ── Truck ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Truck (screen-right translation)")
    class Truck {

        @Test
        @DisplayName("Instant truck translates camera immediately")
        void instantTruck() {
            double[] startPos = camPos.clone();
            controller.requestTruck(1000.0, 0.0);
            controller.update(0.016f, cam, camPos);

            double dx = camPos[0] - startPos[0];
            double dy = camPos[1] - startPos[1];
            double dz = camPos[2] - startPos[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(1000.0, dist, 0.1, "Instant truck should translate exactly 1000 km");
        }

        @Test
        @DisplayName("Timed truck is non-blocking (position changes gradually)")
        void timedTruckNonBlocking() {
            double[] startPos = camPos.clone();
            controller.requestTruck(1000.0, 2.0);

            // First frame: advance 0.5s of 2.0s duration
            controller.update(0.5f, cam, camPos);

            // Should have moved partially but not fully
            double dx = camPos[0] - startPos[0];
            double dy = camPos[1] - startPos[1];
            double dz = camPos[2] - startPos[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertTrue(dist > 0.0, "Should have moved");
            assertTrue(dist < 1000.0, "Should not have reached end yet");
            assertTrue(controller.isActive(), "Transition should still be active");
        }

        @Test
        @DisplayName("Truck does not modify camera orientation")
        void truckPreservesOrientation() {
            float[] startAxes = cam.getRotation().toAngles(null);
            controller.requestTruck(1000.0, 0.0);
            controller.update(0.016f, cam, camPos);
            float[] endAxes = cam.getRotation().toAngles(null);

            assertArrayEquals(startAxes, endAxes, 1e-6f, "Orientation should be unchanged");
        }
    }

    // ── Crane ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Crane (screen-up translation)")
    class Crane {

        @Test
        @DisplayName("Instant crane translates camera immediately")
        void instantCrane() {
            double[] startPos = camPos.clone();
            controller.requestCrane(500.0, 0.0);
            controller.update(0.016f, cam, camPos);

            double dx = camPos[0] - startPos[0];
            double dy = camPos[1] - startPos[1];
            double dz = camPos[2] - startPos[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(500.0, dist, 0.1, "Instant crane should translate exactly 500 km");
        }
    }

    // ── Dolly ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Dolly (look-direction translation)")
    class Dolly {

        @Test
        @DisplayName("Instant dolly translates camera along look direction")
        void instantDolly() {
            double[] startPos = camPos.clone();
            controller.requestDolly(2000.0, 0.0);
            controller.update(0.016f, cam, camPos);

            double dx = camPos[0] - startPos[0];
            double dy = camPos[1] - startPos[1];
            double dz = camPos[2] - startPos[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(2000.0, dist, 0.1, "Instant dolly should translate exactly 2000 km");
        }

        @Test
        @DisplayName("Negative dolly moves camera backward")
        void negativeDolly() {
            // Camera looks along +X initially
            double startX = camPos[0];
            controller.requestDolly(-500.0, 0.0);
            controller.update(0.016f, cam, camPos);

            assertTrue(camPos[0] < startX, "Negative dolly should move camera in -X direction");
        }
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Transition cancellation")
    class Cancellation {

        @Test
        @DisplayName("New cinematic command cancels in-progress transition")
        void newCommandCancelsPrevious() {
            controller.requestTruck(1000.0, 5.0);
            controller.update(0.5f, cam, camPos); // advance partially
            assertTrue(controller.isActive());

            // Issue a new crane — should cancel the truck
            controller.requestCrane(500.0, 0.0);
            controller.update(0.016f, cam, camPos);

            // Transition should have completed (instant crane)
            assertFalse(controller.isActive(), "Instant crane should complete immediately");
        }
    }
}
