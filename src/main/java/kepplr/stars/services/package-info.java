/**
 * Star catalog service interfaces. The star catalog package utilizes the indirect data model, so the star interface
 * itself is rather sparse.
 *
 * <p>Catalog implementations provide implementations of a set of service interfaces that provide generic features.
 * These service interfaces may be aggregated into a derived implementation with the
 * {@link crucible.core.designpatterns.Patterns#createClassMapDelegate(java.util.Map, Class, Class)} method. Interfaces
 * in this services package are designed specifically to support this type of aggregation.
 *
 * @crucible.reliability semireliable
 * @crucible.volatility volatile
 * @crucible.disclosure group
 */
package kepplr.stars.services;
