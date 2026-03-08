package kepplr.stars.catalogs.yaleBSC;

import com.google.common.base.Predicate;
import java.io.*;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import kepplr.stars.*;
import picante.math.vectorspace.UnwritableVectorIJK;

/**
 * Yale Bright Star Catalog (BSC5 / VizieR V/50) reader + in-memory catalog.
 *
 * <p>Input format: fixed-width "catalog" file, 197 bytes/record, 9110 records. See VizieR V/50 and the byte-by-byte
 * field description for the "catalog" file.
 *
 * <p>This implementation:
 *
 * <ul>
 *   <li>Loads all stars into memory (only ~9k records).
 *   <li>Implements {@link StarCatalog} and a conservative {@link TiledStarCatalog} lookup by returning all stars inside
 *       the supplied cone (O(N) scan). This is fast enough for 9k.
 *   <li>Skips records with missing J2000 RA/Dec (the 14 non-stellar retained HR entries).
 * </ul>
 *
 * <p>To load the built-in catalog:
 *
 * <pre>
 * 	YaleBrightStarCatalog bsc = YaleBrightStarCatalog.loadFromResource("/kepplr/stars/catalogs/yaleBSC/ybsc5.gz");
 * 	</pre>
 */
public final class YaleBrightStarCatalog implements StarCatalog<YaleBrightStar>, TiledStarCatalog<YaleBrightStar> {

    // Constants for proper-motion propagation
    static final double ARCSEC_TO_RAD = Math.PI / (180.0 * 3600.0);
    static final double SECONDS_PER_JULIAN_YEAR = 365.25 * 86400.0;

    private final List<YaleBrightStar> stars; // stable iteration order
    private final Map<String, YaleBrightStar> byId; // fast lookup

    private YaleBrightStarCatalog(List<YaleBrightStar> stars) {
        this.stars = Collections.unmodifiableList(new ArrayList<>(stars));
        Map<String, YaleBrightStar> map = new HashMap<>(stars.size() * 2);
        for (YaleBrightStar s : stars) map.put(s.getID(), s);
        this.byId = Collections.unmodifiableMap(map);
    }

    // ---------------- StarCatalog ----------------

    @Override
    public Iterable<YaleBrightStar> filter(Predicate<? super YaleBrightStar> filter) {
        Objects.requireNonNull(filter, "filter");
        return () -> stars.stream().filter(filter::apply).iterator();
    }

    @Override
    public YaleBrightStar getStar(String id) {
        YaleBrightStar s = byId.get(id);
        if (s == null) throw new StarCatalogLookupException("Unknown star id: " + id);
        return s;
    }

    @Override
    public Iterator<YaleBrightStar> iterator() {
        return stars.iterator();
    }

    public int size() {
        return stars.size();
    }

    // ---------------- TiledStarCatalog ----------------

    /**
     * Conservative cone lookup. The caller provides a unit vector {@code location} and an angular radius
     * {@code coneHalfAngle}. All stars whose angular separation from location is <= coneHalfAngle are added to
     * {@code result}.
     *
     * <p>Implementation note: since we don't know your {@link TileSet} API surface here, this method supports two
     * common patterns:
     *
     * <ul>
     *   <li>If {@code result} implements {@link Collection}, it will be cleared and filled via {@code add()}.
     *   <li>Otherwise, it will attempt to invoke {@code clear()} and {@code add(Object)} reflectively.
     * </ul>
     */
    @Override
    public void lookup(UnwritableVectorIJK location, double coneHalfAngle, TileSet<? super YaleBrightStar> result) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(result, "result");
        if (coneHalfAngle < 0) throw new IllegalArgumentException("coneHalfAngle must be >= 0");

        // Reset the caller-provided result set.
        result.clearTiles();

        // This catalog is small (~9k). For correctness (and convenience), we build a transient tile
        // containing exactly the stars in the cone. This still satisfies the TiledStarCatalog contract
        // (which permits "extra" stars/tiles), but yields precise results for typical callers that
        // iterate the returned TileSet directly.
        double li = location.getI();
        double lj = location.getJ();
        double lk = location.getK();

        double cosMax = Math.cos(coneHalfAngle);

        ArrayList<YaleBrightStar> hits = new ArrayList<>();

        for (YaleBrightStar s : stars) {
            double ra = s.getRa0Rad();
            double dec = s.getDec0Rad();

            double cosDec = Math.cos(dec);
            double sinDec = Math.sin(dec);
            double cosRa = Math.cos(ra);
            double sinRa = Math.sin(ra);

            double x = cosDec * cosRa;
            double y = cosDec * sinRa;
            double z = sinDec;

            double dot = li * x + lj * y + lk * z;
            if (dot >= cosMax) hits.add(s);
        }

        result.add(transientTile(hits));
    }

    /**
     * Creates a "one-off" {@link StarTile} containing the supplied stars.
     *
     * <p>This catalog does not build a persistent sky tiling (it does not need to, given the small size). Instead, each
     * {@link #lookup} returns a single transient tile with the exact hits.
     *
     * <p>Implementation notes:
     *
     * <ul>
     *   <li>If {@code StarTile} is an interface (typical), a dynamic proxy is used so we don't need to depend on the
     *       full {@code StarTile} API surface.
     *   <li>If {@code StarTile} is a concrete class in your codebase, we attempt reflective construction using common
     *       constructor shapes like {@code (Iterable)} or {@code (Collection)}.
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static StarTile<YaleBrightStar> transientTile(List<YaleBrightStar> stars) {
        Objects.requireNonNull(stars, "stars");

        Class<?> tileType = StarTile.class;

        // Common case: StarTile is an interface.
        if (tileType.isInterface()) {
            java.lang.reflect.InvocationHandler h = (proxy, method, args) -> {
                String name = method.getName();

                if (name.equals("iterator") && method.getParameterCount() == 0) {
                    return stars.iterator();
                }

                // Basic Object methods
                if (name.equals("toString") && method.getParameterCount() == 0) {
                    return "TransientStarTile(size=" + stars.size() + ")";
                }
                if (name.equals("hashCode") && method.getParameterCount() == 0) {
                    return System.identityHashCode(proxy);
                }
                if (name.equals("equals") && method.getParameterCount() == 1) {
                    return proxy == args[0];
                }

                // Conservative defaults for any additional StarTile methods.
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class) return false;
                if (rt == byte.class) return (byte) 0;
                if (rt == short.class) return (short) 0;
                if (rt == int.class) return 0;
                if (rt == long.class) return 0L;
                if (rt == float.class) return 0.0f;
                if (rt == double.class) return 0.0;
                if (rt == char.class) return '\0';
                return null;
            };

            return (StarTile<YaleBrightStar>) java.lang.reflect.Proxy.newProxyInstance(
                    YaleBrightStarCatalog.class.getClassLoader(), new Class<?>[] {tileType, Iterable.class}, h);
        }

        // Fallback: StarTile is a concrete class. Try a few constructor shapes.
        try {
            for (Class<?>[] sig : new Class<?>[][] {{Iterable.class}, {Collection.class}, {List.class}, {Set.class}}) {
                try {
                    Constructor<?> ctor = tileType.getConstructor(sig);
                    return (StarTile<YaleBrightStar>) ctor.newInstance(stars);
                } catch (NoSuchMethodException ignored) {
                    // try next
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to construct StarTile reflectively.", e);
        }

        throw new IllegalStateException(
                "StarTile is not an interface and does not expose a supported constructor shape. "
                        + "Please adapt transientTile() to your StarTile implementation.");
    }

    // ---------------- loading ----------------

    /**
     * Loads a BSC5 fixed-width catalog file.
     *
     * <p>Supports:
     *
     * <ul>
     *   <li>Plain text .dat (ASCII) file
     *   <li>GZIP-compressed .gz
     *   <li>Classpath resource (use {@link #loadFromResource(String)})
     * </ul>
     *
     * @param catalogFile path to the BSC5 "catalog" file (record length 197)
     */
    public static YaleBrightStarCatalog load(Path catalogFile) throws IOException {
        Objects.requireNonNull(catalogFile, "catalogFile");
        try (InputStream is = Files.newInputStream(catalogFile);
                InputStream maybeGz = catalogFile.toString().endsWith(".gz") ? new GZIPInputStream(is) : is;
                BufferedReader br = new BufferedReader(new InputStreamReader(maybeGz, StandardCharsets.US_ASCII))) {
            return load(br);
        }
    }

    /**
     * Loads the catalog from a classpath resource (optionally gzip-compressed).
     *
     * @param resourcePath e.g. {@code "/catalogs/bsc5/catalog"} or {@code "/catalogs/bsc5/catalog.gz"}
     */
    public static YaleBrightStarCatalog loadFromResource(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        InputStream is = YaleBrightStarCatalog.class.getResourceAsStream(resourcePath);
        if (is == null) throw new IllegalArgumentException("Resource not found: " + resourcePath);
        try (InputStream maybeGz = resourcePath.endsWith(".gz") ? new GZIPInputStream(is) : is;
                BufferedReader br = new BufferedReader(new InputStreamReader(maybeGz, StandardCharsets.US_ASCII))) {
            return load(br);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static YaleBrightStarCatalog load(BufferedReader br) throws IOException {
        List<YaleBrightStar> stars = new ArrayList<>(9200);
        String line;
        int lineNo = 0;
        while ((line = br.readLine()) != null) {
            lineNo++;
            // Fixed width; but be defensive about truncated lines.
            if (line.isEmpty()) continue;
            try {
                Optional<YaleBrightStar> parsed = parseCatalogLine(line);
                parsed.ifPresent(stars::add);
            } catch (RuntimeException e) {
                throw new IOException("Failed parsing BSC5 line " + lineNo + ": " + line, e);
            }
        }
        return new YaleBrightStarCatalog(stars);
    }

    /**
     * Parses one fixed-width record from the V/50 "catalog" file.
     *
     * <p>Byte indices are 0-based and inclusive in the source documentation; substring end is exclusive.
     */
    static Optional<YaleBrightStar> parseCatalogLine(String line) {
        // Required identifiers
        int hr = parseInt(slice(line, 0, 4), -1);
        if (hr < 1) return Optional.empty();

        int hd = parseInt(slice(line, 25, 31), -1);

        String name = slice(line, 4, 14).trim();
        String dm = slice(line, 14, 25).trim();
        String spType = slice(line, 127, 147).trim();

        // RA/Dec J2000: bytes 75-82 + 83-89, per V/50 format.
        int rah = parseInt(slice(line, 75, 77), -1);
        int ram = parseInt(slice(line, 77, 79), -1);
        double ras = parseDouble(slice(line, 79, 83), Double.NaN);

        String decSignStr = slice(line, 83, 84).trim();
        int decd = parseInt(slice(line, 84, 86), -1);
        int decm = parseInt(slice(line, 86, 88), -1);
        int decs = parseInt(slice(line, 88, 90), -1);

        // Non-stellar/removed objects often have blanks in these fields -> skip.
        if (rah < 0 || ram < 0 || !Double.isFinite(ras) || decSignStr.isEmpty() || decd < 0 || decm < 0 || decs < 0) {
            return Optional.empty();
        }

        int sign = decSignStr.equals("-") ? -1 : +1;

        double raHours = rah + ram / 60.0 + ras / 3600.0;
        double raRad = raHours * (Math.PI / 12.0);

        double decDeg = sign * (decd + decm / 60.0 + decs / 3600.0);
        double decRad = decDeg * (Math.PI / 180.0);

        double vmag = parseDouble(slice(line, 102, 107), Double.NaN);
        if (!Double.isFinite(vmag)) vmag = Double.NaN;

        double pmRa = parseDouble(slice(line, 148, 154), 0.0); // arcsec/yr
        double pmDec = parseDouble(slice(line, 154, 160), 0.0); // arcsec/yr

        YaleBrightStar star = new YaleBrightStar(hr, hd, name, dm, spType, vmag, raRad, decRad, pmRa, pmDec);
        return Optional.of(star);
    }

    private static String slice(String s, int start, int endExclusive) {
        if (start >= s.length()) return "";
        int end = Math.min(endExclusive, s.length());
        return s.substring(start, end);
    }

    private static int parseInt(String s, int defaultVal) {
        s = s.trim();
        if (s.isEmpty()) return defaultVal;
        return Integer.parseInt(s);
    }

    private static double parseDouble(String s, double defaultVal) {
        s = s.trim();
        if (s.isEmpty()) return defaultVal;
        return Double.parseDouble(s);
    }
}
