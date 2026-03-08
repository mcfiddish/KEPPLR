package kepplr.stars.catalogs.gaia.tools;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Merges two Gaia tile packs with identical tiling and record/compression format.
 *
 * <p>Use this to incrementally build a "partial sky" pack: generate a small pack for a region, then merge it into your
 * main pack.
 *
 * <p>Each record in the primary and secondary tile packs will be present in the merged tile pack. In case of identical
 * source ids, take the secondary record.
 *
 * <h2>Files merged</h2>
 *
 * This tool writes:
 *
 * <ul>
 *   <li>{@code gaia.properties} (copied from primary; if primary has UNKNOWN/NaN metadata and secondary has values,
 *       they are filled in)
 *   <li>{@code gaia.idx}, {@code gaia.dat}
 * </ul>
 *
 * <p>It does <b>not</b> merge {@code gaia.sourceidx}. If you need {@code getStar("GAIA:<id>")}, rebuild the source
 * index after merging by running {@link GaiaBuildSourceIndex}.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * java GaiaMergeTilePacks \
 *   --primary /path/to/main_pack \
 *   --secondary /path/to/new_region_pack \
 *   --out /path/to/merged_pack
 * }</pre>
 */
public final class GaiaMergeTilePacks {

    private static final String PROPS = "gaia.properties";
    private static final String IDX = "gaia.idx";
    private static final String DAT = "gaia.dat";

    private GaiaMergeTilePacks() {}

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);

        Path pDir = a.primary;
        Path sDir = a.secondary;
        Path oDir = a.out;
        Files.createDirectories(oDir);

        Properties pProps = loadProps(pDir.resolve(PROPS));
        Properties sProps = loadProps(sDir.resolve(PROPS));

        int nLat = Integer.parseInt(req(pProps, "tiling.nLat"));
        int nLon = Integer.parseInt(req(pProps, "tiling.nLon"));
        int tileCount = nLat * nLon;

        // Validate secondary tiling matches.
        int snLat = Integer.parseInt(req(sProps, "tiling.nLat"));
        int snLon = Integer.parseInt(req(sProps, "tiling.nLon"));
        if (snLat != nLat || snLon != nLon) {
            throw new IllegalArgumentException(
                    "Tiling mismatch: primary " + nLat + "x" + nLon + " vs secondary " + snLat + "x" + snLon);
        }

        String pRelease = pProps.getProperty("gaia.dataRelease", "").trim();
        String sRelease = sProps.getProperty("gaia.dataRelease", "").trim();
        if (pRelease.isEmpty() || sRelease.isEmpty()) {
            throw new IllegalArgumentException("Missing gaia.dataRelease in one or both packs");
        }
        if (!pRelease.equalsIgnoreCase(sRelease)) {
            throw new IllegalArgumentException(
                    "Data release mismatch:\nprimary=" + pRelease + "\nsecondary=" + sRelease);
        }

        String pEpochString = pProps.getProperty("gaia.refEpoch", "").trim();
        String sEpochString = sProps.getProperty("gaia.refEpoch", "").trim();
        if (pEpochString.isEmpty() || sEpochString.isEmpty()) {
            throw new IllegalArgumentException("Missing gaia.refEpoch in one or both packs");
        }
        double pEpoch = Double.parseDouble(pEpochString);
        double sEpoch = Double.parseDouble(sEpochString);
        if (Double.compare(pEpoch, sEpoch) != 0) {
            throw new IllegalArgumentException("Epoch mismatch:\nprimary=" + pEpoch + "\nsecondary=" + sEpoch);
        }

        // Validate format compatibility (record + compression).
        String pRec = pProps.getProperty("format.record", "").trim();
        String sRec = sProps.getProperty("format.record", "").trim();
        if (!pRec.equals(sRec)) {
            throw new IllegalArgumentException("Record format mismatch:\nprimary=" + pRec + "\nsecondary=" + sRec);
        }
        String pComp = pProps.getProperty("format.compression", "").trim();
        String sComp = sProps.getProperty("format.compression", "").trim();
        if (!pComp.equals(sComp)) {
            throw new IllegalArgumentException("Compression mismatch:\nprimary=" + pComp + "\nsecondary=" + sComp);
        }

        IndexTable pIdx = readIndex(pDir.resolve(IDX), tileCount);
        IndexTable sIdx = readIndex(sDir.resolve(IDX), tileCount);

        Path pDat = pDir.resolve(DAT);
        Path sDat = sDir.resolve(DAT);

        Path outIdx = oDir.resolve(IDX);
        Path outDat = oDir.resolve(DAT);
        Path outProps = oDir.resolve(PROPS);

        merge(pProps, sProps, pIdx, sIdx, pDat, sDat, outIdx, outDat, outProps);

        System.out.println("Merged pack written to: " + oDir.toAbsolutePath());
        System.out.println("Note: gaia.sourceidx not merged; rebuild it if you need getStar().");
    }

    private static void merge(
            Properties pProps,
            Properties sProps,
            IndexTable pIdx,
            IndexTable sIdx,
            Path pDat,
            Path sDat,
            Path outIdx,
            Path outDat,
            Path outPropsPath)
            throws IOException {

        int tileCount = pIdx.tileCount;

        long[] oOff = new long[tileCount];
        int[] oLen = new int[tileCount];
        int[] oCnt = new int[tileCount];

        try (RandomAccessFile pRaf = new RandomAccessFile(pDat.toFile(), "r");
                RandomAccessFile sRaf = new RandomAccessFile(sDat.toFile(), "r");
                OutputStream out = new BufferedOutputStream(Files.newOutputStream(outDat))) {

            long pos = 0;
            long[] posHolder = new long[] {pos};

            for (int t = 0; t < tileCount; t++) {
                // Merge rule (per tile):
                // - If only one pack has data for this tile, copy its compressed block.
                // - If both packs have data, decode both blocks and write the union by source_id.
                //   If a source_id appears in both, prefer the secondary record.
                int pCnt = pIdx.counts[t];
                int sCnt = sIdx.counts[t];

                if (pCnt == 0 && sCnt == 0) {
                    oOff[t] = 0;
                    oLen[t] = 0;
                    oCnt[t] = 0;
                    continue;
                }

                if (pCnt > 0 && sCnt == 0) {
                    copyTileBlock(pRaf, pIdx, t, out, oOff, oLen, oCnt, posHolder);
                    pos = posHolder[0];
                    continue;
                }

                if (pCnt == 0 && sCnt > 0) {
                    copyTileBlock(sRaf, sIdx, t, out, oOff, oLen, oCnt, posHolder);
                    pos = posHolder[0];
                    continue;
                }

                // Both non-empty: union.
                TileRecords pRecs = decodeTile(pRaf, pIdx.offsets[t], pIdx.lengths[t], pCnt);
                TileRecords sRecs = decodeTile(sRaf, sIdx.offsets[t], sIdx.lengths[t], sCnt);

                // Defensive: ensure per-tile records are sorted by unsigned sourceId.
                ensureSortedBySourceId(pRecs);
                ensureSortedBySourceId(sRecs);

                TileRecords merged = unionBySourceIdPreferSecondary(pRecs, sRecs);

                byte[] buf = encodeTile(merged);

                oOff[t] = pos;
                oLen[t] = buf.length;
                oCnt[t] = merged.count;

                out.write(buf);
                pos += buf.length;
                posHolder[0] = pos;
            }
        }

        // write output index
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(outIdx)))) {
            for (int t = 0; t < tileCount; t++) {
                dos.writeLong(oOff[t]);
                dos.writeInt(oLen[t]);
                dos.writeInt(oCnt[t]);
            }
        }

        // write properties (mostly primary, but fill in DR/epoch if primary missing)
        Properties mergedProps = new Properties();
        mergedProps.putAll(pProps);

        String pDR = pProps.getProperty("gaia.dataRelease", "UNKNOWN").trim();
        String sDR = sProps.getProperty("gaia.dataRelease", "UNKNOWN").trim();
        if ((pDR.isEmpty() || pDR.equalsIgnoreCase("UNKNOWN")) && !(sDR.isEmpty() || sDR.equalsIgnoreCase("UNKNOWN"))) {
            mergedProps.setProperty("gaia.dataRelease", sDR);
        }

        String pEpoch = pProps.getProperty("gaia.refEpoch", "").trim();
        String sEpoch = sProps.getProperty("gaia.refEpoch", "").trim();
        if ((pEpoch.isEmpty() || pEpoch.equalsIgnoreCase("NaN"))
                && !sEpoch.isEmpty()
                && !sEpoch.equalsIgnoreCase("NaN")) {
            mergedProps.setProperty("gaia.refEpoch", sEpoch);
        }

        mergedProps.setProperty("builder.mergedTimestamp", Long.toString(System.currentTimeMillis()));

        try (OutputStream os = Files.newOutputStream(outPropsPath)) {
            mergedProps.store(os, "Merged Gaia tile pack metadata");
        }
    }

    private static Properties loadProps(Path path) throws IOException {
        Properties p = new Properties();
        try (InputStream is = Files.newInputStream(path)) {
            p.load(is);
        }
        return p;
    }

    private static String req(Properties p, String k) {
        String v = p.getProperty(k);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("Missing property: " + k);
        return v.trim();
    }

    private static IndexTable readIndex(Path path, int tileCount) throws IOException {
        long[] offsets = new long[tileCount];
        int[] lengths = new int[tileCount];
        int[] counts = new int[tileCount];

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            for (int t = 0; t < tileCount; t++) {
                offsets[t] = dis.readLong();
                lengths[t] = dis.readInt();
                counts[t] = dis.readInt();
            }
        }
        return new IndexTable(tileCount, offsets, lengths, counts);
    }

    private static void copyTileBlock(
            RandomAccessFile raf,
            IndexTable idx,
            int tileId,
            OutputStream out,
            long[] oOff,
            int[] oLen,
            int[] oCnt,
            long[] posHolder)
            throws IOException {

        long off = idx.offsets[tileId];
        int len = idx.lengths[tileId];
        int cnt = idx.counts[tileId];

        if (len == 0 || cnt == 0) {
            oOff[tileId] = 0;
            oLen[tileId] = 0;
            oCnt[tileId] = 0;
            return;
        }

        byte[] buf = new byte[len];
        raf.seek(off);
        raf.readFully(buf);

        oOff[tileId] = posHolder[0];
        oLen[tileId] = len;
        oCnt[tileId] = cnt;

        out.write(buf);
        posHolder[0] += len;
    }

    private static final class TileRecords {
        final int count;
        final long[] sourceId;
        final float[] x;
        final float[] y;
        final float[] z;
        final float[] gmag;

        TileRecords(int count, long[] sourceId, float[] x, float[] y, float[] z, float[] gmag) {
            this.count = count;
            this.sourceId = sourceId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.gmag = gmag;
        }
    }

    private static TileRecords decodeTile(RandomAccessFile raf, long off, int len, int cnt) throws IOException {
        byte[] compressed = new byte[len];
        raf.seek(off);
        raf.readFully(compressed);

        long[] ids = new long[cnt];
        float[] x = new float[cnt];
        float[] y = new float[cnt];
        float[] z = new float[cnt];
        float[] g = new float[cnt];

        try (DataInputStream dis = new DataInputStream(
                new InflaterInputStream(new BufferedInputStream(new ByteArrayInputStream(compressed))))) {
            for (int i = 0; i < cnt; i++) {
                ids[i] = dis.readLong();
                x[i] = dis.readFloat();
                y[i] = dis.readFloat();
                z[i] = dis.readFloat();
                g[i] = dis.readFloat();
            }
        }
        return new TileRecords(cnt, ids, x, y, z, g);
    }

    private static byte[] encodeTile(TileRecords recs) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(256, recs.count * 24));
        try (DeflaterOutputStream def = new DeflaterOutputStream(new BufferedOutputStream(baos));
                DataOutputStream dos = new DataOutputStream(def)) {
            for (int i = 0; i < recs.count; i++) {
                dos.writeLong(recs.sourceId[i]);
                dos.writeFloat(recs.x[i]);
                dos.writeFloat(recs.y[i]);
                dos.writeFloat(recs.z[i]);
                dos.writeFloat(recs.gmag[i]);
            }
            dos.flush();
            def.finish();
        }
        return baos.toByteArray();
    }

    private static void ensureSortedBySourceId(TileRecords recs) {
        // Fast path: already sorted (unsigned).
        boolean sorted = true;
        for (int i = 1; i < recs.count; i++) {
            if (Long.compareUnsigned(recs.sourceId[i - 1], recs.sourceId[i]) > 0) {
                sorted = false;
                break;
            }
        }
        if (sorted) return;

        // Sort in-place by unsigned sourceId.
        sortBySourceIdUnsigned(recs, 0, recs.count - 1);
    }

    private static void sortBySourceIdUnsigned(TileRecords r, int lo, int hi) {
        // In-place quicksort on parallel arrays.
        int i = lo, j = hi;
        long pivot = r.sourceId[lo + ((hi - lo) >>> 1)];
        while (i <= j) {
            while (Long.compareUnsigned(r.sourceId[i], pivot) < 0) i++;
            while (Long.compareUnsigned(r.sourceId[j], pivot) > 0) j--;
            if (i <= j) {
                swap(r, i, j);
                i++;
                j--;
            }
        }
        if (lo < j) sortBySourceIdUnsigned(r, lo, j);
        if (i < hi) sortBySourceIdUnsigned(r, i, hi);
    }

    private static void swap(TileRecords r, int a, int b) {
        if (a == b) return;

        long lid = r.sourceId[a];
        r.sourceId[a] = r.sourceId[b];
        r.sourceId[b] = lid;

        float fx = r.x[a];
        r.x[a] = r.x[b];
        r.x[b] = fx;
        float fy = r.y[a];
        r.y[a] = r.y[b];
        r.y[b] = fy;
        float fz = r.z[a];
        r.z[a] = r.z[b];
        r.z[b] = fz;
        float fg = r.gmag[a];
        r.gmag[a] = r.gmag[b];
        r.gmag[b] = fg;
    }

    private static TileRecords unionBySourceIdPreferSecondary(TileRecords primary, TileRecords secondary) {
        int aN = primary.count;
        int bN = secondary.count;

        long[] ids = new long[aN + bN];
        float[] x = new float[aN + bN];
        float[] y = new float[aN + bN];
        float[] z = new float[aN + bN];
        float[] g = new float[aN + bN];

        int i = 0, j = 0, o = 0;
        while (i < aN && j < bN) {
            long aId = primary.sourceId[i];
            long bId = secondary.sourceId[j];
            int cmp = Long.compareUnsigned(aId, bId);

            if (cmp < 0) {
                ids[o] = aId;
                x[o] = primary.x[i];
                y[o] = primary.y[i];
                z[o] = primary.z[i];
                g[o] = primary.gmag[i];
                i++;
                o++;
            } else if (cmp > 0) {
                ids[o] = bId;
                x[o] = secondary.x[j];
                y[o] = secondary.y[j];
                z[o] = secondary.z[j];
                g[o] = secondary.gmag[j];
                j++;
                o++;
            } else {
                // Same sourceId: prefer secondary record.
                ids[o] = bId;
                x[o] = secondary.x[j];
                y[o] = secondary.y[j];
                z[o] = secondary.z[j];
                g[o] = secondary.gmag[j];
                i++;
                j++;
                o++;
            }
        }

        while (i < aN) {
            ids[o] = primary.sourceId[i];
            x[o] = primary.x[i];
            y[o] = primary.y[i];
            z[o] = primary.z[i];
            g[o] = primary.gmag[i];
            i++;
            o++;
        }

        while (j < bN) {
            ids[o] = secondary.sourceId[j];
            x[o] = secondary.x[j];
            y[o] = secondary.y[j];
            z[o] = secondary.z[j];
            g[o] = secondary.gmag[j];
            j++;
            o++;
        }

        // Trim arrays to actual output size.
        if (o == ids.length) {
            return new TileRecords(o, ids, x, y, z, g);
        }
        return new TileRecords(
                o,
                Arrays.copyOf(ids, o),
                Arrays.copyOf(x, o),
                Arrays.copyOf(y, o),
                Arrays.copyOf(z, o),
                Arrays.copyOf(g, o));
    }

    private static final class IndexTable {
        final int tileCount;
        final long[] offsets;
        final int[] lengths;
        final int[] counts;

        IndexTable(int tileCount, long[] offsets, int[] lengths, int[] counts) {
            this.tileCount = tileCount;
            this.offsets = offsets;
            this.lengths = lengths;
            this.counts = counts;
        }
    }

    private static final class Args {
        final Path primary;
        final Path secondary;
        final Path out;

        Args(Path primary, Path secondary, Path out) {
            this.primary = primary;
            this.secondary = secondary;
            this.out = out;
        }

        static Args parse(String[] args) {
            String p = null, s = null, o = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--primary":
                        p = args[++i];
                        break;
                    case "--secondary":
                        s = args[++i];
                        break;
                    case "--out":
                        o = args[++i];
                        break;
                    default:
                    // ignore
                }
            }
            if (p == null || s == null || o == null) {
                throw new IllegalArgumentException("Usage: --primary <dir> --secondary <dir> --out <dir>");
            }
            return new Args(Paths.get(p), Paths.get(s), Paths.get(o));
        }
    }
}
