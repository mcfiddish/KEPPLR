package kepplr.stars.catalogs.gaia;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Properties;
import java.io.DataOutputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Files;

/**
 * Tests for GaiaCatalog error handling and diagnostic messages.
 */
class GaiaCatalogTest {

    @TempDir
    Path tempDir;

    /**
     * When source index is missing, getStar() should produce a clear error
     * that tells the user exactly what to do.
     */
    @Test
    void getStar_throwsClearMessage_whenSourceIndexMissing() throws IOException {
        // Create a minimal tile pack without source index
        createMinimalPack(tempDir, false);

        GaiaCatalog cat = GaiaCatalog.load(tempDir);

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> cat.getStar("GAIA:1234567890")
        );

        String msg = ex.getMessage();
        // The message should mention the missing file
        assertTrue(
            msg.toLowerCase().contains("source index") || msg.toLowerCase().contains("sourceidx"),
            "Error message should mention 'source index' or 'sourceidx': " + msg
        );
        // The message should mention how to fix it (build the index)
        assertTrue(
            msg.toLowerCase().contains("build") || msg.toLowerCase().contains("gaiauildsourceindex"),
            "Error message should mention how to rebuild: " + msg
        );
    }

    /**
     * When source index file exists but is corrupted, getStar() should report
     * a corruption error rather than a missing-index error.
     */
    @Test
    void getStar_reportsCorruption_whenIndexFileCorrupt() throws IOException {
        // Create a minimal tile pack
        createMinimalPack(tempDir, false);

        // Write a corrupt index file (wrong size)
        Path corruptIdx = tempDir.resolve("gaia.sourceidx");
        Files.write(corruptIdx, new byte[15]); // not 16-byte aligned

        GaiaCatalog cat = GaiaCatalog.load(tempDir);

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> cat.getStar("GAIA:1234567890")
        );

        String msg = ex.getMessage();
        // Should NOT suggest rebuilding - the file exists, it's just corrupt
        assertFalse(
            msg.toLowerCase().contains("rebuild"),
            "Corruption error should not suggest rebuild: " + msg
        );
    }

    // ----- helper methods -----

    /**
     * The tile cache tracks estimated memory usage.
     */
    @Test
    void cacheStats_reportsEstimatedMemory() throws IOException {
        createMinimalPack(tempDir, false);

        GaiaCatalog cat = GaiaCatalog.load(tempDir, 10);

        GaiaCatalog.CacheStats stats = cat.getCacheStats();

        assertNotNull(stats);
        assertTrue(stats.cachedTileCount >= 0);
        assertTrue(stats.estimatedMemoryBytes >= 0);
    }

    private void createMinimalPack(Path dir, boolean withSourceIndex) throws IOException {
        Properties props = new Properties();
        props.setProperty("tiling.nLat", "2");
        props.setProperty("tiling.nLon", "2");
        props.setProperty("gaia.dataRelease", "DR3");
        props.setProperty("gaia.refEpoch", "2016.0");
        Files.write(dir.resolve("gaia.properties"), props);

        // Write empty idx (2x2 = 4 tiles)
        int tileCount = 4;
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(Files.write(dir.resolve("gaia.idx"))))) {
            for (int i = 0; i < tileCount; i++) {
                dos.writeLong(0);    // offset
                dos.writeInt(0);     // length
                dos.writeInt(0);     // count (empty tile)
            }
        }

        // Write empty dat
        Files.write(dir.resolve("gaia.dat"), new byte[0]);

        if (withSourceIndex) {
            // Write empty source index
            Files.write(dir.resolve("gaia.sourceidx"), new byte[0]);
        }
    }
}