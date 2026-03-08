package kepplr.stars.catalogs.gaia.tools;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import kepplr.stars.catalogs.gaia.LatLonTiling;
import picante.math.vectorspace.UnwritableVectorIJK;

/**
 * Builds a Gaia tile pack (gaia.properties/gaia.idx/gaia.dat) from a delimited text export.
 *
 * <h2>Input</h2>
 *
 * A CSV/TSV-like file with at least these columns (by name):
 *
 * <ul>
 *   <li>{@code source_id} (unsigned 64-bit)
 *   <li>{@code ra} (deg)
 *   <li>{@code dec} (deg)
 *   <li>{@code phot_g_mean_mag} (mag)
 * </ul>
 *
 * <p>Any additional columns are ignored by this builder.
 *
 * <h2>Output format</h2>
 *
 * For each tile, we write a compressed block containing {@code count} fixed-size records:
 *
 * <pre>
 * long   sourceId
 * float  x
 * float  y
 * float  z
 * float  gMag
 * </pre>
 *
 * <p>{@code gaia.idx} contains, for every tile in order:
 *
 * <pre>
 * long offset
 * int  lengthBytes
 * int  count
 * </pre>
 *
 * <h2>Scalability strategy</h2>
 *
 * The builder uses an external sort:
 *
 * <ol>
 *   <li>First pass: stream CSV -> write binary temp records with (tileId + star record).
 *   <li>Sort temp records by tileId in chunked runs.
 *   <li>K-way merge runs while writing per-tile compressed blocks.
 * </ol>
 *
 * This supports very large catalogs with bounded memory (you control chunk size).
 */
public final class GaiaCsvToTilePack {

    private static final int RECORD_BYTES = 4 /*tileId*/ + 8 + 4 * 4; // 4 + 24 = 28 bytes
    private static final double DEG_TO_RAD = Math.PI / 180.0;

    private GaiaCsvToTilePack() {}

    /**
     * @param release Gaia Data Release version (must be gaiadr3, gaiaedr3, or gaiadr2)
     * @param location look direction
     * @param coneHalfAngleRad half angle of cone in radians
     * @param maxGmag maximum (dimmest) magnitude
     * @param outCsv name of CSV output file
     * @return sample command string for gaia_download_region.py
     */
    public static String downloadCommandForCone(
            String release, // e.g. "gaiadr3"
            UnwritableVectorIJK location,
            double coneHalfAngleRad,
            double maxGmag,
            String outCsv) {

        double i = location.getI();
        double j = location.getJ();
        double k = location.getK();

        double raRad = Math.atan2(j, i);
        if (raRad < 0) raRad += 2.0 * Math.PI;

        double decRad = Math.asin(Math.max(-1.0, Math.min(1.0, k)));

        double raDeg = Math.toDegrees(raRad);
        double decDeg = Math.toDegrees(decRad);
        double radiusDeg = Math.toDegrees(coneHalfAngleRad);

        return String.format(
                "python gaia_download_region.py --release %s --ra %.8f --dec %.8f --radius %.8f --max-gmag %.2f --out %s",
                release, raDeg, decDeg, radiusDeg, maxGmag, outCsv);
    }

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);

        Path outDir = a.outDir;
        Files.createDirectories(outDir);

        Path tmp = outDir.resolve("gaia.tmp.bin");
        Path runsDir = outDir.resolve("runs");
        Files.createDirectories(runsDir);

        LatLonTiling tiling = new LatLonTiling(a.nLat, a.nLon);

        System.out.println("Pass 1: streaming input -> temp binary: " + tmp);
        Pass1Result p1 = writeTempBinary(a, tiling, tmp);

        System.out.println("Pass 2: external sort into runs (chunk records: " + a.chunkRecords + ")");
        List<Path> runs = sortIntoRuns(tmp, runsDir, a.chunkRecords);

        System.out.println("Pass 3: merge runs -> tile pack");
        Path datPath = outDir.resolve("gaia.dat");
        Path idxPath = outDir.resolve("gaia.idx");
        Path propsPath = outDir.resolve("gaia.properties");

        buildPackFromRuns(a, p1.dataRelease, p1.refEpochYear, tiling, runs, datPath, idxPath, propsPath);

        if (!a.keepTemp) {
            Files.deleteIfExists(tmp);
            for (Path r : runs) Files.deleteIfExists(r);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(runsDir)) {
                // remove directory if empty
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(runsDir);
            } catch (IOException ignored) {
            }
        }

        System.out.println("Done. Total accepted stars: " + p1.kept);
        System.out.println("Pack dir: " + outDir.toAbsolutePath());
    }

    // ---------- Pass 1 ----------

    /**
     * Result of the first pass (stream input -> temp binary) including any metadata found in a leading comment header.
     */
    private static final class Pass1Result {
        final long kept;
        final String dataRelease;
        final double refEpochYear;
        final Float maxGmag; // may be null if not present in header

        Pass1Result(long kept, String dataRelease, double refEpochYear, Float maxGmag) {
            this.kept = kept;
            this.dataRelease = dataRelease;
            this.refEpochYear = refEpochYear;
            this.maxGmag = maxGmag;
        }
    }

    /** Parsed leading comment header plus either a CSV header line or an already-read first data line. */
    private static final class InputHeader {
        String dataRelease; // e.g., DR3
        Double refEpochYear; // e.g., 2016.0
        Float maxGmag; // e.g., 18.0
        String csvHeaderLine; // only when hasHeader==true
        String firstDataLine; // only when hasHeader==false
    }

    /**
     * Reads and parses an optional leading comment header (lines starting with '#') and returns the first non-comment
     * line as either the CSV header (if {@code hasHeader}) or the first data line (otherwise).
     */
    private static InputHeader readInputHeader(BufferedReader br, boolean hasHeader) throws IOException {
        InputHeader h = new InputHeader();
        String line;
        while ((line = br.readLine()) != null) {
            String s = line.trim();
            if (s.isEmpty()) continue;

            if (s.startsWith("#")) {
                // Parse '# key=value' lines. Unknown keys are ignored.
                s = s.substring(1).trim();
                int eq = s.indexOf('=');
                if (eq > 0) {
                    String key = s.substring(0, eq).trim();
                    String val = s.substring(eq + 1).trim();
                    switch (key) {
                        case "dataRelease":
                            h.dataRelease = val;
                            break;
                        case "refEpochYear":
                            try {
                                h.refEpochYear = Double.parseDouble(val);
                            } catch (NumberFormatException ignored) {
                            }
                            break;
                        case "maxGmag":
                            try {
                                h.maxGmag = Float.parseFloat(val);
                            } catch (NumberFormatException ignored) {
                            }
                            break;
                        default:
                            break;
                    }
                }
                continue;
            }

            if (hasHeader) h.csvHeaderLine = line;
            else h.firstDataLine = line;
            return h;
        }
        return h;
    }

    /**
     * Parses a single CSV/TSV line, applies the magnitude filter, computes unit vector + tile id, and writes the
     * temp-binary record to {@code dos}. Returns 1 if accepted, 0 otherwise.
     */
    private static long processDataLine(
            String line,
            long lineNo,
            char delim,
            ColumnMap cm,
            LatLonTiling tiling,
            DataOutputStream dos,
            float effectiveMaxGmag,
            boolean skipBadLines)
            throws IOException {

        if (line == null) return 0;
        if (line.isEmpty()) return 0;

        // naive split; for Gaia exports this is typically fine if you use TSV or non-quoted CSV.
        // If you need robust CSV quoting, swap this with a dedicated parser (or preconvert to TSV).
        String[] parts = split(line, delim);
        if (!cm.validFor(parts.length)) return 0;

        try {
            long sourceId = parseUnsignedLong(parts[cm.sourceIdx].trim());
            double raDeg = Double.parseDouble(parts[cm.raIdx].trim());
            double decDeg = Double.parseDouble(parts[cm.decIdx].trim());
            float gmag = Float.parseFloat(parts[cm.gmagIdx].trim());

            if (gmag > effectiveMaxGmag) return 0;

            double ra = raDeg * DEG_TO_RAD;
            double dec = decDeg * DEG_TO_RAD;

            // unit vector
            double cosDec = Math.cos(dec);
            float x = (float) (cosDec * Math.cos(ra));
            float y = (float) (cosDec * Math.sin(ra));
            float z = (float) Math.sin(dec);

            int tileId = tiling.tileIdForRaDec(ra, dec);

            dos.writeInt(tileId);
            dos.writeLong(sourceId);
            dos.writeFloat(x);
            dos.writeFloat(y);
            dos.writeFloat(z);
            dos.writeFloat(gmag);
            return 1;
        } catch (RuntimeException e) {
            if (!skipBadLines) {
                throw new IOException("Parse error at line " + lineNo + ": " + line, e);
            }
            return 0;
        }
    }

    private static Pass1Result writeTempBinary(Args a, LatLonTiling tiling, Path tmp) throws IOException {
        long kept = 0;

        // Metadata parsed from an optional leading comment header; defaults fall back to CLI args.
        InputHeader headerMeta = null;
        String dataRelease = a.dataRelease;
        double refEpochYear = a.refEpochYear;
        Float headerMaxGmag = null;

        try (BufferedReader br = openText(a.input);
                OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp));
                DataOutputStream dos = new DataOutputStream(os)) {

            headerMeta = readInputHeader(br, a.hasHeader);

            if (headerMeta.dataRelease != null && !headerMeta.dataRelease.trim().isEmpty()) {
                dataRelease = headerMeta.dataRelease;
            }
            if (headerMeta.refEpochYear != null) {
                refEpochYear = headerMeta.refEpochYear;
            }
            headerMaxGmag = headerMeta.maxGmag;

            // Filtering: be conservative if both CLI and header provide a cap.
            float effectiveMaxGmag = a.maxGmag;
            if (headerMeta.maxGmag != null) {
                effectiveMaxGmag = Math.min(effectiveMaxGmag, headerMeta.maxGmag);
            }

            String headerLine = null;
            String firstDataLine = null;
            if (a.hasHeader) {
                headerLine = headerMeta.csvHeaderLine;
                if (headerLine == null) throw new EOFException("No CSV header found (input may be empty)");
            } else {
                firstDataLine = headerMeta.firstDataLine;
                if (firstDataLine == null) throw new EOFException("Empty input");
            }

            ColumnMap cm;
            if (a.hasHeader) {
                cm = ColumnMap.fromHeader(headerLine, a.delim);
            } else {
                if (a.sourceCol < 0 || a.raCol < 0 || a.decCol < 0 || a.gmagCol < 0) {
                    throw new IllegalArgumentException(
                            "Without header, you must specify --source-col/--ra-col/--dec-col/--gmag-col");
                }
                cm = ColumnMap.fromIndices(a.sourceCol, a.raCol, a.decCol, a.gmagCol);
            }

            long lineNo = a.hasHeader ? 2 : 1;

            // If hasHeader==false and we already read the first data line, process it first.
            if (!a.hasHeader) {
                kept += processDataLine(
                        firstDataLine, lineNo, a.delim, cm, tiling, dos, effectiveMaxGmag, a.skipBadLines);
                lineNo++;
            }

            String line;
            while ((line = br.readLine()) != null) {
                kept += processDataLine(line, lineNo, a.delim, cm, tiling, dos, effectiveMaxGmag, a.skipBadLines);
                lineNo++;
            }
        }

        return new Pass1Result(kept, dataRelease, refEpochYear, headerMaxGmag);
    }

    private static BufferedReader openText(Path input) throws IOException {
        InputStream is = Files.newInputStream(input);
        if (input.toString().endsWith(".gz")) {
            is = new java.util.zip.GZIPInputStream(is);
        }
        return new BufferedReader(new InputStreamReader(is));
    }

    private static String[] split(String line, char delim) {
        // fast-ish split without regex
        ArrayList<String> out = new ArrayList<>(16);
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == delim) {
                out.add(line.substring(start, i));
                start = i + 1;
            }
        }
        out.add(line.substring(start));
        return out.toArray(new String[0]);
    }

    private static long parseUnsignedLong(String s) {
        // Gaia source_id fits unsigned 64-bit. Java long is signed, but parsing as unsigned is fine.
        return Long.parseUnsignedLong(s);
    }

    // ---------- Pass 2: chunk runs ----------

    private static List<Path> sortIntoRuns(Path tmp, Path runsDir, int chunkRecords) throws IOException {
        long totalBytes = Files.size(tmp);
        if (totalBytes % RECORD_BYTES != 0) {
            throw new IOException("Temp file is not aligned to record size: " + totalBytes);
        }
        long totalRecords = totalBytes / RECORD_BYTES;

        List<Path> runs = new ArrayList<>();
        try (InputStream is = new BufferedInputStream(Files.newInputStream(tmp));
                DataInputStream dis = new DataInputStream(is)) {

            long remaining = totalRecords;
            int runNo = 0;

            while (remaining > 0) {
                int n = (int) Math.min(remaining, chunkRecords);
                Record[] arr = new Record[n];
                for (int i = 0; i < n; i++) {
                    int tileId = dis.readInt();
                    long sourceId = dis.readLong();
                    float x = dis.readFloat();
                    float y = dis.readFloat();
                    float z = dis.readFloat();
                    float gmag = dis.readFloat();
                    arr[i] = new Record(tileId, sourceId, x, y, z, gmag);
                }

                Arrays.sort(arr, Comparator.comparingInt(r -> r.tileId));

                Path run = runsDir.resolve(String.format("run_%05d.bin", runNo++));
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(run));
                        DataOutputStream dos = new DataOutputStream(os)) {
                    for (Record r : arr) r.write(dos);
                }

                runs.add(run);
                remaining -= n;
                System.out.println("  wrote run " + run.getFileName() + " (" + n + " records)");
            }
        }
        return runs;
    }

    // ---------- Pass 3: merge + pack ----------

    private static void buildPackFromRuns(
            Args a,
            String dataRelease,
            double refEpochYear,
            LatLonTiling tiling,
            List<Path> runs,
            Path datPath,
            Path idxPath,
            Path propsPath)
            throws IOException {

        int tileCount = tiling.tileCount;
        long[] offsets = new long[tileCount];
        int[] lengths = new int[tileCount];
        int[] counts = new int[tileCount];

        Arrays.fill(offsets, 0L);
        Arrays.fill(lengths, 0);
        Arrays.fill(counts, 0);

        // Open all run streams (count is typically modest); if you have too many runs, increase chunkRecords.
        List<RunReader> readers = new ArrayList<>(runs.size());
        for (Path r : runs) readers.add(new RunReader(r));

        PriorityQueue<RunHead> pq = new PriorityQueue<>(
                Comparator.comparingInt((RunHead h) -> h.record.tileId).thenComparingLong(h -> h.record.sourceId));

        for (int i = 0; i < readers.size(); i++) {
            Record rec = readers.get(i).next();
            if (rec != null) pq.add(new RunHead(i, rec));
        }

        long pos = 0;
        try (OutputStream dataOut = new BufferedOutputStream(Files.newOutputStream(datPath))) {

            int currentTile = -1;
            int currentCount = 0;

            // We'll stream-compress one tile at a time.
            CountingOutputStream countingOut = new CountingOutputStream(dataOut);
            DeflaterOutputStream deflaterOut = null;
            DataOutputStream tileDos = null;

            while (!pq.isEmpty()) {
                RunHead h = pq.poll();
                Record r = h.record;

                // advance that run
                Record next = readers.get(h.runIndex).next();
                if (next != null) pq.add(new RunHead(h.runIndex, next));

                if (r.tileId != currentTile) {
                    // close previous tile
                    if (tileDos != null) {
                        tileDos.flush();
                        deflaterOut.finish();
                        deflaterOut.flush();
                        deflaterOut
                                .close(); // closes wrapper, not underlying dataOut (CountingOutputStream doesn't close
                        // base)
                        tileDos = null;
                        deflaterOut = null;

                        lengths[currentTile] = (int) (countingOut.count - pos);
                        pos = countingOut.count;
                        counts[currentTile] = currentCount;
                    }

                    // start new tile
                    currentTile = r.tileId;
                    if (currentTile < 0 || currentTile >= tileCount) {
                        throw new IOException("Invalid tile id in runs: " + currentTile);
                    }
                    offsets[currentTile] = pos;
                    currentCount = 0;

                    deflaterOut = new DeflaterOutputStream(countingOut);
                    tileDos = new DataOutputStream(deflaterOut);
                }

                // write star record (without tileId)
                tileDos.writeLong(r.sourceId);
                tileDos.writeFloat(r.x);
                tileDos.writeFloat(r.y);
                tileDos.writeFloat(r.z);
                tileDos.writeFloat(r.gmag);
                currentCount++;
            }

            // finalize last tile
            if (tileDos != null) {
                tileDos.flush();
                deflaterOut.finish();
                deflaterOut.flush();
                deflaterOut.close();

                lengths[currentTile] = (int) (countingOut.count - pos);
                pos = countingOut.count;
                counts[currentTile] = currentCount;
            }
        } finally {
            for (RunReader rr : readers) rr.close();
        }

        // Write idx
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(idxPath));
                DataOutputStream dos = new DataOutputStream(os)) {
            for (int t = 0; t < tileCount; t++) {
                dos.writeLong(offsets[t]);
                dos.writeInt(lengths[t]);
                dos.writeInt(counts[t]);
            }
        }

        // Write properties
        Properties props = new Properties();
        props.setProperty("tiling.nLat", Integer.toString(tiling.nLat));
        props.setProperty("tiling.nLon", Integer.toString(tiling.nLon));
        props.setProperty("gaia.dataRelease", dataRelease);
        props.setProperty("gaia.refEpoch", Double.toString(refEpochYear));
        props.setProperty("format.record", "sourceId:long,x:float,y:float,z:float,gMag:float");
        props.setProperty("format.compression", "zlib");
        props.setProperty("builder.timestamp", Long.toString(System.currentTimeMillis()));
        try (OutputStream os = Files.newOutputStream(propsPath)) {
            props.store(os, "Gaia tile pack metadata");
        }
    }

    // ---------- helpers ----------

    private static final class Record {
        final int tileId;
        final long sourceId;
        final float x, y, z, gmag;

        Record(int tileId, long sourceId, float x, float y, float z, float gmag) {
            this.tileId = tileId;
            this.sourceId = sourceId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.gmag = gmag;
        }

        void write(DataOutputStream dos) throws IOException {
            dos.writeInt(tileId);
            dos.writeLong(sourceId);
            dos.writeFloat(x);
            dos.writeFloat(y);
            dos.writeFloat(z);
            dos.writeFloat(gmag);
        }
    }

    private static final class RunHead {
        final int runIndex;
        final Record record;

        RunHead(int runIndex, Record record) {
            this.runIndex = runIndex;
            this.record = record;
        }
    }

    private static final class RunReader implements Closeable {
        final DataInputStream dis;
        final Path path;

        RunReader(Path path) throws IOException {
            this.path = path;
            this.dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)));
        }

        Record next() throws IOException {
            try {
                int tileId = dis.readInt();
                long sourceId = dis.readLong();
                float x = dis.readFloat();
                float y = dis.readFloat();
                float z = dis.readFloat();
                float gmag = dis.readFloat();
                return new Record(tileId, sourceId, x, y, z, gmag);
            } catch (EOFException eof) {
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            dis.close();
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream out;
        long count = 0;

        CountingOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b) throws IOException {
            out.write(b);
            count += b.length;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            /* do not close underlying */
        }
    }

    // ---------- CLI ----------

    private static final class ColumnMap {
        final int sourceIdx, raIdx, decIdx, gmagIdx;

        private ColumnMap(int sourceIdx, int raIdx, int decIdx, int gmagIdx) {
            this.sourceIdx = sourceIdx;
            this.raIdx = raIdx;
            this.decIdx = decIdx;
            this.gmagIdx = gmagIdx;
        }

        static ColumnMap fromHeader(String headerLine, char delim) {
            String[] cols = split(headerLine, delim);
            Map<String, Integer> map = new HashMap<>();
            for (int i = 0; i < cols.length; i++) {
                map.put(cols[i].trim().toLowerCase(Locale.ROOT), i);
            }
            int s = requireCol(map, "source_id");
            int ra = requireCol(map, "ra");
            int dec = requireCol(map, "dec");
            int g = requireCol(map, "phot_g_mean_mag");
            return new ColumnMap(s, ra, dec, g);
        }

        static ColumnMap fromIndices(int s, int ra, int dec, int g) {
            return new ColumnMap(s, ra, dec, g);
        }

        boolean validFor(int len) {
            return sourceIdx >= 0
                    && raIdx >= 0
                    && decIdx >= 0
                    && gmagIdx >= 0
                    && sourceIdx < len
                    && raIdx < len
                    && decIdx < len
                    && gmagIdx < len;
        }

        private static int requireCol(Map<String, Integer> map, String name) {
            Integer idx = map.get(name);
            if (idx == null) throw new IllegalArgumentException("Missing required column in header: " + name);
            return idx;
        }
    }

    private static final class Args {
        final Path input;
        final Path outDir;

        final boolean hasHeader;
        final char delim;
        final float maxGmag;
        final String dataRelease;
        final double refEpochYear;
        final int nLat;
        final int nLon;
        final int chunkRecords;
        final boolean skipBadLines;
        final boolean keepTemp;

        final int sourceCol, raCol, decCol, gmagCol;

        private Args(
                Path input,
                Path outDir,
                boolean hasHeader,
                char delim,
                float maxGmag,
                String dataRelease,
                double refEpochYear,
                int nLat,
                int nLon,
                int chunkRecords,
                boolean skipBadLines,
                boolean keepTemp,
                int sourceCol,
                int raCol,
                int decCol,
                int gmagCol) {
            this.input = input;
            this.outDir = outDir;
            this.hasHeader = hasHeader;
            this.delim = delim;
            this.maxGmag = maxGmag;
            this.dataRelease = dataRelease;
            this.refEpochYear = refEpochYear;
            this.nLat = nLat;
            this.nLon = nLon;
            this.chunkRecords = chunkRecords;
            this.skipBadLines = skipBadLines;
            this.keepTemp = keepTemp;
            this.sourceCol = sourceCol;
            this.raCol = raCol;
            this.decCol = decCol;
            this.gmagCol = gmagCol;
        }

        static Args parse(String[] args) {
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (!a.startsWith("--")) continue;
                String key = a.substring(2);
                String val = "true";
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) val = args[++i];
                m.put(key, val);
            }

            Path input = Paths.get(req(m, "input"));
            Path out = Paths.get(req(m, "out"));

            boolean header = Boolean.parseBoolean(m.getOrDefault("header", "true"));
            char delim = m.getOrDefault("delim", "\t").charAt(0); // default TSV
            float maxG = Float.parseFloat(m.getOrDefault("max-gmag", "18.0"));
            String dr = m.getOrDefault("dr", "DR3");
            double refEpoch = Double.parseDouble(m.getOrDefault("ref-epoch", "2016.0"));

            int nLat = Integer.parseInt(m.getOrDefault("nlat", "360"));
            int nLon = Integer.parseInt(m.getOrDefault("nlon", "720"));

            int chunk = Integer.parseInt(m.getOrDefault("chunk-records", "2000000")); // 2 million records per run
            boolean skip = Boolean.parseBoolean(m.getOrDefault("skip-bad-lines", "true"));
            boolean keep = Boolean.parseBoolean(m.getOrDefault("keep-temp", "false"));

            int sourceCol = Integer.parseInt(m.getOrDefault("source-col", "-1"));
            int raCol = Integer.parseInt(m.getOrDefault("ra-col", "-1"));
            int decCol = Integer.parseInt(m.getOrDefault("dec-col", "-1"));
            int gmagCol = Integer.parseInt(m.getOrDefault("gmag-col", "-1"));

            return new Args(
                    input, out, header, delim, maxG, dr, refEpoch, nLat, nLon, chunk, skip, keep, sourceCol, raCol,
                    decCol, gmagCol);
        }

        private static String req(Map<String, String> m, String k) {
            String v = m.get(k);
            if (v == null) throw new IllegalArgumentException("Missing required arg: --" + k);
            return v;
        }
    }
}
