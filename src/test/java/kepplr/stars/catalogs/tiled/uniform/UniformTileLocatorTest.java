package kepplr.stars.catalogs.tiled.uniform;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picante.math.coords.CoordConverters;
import picante.math.coords.LatitudinalVector;
import picante.math.vectorspace.UnwritableVectorIJK;
import picante.math.vectorspace.VectorIJK;

public class UniformTileLocatorTest {

    private int numRaTiles[];
    private UniformBandedTiling tiling;
    private UniformTileLocator<Object> locator;
    private Initializer initializer;
    private TestReceiver<Object> receiver;

    @BeforeEach
    public void setUp() throws Exception {
        numRaTiles = new int[] {4, 8, 12, 9, 5};
        tiling = new UniformBandedTiling(numRaTiles);
        locator = new UniformTileLocator<Object>(tiling);
        receiver = new TestReceiver<Object>();
        initializer = new Initializer(tiling);
    }

    @Test
    public void testNorthPoleQueryTenthTileSize() {
        double angle = initializer.getConeAngleAddition() / 10.0;
        UnwritableVectorIJK vector = VectorIJK.K;

        locator.query(vector, angle, receiver, null);
        tileTest(receiver.getTiles(), vector, angle);
    }

    @Test
    public void testNorthPoleQueryTileSize() {
        double angle = initializer.getConeAngleAddition();
        UnwritableVectorIJK vector = VectorIJK.K;

        locator.query(vector, angle, receiver, null);
        tileTest(receiver.getTiles(), vector, angle);
    }

    @Test
    public void testNorthPoleQueryFiveTileSize() {
        double angle = 5.0 * initializer.getConeAngleAddition();
        UnwritableVectorIJK vector = VectorIJK.K;

        locator.query(vector, angle, receiver, null);
        tileTest(receiver.getTiles(), vector, angle);
    }

    @Test
    public void testSouthPoleQueryTenthTileSize() {
        double angle = initializer.getConeAngleAddition() / 10.0;
        UnwritableVectorIJK vector = VectorIJK.MINUS_K;

        locator.query(vector, angle, receiver, null);
        tileTest(receiver.getTiles(), vector, angle);
    }

    @Test
    public void testSouthPoleQueryTileSize() {
        double angle = initializer.getConeAngleAddition();
        UnwritableVectorIJK vector = VectorIJK.MINUS_K;

        locator.query(vector, angle, receiver, null);
        tileTest(receiver.getTiles(), vector, angle);
    }

    @Test
    public void testSouthPoleQueryFiveTileSize() {
        double angle = 5.0 * initializer.getConeAngleAddition();
        UnwritableVectorIJK vector = VectorIJK.MINUS_K;

        locator.query(vector, angle, receiver, null);
        tileTest(receiver.getTiles(), vector, angle);
    }

    @Test
    public void testCornerTileQueryTenthTileSize() {
        double angle = initializer.getConeAngleAddition() / 10.0;
        int b = 2;
        int t = 5;
        UnwritableVectorIJK vector = CoordConverters.convert(new LatitudinalVector(
                1.0,
                tiling.getDeclinationRange(b).getBegin(),
                tiling.getRightAscensionRange(b, t).getBegin()));

        locator.query(vector, angle, receiver, null);
        tileTest(receiver.getTiles(), vector, angle);
    }

    @Test
    public void testCornerTileQueryTileSize() {
        double angle = initializer.getConeAngleAddition();
        int b = 2;
        int t = 5;

        UnwritableVectorIJK vector = CoordConverters.convert(new LatitudinalVector(
                1.0,
                tiling.getDeclinationRange(b).getBegin(),
                tiling.getRightAscensionRange(b, t).getBegin()));

        locator.query(vector, angle, receiver, null);
        tileTest(receiver.getTiles(), vector, angle);
    }

    @Test
    public void testCornerTileQueryFiveTileSize() {
        double angle = 5.0 * initializer.getConeAngleAddition();
        int b = 2;
        int t = 5;

        UnwritableVectorIJK vector = CoordConverters.convert(new LatitudinalVector(
                1.0,
                tiling.getDeclinationRange(b).getBegin(),
                tiling.getRightAscensionRange(b, t).getBegin()));

        locator.query(vector, angle, receiver, null);
        tileTest(receiver.getTiles(), vector, angle);
    }

    @Test
    public void testMapEdgeQueryTenthTileSize() {
        double angle = initializer.getConeAngleAddition() / 10.0;
        VectorIJK vector = new VectorIJK(1.0, 0.0, -0.5);
        locator.query(vector, angle, receiver, null);
        tileTest(receiver.getTiles(), vector, angle);
    }

    @Test
    public void testMapEdgeQueryTileSize() {
        double angle = initializer.getConeAngleAddition();
        VectorIJK vector = new VectorIJK(1.0, 0.0, -0.5);
        locator.query(vector, angle, receiver, null);
        tileTest(receiver.getTiles(), vector, angle);
    }

    @Test
    public void testMapEdgeQueryFiveTileSize() {
        double angle = 5.0 * initializer.getConeAngleAddition();
        VectorIJK vector = new VectorIJK(1.0, 0.0, -0.5);
        locator.query(vector, angle, receiver, null);
        tileTest(receiver.getTiles(), vector, angle);
    }

    private void tileTest(Set<Pair> set, UnwritableVectorIJK v, double angle) {

        angle += initializer.getConeAngleAddition();
        UnwritableVectorIJK[][] tileCenters = initializer.getCenterVectors();

        for (int b = 0; b < tileCenters.length; b++) {
            for (int t = 0; t < tileCenters[b].length; t++) {
                if (v.getSeparation(tileCenters[b][t]) < angle) {
                    assertTrue(set.contains(new Pair(b, t)));
                } else {
                    assertFalse(set.contains(new Pair(b, t)));
                }
            }
        }
    }
}

class TestReceiver<R> implements TileReceiver<R> {

    private final Set<Pair> tiles = new TreeSet<Pair>();

    @Override
    public void add(int band, int tile, @SuppressWarnings("unused") R recipient) {
        Pair newPair = new Pair(band, tile);

        if (tiles.contains(newPair)) {
            throw new RuntimeException("Duplicate tile detected.");
        }

        tiles.add(newPair);
    }

    public void reset() {
        tiles.clear();
    }

    public Set<Pair> getTiles() {
        return tiles;
    }
}

class Pair implements Comparable<Pair> {

    public Pair(int band, int tile) {
        super();
        this.band = band;
        this.tile = tile;
    }

    int band;
    int tile;

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + band;
        result = prime * result + tile;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof Pair)) {
            return false;
        }
        Pair other = (Pair) obj;
        if (band != other.band) {
            return false;
        }
        if (tile != other.tile) {
            return false;
        }
        return true;
    }

    @Override
    public int compareTo(Pair o) {

        if (o.band == this.band) {
            return (this.tile < o.tile ? -1 : (this.tile == o.tile ? 0 : 1));
        }

        return (this.band < o.band ? -1 : (this.band == o.band ? 0 : 1));
    }
}
