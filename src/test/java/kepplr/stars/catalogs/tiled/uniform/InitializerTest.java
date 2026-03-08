package kepplr.stars.catalogs.tiled.uniform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static picante.math.coords.AssertTools.assertEquivalentVector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picante.math.coords.CoordConverters;
import picante.math.coords.LatitudinalVector;
import picante.math.intervals.UnwritableInterval;
import picante.math.vectorspace.UnwritableVectorIJK;
import picante.math.vectorspace.VectorIJK;

public class InitializerTest {

    private UniformBandedTiling singleTopTile;
    private UniformBandedTiling multiTopTile;
    private Initializer initSingleTop;
    private Initializer initMultiTop;
    private int[] singleTopBands;
    private int[] multiTopBands;

    @BeforeEach
    public void setUp() throws Exception {
        singleTopBands = new int[] {1, 5, 10, 2};
        singleTopTile = new UniformBandedTiling(singleTopBands);
        initSingleTop = new Initializer(singleTopTile);
        multiTopBands = new int[] {2, 2, 2, 2, 1};
        multiTopTile = new UniformBandedTiling(multiTopBands);
        initMultiTop = new Initializer(multiTopTile);
    }

    @Test
    public void testGetCenterVectorsSingleTop() {

        UnwritableVectorIJK[][] centers = initSingleTop.getCenterVectors();

        /*
         * Treat the top band separately.
         */
        assertEquals(singleTopBands[0], centers[0].length);
        assertEquivalentVector(VectorIJK.K, centers[0][0]);

        for (int b = 1; b < singleTopTile.getNumberOfBands(); b++) {
            for (int t = 0; t < singleTopTile.getNumberOfTiles(b); t++) {
                assertEquals(singleTopBands[b], centers[b].length);
                assertEquivalentVector(
                        computeCenter(singleTopTile.getRightAscensionRange(b, t), singleTopTile.getDeclinationRange(b)),
                        centers[b][t]);
            }
        }
    }

    @Test
    public void testGetCenterVectorsMultiTop() {

        UnwritableVectorIJK[][] centers = initMultiTop.getCenterVectors();

        for (int b = 0; b < multiTopTile.getNumberOfBands() - 1; b++) {
            for (int t = 0; t < multiTopTile.getNumberOfTiles(b); t++) {
                assertEquals(multiTopBands[b], centers[b].length);
                assertEquivalentVector(
                        computeCenter(multiTopTile.getRightAscensionRange(b, t), multiTopTile.getDeclinationRange(b)),
                        centers[b][t]);
            }
        }

        /*
         * Handle the bottom band separately as it consists of a single tile.
         */
        assertEquals(multiTopBands[multiTopBands.length - 1], centers[centers.length - 1].length);
        assertEquivalentVector(VectorIJK.MINUS_K, centers[centers.length - 1][0]);
    }

    private UnwritableVectorIJK computeCenter(UnwritableInterval raRange, UnwritableInterval decRange) {
        return createVector(raRange.getMiddle(), decRange.getMiddle());
    }

    @Test
    public void testGetConeAngleAdditionSingleTop() {

        double checkAngle = initSingleTop.getConeAngleAddition();
        UnwritableVectorIJK[][] centers = initSingleTop.getCenterVectors();

        for (int b = 0; b < singleTopBands.length; b++) {
            for (int t = 0; t < singleTopBands[b]; t++) {
                for (UnwritableVectorIJK u : computeCorners(
                        singleTopTile.getRightAscensionRange(b, t), singleTopTile.getDeclinationRange(b))) {
                    assertTrue(b + " " + t, checkAngle >= centers[b][t].getSeparation(u));
                }
            }
        }
    }

    @Test
    public void testGetConeAngleAdditionMultiTop() {

        double checkAngle = initMultiTop.getConeAngleAddition();
        UnwritableVectorIJK[][] centers = initMultiTop.getCenterVectors();

        for (int b = 0; b < multiTopBands.length; b++) {
            for (int t = 0; t < multiTopBands[b]; t++) {
                for (UnwritableVectorIJK u : computeCorners(
                        multiTopTile.getRightAscensionRange(b, t), multiTopTile.getDeclinationRange(b))) {
                    assertTrue(b + " " + t, checkAngle >= centers[b][t].getSeparation(u));
                }
            }
        }
    }

    private UnwritableVectorIJK[] computeCorners(UnwritableInterval raRange, UnwritableInterval decRange) {
        UnwritableVectorIJK[] result = new UnwritableVectorIJK[4];

        result[0] = createVector(raRange.getBegin(), decRange.getBegin());
        result[1] = createVector(raRange.getBegin(), decRange.getEnd());
        result[2] = createVector(raRange.getEnd(), decRange.getBegin());
        result[3] = createVector(raRange.getEnd(), decRange.getEnd());

        return result;
    }

    private UnwritableVectorIJK createVector(double ra, double dec) {
        return CoordConverters.convert(new LatitudinalVector(1.0, dec, ra));
    }
}
