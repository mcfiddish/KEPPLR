package kepplr.stars.catalogs.tiled.uniform;

import picante.math.coords.CoordConverters;
import picante.math.coords.LatitudinalVector;
import picante.math.intervals.UnwritableInterval;
import picante.math.vectorspace.UnwritableVectorIJK;
import picante.math.vectorspace.VectorIJK;

/**
 * Internal assistance class used to adapt the contents of a UniformBandedTiling into the array of tile center unit
 * vectors and an augmentation to a cone angle supplied in a simple query cone as required by the UniformTileLocator.
 *
 * <p>It is not intended for the consumer of this class to retain a reference past initialization.
 *
 * @author F.S.Turner
 */
class Initializer {

    /**
     * A slight increase, via product, to the derived maximum angular separation between each tile boundary and its
     * center vector. This is done, only to insure that the uniform tile locator class is able to perform its job.
     */
    private static final double ANGLE_ADJUSTMENT = 1.000001;

    private UnwritableVectorIJK[][] centers;
    private double coneAngleAddition = -Double.MAX_VALUE;

    /**
     * Creates an initializer that derives the products required for instantiating a <code>UniformTileLocator</code>
     * from the supplied tiling.
     *
     * @param tiling a uniform, declination banded tiling
     */
    public Initializer(UniformBandedTiling tiling) {

        /*
         * Loop over all of the tiles and populate the various structures used to manage the
         * construction..
         */
        int numBand = tiling.getNumberOfBands();

        centers = new UnwritableVectorIJK[numBand][];

        processTriangularBand(tiling, 0);
        processTriangularBand(tiling, numBand - 1);

        for (int b = 1; b < numBand - 1; b++) {
            processBand(tiling, b);
        }
    }

    /**
     * Retrieves a reference to the ragged array of tile center vectors.
     *
     * @return an unwritable array of unit vectors
     */
    public UnwritableVectorIJK[][] getCenterVectors() {
        return centers;
    }

    /**
     * Retrieves the maximum angular separation between any tile in tiling and the tile center vector
     *
     * @return an angle in radians
     */
    public double getConeAngleAddition() {
        /*
         * Return a slight increase in the angular addition, to handle any issues with round off in
         * computations.
         */
        return coneAngleAddition * ANGLE_ADJUSTMENT;
    }

    /**
     * Updates the cone angle addition, if the supplied angle exceeds the current value
     *
     * @param angle angle in question, expressed in radians
     */
    private void updateConeAngleAddition(double angle) {
        if (angle > coneAngleAddition) {
            coneAngleAddition = angle;
        }
    }

    /**
     * Processes a standard, &quot;square&quot; band from a tiling
     *
     * @param tiling the tiling of interest
     * @param bandIndex the index of the band to process from tiling
     */
    private void processBand(UniformBandedTiling tiling, int bandIndex) {

        centers[bandIndex] = new UnwritableVectorIJK[tiling.getNumberOfTiles(bandIndex)];

        UnwritableInterval decRange = tiling.getDeclinationRange(bandIndex);

        for (int t = 0; t < tiling.getNumberOfTiles(bandIndex); t++) {

            UnwritableInterval raRange = tiling.getRightAscensionRange(bandIndex, t);

            centers[bandIndex][t] = createCenterVector(raRange, decRange);

            UnwritableVectorIJK v = convertToVector(raRange.getBegin(), decRange.getEnd());
            updateConeAngleAddition(v.getSeparation(centers[bandIndex][t]));

            v = convertToVector(raRange.getBegin(), decRange.getBegin());
            updateConeAngleAddition(v.getSeparation(centers[bandIndex][t]));
        }
    }

    /**
     * Processes a triangular (top or bottom) band from a tiling
     *
     * @param tiling the tiling of interest
     * @param bandIndex the index of the band to process from tiling, must be either 0 or tiling.getNumberOfBands()-1,
     *     though no explicit checking is performed
     */
    private void processTriangularBand(UniformBandedTiling tiling, int bandIndex) {

        boolean isTopBand = (bandIndex == 0);

        if (tiling.getNumberOfTiles(bandIndex) == 1) {

            /*
             * In this case there is a single tile covering the triangular top or bottom band. So just
             * assign the center vector to be the pole. Note: which pole is determined by whether this is
             * the top or bottom band.
             */
            centers[bandIndex] = new UnwritableVectorIJK[1];
            centers[bandIndex][0] = (isTopBand) ? VectorIJK.K : VectorIJK.MINUS_K;
            updateConeAngleAddition(tiling.getDeclinationBandSize());

        } else {

            UnwritableInterval decRange = tiling.getDeclinationRange(bandIndex);

            /*
             * There are multiple tiles in the top band. These tiles are necessarily triangular.
             */
            centers[bandIndex] = new UnwritableVectorIJK[tiling.getNumberOfTiles(bandIndex)];

            for (int t = 0; t < tiling.getNumberOfTiles(bandIndex); t++) {

                UnwritableInterval raRange = tiling.getRightAscensionRange(bandIndex, t);

                centers[bandIndex][t] = createCenterVector(raRange, decRange);

                /*
                 * Handle the fact that the top band and bottom band need to be treated differently here,
                 * due to the fact that the top or bottom of the tile is a single vertex in 3D space.
                 */
                UnwritableVectorIJK v =
                        convertToVector(raRange.getBegin(), (isTopBand) ? decRange.getBegin() : decRange.getEnd());

                /*
                 * Due to the symmetry of the triangular tile, we need only consider the separation between
                 * the top vertex of the tile and one of the base vertices.
                 */
                updateConeAngleAddition(v.getSeparation(centers[bandIndex][t]));
                updateConeAngleAddition(tiling.getDeclinationBandSize() / 2.0);
            }
        }
    }

    /**
     * Creates a center vector from the supplied tile extents
     *
     * @param raRange the extent of the tile in right ascension
     * @param decRange the extent of the tile in declination
     * @return a vector in the &quot;center&quot; of the tile. Center is defined to be simply the vector whose right
     *     ascension and declination lie in the middle of the specified ranges
     */
    private UnwritableVectorIJK createCenterVector(UnwritableInterval raRange, UnwritableInterval decRange) {
        return convertToVector(raRange.getMiddle(), decRange.getMiddle());
    }

    /**
     * Converts a right ascension and declination into a unit vector.
     *
     * @param ra the right ascension of interest, expressed in radians
     * @param dec the declination of interest, expressed in radians
     * @return a reference to buffer for convenience
     */
    private UnwritableVectorIJK convertToVector(double ra, double dec) {
        return CoordConverters.convert(new LatitudinalVector(1.0, dec, ra));
    }
}
