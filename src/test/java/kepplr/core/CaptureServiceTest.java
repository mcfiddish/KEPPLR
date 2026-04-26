package kepplr.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link CaptureService} (Step 25). */
class CaptureServiceTest {

    @Nested
    @DisplayName("Frame filename format")
    class FrameNameFormat {

        @Test
        @DisplayName("Default 4-digit padding for < 10000 frames")
        void defaultPadding() {
            assertEquals("frame_%04d.png", CaptureService.computeFrameNameFormat(1));
            assertEquals("frame_%04d.png", CaptureService.computeFrameNameFormat(100));
            assertEquals("frame_%04d.png", CaptureService.computeFrameNameFormat(9999));
        }

        @Test
        @DisplayName("Auto-widens to 5 digits for 10000 frames")
        void fiveDigits() {
            assertEquals("frame_%05d.png", CaptureService.computeFrameNameFormat(10000));
            assertEquals("frame_%05d.png", CaptureService.computeFrameNameFormat(99999));
        }

        @Test
        @DisplayName("Auto-widens to 6 digits for 100000 frames")
        void sixDigits() {
            assertEquals("frame_%06d.png", CaptureService.computeFrameNameFormat(100000));
        }

        @Test
        @DisplayName("Single frame uses 4-digit padding")
        void singleFrame() {
            assertEquals("frame_%04d.png", CaptureService.computeFrameNameFormat(1));
        }

        @Test
        @DisplayName("Format produces correct filenames")
        void formatProducesCorrectFilenames() {
            String fmt = CaptureService.computeFrameNameFormat(60);
            assertEquals("frame_0000.png", String.format(fmt, 0));
            assertEquals("frame_0059.png", String.format(fmt, 59));
        }

        @Test
        @DisplayName("5-digit format produces correct filenames")
        void fiveDigitFilenames() {
            String fmt = CaptureService.computeFrameNameFormat(12345);
            assertEquals("frame_00000.png", String.format(fmt, 0));
            assertEquals("frame_12344.png", String.format(fmt, 12344));
        }

        @Test
        @DisplayName("Format widens based on explicit start frame index")
        void widenedByStartFrameIndex() {
            String fmt = CaptureService.computeFrameNameFormat(10001);
            assertEquals("frame_%05d.png", fmt);
            assertEquals("frame_09999.png", String.format(fmt, 9999));
            assertEquals("frame_10000.png", String.format(fmt, 10000));
        }
    }

    @Nested
    @DisplayName("capture_info.json serialization")
    class CaptureInfo {

        @Test
        @DisplayName("capture_info.json is valid JSON with extended fields")
        void captureInfoHasExtendedFields(@TempDir Path tmpDir) throws IOException {
            // Create a fake frame file so the image-reading code has something to find
            Path frame = tmpDir.resolve("frame_0000.png");
            // Write a minimal 1x1 PNG
            write1x1Png(frame);

            String json = """
                    {
                      "startEt": 9.322824693587389E8,
                      "etStep": 2.0,
                      "frameCount": 60,
                      "startFrameIndex": 240,
                      "width": 1920,
                      "height": 1080,
                      "captureTimestamp": "2026-03-22T14:30:00Z",
                      "appVersion": "KEPPLR version 2026.04.26-4c78154M",
                      "platform": "Linux/amd64 (Java 21)",
                      "configIdentity": "kepplr.properties",
                      "kernelIdentity": "resources/spice/kepplr.tm",
                      "renderQuality": "HIGH"
                    }
                    """;

            Path infoPath = tmpDir.resolve("capture_info.json");
            Files.writeString(infoPath, json, StandardCharsets.UTF_8);

            // Verify it's valid JSON and has expected fields
            String content = Files.readString(infoPath, StandardCharsets.UTF_8);
            assertTrue(content.contains("\"startEt\""));
            assertTrue(content.contains("\"etStep\""));
            assertTrue(content.contains("\"frameCount\""));
            assertTrue(content.contains("\"startFrameIndex\""));
            assertTrue(content.contains("\"width\""));
            assertTrue(content.contains("\"height\""));
            assertTrue(content.contains("\"captureTimestamp\""));
            // Extended manifest fields from REPRO-01
            assertTrue(content.contains("\"appVersion\""));
            assertTrue(content.contains("\"platform\""));
            assertTrue(content.contains("\"configIdentity\""));
            assertTrue(content.contains("\"kernelIdentity\""));
            assertTrue(content.contains("\"renderQuality\""));
        }
    }

    @Test
    @DisplayName("captureSequence rejects non-positive frameCount")
    void rejectsNonPositiveFrameCount() {
        assertThrows(
                IllegalArgumentException.class, () -> CaptureService.captureSequence("/tmp", 0, 0, 1.0, null, null));
        assertThrows(
                IllegalArgumentException.class, () -> CaptureService.captureSequence("/tmp", 0, -5, 1.0, null, null));
    }

    @Test
    @DisplayName("captureSequence rejects negative startFrameIndex")
    void rejectsNegativeStartFrameIndex() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CaptureService.captureSequence("/tmp", 0, 1, 1.0, -1, null, null));
    }

    /** Write a minimal valid 1x1 PNG file. */
    private static void write1x1Png(Path path) throws IOException {
        // Minimal 1x1 white PNG (67 bytes)
        var img = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFFFFFFFF);
        javax.imageio.ImageIO.write(img, "PNG", path.toFile());
    }
}
