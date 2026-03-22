package kepplr.apps;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link PngToMovie} (Step 25 update). */
class PngToMovieTest {

    @Test
    @DisplayName("Flat directory layout: verify mode succeeds with frame_*.png files")
    void flatLayoutVerifySucceeds(@TempDir Path tmpDir) throws IOException {
        // Create frame files
        for (int i = 0; i < 5; i++) {
            write1x1Png(tmpDir.resolve(String.format("frame_%04d.png", i)));
        }

        int exit = PngToMovie.run(new String[] {
            "-seq", tmpDir.toString(), "-out", tmpDir.resolve("out.webm").toString(), "-fps", "30", "-verify"
        });
        assertEquals(0, exit);
    }

    @Test
    @DisplayName("Flat directory layout: fails when no frame_*.png files exist")
    void flatLayoutFailsWhenEmpty(@TempDir Path tmpDir) {
        int exit = PngToMovie.run(new String[] {
            "-seq", tmpDir.toString(), "-out", tmpDir.resolve("out.webm").toString(), "-fps", "30", "-verify"
        });
        assertEquals(2, exit);
    }

    @Test
    @DisplayName("Legacy layout: verify mode succeeds with manifest.json + frames/")
    void legacyLayoutVerifySucceeds(@TempDir Path tmpDir) throws IOException {
        Path framesDir = tmpDir.resolve("frames");
        Files.createDirectories(framesDir);

        // Create manifest
        String manifest = """
                { "frameCount": 3, "padWidth": 4 }
                """;
        Files.writeString(tmpDir.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);

        // Create frame files
        for (int i = 0; i < 3; i++) {
            write1x1Png(framesDir.resolve(String.format("frame_%04d.png", i)));
        }

        int exit = PngToMovie.run(new String[] {
            "-seq", tmpDir.toString(), "-out", tmpDir.resolve("out.webm").toString(), "-fps", "30", "-verify"
        });
        assertEquals(0, exit);
    }

    @Test
    @DisplayName("capture_info.json is printed when present but not required")
    void captureInfoIsPrintedButNotRequired(@TempDir Path tmpDir) throws IOException {
        // Create frames
        for (int i = 0; i < 3; i++) {
            write1x1Png(tmpDir.resolve(String.format("frame_%04d.png", i)));
        }

        // Add capture_info.json
        String info = """
                {
                  "startEt": 1000.0,
                  "etStep": 2.0,
                  "frameCount": 3,
                  "width": 1920,
                  "height": 1080,
                  "captureTimestamp": "2026-03-22T14:30:00Z"
                }
                """;
        Files.writeString(tmpDir.resolve("capture_info.json"), info, StandardCharsets.UTF_8);

        int exit = PngToMovie.run(new String[] {
            "-seq", tmpDir.toString(), "-out", tmpDir.resolve("out.webm").toString(), "-fps", "30", "-verify"
        });
        assertEquals(0, exit);
    }

    @Test
    @DisplayName("Print mode produces ffmpeg command")
    void printModeProducesCommand(@TempDir Path tmpDir) throws IOException {
        for (int i = 0; i < 3; i++) {
            write1x1Png(tmpDir.resolve(String.format("frame_%04d.png", i)));
        }

        int exit = PngToMovie.run(new String[] {
            "-seq", tmpDir.toString(), "-out", tmpDir.resolve("out.webm").toString(), "-fps", "30", "-print"
        });
        assertEquals(0, exit);
    }

    /** Write a minimal valid 1x1 PNG file. */
    private static void write1x1Png(Path path) throws IOException {
        var img = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFFFFFFFF);
        javax.imageio.ImageIO.write(img, "PNG", path.toFile());
    }
}
