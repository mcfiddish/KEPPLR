package kepplr.render.body;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BodyCuller} (REDESIGN.md §7.3).
 *
 * <p>Tests cover: apparent-radius formula, NAIF satellite classification, and the three-way cull
 * decision table.
 */
class BodyCullerTest {

    // ── Apparent radius computation ───────────────────────────────────────────────────────────

    /**
     * Earth at 1 AU (1.496×10^8 km), radius 6371 km, 720 px viewport, 45° FOV.
     *
     * <p>Expected: (6371 / 1.496e8) × (360) / tan(22.5°)
     *            = 4.257e-5 × 360 / 0.41421
     *            ≈ 0.03700 px  (much less than 2 px at this distance — correctly small)
     * Hmm — actually at 1 AU Earth should appear very small. Let me use a closer scenario.
     */
    @Test
    void apparentRadius_earthAt50000km() {
        // Earth radius 6371 km, distance 50000 km, 720 px height, 45° FOV
        // tanHalfFov = tan(22.5°) ≈ 0.41421
        // result = (6371 / 50000) × (720/2) / 0.41421
        //        = 0.12742 × 360 / 0.41421
        //        ≈ 110.7 px
        double result = BodyCuller.computeApparentRadiusPx(6371.0, 50_000.0, 720, 45f);
        assertEquals(110.7, result, 1.0);
    }

    @Test
    void apparentRadius_sunAt1AU() {
        // Sun radius 695700 km, distance 1.496e8 km, 720 px, 45° FOV
        // result = (695700 / 1.496e8) × 360 / tan(22.5°)
        //        ≈ 0.004650 × 360 / 0.41421
        //        ≈ 4.04 px  (just above 2 px at 1 AU — renders as full sphere)
        double result = BodyCuller.computeApparentRadiusPx(695_700.0, 1.496e8, 720, 45f);
        assertEquals(4.04, result, 0.1);
    }

    @Test
    void apparentRadius_invalidInputs_returnsZero() {
        assertEquals(0.0, BodyCuller.computeApparentRadiusPx(0.0, 1e6, 720, 45f));   // zero radius
        assertEquals(0.0, BodyCuller.computeApparentRadiusPx(1000.0, 0.0, 720, 45f)); // zero dist
        assertEquals(0.0, BodyCuller.computeApparentRadiusPx(1000.0, 1e6, 0, 45f));   // zero height
        assertEquals(0.0, BodyCuller.computeApparentRadiusPx(1000.0, 1e6, 720, 0f));  // zero fov
        assertEquals(0.0, BodyCuller.computeApparentRadiusPx(-1.0, 1e6, 720, 45f));   // neg radius
    }

    @Test
    void apparentRadius_scalesLinearlyWithRadius() {
        double r1 = BodyCuller.computeApparentRadiusPx(1000.0, 1e6, 720, 45f);
        double r2 = BodyCuller.computeApparentRadiusPx(2000.0, 1e6, 720, 45f);
        assertEquals(r1 * 2.0, r2, 1e-9);
    }

    @Test
    void apparentRadius_scaledInverselyWithDistance() {
        double r1 = BodyCuller.computeApparentRadiusPx(1000.0, 1e6, 720, 45f);
        double r2 = BodyCuller.computeApparentRadiusPx(1000.0, 2e6, 720, 45f);
        assertEquals(r1 / 2.0, r2, 1e-9);
    }

    // ── Satellite classification ──────────────────────────────────────────────────────────────

    @Test
    void isSatellite_naturalMoons() {
        assertTrue(BodyCuller.isSatellite(301), "Moon (301) is a satellite");
        assertTrue(BodyCuller.isSatellite(401), "Phobos (401) is a satellite");
        assertTrue(BodyCuller.isSatellite(402), "Deimos (402) is a satellite");
        assertTrue(BodyCuller.isSatellite(501), "Io (501) is a satellite");
        assertTrue(BodyCuller.isSatellite(502), "Europa (502) is a satellite");
        assertTrue(BodyCuller.isSatellite(601), "Mimas (601) is a satellite");
        assertTrue(BodyCuller.isSatellite(701), "Ariel (701) is a satellite");
        assertTrue(BodyCuller.isSatellite(801), "Triton (801) is a satellite");
        assertTrue(BodyCuller.isSatellite(901), "Charon (901) is a satellite");
    }

    @Test
    void isSatellite_planetBodies_false() {
        assertFalse(BodyCuller.isSatellite(199), "Mercury (199) is a planet");
        assertFalse(BodyCuller.isSatellite(299), "Venus (299) is a planet");
        assertFalse(BodyCuller.isSatellite(399), "Earth (399) is a planet");
        assertFalse(BodyCuller.isSatellite(499), "Mars (499) is a planet");
        assertFalse(BodyCuller.isSatellite(599), "Jupiter (599) is a planet");
        assertFalse(BodyCuller.isSatellite(699), "Saturn (699) is a planet");
        assertFalse(BodyCuller.isSatellite(799), "Uranus (799) is a planet");
        assertFalse(BodyCuller.isSatellite(899), "Neptune (899) is a planet");
        assertFalse(BodyCuller.isSatellite(999), "Pluto (999) is a planet");
    }

    @Test
    void isSatellite_sun_false() {
        assertFalse(BodyCuller.isSatellite(10), "Sun (10) is not a satellite");
    }

    @Test
    void isSatellite_barycenters_false() {
        assertFalse(BodyCuller.isSatellite(1), "Mercury barycenter (1) is not a satellite");
        assertFalse(BodyCuller.isSatellite(3), "Earth-Moon barycenter (3) is not a satellite");
        assertFalse(BodyCuller.isSatellite(9), "Pluto barycenter (9) is not a satellite");
    }

    @Test
    void isSatellite_spacecraft_negative_false() {
        assertFalse(BodyCuller.isSatellite(-98),  "New Horizons (-98) is not a satellite");
        assertFalse(BodyCuller.isSatellite(-77),  "Cassini (-77) is not a satellite");
    }

    @Test
    void isSatellite_largeBodyIds_false() {
        // Asteroid NAIF IDs are 2000001+ — not in the satellite range
        assertFalse(BodyCuller.isSatellite(2000001), "Ceres (2000001) is not a satellite");
    }

    // ── Cull decision table ───────────────────────────────────────────────────────────────────

    @Test
    void decide_aboveThreshold_drawFull() {
        double aboveThreshold = KepplrConstants.POINT_SPRITE_THRESHOLD_PX + 1.0;
        // Satellite above threshold → DRAW_FULL
        assertEquals(CullDecision.DRAW_FULL, BodyCuller.decide(aboveThreshold, 301));
        // Planet above threshold → DRAW_FULL
        assertEquals(CullDecision.DRAW_FULL, BodyCuller.decide(aboveThreshold, 399));
        // Spacecraft (negative ID) above threshold → DRAW_FULL
        assertEquals(CullDecision.DRAW_FULL, BodyCuller.decide(aboveThreshold, -98));
    }

    @Test
    void decide_belowThreshold_satellite_culled() {
        double belowThreshold = KepplrConstants.POINT_SPRITE_THRESHOLD_PX - 0.5;
        assertEquals(CullDecision.CULL, BodyCuller.decide(belowThreshold, 301)); // Moon
        assertEquals(CullDecision.CULL, BodyCuller.decide(belowThreshold, 401)); // Phobos
        assertEquals(CullDecision.CULL, BodyCuller.decide(belowThreshold, 501)); // Io
    }

    @Test
    void decide_belowThreshold_nonSatellite_drawSprite() {
        double belowThreshold = KepplrConstants.POINT_SPRITE_THRESHOLD_PX - 0.5;
        assertEquals(CullDecision.DRAW_SPRITE, BodyCuller.decide(belowThreshold, 399)); // Earth
        assertEquals(CullDecision.DRAW_SPRITE, BodyCuller.decide(belowThreshold, 10));  // Sun
        assertEquals(CullDecision.DRAW_SPRITE, BodyCuller.decide(belowThreshold, -98)); // NH
    }

    @Test
    void decide_atExactThreshold_drawFull() {
        // Boundary: exactly at threshold is DRAW_FULL (≥ threshold)
        assertEquals(CullDecision.DRAW_FULL,
                BodyCuller.decide(KepplrConstants.POINT_SPRITE_THRESHOLD_PX, 301));
    }
}
