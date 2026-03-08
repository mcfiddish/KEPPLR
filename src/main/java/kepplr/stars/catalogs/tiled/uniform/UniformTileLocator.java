package kepplr.stars.catalogs.tiled.uniform;

import kepplr.stars.StarCatalogLookupException;
import picante.math.coords.CoordConverters;
import picante.math.coords.LatitudinalVector;
import picante.math.vectorspace.UnwritableVectorIJK;
import picante.math.vectorspace.VectorIJK;

/**
 * Class implementing a tile lookup over a uniform banded tiling. The design of this class and the several ones
 * connected to it is a bit strange. The tiling geometry is specified independently of any actual tile data. Normally
 * this might be handled by making the tiling interface parameterized where the type parameter is the data associated
 * with the tile itself. While this may be the natural way to handle it, the decision was made here to completely
 * divorce the tile geometry from the data. The {@link UniformBandedTiling} class has no knowledge of what it is tiling,
 * what so ever.
 *
 * <p>This afforded some nice properties, namely that instead of being an interface of some sort it is a simple class.
 * Further, the {@link TileReceiver} interface, allows this class to remain ignorant of the details of just how the data
 * is added to whatever is capturing individual tiles that result from a query. For reasons of flexibility, the
 * TileReceiver interface is parameterized. This allows the consumer of this class to decide whether or not a reference
 * to the object receiving the individual tile data should be passed through the utilized locator, or not. See the
 * TileLocator interface specification for a discussion of how to avoid having to pass this through.
 *
 * <p>This class is <b>not</b> thread-safe as implemented, but this is only due to the fact that the object performing
 * conversions from vectors to right ascension and declination is held as a buffer. Simply removing this, and
 * instantiating one in the query() method would make it thread-safe.
 *
 * <p>Notes about the query algorithm: this class caches the vector through the &quot;center&quot; of each tile whose
 * geometry is specified by the tiling. When a query is performed, the half-angle of the search cone is augmented by the
 * maximum angular separation between any tile and its boundary. While this will return extra tiles, it allows the query
 * to execute quickly. Selecting a tile size appropriate for your application will ultimately lead to higher
 * performance.
 *
 * @author F.S.Turner
 */
public class UniformTileLocator<R> {

    private final UniformBandedTiling tiling;

    /**
     * The half angle addition, i.e. maximum separation between the center of any tile and its boundary in the tiling.
     *
     * <p>TODO: Consider storing, instead of a single addition angle, the maximum angle associated with each tile. This
     * would allow the reduction, potentially, in the number of tiles returned by the algorithm.
     */
    private final double coneAngleAddition;

    /** Cache of center vectors. If tiling is ragged, so too is this array. */
    private final UnwritableVectorIJK[][] centers;

    /**
     * Creates a uniform tile locator from the specified tiling geometry.
     *
     * @param tiling the tile geometry
     */
    public UniformTileLocator(UniformBandedTiling tiling) {
        this.tiling = tiling;
        Initializer i = new Initializer(this.tiling);
        this.coneAngleAddition = i.getConeAngleAddition();
        this.centers = i.getCenterVectors();
    }

    /**
     * Queries the tiling geometry for tiles of interest with a simple cone.
     *
     * @param center a vector, with non-zero length, representing the center of a cone which contains the region of
     *     interest
     * @param halfAngle the half angle of the query cone, expressed in radians
     * @param receiver the class to receive the individual tile indices of tiles that may intersect the query cone
     * @param recipient the recipient, passed to the receiver when tiles are to be added.
     */
    public void query(UnwritableVectorIJK center, double halfAngle, TileReceiver<R> receiver, R recipient) {

        /*
         * TODO: a check should be put in here to handle the case where halfAngle + coneAngleAddition
         * exceeds Math.PI/2. Convexity of the region breaks down, and its not immediately clear the
         * algorithm will continue to function as intended.
         */

        /*
         * Unitize center into the local buffer.
         */
        VectorIJK centerBuffer = new VectorIJK(center).unitize();

        /*
         * Convert the supplied center into right ascension and declination.
         */
        LatitudinalVector coord = CoordConverters.convertToLatitudinal(centerBuffer);

        double ra = coord.getLongitude();
        double dec = coord.getLatitude();

        /*
         * Determine the declination bands that will need to be considered in responding to the query.
         * To do this we add and subtract from dec the requested halfAngle and coneAngleAddtion. Note:
         * it is entirely possible that either upper or lower will roll over the -PI/2 to PI/2 range
         * imposed by the tiling. This is of course a clear indication that either pole is within
         * halfAngle + coneAngleAddition of center.
         *
         * To accelerate the computational efficiency, compute the cosine of the query angle: (halfAngle
         * + coneAngleAddition). If the dot product of unit centerBuffer, and the element from the
         * centers array exceeds the test value, then the center of the tile is within the tolerance of
         * centerBuffer.
         */
        double testCosine = Math.cos(halfAngle + coneAngleAddition);

        for (int b = tiling.getBand(dec + halfAngle + coneAngleAddition);
                b <= tiling.getBand(dec - halfAngle - coneAngleAddition);
                b++) {
            int startTile = tiling.getTile(b, ra);
            processDecBand(b, startTile, centerBuffer, testCosine, receiver, recipient);
        }
    }

    /**
     * Process a declination band, locating any tiles that may fall inside the query cone.
     *
     * @param band the declination band of interest
     * @param startTile the tile at which to start looking, it contains the specified center vector
     * @param center a unit vector at the center of the query cone
     * @param testCosine the cosine of the allowed angular separation between the center of each tile and the center of
     *     the query cone. Usually the cone half angle plus the maximum separation between the center of a tile and its
     *     set of edge vectors
     * @param receiver the tile receiver, code that populates the recipient with actual tile data
     * @param recipient the recipient of tile data passed along to receiver
     */
    private void processDecBand(
            int band,
            int startTile,
            UnwritableVectorIJK center,
            double testCosine,
            TileReceiver<R> receiver,
            R recipient) {

        /*
         * Since the simple, circular cone used to request tiles is a convex region, the same must be
         * true of the map projected space when it is considered through its connection back to three
         * dimensions. So, start at the tile containing ra, and move away from it in increasing and
         * decreasing right ascension search for tiles until a break occurs.
         *
         * Start the search in the decreasing tile direction. Continue looping until either a tile is
         * found that does not meet the criteria, or the the result reaches back to the starting value.
         * Assume center was unitized before being supplied to this routine.
         */
        int t = startTile;

        while (center.getDot(centers[band][t]) > testCosine) {
            receiver.add(band, t, recipient);
            t--;

            if (t < 0) {
                t = centers[band].length - 1;
            }

            /*
             * If t wraps completely around to the start tile, then every tile was added just once to the
             * receiver. Return to the caller as no searching in the opposite direction is required.
             */
            if (t == startTile) {
                return;
            }
        }

        int nTiles = centers[band].length;

        t = (startTile + 1) % nTiles;

        while (center.getDot(centers[band][t]) > testCosine) {

            receiver.add(band, t, recipient);
            t++;

            /*
             * Handle the wrapping around across the last tile.
             */
            if (t == nTiles) {
                t = 0;
            }

            /*
             * If this algorithm works properly, the following should never happen. Warn the user if it
             * does, not that they will be able to do much.
             */
            if (t == startTile) {
                throw new StarCatalogLookupException("Tile search algorithm failure.  This should never "
                        + "happen under normal execution circumstances.  Band: " + band
                        + " wrapped around in the increasing tile " + "search, after failing to do so in the "
                        + "decreasing search.");
            }
        }
    }
}
