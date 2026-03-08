package crucible.core.stars;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertTrue;

import kepplr.stars.*;
import org.junit.jupiter.api.Test;
import picante.junit.CaptureAndAnswer;
import picante.math.vectorspace.UnwritableVectorIJK;
import picante.math.vectorspace.VectorIJK;

public class TiledStarCatalogsTest {

    /**
     * This test is handled by the
     * {@link UniformBandTiledCatalogTest#testCreateMemoryTiledCatalogStarCatalogOfSDoubleDouble()}
     */
    public void testCreateMemoryTiledCatalogStarCatalogOfSLocationCalculatorDoubleDouble() {}

    /**
     * This test is handled by the
     * {@link UniformBandTiledCatalogTest#testCreateMemoryTiledCatalogStarCatalogOfSStarFilterOfQsuperSDoubleDouble()}
     */
    public void testCreateMemoryTiledCatalogStarCatalogOfSPredicateOfQsuperSLocationCalculatorDoubleDouble() {}

    @Test
    @SuppressWarnings("unchecked")
    public void testConcatTiledStarCatalogTiledStarCatalog() {

        TiledStarCatalog<Star> a = createMock(TiledStarCatalog.class);
        TiledStarCatalog<TestStar> b = createMock(TiledStarCatalog.class);
        final StarTile<Star> aTile = createMock(StarTile.class);
        final StarTile<TestStar> bTile = createMock(StarTile.class);

        TiledStarCatalog<Star> concat = TiledStarCatalogs.concat(a, b);

        UnwritableVectorIJK direction = VectorIJK.K;
        double angle = Math.toRadians(5.0);

        CaptureAndAnswer<TileSet<Star>> captureA = new CaptureAndAnswer<TileSet<Star>>() {
            @Override
            public void set(TileSet<Star> tile) {
                tile.add(aTile);
            }
        };

        CaptureAndAnswer<TileSet<Star>> captureB = new CaptureAndAnswer<TileSet<Star>>() {
            @Override
            public void set(TileSet<Star> tile) {
                tile.add(bTile);
            }
        };

        a.lookup(eq(direction), eq(angle), capture(captureA.getCapture()));
        expectLastCall().andAnswer(captureA);

        b.lookup(eq(direction), eq(angle), capture(captureB.getCapture()));
        expectLastCall().andAnswer(captureB);

        replay(a, b);

        TileSet<Star> result = new TileSet<Star>();

        concat.lookup(direction, angle, result);

        /*
         * Verify that result contains the two tiles we expect:
         */
        assertTrue(result.getTiles().contains(aTile));
        assertTrue(result.getTiles().contains(bTile));

        verify(a, b);
    }

    static class TestStar implements Star {

        @Override
        public String getID() {
            throw new UnsupportedOperationException();
        }

        @Override
        public double getMagnitude() {
            throw new UnsupportedOperationException();
        }

        @Override
        public VectorIJK getLocation(
                @SuppressWarnings("unused") double et, @SuppressWarnings("unused") VectorIJK buffer) {
            throw new UnsupportedOperationException();
        }
    }
}
