package kepplr.render.frustum;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FrustumLayer} assignment (REDESIGN.md §8.1, §8.2, §8.3).
 *
 * <p>All expected values are derived from the base ranges in {@link KepplrConstants} with 10% overlap applied. Base
 * ranges (km):
 *
 * <ul>
 *   <li>NEAR: [0.001, 1 000] → camera near/far [0.001, 1 100]
 *   <li>MID: [1 000, 1×10⁹] → camera near/far [900, 1.1×10⁹]
 *   <li>FAR: [1×10⁹, 1×10¹⁵] → camera near/far [9×10⁸, 1×10¹⁵]
 * </ul>
 */
class FrustumLayerTest {

    // ── Frustum plane values ──────────────────────────────────────────────────────────────────

    @Test
    void nearPlanes() {
        assertEquals(0.001, FrustumLayer.NEAR.nearKm, 1e-6, "NEAR near plane");
        assertEquals(1_100.0, FrustumLayer.NEAR.farKm, 1e-3, "NEAR far plane (+10%)");
    }

    @Test
    void midPlanes() {
        assertEquals(900.0, FrustumLayer.MID.nearKm, 1e-3, "MID near plane (−10%)");
        assertEquals(1.1e9, FrustumLayer.MID.farKm, 1e3, "MID far plane (+10%)");
    }

    @Test
    void farPlanes() {
        assertEquals(9e8, FrustumLayer.FAR.nearKm, 1e3, "FAR near plane (−10%)");
        assertEquals(1e15, FrustumLayer.FAR.farKm, 1.0, "FAR far plane");
    }

    // ── Point bodies (zero radius) fully inside a single layer ───────────────────────────────

    @Test
    void pointInNear_assignsNear() {
        assertEquals(FrustumLayer.NEAR, FrustumLayer.assign(0.5, 0.0)); // 500 m
        assertEquals(FrustumLayer.NEAR, FrustumLayer.assign(100.0, 0.0)); // 100 km
        assertEquals(FrustumLayer.NEAR, FrustumLayer.assign(999.0, 0.0)); // just inside
    }

    @Test
    void pointInMid_assignsMid() {
        assertEquals(FrustumLayer.MID, FrustumLayer.assign(1e6, 0.0)); // 1 million km
        assertEquals(FrustumLayer.MID, FrustumLayer.assign(1e8, 0.0)); // 100 million km
    }

    @Test
    void pointInFar_assignsFar() {
        assertEquals(FrustumLayer.FAR, FrustumLayer.assign(2e9, 0.0)); // 2 billion km
        assertEquals(FrustumLayer.FAR, FrustumLayer.assign(1e12, 0.0)); // 1 trillion km
    }

    // ── Bounding-volume fits within a single layer ────────────────────────────────────────────

    @Test
    void earthAtOneAU_fitsMid() {
        // Earth at ~1.5×10^8 km, radius ~6371 km — easily within MID
        assertEquals(FrustumLayer.MID, FrustumLayer.assign(1.5e8, 6_371.0));
    }

    @Test
    void moonAtEarthDistance_fitsMid() {
        // Moon ~384400 km, radius ~1737 km
        assertEquals(FrustumLayer.MID, FrustumLayer.assign(384_400.0, 1_737.0));
    }

    @Test
    void saturnSystemAtSaturnDistance_fitsFar() {
        // Saturn ~1.4e9 km, radius ~58232 km
        assertEquals(FrustumLayer.FAR, FrustumLayer.assign(1.4e9, 58_232.0));
    }

    @Test
    void sunAtNearSolarOrbit_fitsMid() {
        // Camera 1e7 km from Sun, Sun radius ~695700 km — Sun body stays in MID
        assertEquals(FrustumLayer.MID, FrustumLayer.assign(1e7, 695_700.0));
    }

    // ── Overlap zone: point in Near–Mid overlap [900, 1100] km ───────────────────────────────

    @Test
    void pointAt950km_assignsNear_notMid() {
        // 950 km is inside NEAR expanded range [0.001, 1100]; NEAR is preferred (nearest-first)
        assertEquals(FrustumLayer.NEAR, FrustumLayer.assign(950.0, 0.0));
    }

    @Test
    void pointAt1050km_assignsNear() {
        // 1050 km is still inside NEAR far plane (1100 km)
        assertEquals(FrustumLayer.NEAR, FrustumLayer.assign(1050.0, 0.0));
    }

    @Test
    void pointAt1200km_assignsMid() {
        // 1200 km exceeds NEAR far plane (1100 km) but is inside MID [900, 1.1e9]
        assertEquals(FrustumLayer.MID, FrustumLayer.assign(1200.0, 0.0));
    }

    // ── Overlap zone: point in Mid–Far overlap [9×10^8, 1.1×10^9] km ────────────────────────

    @Test
    void pointAt9_5e8_assignsMid() {
        // 9.5×10^8 km is inside MID far plane (1.1×10^9); MID preferred over FAR
        assertEquals(FrustumLayer.MID, FrustumLayer.assign(9.5e8, 0.0));
    }

    @Test
    void pointAt1_05e9_assignsMid() {
        // 1.05×10^9 km is inside MID far plane (1.1×10^9); MID still preferred
        assertEquals(FrustumLayer.MID, FrustumLayer.assign(1.05e9, 0.0));
    }

    @Test
    void pointAt1_2e9_assignsFar() {
        // 1.2×10^9 km exceeds MID far plane (1.1×10^9)
        assertEquals(FrustumLayer.FAR, FrustumLayer.assign(1.2e9, 0.0));
    }

    // ── Body spanning multiple layers: near-edge priority ────────────────────────────────────

    @Test
    void largeBodySpanningNearMid_assignsNear() {
        // Body centered at 1050 km, radius 500 km → lo=550, hi=1550.
        // lo=550 < NEAR.farKm=1100 → NEAR is chosen to avoid clipping the near edge.
        // The far hemisphere (1100–1550 km) is occluded by the body itself from this vantage;
        // clipping it at the NEAR far plane causes no visible artifact.
        assertEquals(FrustumLayer.NEAR, FrustumLayer.assign(1050.0, 500.0));
    }

    @Test
    void closeApproachToLargeMoon_assignsNear() {
        // Camera ~1660 km from Europa center (radius 1560 km) → lo=100, hi=3220.
        // Near edge at 100 km is within NEAR range; MID near plane (900 km) would clip it.
        assertEquals(FrustumLayer.NEAR, FrustumLayer.assign(1660.0, 1560.0));
    }

    @Test
    void europaAt2458km_assignsNear() {
        // Screenshot regression: dist=2458 km, radius=1562 km → lo=896 km.
        // The near edge is still inside the NEAR band, so MID would clip the centre cap.
        assertEquals(FrustumLayer.NEAR, FrustumLayer.assign(2458.0, 1562.0));
    }

    @Test
    void europaAt2461km_assignsNear() {
        // Screenshot regression: dist=2461 km, radius=1562 km → lo=899 km.
        // Still too close to hand off to MID; keep the body in NEAR.
        assertEquals(FrustumLayer.NEAR, FrustumLayer.assign(2461.0, 1562.0));
    }

    @Test
    void landerOnSurface_assignsNear() {
        // Camera on Europa surface: dist ≈ radius → lo ≈ 0, hi ≈ 3120.
        // Must use NEAR so the surface geometry is not clipped.
        assertEquals(FrustumLayer.NEAR, FrustumLayer.assign(1560.0, 1560.0));
    }

    @Test
    void nearNearPlane_assignsNear() {
        // Exactly at the NEAR near plane (1 m = 0.001 km) — should be NEAR
        assertEquals(FrustumLayer.NEAR, FrustumLayer.assign(0.001, 0.0));
    }
}
