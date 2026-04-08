package kepplr.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.RotationMatrixIJK;
import picante.math.vectorspace.VectorIJK;
import picante.mechanics.EphemerisID;
import picante.surfaces.Ellipsoid;
import picante.surfaces.Surfaces;

@DisplayName("InstrumentFrustumManager intersection helpers")
class InstrumentFrustumManagerTest {

    private static final EphemerisID TEST_BODY = () -> "TEST_BODY";

    @Test
    @DisplayName("intersectBodyEllipsoid returns nearest forward hit in body-fixed coordinates")
    void intersectBodyEllipsoidReturnsForwardHit() {
        Ellipsoid shape = Surfaces.createEllipsoidalSurface(10.0, 10.0, 10.0);
        RotationMatrixIJK identity = new RotationMatrixIJK();

        InstrumentFrustumManager.BodyIntersection hit = InstrumentFrustumManager.intersectBodyEllipsoid(
                new VectorIJK(-20.0, 0.0, 0.0),
                new VectorIJK(1.0, 0.0, 0.0),
                new VectorIJK(0.0, 0.0, 0.0),
                identity,
                shape,
                TEST_BODY);

        assertNotNull(hit);
        assertEquals(TEST_BODY, hit.bodyId());
        assertEquals(-10.0, hit.hitBodyFixed().getI(), 1e-9);
        assertEquals(0.0, hit.hitBodyFixed().getJ(), 1e-9);
        assertEquals(0.0, hit.hitBodyFixed().getK(), 1e-9);
        assertEquals(10.0, hit.distanceKm(), 1e-9);
    }

    @Test
    @DisplayName("intersectBodyEllipsoid returns null when the ray misses the ellipsoid")
    void intersectBodyEllipsoidReturnsNullOnMiss() {
        Ellipsoid shape = Surfaces.createEllipsoidalSurface(10.0, 10.0, 10.0);
        RotationMatrixIJK identity = new RotationMatrixIJK();

        InstrumentFrustumManager.BodyIntersection hit = InstrumentFrustumManager.intersectBodyEllipsoid(
                new VectorIJK(-20.0, 0.0, 0.0),
                new VectorIJK(0.0, 1.0, 0.0),
                new VectorIJK(0.0, 0.0, 0.0),
                identity,
                shape,
                TEST_BODY);

        assertNull(hit);
    }

    @Test
    @DisplayName("intersectTargetBody reuses the target body geometry and rotation")
    void intersectTargetBodyUsesTargetMetadata() {
        Ellipsoid shape = Surfaces.createEllipsoidalSurface(10.0, 10.0, 10.0);
        RotationMatrixIJK identity = new RotationMatrixIJK();
        InstrumentFrustumManager.BodyIntersection target = new InstrumentFrustumManager.BodyIntersection(
                TEST_BODY,
                new VectorIJK(100.0, 0.0, 0.0),
                identity,
                shape,
                new VectorIJK(-10.0, 0.0, 0.0),
                0.0);

        InstrumentFrustumManager.BodyIntersection hit = InstrumentFrustumManager.intersectTargetBody(
                new VectorIJK(80.0, 0.0, 0.0), new VectorIJK(1.0, 0.0, 0.0), target);

        assertNotNull(hit);
        assertEquals(TEST_BODY, hit.bodyId());
        assertEquals(-10.0, hit.hitBodyFixed().getI(), 1e-9);
        assertEquals(0.0, hit.hitBodyFixed().getJ(), 1e-9);
        assertEquals(0.0, hit.hitBodyFixed().getK(), 1e-9);
        assertEquals(10.0, hit.distanceKm(), 1e-9);
    }
}
