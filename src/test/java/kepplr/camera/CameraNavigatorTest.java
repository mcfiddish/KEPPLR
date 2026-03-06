package kepplr.camera;

import static org.junit.jupiter.api.Assertions.*;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CameraNavigator}.
 *
 * <p>JME math classes (Quaternion, Vector3f) work headlessly — no GL context required. No ephemeris is needed; all
 * inputs are geometric.
 */
@DisplayName("CameraNavigator")
class CameraNavigatorTest {

    /**
     * Distance tolerance for orbit preservation assertions. Float32 arithmetic (used internally by JME Quaternion) has
     * ~1e-7 relative error; at 50,000 km that is ~0.005 km. We allow 0.1 km to be conservative while still catching
     * gross errors.
     */
    private static final double ORBIT_DIST_TOLERANCE_KM = 0.1;

    /** Distance tolerance for zoom and pose assertions where double arithmetic is used throughout. */
    private static final double DIST_TOLERANCE_KM = 1e-6;

    /** Quaternion component tolerance for floating-point equality. */
    private static final float QUAT_TOLERANCE = 1e-5f;

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double len(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    /** Identity camera: position at +Z from origin, looking toward -Z (standard JME camera). */
    private static Quaternion identityOrientation() {
        return new Quaternion(); // identity
    }

    private static Vector3f screenRight() {
        return new Vector3f(1, 0, 0); // camera-local +X = screen right
    }

    private static Vector3f screenUp() {
        return new Vector3f(0, 1, 0); // camera-local +Y = screen up
    }

    // ─────────────────────────────────────────────────────────────────────────
    // rotateInPlace
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("rotateInPlace")
    class RotateInPlaceTests {

        @Test
        @DisplayName("Zero delta returns identity-equivalent orientation")
        void zeroDeltaReturnsUnchanged() {
            Quaternion original = identityOrientation();
            Quaternion result = CameraNavigator.rotateInPlace(original, screenRight(), screenUp(), 0f, 0f);
            assertNotNull(result);
            // Should be equal to original (or its negation — both represent the same rotation)
            float dot = Math.abs(original.dot(result));
            assertEquals(1.0f, dot, QUAT_TOLERANCE, "Zero delta should not change orientation");
        }

        @Test
        @DisplayName("Non-zero delta changes orientation")
        void nonZeroDeltaChangesOrientation() {
            Quaternion original = identityOrientation();
            Quaternion result =
                    CameraNavigator.rotateInPlace(original, screenRight(), screenUp(), (float) Math.toRadians(10), 0f);
            float dot = Math.abs(original.dot(result));
            assertTrue(dot < 1.0f, "Non-zero delta should change orientation");
        }

        @Test
        @DisplayName("Result is a unit quaternion")
        void resultIsUnitQuaternion() {
            Quaternion result = CameraNavigator.rotateInPlace(
                    identityOrientation(), screenRight(), screenUp(), (float) Math.toRadians(45), (float)
                            Math.toRadians(30));
            float norm = result.norm();
            assertEquals(1.0f, norm, QUAT_TOLERANCE, "Result must be a unit quaternion");
        }

        @Test
        @DisplayName("rotateInPlace never returns null")
        void neverReturnsNull() {
            assertNotNull(CameraNavigator.rotateInPlace(identityOrientation(), screenRight(), screenUp(), 0f, 0f));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // orbit — null focus (no-op)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("orbit — null focus")
    class OrbitNullFocusTests {

        @Test
        @DisplayName("orbit with null focus returns unchanged position")
        void nullFocusReturnsUnchangedPosition() {
            double[] camPos = {1_000.0, 2_000.0, 3_000.0};
            CameraNavigator.OrbitResult result =
                    CameraNavigator.orbit(camPos, identityOrientation(), null, screenRight(), screenUp(), 0.1f, 0.1f);
            assertArrayEquals(
                    camPos, result.position(), DIST_TOLERANCE_KM, "Position must be unchanged for null focus");
        }

        @Test
        @DisplayName("orbit with null focus returns unchanged orientation")
        void nullFocusReturnsUnchangedOrientation() {
            Quaternion original = identityOrientation();
            CameraNavigator.OrbitResult result =
                    CameraNavigator.orbit(new double[3], original, null, screenRight(), screenUp(), 0.1f, 0.1f);
            float dot = Math.abs(original.dot(result.orientation()));
            assertEquals(1.0f, dot, QUAT_TOLERANCE, "Orientation must be unchanged for null focus");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // orbit — distance preservation
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("orbit — distance preservation")
    class OrbitDistanceTests {

        @Test
        @DisplayName("Orbit preserves camera-to-focus distance after horizontal drag")
        void horizontalOrbitPreservesDistance() {
            double[] focusPos = {0.0, 0.0, 0.0};
            // Camera offset in X; orbit around screen-up (Y axis) moves camera in XZ plane
            double[] camPos = {50_000.0, 0.0, 0.0};
            double originalDist = dist(camPos, focusPos);

            CameraNavigator.OrbitResult result = CameraNavigator.orbit(
                    camPos, identityOrientation(), focusPos, screenRight(), screenUp(), 0f, (float) Math.toRadians(30));

            double newDist = dist(result.position(), focusPos);
            assertEquals(originalDist, newDist, ORBIT_DIST_TOLERANCE_KM, "Orbit must preserve distance from focus");
        }

        @Test
        @DisplayName("Orbit preserves camera-to-focus distance after vertical drag")
        void verticalOrbitPreservesDistance() {
            double[] focusPos = {0.0, 0.0, 0.0};
            // Camera offset in Z (not aligned with screen-right X), so rotating around X moves the camera
            double[] camPos = {0.0, 0.0, 50_000.0};
            double originalDist = dist(camPos, focusPos);

            CameraNavigator.OrbitResult result = CameraNavigator.orbit(
                    camPos, identityOrientation(), focusPos, screenRight(), screenUp(), (float) Math.toRadians(20), 0f);

            double newDist = dist(result.position(), focusPos);
            assertEquals(originalDist, newDist, ORBIT_DIST_TOLERANCE_KM, "Orbit must preserve distance from focus");
        }

        @Test
        @DisplayName("Orbit preserves distance after combined horizontal and vertical drag")
        void combinedOrbitPreservesDistance() {
            double[] focusPos = {1e8, 2e7, -3e7};
            // Camera offset not aligned with screen-right or screen-up
            double[] camPos = {1e8 + 10_000.0, 2e7 + 30_000.0, -3e7 + 20_000.0};
            double originalDist = dist(camPos, focusPos);

            CameraNavigator.OrbitResult result = CameraNavigator.orbit(
                    camPos,
                    identityOrientation(),
                    focusPos,
                    screenRight(),
                    screenUp(),
                    (float) Math.toRadians(15),
                    (float) Math.toRadians(25));

            double newDist = dist(result.position(), focusPos);
            assertEquals(originalDist, newDist, ORBIT_DIST_TOLERANCE_KM, "Orbit must preserve distance from focus");
        }

        @Test
        @DisplayName("Orbit actually moves the camera (non-zero delta)")
        void orbitMovesCamera() {
            double[] focusPos = {0.0, 0.0, 0.0};
            // Camera in XY plane offset from X; orbit around screen-up (Y axis) rotates in XZ plane
            double[] camPos = {50_000.0, 0.0, 0.0};

            // Orbit around screen-up (Y axis) — moves the camera since camPos is not along Y
            CameraNavigator.OrbitResult result = CameraNavigator.orbit(
                    camPos, identityOrientation(), focusPos, screenRight(), screenUp(), 0f, (float) Math.toRadians(45));

            double movedDist = dist(camPos, result.position());
            assertTrue(movedDist > 1.0, "Orbit should move the camera; moved " + movedDist + " km");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // orbit — camera-relative axes
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("orbit — camera-relative axes")
    class OrbitCameraRelativeTests {

        @Test
        @DisplayName("After rotating camera 90° around Z, orbit axes reflect new orientation")
        void orbitAxesFollowCameraOrientation() {
            // Rotate camera 90° around world Z so screen-right now points in world +Y
            double[] focusPos = {0.0, 0.0, 0.0};
            double[] camPos = {50_000.0, 0.0, 0.0};

            // Orientation: 90° yaw around world +Y so camera faces +X (standard view)
            // Use a simple 45° pitch so we can check orbit direction clearly
            Quaternion rotated90 = new Quaternion().fromAngleNormalAxis((float) Math.toRadians(90), Vector3f.UNIT_Y);

            // Screen axes after rotation
            Vector3f newScreenRight = rotated90.mult(new Vector3f(1, 0, 0));
            Vector3f newScreenUp = rotated90.mult(new Vector3f(0, 1, 0));

            // Orbit with original axes vs. rotated axes should produce different camera positions
            CameraNavigator.OrbitResult withOrigAxes = CameraNavigator.orbit(
                    camPos, identityOrientation(), focusPos, screenRight(), screenUp(), (float) Math.toRadians(45), 0f);

            CameraNavigator.OrbitResult withNewAxes = CameraNavigator.orbit(
                    camPos, rotated90, focusPos, newScreenRight, newScreenUp, (float) Math.toRadians(45), 0f);

            // The resulting positions should differ because the orbit axis differs
            double positionDiff = dist(withOrigAxes.position(), withNewAxes.position());
            assertTrue(
                    positionDiff > 1.0,
                    "Orbit with different camera orientations should yield different positions; diff=" + positionDiff);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // zoom — null focus (no-op)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("zoom — null focus")
    class ZoomNullFocusTests {

        @Test
        @DisplayName("zoom with null focus returns unchanged position")
        void nullFocusReturnsUnchangedPosition() {
            double[] camPos = {50_000.0, 0.0, 0.0};
            double[] result = CameraNavigator.zoom(
                    camPos,
                    null,
                    -1,
                    100.0,
                    KepplrConstants.FRUSTUM_FAR_MAX_KM,
                    KepplrConstants.CAMERA_ZOOM_FACTOR_PER_STEP);
            assertArrayEquals(camPos, result, DIST_TOLERANCE_KM, "Position must be unchanged for null focus");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // zoom — exponential behavior
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("zoom — exponential behavior")
    class ZoomExponentialTests {

        @Test
        @DisplayName("steps=1 multiplies distance by factor (zoom in for factor < 1)")
        void oneStepChangesByFactor() {
            double[] focusPos = {0.0, 0.0, 0.0};
            double[] camPos = {50_000.0, 0.0, 0.0};
            double factor = KepplrConstants.CAMERA_ZOOM_FACTOR_PER_STEP; // 0.85 < 1 → zoom in

            double[] result = CameraNavigator.zoom(camPos, focusPos, 1, 1.0, 1e20, factor);
            double newDist = len(result);
            assertEquals(50_000.0 * factor, newDist, DIST_TOLERANCE_KM, "steps=1 must multiply distance by factor");
            assertTrue(newDist < 50_000.0, "factor < 1, so steps=1 should decrease distance (zoom in)");
        }

        @Test
        @DisplayName("steps=2 applies factor^2")
        void twoStepsEqualFactorSquared() {
            double[] focusPos = {0.0, 0.0, 0.0};
            double[] camPos = {50_000.0, 0.0, 0.0};
            double factor = KepplrConstants.CAMERA_ZOOM_FACTOR_PER_STEP;

            double[] result = CameraNavigator.zoom(camPos, focusPos, 2, 1.0, 1e20, factor);
            double newDist = len(result);
            assertEquals(
                    50_000.0 * factor * factor,
                    newDist,
                    DIST_TOLERANCE_KM,
                    "steps=2 must multiply distance by factor^2");
        }

        @Test
        @DisplayName("steps=-1 zooms out (increases distance for factor < 1)")
        void negativeStepsZoomOut() {
            double[] focusPos = {0.0, 0.0, 0.0};
            double[] camPos = {50_000.0, 0.0, 0.0};

            double[] result =
                    CameraNavigator.zoom(camPos, focusPos, -1, 1.0, 1e20, KepplrConstants.CAMERA_ZOOM_FACTOR_PER_STEP);
            double newDist = len(result);
            assertTrue(
                    newDist > 50_000.0, "steps=-1 with factor < 1 should increase distance (zoom out); got " + newDist);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // zoom — clamping
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("zoom — clamping")
    class ZoomClampingTests {

        @Test
        @DisplayName("Zoom in past minimum is clamped to minDistKm")
        void zoomInClampedAtMinimum() {
            double[] focusPos = {0.0, 0.0, 0.0};
            double[] camPos = {200.0, 0.0, 0.0}; // close to focus
            double minDist = 150.0;

            // Many positive steps with factor=0.5 (zoom in) should clamp at minimum
            double[] result = CameraNavigator.zoom(camPos, focusPos, 100, minDist, 1e20, 0.5);
            double newDist = len(result);
            assertEquals(minDist, newDist, DIST_TOLERANCE_KM, "Zoom past minimum must be clamped at minDistKm");
        }

        @Test
        @DisplayName("Zoom out past maximum is clamped to FRUSTUM_FAR_MAX_KM")
        void zoomOutClampedAtMaximum() {
            double[] focusPos = {0.0, 0.0, 0.0};
            double[] camPos = {1e14, 0.0, 0.0}; // close to max
            double maxDist = KepplrConstants.FRUSTUM_FAR_MAX_KM;

            // Many negative steps with factor=0.5 (zoom out: factor^-1 = 2) should clamp at maximum
            double[] result = CameraNavigator.zoom(camPos, focusPos, -100, 1.0, maxDist, 0.5);
            double newDist = len(result);
            assertEquals(
                    maxDist, newDist, DIST_TOLERANCE_KM, "Zoom past maximum must be clamped at FRUSTUM_FAR_MAX_KM");
        }

        @Test
        @DisplayName("Zoom direction toward focus is preserved after clamping")
        void directionPreservedAfterMinClamp() {
            double[] focusPos = {0.0, 0.0, 0.0};
            double[] camPos = {200.0, 0.0, 0.0};
            double minDist = 150.0;

            double[] result = CameraNavigator.zoom(camPos, focusPos, 100, minDist, 1e20, 0.5);
            // Direction should still be along +X
            assertTrue(result[0] > 0, "Camera should still be on the +X side of focus after clamping");
            assertEquals(0.0, result[1], DIST_TOLERANCE_KM, "Y component should be zero");
            assertEquals(0.0, result[2], DIST_TOLERANCE_KM, "Z component should be zero");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // J2000 pose integrity
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("J2000 pose integrity")
    class PoseIntegrityTests {

        @Test
        @DisplayName("Camera position remains finite in J2000 after chained orbit operations")
        void chainedOrbitPositionIsFinite() {
            double[] focusPos = {1.5e8, 0.0, 0.0};
            double[] camPos = {1.5e8 + 50_000.0, 0.0, 0.0};
            Quaternion orientation = identityOrientation();

            for (int i = 0; i < 36; i++) {
                // 36 × 10° = 360° full orbit
                CameraNavigator.OrbitResult r = CameraNavigator.orbit(
                        camPos, orientation, focusPos, screenRight(), screenUp(), (float) Math.toRadians(10), 0f);
                camPos = r.position();
                orientation = r.orientation();
            }

            assertTrue(Double.isFinite(camPos[0]), "X must be finite after 360° orbit");
            assertTrue(Double.isFinite(camPos[1]), "Y must be finite after 360° orbit");
            assertTrue(Double.isFinite(camPos[2]), "Z must be finite after 360° orbit");
        }

        @Test
        @DisplayName("Camera position remains finite in J2000 after chained zoom operations")
        void chainedZoomPositionIsFinite() {
            double[] focusPos = {0.0, 0.0, 0.0};
            double[] camPos = {50_000.0, 0.0, 0.0};

            // Zoom in close (steps=1 → zoom in; will clamp at min)
            for (int i = 0; i < 50; i++) {
                camPos = CameraNavigator.zoom(
                        camPos,
                        focusPos,
                        1,
                        100.0,
                        KepplrConstants.FRUSTUM_FAR_MAX_KM,
                        KepplrConstants.CAMERA_ZOOM_FACTOR_PER_STEP);
            }
            // Zoom back out (steps=-1 → zoom out; will clamp at max)
            for (int i = 0; i < 50; i++) {
                camPos = CameraNavigator.zoom(
                        camPos,
                        focusPos,
                        -1,
                        100.0,
                        KepplrConstants.FRUSTUM_FAR_MAX_KM,
                        KepplrConstants.CAMERA_ZOOM_FACTOR_PER_STEP);
            }

            assertTrue(Double.isFinite(camPos[0]), "X must be finite after repeated zoom");
            assertTrue(Double.isFinite(camPos[1]), "Y must be finite after repeated zoom");
            assertTrue(Double.isFinite(camPos[2]), "Z must be finite after repeated zoom");
        }
    }
}
