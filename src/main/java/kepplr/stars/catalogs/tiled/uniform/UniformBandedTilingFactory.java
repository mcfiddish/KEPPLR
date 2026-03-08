package kepplr.stars.catalogs.tiled.uniform;

/**
 * Factory methods that create <code>UniformBandedTiling</code> from various map projections and other criteria.
 *
 * @author F.S.Turner
 */
public class UniformBandedTilingFactory {

    /**
     * Creates a quasi-sinusoidal map projection tile structure based on the supplied tile size. If the tile size
     * specified does not cleanly divide into the various angle ranges involved, the method selects one that is slightly
     * smaller that does accommodate the range. This particular map projection attempts to create tiles of roughly
     * uniform solid angle.
     *
     * @param tileSize the target size of a tile expressed in radians.
     * @return a newly created <code>UniformBandedTiling</code> that strives to match the supplied tile size
     * @throws IllegalArgumentException if tileSize is not strictly positive.
     */
    public static UniformBandedTiling createSinusoidalTiling(double tileSize) {

        if (tileSize <= 0) {
            throw new IllegalArgumentException(
                    "Invalid tile size: " + tileSize + " specified.  It must be a positive number.");
        }

        int numDecBands = (int) Math.ceil(Math.PI / tileSize);
        double decBandSize = Math.PI / (numDecBands);

        double raScale = 2.0 * Math.PI / tileSize;

        int[] raBandTiles = new int[numDecBands];

        for (int b = 0; b < numDecBands; b++) {
            int numTile = (int) Math.ceil(Math.sin((b + 0.5) * decBandSize) * raScale);
            raBandTiles[b] = numTile;
        }

        return new UniformBandedTiling(raBandTiles);
    }
}
