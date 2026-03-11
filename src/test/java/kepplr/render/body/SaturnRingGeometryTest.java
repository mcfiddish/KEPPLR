package kepplr.render.body;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.RotationMatrixIJK;

/**
 * Unit tests for Saturn ring geometry constants and static helpers in SaturnRingManager.
 */
class SaturnRingGeometryTest {

    @Test
    void ringRadiiConstantsArePhysicallyPlausible() {
        // D ring inner edge ~74,490 km; F ring outer edge ~140,180 km
        assertTrue(KepplrConstants.SATURN_RING_INNER_RADIUS_KM > 70_000.0);
        assertTrue(KepplrConstants.SATURN_RING_INNER_RADIUS_KM < 80_000.0);
        assertTrue(KepplrConstants.SATURN_RING_OUTER_RADIUS_KM > 130_000.0);
        assertTrue(KepplrConstants.SATURN_RING_OUTER_RADIUS_KM < 150_000.0);
        assertTrue(KepplrConstants.SATURN_RING_OUTER_RADIUS_KM > KepplrConstants.SATURN_RING_INNER_RADIUS_KM);
    }

    @Test
    void appliesToBodyReturnsTrueOnlyForSaturn() {
        assertTrue(SaturnRingManager.appliesToBody(KepplrConstants.SATURN_NAIF_ID));
        assertFalse(SaturnRingManager.appliesToBody(399)); // Earth
        assertFalse(SaturnRingManager.appliesToBody(599)); // Jupiter
        assertFalse(SaturnRingManager.appliesToBody(799)); // Uranus
        assertFalse(SaturnRingManager.appliesToBody(10));  // Sun
        assertFalse(SaturnRingManager.appliesToBody(0));
    }

    @Test
    void extractRingPlaneNormalReturnsNullForNullInput() {
        assertNull(SaturnRingManager.extractRingPlaneNormal(null));
    }

    @Test
    void extractRingPlaneNormalFromIdentityIsZAxis() {
        // Identity rotation: J2000 == body-fixed. Ring normal = Z = (0,0,1).
        RotationMatrixIJK identity = new RotationMatrixIJK(
                1, 0, 0,
                0, 1, 0,
                0, 0, 1);
        double[] normal = SaturnRingManager.extractRingPlaneNormal(identity);

        assertNotNull(normal);
        assertEquals(3, normal.length);
        assertEquals(0.0, normal[0], 1e-12);
        assertEquals(0.0, normal[1], 1e-12);
        assertEquals(1.0, normal[2], 1e-12);
    }

    @Test
    void extractRingPlaneNormalReturnsUnitVector() {
        // Rotation Rx(90°): body Z points along +J2000 Y.
        // Matrix (row, col):
        //  row0: (1,  0,  0)
        //  row1: (0,  0, -1)
        //  row2: (0,  1,  0)
        // RotationMatrixIJK constructor is column-major (ii, ji, ki, ij, jj, kj, ik, jk, kk):
        //  col0=(1,0,0), col1=(0,0,1), col2=(0,-1,0)
        // Ring normal = row2 of R = (0, 1, 0) in J2000.
        RotationMatrixIJK rotX90 = new RotationMatrixIJK(
                1, 0,  0,   // col 0: (ii=1, ji=0, ki=0)
                0, 0,  1,   // col 1: (ij=0, jj=0, kj=1)
                0, -1, 0);  // col 2: (ik=0, jk=-1, kk=0)
        double[] normal = SaturnRingManager.extractRingPlaneNormal(rotX90);

        assertNotNull(normal);
        double len = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
        assertEquals(1.0, len, 1e-10, "Ring plane normal must be a unit vector");

        // Expected: (0, 1, 0)
        assertEquals(0.0, normal[0], 1e-12);
        assertEquals(1.0, normal[1], 1e-12);
        assertEquals(0.0, normal[2], 1e-12);
    }

    @Test
    void saturnNaifIdConstantIs699() {
        assertEquals(699, KepplrConstants.SATURN_NAIF_ID);
    }
}
