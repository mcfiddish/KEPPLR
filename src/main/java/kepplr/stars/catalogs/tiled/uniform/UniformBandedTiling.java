package kepplr.stars.catalogs.tiled.uniform;

import picante.math.intervals.UnwritableInterval;

/**
 * Tiling that consists of uniform bands of declination and uniform tiles of right ascension within a given declination
 * band. The tiling should start with the (0,0) tile having a top declination boundary of PI/2, and proceeding from
 * there. For example:
 *
 * <pre>
 * +-----------------------------------------------------+ +PI/2
 * |                                                     |
 * | (0,0)                                               |
 * +-----------------------------------------------------+
 * |                          |                          |
 * | (1,0)                    | (1,1)                    |
 * +-----------------------------------------------------+   D
 * |            |             |             |            |   E
 * | (2,0)      | (2,1)       | (2,2)       | (2,3)      |   C
 * +-----------------------------------------------------+
 * |                          |                          |
 * | (3,0)                    | (3,1)                    |
 * +-----------------------------------------------------+
 * |                                                     |
 * | (4,0)                                               |
 * +-----------------------------------------------------+ -PI/2
 *
 * 0                        RA                          2*PI
 * </pre>
 *
 * <p>Note: Even though the tiling illustrated above is symmetric about the equator, this is not a specific requirement.
 * The individual declination bands must be the same size in declination angle, and for a given band each of the tiles
 * must be the same size in right ascension.
 *
 * @author F.S.Turner
 */
public class UniformBandedTiling {

    /** Static numeric constant containing 2*Math.PI */
    private static final double TWOPI = Math.PI * 2.0;

    /** Static numeric constant containing Math.PI/2 */
    private static final double HALFPI = Math.PI / 2.0;

    private final int numDecBands;
    private final int[] raBandTiles;

    /**
     * Creates a <code>UniformBandedTiling</code> from the supplied banding parameters. The number of declination bands
     * is inferred from the length of the supplied array containing the tile counts per band.
     *
     * @param raBandTiles an array of tile counts per each declination band, these tile counts must be explicitly
     *     greater than 0.
     */
    public UniformBandedTiling(int[] raBandTiles) {

        if (raBandTiles.length == 0) {
            throw new IllegalArgumentException(
                    "The right ascension band tiles array input must" + " have at least 1 element.");
        }

        this.numDecBands = raBandTiles.length;
        this.raBandTiles = new int[raBandTiles.length];
        System.arraycopy(raBandTiles, 0, this.raBandTiles, 0, raBandTiles.length);
    }

    /**
     * Obtains the range of the declination bands
     *
     * @return the declination band size in radians
     */
    public double getDeclinationBandSize() {
        return Math.PI / numDecBands;
    }

    /**
     * Obtains the size of a particular right ascension declination band's tile
     *
     * @param band the index of the declination band of interest
     * @return the size, in radians, of each right ascension tile in the band
     * @throws IndexOutOfBoundsException if band is out of range
     */
    public double getRightAscensionTileSize(int band) {
        return TWOPI / raBandTiles[band];
    }

    /**
     * Retrieves the number of declination bands.
     *
     * @return the number of declination bands in the tiling.
     */
    public int getNumberOfBands() {
        return raBandTiles.length;
    }

    /**
     * Retrieves the number of tiles in a particular declination band.
     *
     * @param band the index of the band of interest
     * @return the number of tiles in the requested band
     * @throws IndexOutOfBoundsException if band is out of range
     */
    public int getNumberOfTiles(int band) {
        return raBandTiles[band];
    }

    /**
     * Retrieves an interval capturing the declination range for a particular band.
     *
     * @param band index of the band of interest.
     * @return an interval, expressed in radians, of the range of the band
     * @throws IndexOutOfBoundsException if band is out of range
     */
    public UnwritableInterval getDeclinationRange(int band) {
        /*
         * Cheap, dirty trick to check the range on band and force the IndexOutOfBoundsException.
         */
        double decBandSize = raBandTiles[band];
        decBandSize = Math.PI / numDecBands;
        return (band == raBandTiles.length - 1)
                ? new UnwritableInterval(-HALFPI, HALFPI - (band) * decBandSize)
                : new UnwritableInterval(HALFPI - (band + 1) * decBandSize, HALFPI - (band) * decBandSize);
    }

    /**
     * Retrieves an interval capturing the right ascension range for a particular tile in a particular band.
     *
     * @param band the index of the band in which the tile of interest lives
     * @param tile the index of the tile of interest
     * @return an interval, expressed in radians, of the right ascension range of the requested tile
     * @throws IndexOutOfBoundsException if either band or tile are out of range
     */
    public UnwritableInterval getRightAscensionRange(int band, int tile) {

        if (tile > raBandTiles[band] - 1 || tile < 0) {
            throw new IndexOutOfBoundsException(
                    "Tile index: " + tile + " is out of range: [0," + (raBandTiles[band] - 1) + "].");
        }

        double tileSize = TWOPI / raBandTiles[band];
        return (tile == raBandTiles[band] - 1)
                ? new UnwritableInterval(tile * tileSize, TWOPI)
                : new UnwritableInterval(tile * tileSize, (tile + 1) * tileSize);
    }

    /**
     * Computes the index into the band for a given declination. It is clamped to be a valid value, namely values
     * greater than PI/2 are clamped to the 0 index, and similiar for values less than -PI/2 are clamped to the highest
     * band index.
     *
     * <p>Note: Due to round off errors in the computations involved here, it is entirely possible that the index
     * returned for values within a few Math.ulp(value) of the transition between bands will be different from the
     * angles captured by getDeclinationRange(value).
     *
     * @param dec the declination, in radians
     * @return an index for a particular band containing declination
     */
    public int getBand(double dec) {
        return (int) Math.max(0, Math.min(numDecBands - 1, Math.floor((HALFPI - dec) / Math.PI * numDecBands)));
    }

    /**
     * Computes the index of the tile for a given band and right ascension. It is wrapped via the modulo (%) operator to
     * bring it into the valid [0,TWOPI] range.
     *
     * <p>Note: Due to round off errors in computations involved here, it is entirely possible that the index returned
     * for values within a few Math.ulp(value) of the transition between tiles will be different from the angles
     * captured by getRightAscensionRange(band,tile).
     *
     * @param band the index of the declination band in which ra lies
     * @param ra the right ascension, in radians
     * @return the index of the tile containing ra, in the declination band, band.
     * @throws IndexOutOfBoundsException if band is an inappropriate value.
     */
    public int getTile(int band, double ra) {
        double normRa = (ra < 0) ? ra % TWOPI + TWOPI : ra % TWOPI;

        return (int) Math.max(0, Math.min(raBandTiles[band] - 1, Math.floor(normRa / TWOPI * raBandTiles[band])));
    }
}
