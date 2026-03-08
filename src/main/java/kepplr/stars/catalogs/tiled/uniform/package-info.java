/**
 * An implementation of the {@link crucible.core.stars.TiledStarCatalog} interface. Tiled catalogs, or catalogs
 * implementing them through delegation can utilize the tiling system provided by this package.
 *
 * <p>Support for creating (tiling) existing {@link crucible.core.stars.StarCatalog} implementations exists as well, so
 * long as one is willing to ingest the entire catalog into memory.
 *
 * <p>If you are not writing your own implementation of the {@link crucible.core.stars.TiledStarCatalog} interface, then
 * stick to the static methods:
 *
 * <ul>
 *   <li>{@link crucible.core.stars.TiledStarCatalogs#createMemoryTiledCatalog(crucible.core.stars.StarCatalog,
 *       crucible.stars.services.LocationCalculator, double, double)}
 *   <li>{@link crucible.core.stars.TiledStarCatalogs#createMemoryTiledCatalog(crucible.core.stars.StarCatalog,
 *       com.google.common.base.Predicate, crucible.stars.services.LocationCalculator, double, double)}
 * </ul>
 *
 * The other classes in the package are public so that implementors of the tiled catalog interface may leverage their
 * capabilities.
 *
 * @crucible.reliability semireliable
 * @crucible.volatility volatile
 * @crucible.disclosure group
 */
package kepplr.stars.catalogs.tiled.uniform;
