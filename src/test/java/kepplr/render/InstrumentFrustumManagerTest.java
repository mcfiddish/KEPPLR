package kepplr.render;

import static org.junit.jupiter.api.Assertions.*;

import com.jme3.math.ColorRGBA;
import java.lang.reflect.Modifier;
import java.util.List;
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
                TEST_BODY, new VectorIJK(100.0, 0.0, 0.0), identity, shape, new VectorIJK(-10.0, 0.0, 0.0), 0.0);

        InstrumentFrustumManager.BodyIntersection hit = InstrumentFrustumManager.intersectTargetBody(
                new VectorIJK(80.0, 0.0, 0.0), new VectorIJK(1.0, 0.0, 0.0), target);

        assertNotNull(hit);
        assertEquals(TEST_BODY, hit.bodyId());
        assertEquals(-10.0, hit.hitBodyFixed().getI(), 1e-9);
        assertEquals(0.0, hit.hitBodyFixed().getJ(), 1e-9);
        assertEquals(0.0, hit.hitBodyFixed().getK(), 1e-9);
        assertEquals(10.0, hit.distanceKm(), 1e-9);
    }

    @Test
    @DisplayName("footprint color control exists as an internal render-manager hook")
    void footprintColorControlIsInternal() throws NoSuchMethodException {
        var method = InstrumentFrustumManager.class.getDeclaredMethod(
                "setFootprintColors", int.class, ColorRGBA.class, ColorRGBA.class);

        assertFalse(Modifier.isPublic(method.getModifiers()));
    }

    @Test
    @DisplayName("retained footprint implementation has per-vertex color storage")
    void retainedFootprintsHavePerVertexColorStorage() throws ClassNotFoundException, NoSuchFieldException {
        Class<?> overlayClass = Class.forName("kepplr.render.InstrumentFrustumManager$PersistentCoverageOverlay");

        assertEquals(
                java.util.List.class,
                overlayClass.getDeclaredField("recordedColors").getType());
        assertEquals(
                java.nio.FloatBuffer.class,
                overlayClass.getDeclaredField("colorBuffer").getType());
    }

    @Test
    @DisplayName("copyColor returns an independent RGBA value")
    void copyColorReturnsIndependentValue() {
        ColorRGBA original = new ColorRGBA(0.1f, 0.2f, 0.3f, 0.4f);
        ColorRGBA copy = InstrumentFrustumManager.copyColor(original);

        assertNotSame(original, copy);
        assertEquals(original.r, copy.r);
        assertEquals(original.g, copy.g);
        assertEquals(original.b, copy.b);
        assertEquals(original.a, copy.a);

        original.r = 0.9f;
        assertEquals(0.1f, copy.r);
    }

    @Test
    @DisplayName("sameColor compares RGBA components exactly")
    void sameColorComparesRgbaComponents() {
        assertTrue(InstrumentFrustumManager.sameColor(
                new ColorRGBA(0.1f, 0.2f, 0.3f, 0.4f), new ColorRGBA(0.1f, 0.2f, 0.3f, 0.4f)));
        assertFalse(InstrumentFrustumManager.sameColor(
                new ColorRGBA(0.1f, 0.2f, 0.3f, 0.4f), new ColorRGBA(0.1f, 0.2f, 0.3f, 0.5f)));
    }

    @Test
    @DisplayName("retained swath color changes force a new persistent segment")
    void persistentSegmentStartsWhenColorChanges() {
        ColorRGBA blue = new ColorRGBA(0f, 0f, 1f, 0.35f);
        ColorRGBA sameBlue = new ColorRGBA(0f, 0f, 1f, 0.35f);
        ColorRGBA purple = new ColorRGBA(1f, 0f, 1f, 0.35f);

        assertTrue(InstrumentFrustumManager.startsNewPersistentSegment(false, List.of(), blue));
        assertFalse(InstrumentFrustumManager.startsNewPersistentSegment(false, List.of(blue), sameBlue));
        assertTrue(InstrumentFrustumManager.startsNewPersistentSegment(false, List.of(blue), purple));
        assertTrue(InstrumentFrustumManager.startsNewPersistentSegment(true, List.of(blue), sameBlue));
    }
}
