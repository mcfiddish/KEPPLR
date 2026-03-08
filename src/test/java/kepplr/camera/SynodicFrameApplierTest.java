package kepplr.camera;

import static org.junit.jupiter.api.Assertions.*;

import com.jme3.math.Quaternion;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.VectorIJK;

/**
 * Unit tests for {@link SynodicFrameApplier}.
 *
 * <p>Uses real SPICE data via the test metakernel. Epoch: 2015 Jul 14 07:59:00 UTC (New Horizons Pluto flyby). All ET
 * values used for heliocentric position queries stay within the test kernel's coverage window (2015 JUL 14
 * 02:00–08:00).
 */
@DisplayName("SynodicFrameApplier")
class SynodicFrameApplierTest {

    /** NAIF ID for Earth. */
    private static final int EARTH = 399;

    /** NAIF ID for the Sun. */
    private static final int SUN = 10;

    private SynodicFrameApplier applier;
    private KEPPLREphemeris eph;

    /** Test epoch: 2015 Jul 14 07:59:00 UTC (end of SPK coverage window). */
    private double testEt;

    @BeforeEach
    void setUp() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTestTemplate();
        eph = KEPPLRConfiguration.getInstance().getEphemeris();
        applier = new SynodicFrameApplier();
        testEt = TestHarness.getTestEpoch(); // 2015 Jul 14 07:59:00 UTC
    }

    @Test
    @DisplayName("focusId == -1 returns fallback with unchanged pose")
    void noFocus_returnsFallback() {
        double[] camPos = {1.0e8, 2.0e8, 3.0e8};
        Quaternion orient = new Quaternion(0f, 0f, 0f, 1f);

        SynodicFrameApplier.ApplyResult result = applier.apply(camPos, orient, -1, SUN, testEt);

        assertTrue(result.fallbackActive());
        assertArrayEquals(camPos, result.newCamHelioJ2000(), 1e-6);
        assertEquals(orient.getX(), result.newOrientation().getX(), 1e-6);
        assertEquals(orient.getW(), result.newOrientation().getW(), 1e-6);
    }

    @Test
    @DisplayName("First apply() returns unchanged pose and fallbackActive == false")
    void firstFrame_returnsUnchangedPose() {
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        assertNotNull(earthPos, "Earth heliocentric position must be available at test epoch");

        double[] camPos = {earthPos.getI() + 50_000.0, earthPos.getJ(), earthPos.getK()};
        Quaternion orient = new Quaternion(0f, 0f, 0f, 1f);

        SynodicFrameApplier.ApplyResult result = applier.apply(camPos, orient, EARTH, SUN, testEt);

        assertFalse(result.fallbackActive());
        assertArrayEquals(camPos, result.newCamHelioJ2000(), 1e-6);
    }

    @Test
    @DisplayName("targetId == focusId uses effective target Sun — no exception, valid result")
    void targetSameAsFocus_usesEffectiveTargetSun() {
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        assertNotNull(earthPos);

        double[] camPos = {earthPos.getI() + 50_000.0, earthPos.getJ(), earthPos.getK()};
        Quaternion orient = new Quaternion(0f, 0f, 0f, 1f);

        // target == focus: effective target becomes SUN
        assertDoesNotThrow(() -> applier.apply(camPos, orient, EARTH, EARTH, testEt));
        SynodicFrameApplier.ApplyResult result = applier.apply(camPos, orient, EARTH, EARTH, testEt);
        assertNotNull(result);
    }

    @Test
    @DisplayName("Two calls 3600 s apart preserve offset magnitude and change offset direction")
    void secondFrame_appliesDeltaRotation() {
        double et1 = testEt - 3600.0;
        double et2 = testEt;

        VectorIJK earthPos1 = eph.getHeliocentricPositionJ2000(EARTH, et1);
        assertNotNull(earthPos1, "Earth heliocentric position must be available at et1");
        VectorIJK earthPos2 = eph.getHeliocentricPositionJ2000(EARTH, et2);
        assertNotNull(earthPos2, "Earth heliocentric position must be available at et2");

        double[] bodyOffset = {50_000.0, 0.0, 0.0};
        Quaternion orient = new Quaternion(0f, 0f, 0f, 1f);

        // First call at et1 — establishes prevBasis; returns unchanged
        double[] camPos1 = {
            earthPos1.getI() + bodyOffset[0], earthPos1.getJ() + bodyOffset[1], earthPos1.getK() + bodyOffset[2]
        };
        applier.apply(camPos1, orient, EARTH, SUN, et1);

        // Simulate tracking: camera offset from Earth stays constant (orbital translation applied)
        double[] camPos2 = {
            earthPos2.getI() + bodyOffset[0], earthPos2.getJ() + bodyOffset[1], earthPos2.getK() + bodyOffset[2]
        };

        SynodicFrameApplier.ApplyResult result = applier.apply(camPos2, orient, EARTH, SUN, et2);

        assertFalse(result.fallbackActive());

        // Offset magnitude must be preserved
        double[] newCam = result.newCamHelioJ2000();
        double[] newOffset = {newCam[0] - earthPos2.getI(), newCam[1] - earthPos2.getJ(), newCam[2] - earthPos2.getK()};
        double origLen = Math.sqrt(dot(bodyOffset, bodyOffset));
        double newLen = Math.sqrt(dot(newOffset, newOffset));
        assertEquals(origLen, newLen, origLen * 0.01, "Offset magnitude must be preserved (within 1%)");

        // Direction must have changed (Earth moves ~1° around Sun per day; 3600 s ≈ 0.04°, detectable)
        double cosAngle = dot(bodyOffset, newOffset) / (origLen * newLen);
        double angleDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosAngle))));
        assertTrue(angleDeg > 0.001, "Synodic delta should rotate offset direction by a non-trivial amount");
    }

    @Test
    @DisplayName("Focus change resets to first-frame behaviour (unchanged pose)")
    void focusChange_resetsToFirstFrame() {
        VectorIJK earthPos1 = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        assertNotNull(earthPos1);

        double[] camPos = {earthPos1.getI() + 50_000.0, earthPos1.getJ(), earthPos1.getK()};
        Quaternion orient = new Quaternion(0f, 0f, 0f, 1f);

        // First call establishes prevBasis for EARTH
        applier.apply(camPos, orient, EARTH, SUN, testEt);

        // Second call with different focusId — should behave like first frame (reset + return unchanged)
        VectorIJK moonPos = eph.getHeliocentricPositionJ2000(301, testEt);
        assertNotNull(moonPos, "Moon heliocentric position must be available at test epoch");

        double[] camPos2 = {moonPos.getI() + 50_000.0, moonPos.getJ(), moonPos.getK()};
        SynodicFrameApplier.ApplyResult result = applier.apply(camPos2, orient, 301, SUN, testEt);

        assertFalse(result.fallbackActive());
        assertArrayEquals(camPos2, result.newCamHelioJ2000(), 1e-6);
    }

    @Test
    @DisplayName("reset() clears state — next apply() behaves as first frame")
    void reset_clearsState() {
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, testEt - 3600.0);
        assertNotNull(earthPos);

        double[] camPos1 = {earthPos.getI() + 50_000.0, earthPos.getJ(), earthPos.getK()};
        Quaternion orient = new Quaternion(0f, 0f, 0f, 1f);

        // Two calls to accumulate state
        applier.apply(camPos1, orient, EARTH, SUN, testEt - 3600.0);
        applier.apply(camPos1, orient, EARTH, SUN, testEt);

        applier.reset();

        // After reset, next call should behave like first frame
        VectorIJK earthPos2 = eph.getHeliocentricPositionJ2000(EARTH, testEt);
        assertNotNull(earthPos2);
        double[] camPos2 = {earthPos2.getI() + 50_000.0, earthPos2.getJ(), earthPos2.getK()};
        SynodicFrameApplier.ApplyResult result = applier.apply(camPos2, orient, EARTH, SUN, testEt);

        assertFalse(result.fallbackActive());
        assertArrayEquals(camPos2, result.newCamHelioJ2000(), 1e-6);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
