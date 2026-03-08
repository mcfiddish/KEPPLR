/**
 * Gaia tile-pack star catalog.
 *
 * <p>This package provides a Gaia-backed implementation of your star-catalog interfaces that scales to very large
 * datasets by using an on-disk <em>tile pack</em> (random-access, per-tile compressed blocks) rather than loading the
 * full catalog into memory or embedding data inside a JAR.
 *
 * <h2>Runtime types</h2>
 *
 * <ul>
 *   <li>{@link crucible.mantle.stars.catalogs.gaia.GaiaStar} – minimal immutable {@code Star} record:
 *       <ul>
 *         <li>{@code source_id} (Gaia source identifier)
 *         <li>Unit direction vector {@code (x,y,z)} in ICRS/J2000
 *         <li>Gaia G-band magnitude {@code phot_g_mean_mag}
 *       </ul>
 *       {@code getLocation(et,...)} returns the stored direction (no epoch propagation by default).
 *   <li>{@link crucible.mantle.stars.catalogs.gaia.GaiaCatalog} – implements {@code StarCatalog<GaiaStar>} and
 *       {@code TiledStarCatalog<GaiaStar>} backed by a tile pack directory.
 * </ul>
 *
 * <h2>Star IDs</h2>
 *
 * <p>Stars are identified as:
 *
 * <pre>{@code
 * GAIA:<source_id>
 * }</pre>
 *
 * <h2>Tile pack directory</h2>
 *
 * <p>A tile pack is a directory containing:
 *
 * <ul>
 *   <li><b>{@code gaia.properties}</b> – metadata (tiling + data release + reference epoch)
 *   <li><b>{@code gaia.idx}</b> – per-tile index table: {@code (offset:long, lengthBytes:int, count:int)} for every
 *       tile id
 *   <li><b>{@code gaia.dat}</b> – concatenated, per-tile compressed blocks (zlib)
 *   <li><b>{@code gaia.sourceidx}</b> (optional) – sorted by {@code source_id}, enables {@code getStar("GAIA:<id>")}
 * </ul>
 *
 * <h3>Tile record layout</h3>
 *
 * <p>Each tile block contains {@code count} fixed-size records:
 *
 * <pre>{@code
 * long  sourceId
 * float x
 * float y
 * float z
 * float gMag
 * }</pre>
 *
 * <h2>Metadata: data release and reference epoch</h2>
 *
 * <p>{@code gaia.properties} records:
 *
 * <ul>
 *   <li>{@code gaia.dataRelease} – e.g. {@code DR3}
 *   <li>{@code gaia.refEpoch} – reference epoch as a Julian year (e.g. {@code 2016.0})
 * </ul>
 *
 * <p>{@link crucible.mantle.stars.catalogs.gaia.GaiaCatalog} exposes these via:
 *
 * <ul>
 *   <li>{@link crucible.mantle.stars.catalogs.gaia.GaiaCatalog#getDataRelease()}
 *   <li>{@link crucible.mantle.stars.catalogs.gaia.GaiaCatalog#getReferenceEpochYear()}
 * </ul>
 *
 * <h2>Lookup behavior</h2>
 *
 * <p>{@code lookup(location, coneHalfAngle, result)} loads only tiles in a conservative covering for the cone. Each
 * tile is then filtered by dot-product so returned stars are within the requested cone.
 *
 * <h2>Important note on “cone-built tiles”</h2>
 *
 * <p>If you build or update your pack from a cone query, any intersecting tile will generally be <em>incomplete</em>
 * (it contains only stars in that cone, not the full tile footprint). This is acceptable only if your tile-update
 * workflow can later <em>replace</em> tiles with more complete data (for example: overwrite mode in a merge tool, or a
 * “tile completeness” marker tracked in metadata).
 */
package kepplr.stars.catalogs.gaia;
