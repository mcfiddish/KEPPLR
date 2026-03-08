package kepplr.camera;

import static org.junit.jupiter.api.Assertions.*;

import com.jme3.math.Quaternion;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.RotationMatrixIJK;
import picante.math.vectorspace.VectorIJK;

/**
 * Unit tests for {@link BodyFixedFrame}.
 *
 * <p>Uses real SPICE data via the test metakernel. Epoch: 2015 Jul 14 07:59:00 UTC (New Horizons Pluto flyby).
 *
 * <p>All ET values used for heliocentric position queries (SPK) stay within the test kernel's coverage window (2015 JUL
 * 14 02:00–08:00). PCK-based rotation queries ({@code getJ2000ToBodyFixedRotation}) are valid at any epoch.
 */
@DisplayName("BodyFixedFrame")
class BodyFixedCameraFrameTest {

    /** NAIF ID for Earth. */
    private static final int EARTH = 399;

    /** NAIF ID that has no body-fixed frame in the test kernel. */
    private static final int NONEXISTENT_BODY = -999999;

    private BodyFixedFrame bodyFixedFrame;
    private KEPPLREphemeris eph;

    /** Test epoch: 2015 Jul 14 07:59:00 UTC (end of SPK coverage window). */
    private double testEt;

    @BeforeEach
    void setUp() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTestTemplate();
        eph = KEPPLRConfiguration.getInstance().getEphemeris();
        bodyFixedFrame = new BodyFixedFrame();
        testEt = TestHarness.getTestEpoch(); // 2015 Jul 14 07:59:00 UTC
    }

    @Test
    @DisplayName("Earth has a body-fixed frame in the test kernel")
    void earthHasBodyFixedFrame() {
        assertTrue(eph.hasBodyFixedFrame(EARTH));
    }

    @Test
    @DisplayName("Earth rotation matrix is non-null at test epoch and all 9 elements are accessible")
    void earthRotationNonNullAtTestEpoch() {
        RotationMatrixIJK rot = eph.getJ2000ToBodyFixedRotation(EARTH, testEt);
        assertNotNull(rot);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                assertFalse(Double.isNaN(rot.get(row, col)), "Element [" + row + "," + col + "] is NaN");
            }
        }
    }

    @Test
    @DisplayName("Non-existent body returns fallback result with unchanged pose")
    void missingBodyReturnsFallback() {
        double[] camPos = {1.0e8, 2.0e8, 3.0e8};
        Quaternion orient = new Quaternion(0f, 0f, 0f, 1f);

        BodyFixedFrame.ApplyResult result = bodyFixedFrame.apply(camPos, orient, NONEXISTENT_BODY, testEt);

        assertTrue(result.fallbackActive());
        assertEquals(camPos[0], result.newCamHelioJ2000()[0], 1e-6);
        assertEquals(camPos[1], result.newCamHelioJ2000()[1], 1e-6);
        assertEquals(camPos[2], result.newCamHelioJ2000()[2], 1e-6);
    }

    @Test
    @DisplayName("No focused body (focusBodyId == -1) returns fallback with unchanged pose")
    void noFocusReturnsFallback() {
        double[] camPos = {5.0e7, 1.5e8, -2.0e7};
        Quaternion orient = new Quaternion(0f, 0f, 0f, 1f);

        BodyFixedFrame.ApplyResult result = bodyFixedFrame.apply(camPos, orient, -1, testEt);

        assertTrue(result.fallbackActive());
        assertEquals(camPos[0], result.newCamHelioJ2000()[0], 1e-6);
        assertEquals(camPos[1], result.newCamHelioJ2000()[1], 1e-6);
        assertEquals(camPos[2], result.newCamHelioJ2000()[2], 1e-6);
    }

    @Test
    @DisplayName("First apply() for Earth returns unchanged position (no delta yet) and fallbackActive==false")
    void firstFrameReturnedUnchanged() {
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        assertNotNull(earthPos, "Earth heliocentric position must be available at test epoch");

        // Camera offset: 50 000 km above Earth in J2000 +Z
        double[] camPos = {earthPos.getI(), earthPos.getJ(), earthPos.getK() + 50_000.0};
        Quaternion orient = new Quaternion(0f, 0f, 0f, 1f);

        BodyFixedFrame.ApplyResult result = bodyFixedFrame.apply(camPos, orient, EARTH, testEt);

        assertFalse(result.fallbackActive());
        assertEquals(camPos[0], result.newCamHelioJ2000()[0], 1e-6);
        assertEquals(camPos[1], result.newCamHelioJ2000()[1], 1e-6);
        assertEquals(camPos[2], result.newCamHelioJ2000()[2], 1e-6);
    }

    @Test
    @DisplayName("Two calls 3600 s apart rotate camera offset by ~15° (Earth sidereal spin)")
    void deltaRotationOverOneHour() {
        // Use et1 = testEt − 3600 (≈ 06:59) and et2 = testEt (≈ 07:59): both within SPK coverage.
        double et1 = testEt - 3600.0;
        double et2 = testEt;

        VectorIJK earthPos1 = eph.getHeliocentricPositionJ2000(EARTH, et1);
        assertNotNull(earthPos1, "Earth heliocentric position must be available at et1");
        VectorIJK earthPos2 = eph.getHeliocentricPositionJ2000(EARTH, et2);
        assertNotNull(earthPos2, "Earth heliocentric position must be available at et2");

        // Body-relative offset at et1: 50 000 km along J2000 +X
        double[] bodyOffset = {50_000.0, 0.0, 0.0};

        // Camera position at et1 = Earth position + body-relative offset
        double[] camPos1 = {
            earthPos1.getI() + bodyOffset[0], earthPos1.getJ() + bodyOffset[1], earthPos1.getK() + bodyOffset[2]
        };
        Quaternion orient = new Quaternion(0f, 0f, 0f, 1f);

        // First call — establishes prevRotation; returns unchanged (first frame)
        bodyFixedFrame.apply(camPos1, orient, EARTH, et1);

        // Simulate CameraInputHandler.applyFocusTracking(): move camera with Earth's orbital motion.
        // In the render loop, this happens before bodyFixedFrame.apply() each frame.
        double[] camPos2 = {
            earthPos2.getI() + bodyOffset[0], earthPos2.getJ() + bodyOffset[1], earthPos2.getK() + bodyOffset[2]
        };

        // Second call — applies one hour of Earth spin; camPos2 offset from Earth is identical to bodyOffset
        BodyFixedFrame.ApplyResult result = bodyFixedFrame.apply(camPos2, orient, EARTH, et2);

        assertFalse(result.fallbackActive());

        // Original body-relative direction = bodyOffset (normalised +X)
        // New body-relative direction = result.newCamHelioJ2000() - earthPos2
        double[] newCam = result.newCamHelioJ2000();
        double[] newOffset = {newCam[0] - earthPos2.getI(), newCam[1] - earthPos2.getJ(), newCam[2] - earthPos2.getK()};

        double origLen = Math.sqrt(dot(bodyOffset, bodyOffset));
        double newLen = Math.sqrt(dot(newOffset, newOffset));
        double cosAngle = dot(bodyOffset, newOffset) / (origLen * newLen);
        double angleDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosAngle))));

        // Earth sidereal rotation: ~360° / 23.9345 h ≈ 15.04°/hr; tolerance ±0.5°
        assertEquals(15.04, angleDeg, 0.5, "Rotation angle over 1 hour should be ~15°");
    }

    @Test
    @DisplayName("After apply() twice + reset(), next apply() behaves like first frame (unchanged pose)")
    void afterResetBehavesLikeFirstFrame() {
        // Use et1 = testEt − 3600, et2 = testEt − 1800, et3 = testEt; all within SPK coverage.
        double et1 = testEt - 3600.0;
        double et2 = testEt - 1800.0;
        double et3 = testEt;

        VectorIJK earthPos1 = eph.getHeliocentricPositionJ2000(EARTH, et1);
        assertNotNull(earthPos1, "Earth heliocentric position must be available at et1");
        VectorIJK earthPos2 = eph.getHeliocentricPositionJ2000(EARTH, et2);
        assertNotNull(earthPos2, "Earth heliocentric position must be available at et2");
        VectorIJK earthPos3 = eph.getHeliocentricPositionJ2000(EARTH, et3);
        assertNotNull(earthPos3, "Earth heliocentric position must be available at et3");

        double[] bodyOffset = {50_000.0, 0.0, 0.0};
        Quaternion orient = new Quaternion(0f, 0f, 0f, 1f);

        // Two frames to accumulate state (simulate orbital tracking between calls)
        double[] camPos1 = {earthPos1.getI() + bodyOffset[0], earthPos1.getJ(), earthPos1.getK()};
        bodyFixedFrame.apply(camPos1, orient, EARTH, et1);

        double[] camPos2 = {earthPos2.getI() + bodyOffset[0], earthPos2.getJ(), earthPos2.getK()};
        bodyFixedFrame.apply(camPos2, orient, EARTH, et2);

        // Reset clears accumulated state
        bodyFixedFrame.reset();

        // Next call should behave like the very first frame — return unchanged pose
        double[] camPos3 = {earthPos3.getI() + bodyOffset[0], earthPos3.getJ(), earthPos3.getK()};
        BodyFixedFrame.ApplyResult result = bodyFixedFrame.apply(camPos3, orient, EARTH, et3);

        assertFalse(result.fallbackActive());
        assertEquals(camPos3[0], result.newCamHelioJ2000()[0], 1e-6);
        assertEquals(camPos3[1], result.newCamHelioJ2000()[1], 1e-6);
        assertEquals(camPos3[2], result.newCamHelioJ2000()[2], 1e-6);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
