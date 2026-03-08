package kepplr.stars;

import picante.math.vectorspace.UnwritableVectorIJK;

/**
 * Interface defining a tiled star catalog. The purpose of this interface is to allow accelerated queries of star
 * catalogs that leverage a tiled geometry. Stars are packed into individual {@link StarTile}s that cover portions of
 * the sky. The catalog interface is a prescription for locating tiles by specifying a particular location of interest
 * and a cone region of the sky.
 *
 * @author F.S.Turner
 * @param <S> the type of star for which the catalog is defined
 */
public interface TiledStarCatalog<S extends Star> {

    /**
     * Queries the tiled catalog for tiles located at a particular region on the sky.
     *
     * <p>Implementor's notes: The tile set supplied by the caller must be cleared prior to populating any of the tiles
     * that may be intersected by the cone. For performance reasons, tiles may be present in the set that are not
     * actually covered by the cone of interest. This is permitted, due to the added complexity of managing the
     * intersection of the cone and arbitrary tile geometries.
     *
     * @param location the center of a cone of interest
     * @param coneHalfAngle the half angle of the cone of interest
     * @param result a tile set of the resultant tiles that may be covered by the cone of interest
     */
    public void lookup(UnwritableVectorIJK location, double coneHalfAngle, TileSet<? super S> result);
}
