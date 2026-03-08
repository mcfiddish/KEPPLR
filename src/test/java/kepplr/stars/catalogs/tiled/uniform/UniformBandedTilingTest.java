package kepplr.stars.catalogs.tiled.uniform;

import static org.junit.Assert.assertEquals;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picante.math.intervals.UnwritableInterval;

public class UniformBandedTilingTest {

    /**
     * This boost is needed to the Math.ulp() function, as when the value is passed into the getBand or getTile method,
     * it is subtracted, etc. This shifts the significance into a different domain.
     */
    private static final double ULP_BOOST = 12.0;

    private static final double TOLERANCE_MINIMAL = 0;

    private static final double HALFPI = Math.PI / 2.0;
    private static final double TWOPI = 2.0 * Math.PI;
    private static final double FIFTHPI = Math.PI / 5.0;

    private UniformBandedTiling tiling;
    private int[] raTiles;

    @BeforeEach
    public void setUp() throws Exception {
        raTiles = new int[] {1, 3, 5, 4, 2};
        tiling = new UniformBandedTiling(raTiles);
    }

    @Test
    public void testConstructorException() {

        Assertions.assertThrows(IllegalArgumentException.class, () -> new UniformBandedTiling(new int[0]));
    }

    @Test
    public void testGetDeclinationBandSize() {
        assertEquals(Math.PI / raTiles.length, tiling.getDeclinationBandSize(), TOLERANCE_MINIMAL);
    }

    @Test
    public void testGetRightAscensionTileSizeLowerBoundException() {

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> tiling.getRightAscensionTileSize(-1));
    }

    @Test
    public void testGetRightAscensionTileSizeUpperBoundException() {

        Assertions.assertThrows(
                IndexOutOfBoundsException.class, () -> tiling.getRightAscensionTileSize(raTiles.length));
    }

    @Test
    public void testGetRightAscensionTileSize() {
        int i = 0;
        for (int nTiles : raTiles) {
            assertEquals(2.0 * Math.PI / nTiles, tiling.getRightAscensionTileSize(i++), TOLERANCE_MINIMAL);
        }
    }

    @Test
    public void testGetNumberOfBands() {
        assertEquals(raTiles.length, tiling.getNumberOfBands());
    }

    @Test
    public void testGetNumberOfTilesLowerBoundException() {

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> tiling.getNumberOfTiles(-1));
    }

    @Test
    public void testGetNumberOfTilesUpperBoundException() {

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> tiling.getNumberOfTiles(raTiles.length));
    }

    @Test
    public void testGetNumberOfTiles() {
        int i = 0;
        for (int nTiles : raTiles) {
            assertEquals(nTiles, tiling.getNumberOfTiles(i++));
        }
    }

    @Test
    public void testGetDeclinationRangeLowerBoundException() {
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> tiling.getDeclinationRange(-1));
    }

    @Test
    public void testGetDeclinationRangeUpperBoundException() {

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> tiling.getDeclinationRange(raTiles.length));
    }

    @Test
    public void testGetDeclinationRange() {
        double decBandSize = tiling.getDeclinationBandSize();
        double start = Math.PI / 2.0;
        for (int i = 0; i < raTiles.length; i++) {
            UnwritableInterval decRange = tiling.getDeclinationRange(i);
            assertEquals(start, decRange.getEnd(), TOLERANCE_MINIMAL);
            start -= decBandSize;
            assertEquals(start, decRange.getBegin(), TOLERANCE_MINIMAL);
        }
    }

    @Test
    public void testGetRightAscensionRangeLowerDecBoundException() {

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> tiling.getRightAscensionRange(-1, 0));
    }

    @Test
    public void testGetRightAscensionRangeUpperDecBoundException() {
        Assertions.assertThrows(
                IndexOutOfBoundsException.class, () -> tiling.getRightAscensionRange(raTiles.length, 0));
    }

    @Test
    public void testGetRightAscensionRangeLowerRaBoundException() {
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> tiling.getRightAscensionRange(2, -1));
    }

    @Test
    public void testGetRightAscensionRangeUpperRaBoundException() {
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> tiling.getRightAscensionRange(2, raTiles[2]));
    }

    @Test
    public void testGetRightAscensionRange() {

        for (int b = 0; b < raTiles.length; b++) {
            double start = 0;
            double raStepSize = tiling.getRightAscensionTileSize(b);
            for (int t = 0; t < raTiles[b]; t++) {
                UnwritableInterval raRange = tiling.getRightAscensionRange(b, t);
                assertEquals(start, raRange.getBegin(), TOLERANCE_MINIMAL);
                start += raStepSize;
                assertEquals(start, raRange.getEnd(), TOLERANCE_MINIMAL);
            }
        }
    }

    @Test
    public void testGetBand() {

        assertEquals(0, tiling.getBand(Math.PI));

        assertEquals(0, tiling.getBand(HALFPI + ULP_BOOST * Math.ulp(HALFPI)));

        for (int b = 0; b < tiling.getNumberOfBands(); b++) {

            UnwritableInterval range = tiling.getDeclinationRange(b);

            double top = range.getEnd();
            double bottom = range.getBegin();
            double middle = range.getMiddle();

            assertEquals(b, tiling.getBand(top - ULP_BOOST * Math.ulp(top)));
            assertEquals(b, tiling.getBand(middle));
            assertEquals(b, tiling.getBand(bottom + ULP_BOOST * Math.ulp(bottom)));
        }

        assertEquals(4, tiling.getBand(-HALFPI - ULP_BOOST * Math.ulp(-HALFPI)));

        /*
         * Just check that Math.floor() is used, instead of ceil.
         */
        for (int i = 0; i < raTiles.length; i++) {
            assertEquals(i, tiling.getBand(HALFPI - i * FIFTHPI));
        }

        assertEquals(raTiles.length - 1, tiling.getBand(-HALFPI));
    }

    @Test
    public void testGetTileZeroToTwoPi() {

        for (int b = 0; b < tiling.getNumberOfBands(); b++) {
            for (int t = 0; t < tiling.getNumberOfTiles(b); t++) {
                UnwritableInterval range = tiling.getRightAscensionRange(b, t);

                double left = range.getBegin();
                double right = range.getEnd();
                double middle = range.getMiddle();

                assertEquals(t, tiling.getTile(b, left + ULP_BOOST * Math.ulp(left)));
                assertEquals(t, tiling.getTile(b, middle));
                assertEquals(t, tiling.getTile(b, right - ULP_BOOST * Math.ulp(right)));

                /*
                 * Make certain floor is used.
                 */
                assertEquals(t, tiling.getTile(b, left));
            }
        }
    }

    @Test
    public void testGetTileTwoPiToFourPi() {

        for (int b = 0; b < tiling.getNumberOfBands(); b++) {
            for (int t = 0; t < tiling.getNumberOfTiles(b); t++) {
                UnwritableInterval range = tiling.getRightAscensionRange(b, t);

                double left = range.getBegin() + TWOPI;
                double right = range.getEnd() + TWOPI;
                double middle = range.getMiddle();
                assertEquals(t, tiling.getTile(b, left + ULP_BOOST * Math.ulp(left)));
                assertEquals(t, tiling.getTile(b, middle));
                assertEquals(t, tiling.getTile(b, right - ULP_BOOST * Math.ulp(right)));

                /*
                 * Make certain floor is used. This test won't pass, due to round off error.
                 */
                // assertEquals(t, tiling.getTile(b, left));
            }
        }
    }

    @Test
    public void testGetTileLowerBoundException() {

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> tiling.getTile(-1, 0));
    }

    @Test
    public void testGetTileUpperBoundException() {

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> tiling.getTile(raTiles.length, 0));
    }
}
