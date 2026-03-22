package kepplr.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import kepplr.commands.SimulationCommands;
import kepplr.state.SimulationState;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shared capture loop logic for screenshot sequences (Step 25).
 *
 * <p>This is a compound operation that calls {@link SimulationCommands} primitives internally. It is NOT a
 * {@code SimulationCommands} method and is NOT loggable by {@code CommandRecorder}.
 *
 * <p>The {@link #captureSequence} method <b>blocks</b> until the entire sequence is complete. It must be called from a
 * background thread (the Groovy script thread or a dedicated capture thread — never the JME or FX thread).
 *
 * <h3>Example</h3>
 *
 * <pre>{@code
 * CaptureService.captureSequence("/tmp/frames", 4.895e8, 60, 2.0, commands, state);
 * }</pre>
 */
public final class CaptureService {

    private static final Logger logger = LogManager.getLogger();

    /** Maximum seconds to wait for a single frame capture or fence before timing out. */
    private static final long FRAME_TIMEOUT_SEC = 30L;

    private CaptureService() {}

    /**
     * Capture a sequence of frames as PNG files.
     *
     * <p>Behavior:
     *
     * <ol>
     *   <li>Creates {@code outputDir} if it doesn't exist.
     *   <li>Sets ET to {@code startET} via {@code SimulationCommands.setET(startET)}.
     *   <li>Pauses the simulation via {@code SimulationCommands.setPaused(true)}.
     *   <li>Loops {@code frameCount} times:
     *       <ol type="a">
     *         <li>On the first frame, captures at {@code startET} without advancing. On subsequent frames, advances ET
     *             by {@code etStep}.
     *         <li>Waits for one render frame (JME enqueue fence).
     *         <li>Captures screenshot to {@code outputDir/frame_NNNN.png}.
     *       </ol>
     *   <li>After the loop, the simulation remains paused at the final ET.
     *   <li>Writes {@code capture_info.json} to {@code outputDir}.
     * </ol>
     *
     * @param outputDir directory for output PNG files (created if it doesn't exist)
     * @param startET starting ET (TDB seconds past J2000)
     * @param frameCount number of frames to capture; must be positive
     * @param etStep ET advance per frame in seconds
     * @param commands simulation commands for time control and screenshot
     * @param state simulation state for reading current ET and viewport dimensions
     * @throws IllegalArgumentException if frameCount is not positive
     */
    public static void captureSequence(
            String outputDir,
            double startET,
            int frameCount,
            double etStep,
            SimulationCommands commands,
            SimulationState state) {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount must be positive: " + frameCount);
        }

        Path outPath = Path.of(outputDir);
        try {
            Files.createDirectories(outPath);
        } catch (IOException e) {
            logger.error("Cannot create output directory '{}': {}", outputDir, e.getMessage());
            return;
        }

        String format = computeFrameNameFormat(frameCount);

        // Set start time and pause
        commands.setET(startET);
        commands.setPaused(true);

        double currentET = startET;
        logger.info(
                "Capture sequence starting: {} frames, startET={}, etStep={}, dir={}",
                frameCount,
                startET,
                etStep,
                outputDir);

        for (int i = 0; i < frameCount; i++) {
            if (Thread.currentThread().isInterrupted()) {
                logger.info("Capture sequence interrupted at frame {}/{}", i, frameCount);
                break;
            }

            if (i > 0) {
                currentET += etStep;
                commands.setET(currentET);
            }

            // Wait for one frame to render so the scene graph updates with the new ET.
            // saveScreenshot itself enqueues on the JME thread and blocks, which serves as
            // both the fence and the capture.
            String framePath = outPath.resolve(String.format(format, i)).toString();
            commands.saveScreenshot(framePath);

            if ((i + 1) % 10 == 0 || i == frameCount - 1) {
                logger.info("Captured frame {}/{}", i + 1, frameCount);
            }
        }

        // Write capture_info.json sidecar
        writeCaptureInfo(outPath, startET, etStep, frameCount, state);
        logger.info("Capture sequence complete: {} frames to {}", frameCount, outputDir);
    }

    /**
     * Compute the frame filename format string, auto-widening the zero-pad if {@code frameCount >= 10000}.
     *
     * @param frameCount total number of frames in the sequence
     * @return a format string like {@code "frame_%04d.png"} or {@code "frame_%05d.png"}
     */
    static String computeFrameNameFormat(int frameCount) {
        int digits = 4; // minimum 4 digits
        int threshold = 10_000;
        while (frameCount >= threshold) {
            digits++;
            threshold *= 10;
        }
        return "frame_%0" + digits + "d.png";
    }

    /**
     * Write the {@code capture_info.json} sidecar file.
     *
     * <p>Reads width and height from the first captured PNG file to avoid requiring JME access.
     */
    private static void writeCaptureInfo(
            Path outPath, double startET, double etStep, int frameCount, SimulationState state) {
        try {
            // Read viewport dimensions from the first captured frame
            int width = 0;
            int height = 0;
            String format = computeFrameNameFormat(frameCount);
            Path firstFrame = outPath.resolve(String.format(format, 0));
            if (Files.isRegularFile(firstFrame)) {
                try {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(firstFrame.toFile());
                    if (img != null) {
                        width = img.getWidth();
                        height = img.getHeight();
                    }
                } catch (IOException e) {
                    logger.warn("Could not read first frame for dimensions: {}", e.getMessage());
                }
            }

            String json = String.format(
                    """
                    {
                      "startEt": %s,
                      "etStep": %s,
                      "frameCount": %d,
                      "width": %d,
                      "height": %d,
                      "captureTimestamp": "%s"
                    }
                    """,
                    Double.toString(startET),
                    Double.toString(etStep),
                    frameCount,
                    width,
                    height,
                    Instant.now().toString());

            Path infoPath = outPath.resolve(KepplrConstants.CAPTURE_INFO_FILENAME);
            Files.writeString(infoPath, json, StandardCharsets.UTF_8);
            logger.info("Wrote {}", infoPath);
        } catch (IOException e) {
            logger.error("Failed to write capture_info.json: {}", e.getMessage());
        }
    }
}
