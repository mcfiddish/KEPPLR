package kepplr.render.vector;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.VectorIJK;

/**
 * Unit tests for {@link VectorRenderer#computeArrowLengthKm} and {@link VectorType#usesOriginBodyRadius()}.
 *
 * <p>Verifies that arrow length scales correctly with body mean radius and per-definition scale factor, and that
 * body-axis vectors report origin-body scaling while other types report focused-body scaling.
 */
@DisplayName("VectorRenderer — arrow length scaling")
class VectorRendererScalingTest {

    private static final double MULTIPLE = KepplrConstants.VECTOR_ARROW_FOCUS_BODY_RADIUS_MULTIPLE;

    @Test
    @DisplayName("scaleFactor=1.0 produces length = radius × MULTIPLE")
    void defaultScaleProducesRadiusTimesMultiple() {
        double radius = 6371.0; // Earth mean radius (km)
        double result = VectorRenderer.computeArrowLengthKm(radius, 1.0);
        assertEquals(radius * MULTIPLE, result, 1e-9, "scaleFactor=1.0 must produce radius × MULTIPLE");
    }

    @Test
    @DisplayName("scaleFactor=2.0 doubles the arrow length relative to scaleFactor=1.0")
    void doubleScaleFactorDoublesLength() {
        double radius = 6371.0;
        double single = VectorRenderer.computeArrowLengthKm(radius, 1.0);
        double doubled = VectorRenderer.computeArrowLengthKm(radius, 2.0);
        assertEquals(2.0 * single, doubled, 1e-9, "scaleFactor=2.0 must double the arrow length");
    }

    @Test
    @DisplayName("tip is at MULTIPLE × radius from body centre for scaleFactor=1.0")
    void tipAtThreeRadiiFromCentre() {
        double radius = 71492.0; // Jupiter equatorial radius (km)
        double arrowLength = VectorRenderer.computeArrowLengthKm(radius, 1.0);
        // Arrow originates at body centre; tip distance from centre = arrowLength.
        assertEquals(MULTIPLE * radius, arrowLength, 1e-6, "tip must be at MULTIPLE × radius from body centre");
    }

    @Test
    @DisplayName("focus body change produces a different arrow length")
    void focusBodyChangeProducesDifferentLength() {
        double earthRadius = 6371.0;
        double jupiterRadius = 69911.0; // Jupiter mean radius (km)
        double earthArrow = VectorRenderer.computeArrowLengthKm(earthRadius, 1.0);
        double jupiterArrow = VectorRenderer.computeArrowLengthKm(jupiterRadius, 1.0);
        assertNotEquals(earthArrow, jupiterArrow, "different focus bodies must produce different arrow lengths");
        assertTrue(jupiterArrow > earthArrow, "Jupiter (larger body) must produce a longer arrow than Earth");
    }

    @Test
    @DisplayName("scaleFactor=0.5 halves the arrow length")
    void halfScaleFactorHalvesLength() {
        double radius = 6371.0;
        double full = VectorRenderer.computeArrowLengthKm(radius, 1.0);
        double half = VectorRenderer.computeArrowLengthKm(radius, 0.5);
        assertEquals(full / 2.0, half, 1e-9, "scaleFactor=0.5 must halve the arrow length");
    }

    // ── usesOriginBodyRadius tests ──────────────────────────────────────────

    @Test
    @DisplayName("bodyAxisX uses origin body radius")
    void bodyAxisXUsesOriginBodyRadius() {
        assertTrue(VectorTypes.bodyAxisX().usesOriginBodyRadius(), "bodyAxisX must use origin body radius");
    }

    @Test
    @DisplayName("bodyAxisY uses origin body radius")
    void bodyAxisYUsesOriginBodyRadius() {
        assertTrue(VectorTypes.bodyAxisY().usesOriginBodyRadius(), "bodyAxisY must use origin body radius");
    }

    @Test
    @DisplayName("bodyAxisZ uses origin body radius")
    void bodyAxisZUsesOriginBodyRadius() {
        assertTrue(VectorTypes.bodyAxisZ().usesOriginBodyRadius(), "bodyAxisZ must use origin body radius");
    }

    @Test
    @DisplayName("velocity does not use origin body radius")
    void velocityDoesNotUseOriginBodyRadius() {
        assertFalse(VectorTypes.velocity().usesOriginBodyRadius(), "velocity must use focused body radius");
    }

    @Test
    @DisplayName("towardBody does not use origin body radius")
    void towardBodyDoesNotUseOriginBodyRadius() {
        assertFalse(VectorTypes.towardBody(10).usesOriginBodyRadius(), "towardBody must use focused body radius");
    }

    @Test
    @DisplayName("default usesOriginBodyRadius returns false")
    void defaultUsesOriginBodyRadiusReturnsFalse() {
        VectorType custom = new VectorType() {
            @Override
            public VectorIJK computeDirection(int originNaifId, double et) {
                return new VectorIJK(1, 0, 0);
            }

            @Override
            public String toScript() {
                return "custom()";
            }
        };
        assertFalse(custom.usesOriginBodyRadius(), "default implementation must return false");
    }
}
