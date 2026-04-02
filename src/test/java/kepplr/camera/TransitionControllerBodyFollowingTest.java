package kepplr.camera;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.commands.DefaultSimulationCommands;
import kepplr.config.KEPPLRConfiguration;
import kepplr.core.SimulationClock;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.state.DefaultSimulationState;
import kepplr.state.StateSnapshot;
import kepplr.state.StateSnapshotCodec;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.RotationMatrixIJK;
import picante.math.vectorspace.VectorIJK;

/**
 * Tests for {@link TransitionController} body-following: the feature that keeps the camera at a fixed radial distance
 * from a body after a goTo completes or after state-string restoration.
 *
 * <p>Uses the test ephemeris kernel; a real {@link KEPPLREphemeris} is required because body-following queries
 * heliocentric positions at the current ET each frame.
 */
@DisplayName("TransitionController body-following")
class TransitionControllerBodyFollowingTest {

    private static final int EARTH = 399;

    /** Mid-point of the test kernel window — well within [2015-JUL-14 02:00, 08:00]. */
    private double et0;

    /** 1 hour after et0 — still within the test kernel window. */
    private double et1;

    @BeforeEach
    void setUp() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTestTemplate();
        et0 = KEPPLRConfiguration.getInstance().getTimeConversion().utcStringToTDB("2015 Jul 14 04:00:00");
        et1 = et0 + 3600.0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static double dist(double[] cam, VectorIJK body) {
        double dx = cam[0] - body.getI();
        double dy = cam[1] - body.getJ();
        double dz = cam[2] - body.getK();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Replicates {@code KepplrApp.computeBodyFixedSpherical}: converts a heliocentric J2000 camera position to
     * body-fixed spherical coordinates {@code [r_km, lat_deg, lon_deg]}.
     *
     * @return spherical coordinates, or {@code null} if the body or rotation is unavailable
     */
    private static double[] bodyFixedSpherical(int focusId, double[] camHelioJ2000, double et) {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        VectorIJK focusPos = eph.getHeliocentricPositionJ2000(focusId, et);
        if (focusPos == null) return null;
        RotationMatrixIJK r = eph.getJ2000ToBodyFixedRotation(focusId, et);
        if (r == null) return null;
        double dx = camHelioJ2000[0] - focusPos.getI();
        double dy = camHelioJ2000[1] - focusPos.getJ();
        double dz = camHelioJ2000[2] - focusPos.getK();
        double bx = r.get(0, 0) * dx + r.get(0, 1) * dy + r.get(0, 2) * dz;
        double by = r.get(1, 0) * dx + r.get(1, 1) * dy + r.get(1, 2) * dz;
        double bz = r.get(2, 0) * dx + r.get(2, 1) * dy + r.get(2, 2) * dz;
        double rKm = Math.sqrt(bx * bx + by * by + bz * bz);
        if (rKm < 1e-9) return new double[] {0.0, 0.0, 0.0};
        double latDeg = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, bz / rKm))));
        double lonDeg = Math.toDegrees(Math.atan2(by, bx));
        return new double[] {rKm, latDeg, lonDeg};
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("requestFollow keeps camera at fixed distance from body (single frame)")
    void requestFollowKeepsCameraAtFixedDistance() {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, et0);
        assertNotNull(earthPos, "Earth must be available in test kernel at et0");

        DefaultSimulationState state = new DefaultSimulationState();
        state.setCurrentEt(et0);

        double followDist = 100_000.0;
        double[] cam = {earthPos.getI(), earthPos.getJ(), earthPos.getK() + followDist};

        TransitionController tc = new TransitionController(state);
        tc.requestFollow(EARTH, followDist);
        tc.update(0.016f, null, cam);

        assertEquals(
                followDist,
                dist(cam, earthPos),
                1.0,
                "camera should be at followDist from Earth after requestFollow+update");
    }

    @Test
    @DisplayName("body-following repositions camera as ET advances by 1 hour")
    void bodyFollowingReposAcrossEtAdvance() {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        VectorIJK earthPos0 = eph.getHeliocentricPositionJ2000(EARTH, et0);
        VectorIJK earthPos1 = eph.getHeliocentricPositionJ2000(EARTH, et1);
        assertNotNull(earthPos0, "Earth must be available in test kernel at et0");
        assertNotNull(earthPos1, "Earth must be available in test kernel at et1");

        DefaultSimulationState state = new DefaultSimulationState();
        state.setCurrentEt(et0);

        double followDist = 100_000.0;
        double[] cam = {earthPos0.getI(), earthPos0.getJ(), earthPos0.getK() + followDist};

        TransitionController tc = new TransitionController(state);
        tc.requestFollow(EARTH, followDist);
        tc.update(0.016f, null, cam); // sets followBodyId, body-following runs at et0

        // Advance ET by 1 hour
        state.setCurrentEt(et1);
        tc.update(0.016f, null, cam); // body-following runs at et1

        assertEquals(
                followDist,
                dist(cam, earthPos1),
                1.0,
                "camera should be at followDist from Earth at new ET after 1-hour advance");

        // Earth should have moved meaningfully in 1 hour (~108,000 km)
        double earthMovement = dist(new double[] {earthPos0.getI(), earthPos0.getJ(), earthPos0.getK()}, earthPos1);
        assertTrue(
                earthMovement > 1000.0, "Earth should have moved at least 1000 km in 1 hour; actual: " + earthMovement);
    }

    @Test
    @DisplayName("getStateString encodes small body-relative offset when camera is near focused body")
    void getStateStringEncodesSmallOffset() {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        VectorIJK earthPos = eph.getHeliocentricPositionJ2000(EARTH, et0);
        assertNotNull(earthPos);

        DefaultSimulationState state = new DefaultSimulationState();
        state.setCurrentEt(et0);
        state.setFocusedBodyId(EARTH);

        double followDist = 100_000.0;
        // Camera is already near Earth (simulates post-goTo / post-body-following state)
        state.setCameraPositionJ2000(new double[] {earthPos.getI(), earthPos.getJ(), earthPos.getK() + followDist});

        SimulationClock clock = new SimulationClock(state, et0);
        TransitionController tc = new TransitionController(state);
        DefaultSimulationCommands commands = new DefaultSimulationCommands(state, clock, tc);

        String encoded = commands.getStateString();
        StateSnapshot snap = StateSnapshotCodec.decode(encoded);

        assertTrue(
                snap.camPosRelativeToFocus(),
                "getStateString should use body-relative encoding when focusedBodyId != -1");
        double[] off = snap.camPosJ2000();
        double offMag = Math.sqrt(off[0] * off[0] + off[1] * off[1] + off[2] * off[2]);
        assertEquals(followDist, offMag, 1.0, "encoded offset magnitude should equal the camera-to-body distance");
    }

    @Test
    @DisplayName("state string round-trip restores ET, J2000 position, body-fixed spherical, orientation, and FOV")
    void stateStringRoundTripRestoresCompleteState() {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        VectorIJK earthPos0 = eph.getHeliocentricPositionJ2000(EARTH, et0);
        assertNotNull(earthPos0);

        // Place camera 100,000 km above Earth in a non-trivial direction so the body-fixed
        // coordinates are interesting (not degenerate along any axis).
        double followDist = 100_000.0;
        double[] camJ2000 = {
            earthPos0.getI() + followDist * 0.5,
            earthPos0.getJ() + followDist * 0.5,
            earthPos0.getK() + followDist * Math.sqrt(0.5)
        };
        float[] savedOrient = {0.1f, 0.2f, 0.3f, 0.9f};
        double savedFov = 30.0;

        DefaultSimulationState state = new DefaultSimulationState();
        state.setCurrentEt(et0);
        state.setFocusedBodyId(EARTH);
        state.setCameraPositionJ2000(camJ2000.clone());
        state.setCameraOrientationJ2000(savedOrient.clone());
        state.setFovDeg(savedFov);

        SimulationClock clock = new SimulationClock(state, et0);
        TransitionController tc = new TransitionController(state);
        DefaultSimulationCommands commands = new DefaultSimulationCommands(state, clock, tc);

        // Compute expected body-fixed spherical at save time
        double[] expectedBodyFixed = bodyFixedSpherical(EARTH, camJ2000, et0);
        assertNotNull(expectedBodyFixed, "body-fixed spherical must be computable at et0");

        // ── Save ──────────────────────────────────────────────────────────────
        String stateStr = commands.getStateString();

        // ── Advance simulation 1 hour and move the camera far away ────────────
        state.setCurrentEt(et1);
        clock.setET(et1);
        state.setCameraPositionJ2000(new double[] {1e10, 2e10, 3e10});
        state.setCameraOrientationJ2000(new float[] {0, 0, 0, 1});
        state.setFovDeg(90.0);

        // ── Restore ──────────────────────────────────────────────────────────
        commands.setStateString(stateStr); // no JME thread: restoreSyncEnabled=false, no await

        // ── Simulate one JME render frame ─────────────────────────────────────
        // 1. clock.advance() would compute snap.et from the new anchor;
        //    in tests we set it directly on state since there is no JME loop.
        state.setCurrentEt(et0);

        // 2. Consume PendingCameraRestore and apply to camera array
        DefaultSimulationState.PendingCameraRestore restore = state.consumePendingCameraRestore();
        assertNotNull(restore, "setStateString must post a PendingCameraRestore");
        double[] cam = restore.posJ2000().clone();

        // 3. transitionController.update() drains inbox (CancelRequest + FollowRequest)
        //    and runs body-following against the current ET (et0)
        tc.update(0.016f, null, cam);

        // 4. Simulate simpleUpdate property writes
        state.setCameraPositionJ2000(cam.clone());
        double[] restoredBodyFixed = bodyFixedSpherical(EARTH, cam, et0);

        // ── Assertions ────────────────────────────────────────────────────────
        // ET
        assertEquals(et0, state.currentEtProperty().get(), 1.0, "restored ET must equal snapshot ET");

        // Camera J2000 position (within 1 km — precision of body-relative encode/decode)
        double[] restoredCam = state.cameraPositionJ2000Property().get();
        assertEquals(camJ2000[0], restoredCam[0], 1.0, "cam J2000 X");
        assertEquals(camJ2000[1], restoredCam[1], 1.0, "cam J2000 Y");
        assertEquals(camJ2000[2], restoredCam[2], 1.0, "cam J2000 Z");

        // Camera orientation
        assertArrayEquals(
                savedOrient, restore.orientJ2000(), 1e-6f, "restored orientation must match saved orientation");

        // FOV
        assertEquals(savedFov, restore.fovDeg(), 0.001, "restored FOV must match saved FOV");

        // Body-fixed spherical: distance, latitude, longitude
        assertNotNull(restoredBodyFixed, "body-fixed spherical must be computable after restore");
        assertEquals(expectedBodyFixed[0], restoredBodyFixed[0], 1.0, "body-fixed distance (km)");
        assertEquals(expectedBodyFixed[1], restoredBodyFixed[1], 0.001, "body-fixed latitude (deg)");
        assertEquals(expectedBodyFixed[2], restoredBodyFixed[2], 0.001, "body-fixed longitude (deg)");
    }

    @Test
    @DisplayName("setStateString restores camera near focused body and re-establishes body-following")
    void setStateStringRestoresCameraAndFollow() {
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        VectorIJK earthPos0 = eph.getHeliocentricPositionJ2000(EARTH, et0);
        VectorIJK earthPos1 = eph.getHeliocentricPositionJ2000(EARTH, et1);
        assertNotNull(earthPos0);
        assertNotNull(earthPos1);

        DefaultSimulationState state = new DefaultSimulationState();
        state.setCurrentEt(et0);
        state.setFocusedBodyId(EARTH);

        double followDist = 100_000.0;
        // Camera is near Earth at et0
        state.setCameraPositionJ2000(new double[] {earthPos0.getI(), earthPos0.getJ(), earthPos0.getK() + followDist});
        state.setCameraOrientationJ2000(new float[] {0, 0, 0, 1});
        state.setFovDeg(45.0);

        SimulationClock clock = new SimulationClock(state, et0);
        TransitionController tc = new TransitionController(state);
        DefaultSimulationCommands commands = new DefaultSimulationCommands(state, clock, tc);

        // Capture state string while camera is near Earth
        String encoded = commands.getStateString();

        // Simulate "time advanced, camera is now far from Earth"
        state.setCameraPositionJ2000(new double[] {0, 0, 0});
        state.setCurrentEt(et1);

        // Restore state string — should post PendingCameraRestore near Earth at et0
        commands.setStateString(encoded);

        DefaultSimulationState.PendingCameraRestore restore = state.consumePendingCameraRestore();
        assertNotNull(restore, "setStateString must post a PendingCameraRestore");

        // PendingCameraRestore position should be near Earth at the snapshot's ET (et0)
        assertEquals(
                followDist,
                dist(restore.posJ2000(), earthPos0),
                1.0,
                "restored camera should be near Earth at snapshot ET");

        // Apply the restore and run one update frame — body-following should re-track Earth at et1
        double[] cam = restore.posJ2000().clone();
        // state ET is already et1 (set above); update processes [CancelRequest, FollowRequest]
        // then body-following runs at et1
        tc.update(0.016f, null, cam);

        assertEquals(
                followDist,
                dist(cam, earthPos1),
                1.0,
                "after restore + body-following, camera should be at followDist from Earth at current ET");
    }
}
