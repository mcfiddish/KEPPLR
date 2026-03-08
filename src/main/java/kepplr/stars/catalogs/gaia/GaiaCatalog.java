package kepplr.stars.catalogs.gaia;

import com.google.common.base.Predicate;
import com.google.common.collect.AbstractIterator;
import java.io.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.zip.InflaterInputStream;
import kepplr.stars.*;
import picante.math.vectorspace.UnwritableVectorIJK;

/**
 * Gaia tile-pack backed catalog.
 *
 * <h2>Why tile packs?</h2>
 *
 * Gaia is too large to load into memory or embed inside a JAR. This catalog reads a compact, random-access "tile pack"
 * stored on disk:
 *
 * <ul>
 *   <li>{@code gaia.idx} – fixed-size table of (offset,length,count) for each tile id
 *   <li>{@code gaia.dat} – concatenated per-tile compressed blocks
 *   <li>{@code gaia.properties} – tiling + format metadata
 * </ul>
 *
 * <p>The tiling is a conservative lat/lon binning (see {@link LatLonTiling}). It is easy to generate and query without
 * external dependencies; it may return extra tiles, which is permitted by {@link TiledStarCatalog}.
 *
 * <h2>Star ID</h2>
 *
 * {@code GAIA:SOURCE_ID}.
 */
public final class GaiaCatalog implements StarCatalog<GaiaStar>, TiledStarCatalog<GaiaStar> {

    public static final String DEFAULT_PROPERTIES_FILE = "gaia.properties";
    public static final String DEFAULT_INDEX_FILE = "gaia.idx";
    public static final String DEFAULT_DATA_FILE = "gaia.dat";
    public static final String DEFAULT_SOURCE_INDEX_FILE = "gaia.sourceidx"; // optional

    private static final double TWO_PI = 2.0 * Math.PI;

    // Metadata
    private final String dataRelease; // e.g., "DR3"
    private final double referenceEpochYear; // e.g., 2016.0 (Julian year)

    // Tile pack metadata
    private final Path dataFile;
    private final FileChannel dataChannel;

    private final int nLat;
    private final int nLon;
    private final int tileCount;

    // index arrays (per tile)
    private final long[] offsets;
    private final int[] lengths;
    private final int[] counts;

    // optional source index (memory-mapped)
    private final Path sourceIndexFile;
    private final FileChannel sourceIndexChannel;
    private final long sourceIndexEntries;

    private final LatLonTiling tiling;

    // Small LRU cache of decoded tiles (tileId -> GaiaStar[])
    private final LinkedHashMap<Integer, GaiaStar[]> tileCache;

    public static final class Summary {
        public final String dataRelease;
        public final double refEpochYear;
        public final int nLat;
        public final int nLon;
        public final int totalTiles;
        public final int populatedTiles;
        public final long totalStars;
        public final long totalCompressedBytes;

        Summary(
                String dataRelease,
                double refEpochYear,
                int nLat,
                int nLon,
                int populatedTiles,
                long totalStars,
                long totalCompressedBytes) {
            this.dataRelease = dataRelease;
            this.refEpochYear = refEpochYear;
            this.nLat = nLat;
            this.nLon = nLon;
            this.totalTiles = Math.multiplyExact(nLat, nLon);
            this.populatedTiles = populatedTiles;
            this.totalStars = totalStars;
            this.totalCompressedBytes = totalCompressedBytes;
        }

        @Override
        public String toString() {
            return "GaiaCatalog.Summary{" + "dataRelease="
                    + dataRelease + ", refEpochYear="
                    + refEpochYear + ", tiles="
                    + populatedTiles + "/" + totalTiles + ", stars="
                    + totalStars + ", compressedBytes="
                    + totalCompressedBytes + "}";
        }
    }

    public Summary summarize() {
        long stars = 0;
        long bytes = 0;
        int populated = 0;

        for (int t = 0; t < tileCount; t++) {
            int c = counts[t];
            if (c > 0) {
                populated++;
                stars += (long) c;
                bytes += (long) lengths[t]; // compressed length from gaia.idx
            }
        }
        return new Summary(dataRelease, referenceEpochYear, tiling.nLat, tiling.nLon, populated, stars, bytes);
    }

    private GaiaCatalog(
            String dataRelease,
            double referenceEpochYear,
            Path dataFile,
            FileChannel dataChannel,
            int nLat,
            int nLon,
            long[] offsets,
            int[] lengths,
            int[] counts,
            Path sourceIndexFile,
            FileChannel sourceIndexChannel,
            long sourceIndexEntries,
            int cacheTiles) {

        this.dataRelease = (dataRelease == null || dataRelease.trim().isEmpty()) ? "UNKNOWN" : dataRelease;
        this.referenceEpochYear = referenceEpochYear;

        this.dataFile = dataFile;
        this.dataChannel = dataChannel;

        this.nLat = nLat;
        this.nLon = nLon;
        this.tileCount = nLat * nLon;

        this.offsets = offsets;
        this.lengths = lengths;
        this.counts = counts;

        this.sourceIndexFile = sourceIndexFile;
        this.sourceIndexChannel = sourceIndexChannel;
        this.sourceIndexEntries = sourceIndexEntries;

        this.tiling = new LatLonTiling(nLat, nLon);

        int cap = Math.max(8, cacheTiles);
        this.tileCache = new LinkedHashMap<Integer, GaiaStar[]>(cap, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, GaiaStar[]> eldest) {
                return size() > cap;
            }
        };
    }

    // ---------------- loading ----------------

    /**
     * Loads a Gaia tile pack from a directory. <br>
     * Expected files (defaults):
     *
     * <ul>
     *   <li>{@code gaia.properties}
     *   <li>{@code gaia.idx}
     *   <li>{@code gaia.dat}
     *   <li>{@code gaia.sourceidx} (optional for {@link #getStar(String)})
     * </ul>
     */
    public static GaiaCatalog load(Path packDir) throws IOException {
        return load(
                packDir,
                DEFAULT_PROPERTIES_FILE,
                DEFAULT_INDEX_FILE,
                DEFAULT_DATA_FILE,
                DEFAULT_SOURCE_INDEX_FILE,
                256);
    }

    public static GaiaCatalog load(Path packDir, int cacheTiles) throws IOException {
        return load(
                packDir,
                DEFAULT_PROPERTIES_FILE,
                DEFAULT_INDEX_FILE,
                DEFAULT_DATA_FILE,
                DEFAULT_SOURCE_INDEX_FILE,
                cacheTiles);
    }

    public static GaiaCatalog load(
            Path packDir, String propertiesFile, String idxFile, String datFile, String sourceIndexFile, int cacheTiles)
            throws IOException {

        Objects.requireNonNull(packDir, "packDir");

        Path propsPath = packDir.resolve(propertiesFile);
        Path idxPath = packDir.resolve(idxFile);
        Path datPath = packDir.resolve(datFile);
        Path srcIdxPath = packDir.resolve(sourceIndexFile);

        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(propsPath)) {
            props.load(is);
        }

        int nLat = Integer.parseInt(required(props, "tiling.nLat"));
        int nLon = Integer.parseInt(required(props, "tiling.nLon"));

        String dr = props.getProperty("gaia.dataRelease", "UNKNOWN").trim();
        double refEpoch = parseDoubleOrNaN(props.getProperty("gaia.refEpoch"));

        int tileCount = nLat * nLon;

        // Read idx file: big-endian fixed entries: offset(long), length(int), count(int)
        long[] offsets = new long[tileCount];
        int[] lengths = new int[tileCount];
        int[] counts = new int[tileCount];

        try (InputStream is = new BufferedInputStream(Files.newInputStream(idxPath));
                DataInputStream dis = new DataInputStream(is)) {
            for (int t = 0; t < tileCount; t++) {
                offsets[t] = dis.readLong();
                lengths[t] = dis.readInt();
                counts[t] = dis.readInt();
            }
        }

        FileChannel dataChannel = FileChannel.open(datPath, StandardOpenOption.READ);

        FileChannel srcIndexChannel = null;
        long srcEntries = 0;
        if (Files.exists(srcIdxPath)) {
            srcIndexChannel = FileChannel.open(srcIdxPath, StandardOpenOption.READ);
            // entry size 16 bytes: sourceId(long), tileId(int), indexInTile(int)
            long size = srcIndexChannel.size();
            if (size % 16 != 0) {
                throw new IOException("Invalid source index size (must be multiple of 16): " + size);
            }
            srcEntries = size / 16;
        }

        return new GaiaCatalog(
                dr,
                refEpoch,
                datPath,
                dataChannel,
                nLat,
                nLon,
                offsets,
                lengths,
                counts,
                srcIdxPath,
                srcIndexChannel,
                srcEntries,
                cacheTiles);
    }

    /**
     * Returns the Gaia data release string recorded in the tile pack metadata (e.g., "DR3"). If unknown, returns
     * "UNKNOWN".
     */
    public String getDataRelease() {
        return dataRelease;
    }

    /**
     * Returns the reference epoch (Julian year) for the positions encoded into this tile pack (e.g., 2016.0). If the
     * pack did not record the epoch, this may be NaN.
     */
    public double getReferenceEpochYear() {
        return referenceEpochYear;
    }

    private static double parseDoubleOrNaN(String s) {
        if (s == null) return Double.NaN;
        s = s.trim();
        if (s.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String required(Properties props, String key) {
        String v = props.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required property: " + key);
        }
        return v.trim();
    }

    // ---------------- StarCatalog ----------------

    @Override
    public Iterable<GaiaStar> filter(Predicate<? super GaiaStar> filter) {
        Objects.requireNonNull(filter, "filter");
        return () -> new FilteringAllStarsIterator(this, filter);
    }

    @Override
    public GaiaStar getStar(String id) {
        if (id == null) throw new IllegalArgumentException("id is null");
        if (!id.startsWith(GaiaStar.CATALOG_PREFIX)) {
            throw new StarCatalogLookupException("Not a Gaia star id: " + id);
        }
        long sourceId;
        try {
            sourceId = Long.parseUnsignedLong(id.substring(GaiaStar.CATALOG_PREFIX.length()));
        } catch (NumberFormatException e) {
            throw new StarCatalogLookupException("Invalid Gaia id: " + id);
        }

        if (sourceIndexChannel == null) {
            throw new UnsupportedOperationException(
                    "This tile pack was built without a source-id index (" + DEFAULT_SOURCE_INDEX_FILE + "). "
                            + "Rebuild with GaiaBuildSourceIndex or enable source indexing during pack build.");
        }

        // binary search the memory-mapped source index
        try {
            long lo = 0, hi = sourceIndexEntries - 1;
            while (lo <= hi) {
                long mid = (lo + hi) >>> 1;
                long midId = readSourceIdAt(mid);
                int cmp = Long.compareUnsigned(midId, sourceId);
                if (cmp < 0) lo = mid + 1;
                else if (cmp > 0) hi = mid - 1;
                else {
                    int tileId = readIntAt(mid, 8);
                    int indexInTile = readIntAt(mid, 12);
                    GaiaStar[] tile = loadTile(tileId);
                    if (indexInTile < 0 || indexInTile >= tile.length) {
                        throw new StarCatalogLookupException("Corrupt source index entry for " + id);
                    }
                    GaiaStar s = tile[indexInTile];
                    if (s.getSourceId() != sourceId) {
                        throw new StarCatalogLookupException("Corrupt source index entry for " + id);
                    }
                    return s;
                }
            }
            throw new StarCatalogLookupException("Unknown star id: " + id);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long readSourceIdAt(long entryIndex) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        sourceIndexChannel.read(buf, entryIndex * 16);
        buf.flip();
        return buf.getLong();
    }

    private int readIntAt(long entryIndex, int offsetWithinEntry) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        sourceIndexChannel.read(buf, entryIndex * 16 + offsetWithinEntry);
        buf.flip();
        return buf.getInt();
    }

    @Override
    public Iterator<GaiaStar> iterator() {
        return new AllStarsIterator(this);
    }

    // ---------------- TiledStarCatalog ----------------

    /**
     * Returns the conservative set of tile IDs that {@link #lookup(UnwritableVectorIJK, double, TileSet)} would
     * consider for this cone. This list may include tiles that do not intersect the cone; filtering is applied per-star
     * during lookup.
     */
    public List<Integer> requiredTiles(UnwritableVectorIJK location, double coneHalfAngle) {
        Objects.requireNonNull(location, "location");
        if (coneHalfAngle < 0) throw new IllegalArgumentException("coneHalfAngle must be >= 0");

        double i = location.getI();
        double j = location.getJ();
        double k = location.getK();

        double dec0 = Math.asin(clamp(k, -1.0, 1.0));
        double ra0 = Math.atan2(j, i);
        if (ra0 < 0) ra0 += TWO_PI;

        return tiling.tilesIntersectingCone(ra0, dec0, coneHalfAngle);
    }

    /**
     * Returns the subset of {@link #requiredTiles(UnwritableVectorIJK, double)} for which this pack has no data (i.e.,
     * per-tile {@code count==0} in {@code gaia.idx}).
     *
     * <p>This is intended for user-facing messaging like: "Missing Gaia tiles: ...; run the downloader/builder."
     */
    public List<Integer> missingTiles(UnwritableVectorIJK location, double coneHalfAngle) {
        List<Integer> needed = requiredTiles(location, coneHalfAngle);
        ArrayList<Integer> missing = new ArrayList<>();
        for (int tileId : needed) {
            if (tileId < 0 || tileId >= tileCount) continue;
            if (counts[tileId] == 0) missing.add(tileId);
        }
        Collections.sort(missing);
        return missing;
    }

    @Override
    public void lookup(UnwritableVectorIJK location, double coneHalfAngle, TileSet<? super GaiaStar> result) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(result, "result");
        if (coneHalfAngle < 0) throw new IllegalArgumentException("coneHalfAngle must be >= 0");

        result.clearTiles();

        // Convert location vector to ra/dec (assumes unit vector)
        double i = location.getI();
        double j = location.getJ();
        double k = location.getK();

        double dec0 = Math.asin(clamp(k, -1.0, 1.0));
        double ra0 = Math.atan2(j, i);
        if (ra0 < 0) ra0 += TWO_PI;

        List<Integer> tileIds = tiling.tilesIntersectingCone(ra0, dec0, coneHalfAngle);

        double cosMax = Math.cos(coneHalfAngle);

        // For each tile: create a StarTile proxy whose iterator filters stars against the cone.
        for (int tileId : tileIds) {
            if (tileId < 0 || tileId >= tileCount) continue;
            if (counts[tileId] == 0) continue;

            GaiaStar[] stars = loadTile(tileId);

            Iterable<GaiaStar> filtered = () -> new ConeFilteringIterator(stars, i, j, k, cosMax);

            StarTile<GaiaStar> tile = transientStarTile(filtered);
            result.add(tile);
        }
    }

    @SuppressWarnings("unchecked")
    private static <S extends Star> StarTile<S> transientStarTile(Iterable<S> iterable) {
        // The project’s StarTile type is expected to be an interface (common in this library).
        // Using a proxy keeps this code independent of the full StarTile API surface.
        ClassLoader cl = StarTile.class.getClassLoader();
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("iterator") && method.getParameterCount() == 0) {
                return iterable.iterator();
            }
            if (name.equals("toString") && method.getParameterCount() == 0) {
                return "TransientStarTile(" + iterable + ")";
            }
            if (name.equals("hashCode") && method.getParameterCount() == 0) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals") && method.getParameterCount() == 1) {
                return proxy == args[0];
            }
            throw new UnsupportedOperationException("Transient StarTile does not implement method: " + method);
        };

        return (StarTile<S>) Proxy.newProxyInstance(cl, new Class<?>[] {StarTile.class}, h);
    }

    private GaiaStar[] loadTile(int tileId) {
        synchronized (tileCache) {
            GaiaStar[] cached = tileCache.get(tileId);
            if (cached != null) return cached;
        }

        long offset = offsets[tileId];
        int length = lengths[tileId];
        int count = counts[tileId];

        if (length == 0 || count == 0) {
            GaiaStar[] empty = new GaiaStar[0];
            synchronized (tileCache) {
                tileCache.put(tileId, empty);
            }
            return empty;
        }

        try {
            ByteBuffer buf = ByteBuffer.allocate(length);
            int read = 0;
            while (read < length) {
                int r = dataChannel.read(buf, offset + read);
                if (r < 0) throw new IOException("Unexpected EOF reading tile block");
                read += r;
            }
            buf.flip();

            byte[] compressed = new byte[length];
            buf.get(compressed);

            GaiaStar[] decoded = decodeTile(compressed, count);

            synchronized (tileCache) {
                tileCache.put(tileId, decoded);
            }
            return decoded;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static GaiaStar[] decodeTile(byte[] compressed, int count) throws IOException {
        GaiaStar[] stars = new GaiaStar[count];
        try (InputStream is =
                        new InflaterInputStream(new BufferedInputStream(new java.io.ByteArrayInputStream(compressed)));
                DataInputStream dis = new DataInputStream(is)) {

            for (int i = 0; i < count; i++) {
                long sourceId = dis.readLong();
                float x = dis.readFloat();
                float y = dis.readFloat();
                float z = dis.readFloat();
                float gMag = dis.readFloat();
                stars[i] = new GaiaStar(sourceId, x, y, z, gMag);
            }
            return stars;
        }
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    // ---------------- Iterators ----------------

    private static final class ConeFilteringIterator extends AbstractIterator<GaiaStar> {
        private final GaiaStar[] stars;
        private final double li, lj, lk;
        private final double cosMax;
        private int idx = 0;

        ConeFilteringIterator(GaiaStar[] stars, double li, double lj, double lk, double cosMax) {
            this.stars = stars;
            this.li = li;
            this.lj = lj;
            this.lk = lk;
            this.cosMax = cosMax;
        }

        @Override
        protected GaiaStar computeNext() {
            while (idx < stars.length) {
                GaiaStar s = stars[idx++];
                double dot = li * s.getX() + lj * s.getY() + lk * s.getZ();
                if (dot >= cosMax) return s;
            }
            return endOfData();
        }
    }

    private static final class AllStarsIterator extends AbstractIterator<GaiaStar> {
        private final GaiaCatalog cat;
        private int tileId = 0;
        private int idxInTile = 0;
        private GaiaStar[] current = null;

        AllStarsIterator(GaiaCatalog cat) {
            this.cat = cat;
        }

        @Override
        protected GaiaStar computeNext() {
            while (true) {
                if (current == null) {
                    // advance to next non-empty tile
                    while (tileId < cat.tileCount && cat.counts[tileId] == 0) tileId++;
                    if (tileId >= cat.tileCount) return endOfData();
                    current = cat.loadTile(tileId);
                    idxInTile = 0;
                }
                if (idxInTile < current.length) {
                    return current[idxInTile++];
                }
                tileId++;
                current = null;
            }
        }
    }

    private static final class FilteringAllStarsIterator extends AbstractIterator<GaiaStar> {
        private final Iterator<GaiaStar> base;
        private final Predicate<? super GaiaStar> filter;

        FilteringAllStarsIterator(GaiaCatalog cat, Predicate<? super GaiaStar> filter) {
            this.base = new AllStarsIterator(cat);
            this.filter = filter;
        }

        @Override
        protected GaiaStar computeNext() {
            while (base.hasNext()) {
                GaiaStar s = base.next();
                if (filter.apply(s)) return s;
            }
            return endOfData();
        }
    }

    // ---------------- cleanup ----------------

    /** Closes underlying file channels. */
    public void close() throws IOException {
        dataChannel.close();
        if (sourceIndexChannel != null) sourceIndexChannel.close();
    }
}
