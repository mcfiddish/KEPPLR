package kepplr.stars.catalogs.gaia.tools;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.InflaterInputStream;

/**
 * Builds an optional GAIA source-id index for {@code GaiaCatalog.getStar(id)}.
 *
 * <p>Output: {@code gaia.sourceidx} – sorted by {@code sourceId} ascending (unsigned), entries are fixed-size 16 bytes:
 *
 * <pre>
 * long sourceId
 * int  tileId
 * int  indexInTile
 * </pre>
 *
 * <p>This tool reads {@code gaia.idx} and {@code gaia.dat} and decodes each tile block. It uses an external sort by
 * {@code sourceId} to support large packs with bounded memory.
 */
public final class GaiaBuildSourceIndex {

    private GaiaBuildSourceIndex() {}

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);

        Path idx = a.packDir.resolve("gaia.idx");
        Path dat = a.packDir.resolve("gaia.dat");
        Path props = a.packDir.resolve("gaia.properties");
        Path out = a.packDir.resolve("gaia.sourceidx");
        Path tmp = a.packDir.resolve("source.tmp.bin");
        Path runsDir = a.packDir.resolve("source_runs");
        Files.createDirectories(runsDir);

        int nLat, nLon;
        Properties p = new Properties();
        try (InputStream is = Files.newInputStream(props)) {
            p.load(is);
        }
        nLat = Integer.parseInt(p.getProperty("tiling.nLat"));
        nLon = Integer.parseInt(p.getProperty("tiling.nLon"));
        int tileCount = nLat * nLon;

        // Read idx table
        long[] offsets = new long[tileCount];
        int[] lengths = new int[tileCount];
        int[] counts = new int[tileCount];
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(idx)))) {
            for (int t = 0; t < tileCount; t++) {
                offsets[t] = dis.readLong();
                lengths[t] = dis.readInt();
                counts[t] = dis.readInt();
            }
        }

        System.out.println("Pass 1: decode tiles -> temp source entries: " + tmp);
        long totalEntries = writeTempSourceEntries(dat, offsets, lengths, counts, tmp);

        System.out.println("Pass 2: external sort into runs by sourceId (chunk entries: " + a.chunkEntries + ")");
        List<Path> runs = sortIntoRuns(tmp, runsDir, a.chunkEntries);

        System.out.println("Pass 3: merge runs -> " + out);
        mergeRuns(runs, out);

        if (!a.keepTemp) {
            Files.deleteIfExists(tmp);
            for (Path r : runs) Files.deleteIfExists(r);
            try {
                Files.deleteIfExists(runsDir);
            } catch (IOException ignored) {
            }
        }

        System.out.println("Done. Entries: " + totalEntries);
    }

    private static long writeTempSourceEntries(Path dat, long[] offsets, int[] lengths, int[] counts, Path tmp)
            throws IOException {
        long kept = 0;
        try (RandomAccessFile raf = new RandomAccessFile(dat.toFile(), "r");
                OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp));
                DataOutputStream dos = new DataOutputStream(os)) {

            for (int tileId = 0; tileId < counts.length; tileId++) {
                int n = counts[tileId];
                if (n == 0) continue;

                byte[] compressed = new byte[lengths[tileId]];
                raf.seek(offsets[tileId]);
                raf.readFully(compressed);

                try (DataInputStream dis = new DataInputStream(
                        new InflaterInputStream(new BufferedInputStream(new ByteArrayInputStream(compressed))))) {
                    for (int idxInTile = 0; idxInTile < n; idxInTile++) {
                        long sourceId = dis.readLong();
                        // skip x,y,z,gMag
                        dis.readFloat();
                        dis.readFloat();
                        dis.readFloat();
                        dis.readFloat();

                        dos.writeLong(sourceId);
                        dos.writeInt(tileId);
                        dos.writeInt(idxInTile);
                        kept++;
                    }
                }
            }
        }
        return kept;
    }

    private static List<Path> sortIntoRuns(Path tmp, Path runsDir, int chunkEntries) throws IOException {
        long bytes = Files.size(tmp);
        if (bytes % 16 != 0) throw new IOException("Temp source file not aligned to 16-byte entries");
        long total = bytes / 16;

        List<Path> runs = new ArrayList<>();
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(tmp)))) {
            long remaining = total;
            int runNo = 0;
            while (remaining > 0) {
                int n = (int) Math.min(remaining, chunkEntries);
                Entry[] arr = new Entry[n];
                for (int i = 0; i < n; i++) {
                    long sourceId = dis.readLong();
                    int tileId = dis.readInt();
                    int idxInTile = dis.readInt();
                    arr[i] = new Entry(sourceId, tileId, idxInTile);
                }

                Arrays.sort(arr, (a, b) -> Long.compareUnsigned(a.sourceId, b.sourceId));

                Path run = runsDir.resolve(String.format("run_%05d.bin", runNo++));
                try (DataOutputStream dos =
                        new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(run)))) {
                    for (Entry e : arr) e.write(dos);
                }
                runs.add(run);
                remaining -= n;
                System.out.println("  wrote run " + run.getFileName() + " (" + n + " entries)");
            }
        }
        return runs;
    }

    private static void mergeRuns(List<Path> runs, Path out) throws IOException {
        List<RunReader> readers = new ArrayList<>(runs.size());
        for (Path r : runs) readers.add(new RunReader(r));

        PriorityQueue<Head> pq =
                new PriorityQueue<>((h1, h2) -> Long.compareUnsigned(h1.entry.sourceId, h2.entry.sourceId));

        for (int i = 0; i < readers.size(); i++) {
            Entry e = readers.get(i).next();
            if (e != null) pq.add(new Head(i, e));
        }

        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            while (!pq.isEmpty()) {
                Head h = pq.poll();
                Entry e = h.entry;

                Entry next = readers.get(h.runIndex).next();
                if (next != null) pq.add(new Head(h.runIndex, next));

                // write entry
                e.write(dos);
            }
        } finally {
            for (RunReader rr : readers) rr.close();
        }
    }

    private static final class Entry {
        final long sourceId;
        final int tileId;
        final int idxInTile;

        Entry(long sourceId, int tileId, int idxInTile) {
            this.sourceId = sourceId;
            this.tileId = tileId;
            this.idxInTile = idxInTile;
        }

        void write(DataOutputStream dos) throws IOException {
            dos.writeLong(sourceId);
            dos.writeInt(tileId);
            dos.writeInt(idxInTile);
        }
    }

    private static final class Head {
        final int runIndex;
        final Entry entry;

        Head(int runIndex, Entry entry) {
            this.runIndex = runIndex;
            this.entry = entry;
        }
    }

    private static final class RunReader implements Closeable {
        final DataInputStream dis;

        RunReader(Path path) throws IOException {
            this.dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)));
        }

        Entry next() throws IOException {
            try {
                long sourceId = dis.readLong();
                int tileId = dis.readInt();
                int idxInTile = dis.readInt();
                return new Entry(sourceId, tileId, idxInTile);
            } catch (EOFException eof) {
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            dis.close();
        }
    }

    private static final class Args {
        final Path packDir;
        final int chunkEntries;
        final boolean keepTemp;

        private Args(Path packDir, int chunkEntries, boolean keepTemp) {
            this.packDir = packDir;
            this.chunkEntries = chunkEntries;
            this.keepTemp = keepTemp;
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

            Path packDir = Paths.get(req(m, "pack"));
            int chunk = Integer.parseInt(m.getOrDefault("chunk-entries", "5000000"));
            boolean keep = Boolean.parseBoolean(m.getOrDefault("keep-temp", "false"));
            return new Args(packDir, chunk, keep);
        }

        private static String req(Map<String, String> m, String k) {
            String v = m.get(k);
            if (v == null) throw new IllegalArgumentException("Missing required arg: --" + k);
            return v;
        }
    }
}
