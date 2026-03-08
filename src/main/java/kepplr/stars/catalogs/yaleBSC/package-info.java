/**
 *
 *
 * <h1>Yale Bright Star Catalog (BSC5 / VizieR V/50) Java reader</h1>
 *
 * <p>This directory contains:
 *
 * <ul>
 *   <li><code>YaleBrightStar</code> – an immutable <code>Star</code> implementation
 *   <li><code>YaleBrightStarCatalog</code> – a loader + in-memory <code>StarCatalog</code> and <code>TiledStarCatalog
 *       </code> implementation
 * </ul>
 *
 * <h2>Input file</h2>
 *
 * <p>Use the VizieR <code>V/50/catalog</code> fixed-width <a
 * href="http://tdc-www.harvard.edu/catalogs/bsc5.html">file</a>, record length 197 bytes. The catalog provides RA/Dec
 * at equinox J2000, epoch 2000.0 with proper motions <code>pmRA</code> and <code>pmDE</code> in arcsec/year. The <code>
 * pmRA</code> field is the projected value <code>cos(dec) * d(ra)/dt</code> (common convention for proper motion in
 * right ascension).
 *
 * <h2>Star Query</h2>
 *
 * <div class="sourceCode" id="cb1">
 *
 * <pre class="sourceCode java"><code class="sourceCode java">
 * YaleBrightStarCatalog bsc = YaleBrightStarCatalog.loadFromResource("/kepplr/stars/catalogs/yaleBSC/ybsc5.gz");
 * YaleBrightStar sirius = bsc.getStar("BSC5:HR2491");
 * System.out.println(sirius.getMagnitude());
 * </code></pre>
 *
 * </div>
 *
 * <h2>Cone query (tiled lookup)</h2>
 *
 * <p><code>YaleBrightStarCatalog.lookup(location, coneHalfAngle, result)</code> performs an O(N) scan and adds all
 * stars within the cone. <div class="sourceCode" id="cb1">
 *
 * <pre class="sourceCode java"><code class="sourceCode java">
 * // retrieve stars in the Pleiades
 * UnwritableVectorIJK lookDir = CoordConverters.convert(new RaDecVector(1.0, Math.toRadians(15*(3. + 47./60 + 24./3600)), Math.toRadians(24 + 7/60. + 0/3600.)));
 * TileSet<? super Star> tileSet = new TileSet<>();
 * bsc.lookup(lookDir, Math.toRadians(1), tileSet);
 * </code></pre>
 *
 * </div>
 */
package kepplr.stars.catalogs.yaleBSC;
