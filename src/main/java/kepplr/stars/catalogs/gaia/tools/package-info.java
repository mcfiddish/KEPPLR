/**
 *
 *
 * <h1>Gaia Star Catalog Tools</h1>
 *
 * <p>This package contains utilities used to generate or enrich on-disk Gaia tile packs. These tools are intended to
 * run outside your library at build-time or as separate command-line utilities.
 *
 * <h2>Downloading Gaia region exports as CSV</h2>
 *
 * <p>Gaia DR3 is served primarily via the Gaia Archive TAP service (ADQL). For a specific sky region you typically run
 * an ADQL cone query against {@code gaiadr3.gaia_source} and download the result as CSV.
 *
 * <p>This package’s Java builder expects an export containing at least:
 *
 * <ul>
 *   <li>{@code source_id}
 *   <li>{@code ra} (degrees)
 *   <li>{@code dec} (degrees)
 *   <li>{@code phot_g_mean_mag}
 * </ul>
 *
 * <p>TSV is recommended if you want to avoid CSV quoting complexity. However CSV is fine if your export is not quoted;
 * the builder can also be driven by column indices without a header.
 *
 * <p>The following script is a reference implementation you can copy into your own tooling repo. It uses
 * {@code astroquery} to submit an async TAP job and download results directly to a CSV file on disk.
 *
 * <h3>Script: gaia_download_region.py</h3>
 *
 * <pre>{@code
 * #!/usr/bin/env python3
 * """Download a Gaia region (cone) from the Gaia Archive as CSV for tile-pack building.
 *
 * Requirements:
 *   pip install astroquery
 *
 * Notes:
 * - This uses an async TAP job and writes results directly to disk (does not load all rows into memory).
 * - For DR3 use --release gaiadr3 (table: gaiadr3.gaia_source).
 * - Output columns match the Java tile-pack builder defaults:
 *     source_id, ra, dec, phot_g_mean_mag
 *
 * Example:
 *   python gaia_download_region.py \
 *     --release gaiadr3 \
 *     --ra 83.8221 --dec -5.3911 --radius 2.0 \
 *     --max-gmag 18.0 \
 *     --out orion.csv
 * """
 *
 * import argparse
 * from astroquery.gaia import Gaia
 *
 *
 * def main():
 *   ap = argparse.ArgumentParser()
 *   ap.add_argument("--release", default="gaiadr3",
 *                   choices=["gaiadr3", "gaiaedr3", "gaiadr2"],
 *                   help="Gaia schema to query (default: gaiadr3).")
 *   ap.add_argument("--ra", type=float, required=True, help="Center RA in degrees (ICRS).")
 *   ap.add_argument("--dec", type=float, required=True, help="Center Dec in degrees (ICRS).")
 *   ap.add_argument("--radius", type=float, required=True, help="Cone radius in degrees.")
 *   ap.add_argument("--max-gmag", type=float, default=18.0, help="Magnitude cap on phot_g_mean_mag.")
 *   ap.add_argument("--out", required=True, help="Output CSV filename.")
 *   ap.add_argument("--overwrite", action="store_true", help="Overwrite output file if it exists.")
 *   args = ap.parse_args()
 *
 *   table = f"{args.release}.gaia_source"
 *
 *   # Minimal columns required by GaiaCsvToTilePack:
 *   cols = "source_id, ra, dec, phot_g_mean_mag"
 *
 *   # ADQL cone query. CONTAINS(POINT, CIRCLE) is the standard TAP geometry predicate.
 *   query = f"""
 * SELECT {cols}
 * FROM {table}
 * WHERE 1 = CONTAINS(
 *   POINT('ICRS', ra, dec),
 *   CIRCLE('ICRS', {args.ra}, {args.dec}, {args.radius})
 * )
 * AND phot_g_mean_mag <= {args.max_gmag}
 * """.strip()
 *
 *   # astroquery will refuse to overwrite unless you remove the file, so handle it explicitly.
 *   import os
 *   if os.path.exists(args.out):
 *     if args.overwrite:
 *       os.remove(args.out)
 *     else:
 *       raise SystemExit(f"Output file exists: {args.out} (use --overwrite)")
 *
 *   # Submit and download to disk.
 *   # dump_to_file=True writes the result directly to output_file.
 *   job = Gaia.launch_job_async(
 *     query,
 *     dump_to_file=True,
 *     output_file=args.out,
 *     output_format="csv"
 *   )
 *
 *   # Useful for logging/debug:
 *   print("Job ID:", job.jobid)
 *   print("Wrote:", args.out)
 *
 *
 * if __name__ == "__main__":
 *   main()
 * }</pre>
 *
 * <h2>Primary Java tools</h2>
 *
 * <h3>1) Build a tile pack from a Gaia export</h3>
 *
 * <pre>{@code
 * java GaiaCsvToTilePack \
 *   --input gaia_subset.csv.gz \
 *   --out /path/to/gaia_pack \
 *   --header true \
 *   --delim ',' \
 *   --max-gmag 18.0 \
 *   --dr DR3 \
 *   --ref-epoch 2016.0 \
 *   --nlat 360 \
 *   --nlon 720 \
 *   --chunk-records 2000000
 * }</pre>
 *
 * <ul>
 *   <li>{@code --nlat/--nlon} controls tile granularity (more tiles → smaller per-tile blocks).
 *   <li>{@code --chunk-records} controls memory/performance tradeoffs for the external sort.
 *   <li>{@code --max-gmag} builds a magnitude-limited subset (common for rendering).
 * </ul>
 *
 * <h3>2) Build an optional source-id index (enables getStar)</h3>
 *
 * <pre>{@code
 * java GaiaBuildSourceIndex \
 *   --pack /path/to/gaia_pack \
 *   --chunk-entries 5000000
 * }</pre>
 *
 * <p>This produces {@code gaia.sourceidx}. Without it, {@code GaiaCatalog.getStar("GAIA:<id>")} should be considered
 * unsupported.
 *
 * <h2>Merging / incrementally updating an existing pack</h2>
 *
 * <p>When you download Gaia data as a cone (region) query, any tiles intersecting that cone are generally
 * <em>partial</em> (they contain only the stars in the cone, not the full tile footprint). This is fine if your
 * workflow supports updating tiles over time by merging new stars into existing tiles.
 *
 * <h3>GaiaMergeTilePacks</h3>
 *
 * <p>{@code GaiaMergeTilePacks} combines two packs that share the same tiling and record format and produces a new
 * pack. The merge policy should be:
 *
 * <ul>
 *   <li><b>Per tile: union stars by {@code source_id}</b> (adds new stars to an existing tile)
 *   <li>If the same {@code source_id} appears in both packs, keep one record (either is fine if identical; otherwise
 *       prefer the one from the “secondary” pack or define a deterministic rule).
 * </ul>
 *
 * <p>This union semantics allows partial tiles to become progressively more complete as additional cone downloads are
 * merged in.
 *
 * <h3>Merge command</h3>
 *
 * <pre>{@code
 * java crucible.core.stars.gaia.tools.GaiaMergeTilePacks \
 *   --primary /path/to/existing_pack \
 *   --secondary /path/to/new_region_pack \
 *   --out /path/to/merged_pack
 * }</pre>
 *
 * <p>Typical workflow for “download + add stars”:
 *
 * <ol>
 *   <li>Download a region CSV using {@code gaia_download_region.py}.
 *   <li>Build a region pack from that CSV using {@link crucible.mantle.stars.catalogs.gaia.tools.GaiaCsvToTilePack}.
 *   <li>Merge region pack into your existing pack using
 *       {@link crucible.mantle.stars.catalogs.gaia.tools.GaiaMergeTilePacks} (union by {@code source_id}).
 *   <li><b>Always rebuild {@code gaia.sourceidx}</b> after any merge (see
 *       {@link crucible.mantle.stars.catalogs.gaia.tools.GaiaBuildSourceIndex}).
 * </ol>
 *
 * <h3>Rebuild source-id index (required after merge)</h3>
 *
 * <pre>{@code
 * java crucible.core.stars.gaia.tools.GaiaBuildSourceIndex \
 *   --pack /path/to/merged_pack \
 *   --chunk-entries 5000000
 * }</pre>
 *
 * <p>Assumption: {@code gaia.sourceidx} should be treated as a derived artifact and rebuilt after any update.
 */
package kepplr.stars.catalogs.gaia.tools;
