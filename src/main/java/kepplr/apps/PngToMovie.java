package kepplr.apps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kepplr.templates.KEPPLRTool;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Standalone CLI tool to encode a KEPPLR PNG sequence into a video using ffmpeg (Step 25 update).
 *
 * <p>Accepts a directory of PNG frames and a frame rate as its minimum required inputs. Two directory layouts are
 * supported:
 *
 * <h3>Layout A — directory of PNGs (preferred, Step 25)</h3>
 *
 * <pre>
 * sequenceDir/
 *   frame_0000.png
 *   frame_0001.png
 *   ...
 *   capture_info.json   (optional, informational)
 * </pre>
 *
 * <h3>Layout B — legacy manifest layout</h3>
 *
 * <pre>
 * sequenceDir/
 *   manifest.json
 *   frames/
 *     frame_0000.png ...
 * </pre>
 *
 * <p>The tool auto-detects which layout is present. If {@code capture_info.json} is found, its contents are printed at
 * startup for informational purposes but are not required.
 *
 * <p>Frame ordering uses alphabetical sort of PNG filenames — the {@code frame_NNNN.png} naming convention guarantees
 * correct ordering.
 *
 * <p>Playback FPS is supplied by the user via CLI ({@code -fps}) and is NOT taken from any sidecar file.
 */
public class PngToMovie implements KEPPLRTool {
    private static final Logger logger = LogManager.getLogger();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PngToMovie() {}

    @Override
    public String shortDescription() {
        return "Encode a KEPPLR PNG sequence directory into a WebM video using ffmpeg.";
    }

    @Override
    public String fullDescription(Options options) {
        String header = """
                Encode a directory of PNG frames (frame_*.png) into a video.
                This is a post-process step; it does not depend on KEPPLR runtime.
                Playback FPS is provided via CLI (-fps) and is not read from any sidecar.

                Examples:
                  Encode VP9 WebM at 60 fps playback:
                    PngToMovie -seq /path/to/seq -out /path/to/seq/animation.webm -fps 60

                  Specify ffmpeg path and CRF:
                    PngToMovie -seq /path/to/seq -out out.webm -fps 30 -ffmpeg /usr/local/bin/ffmpeg -crf 30

                  Print ffmpeg command only:
                    PngToMovie -seq /path/to/seq -out out.webm -fps 30 -print
                """;
        String footer = """

                Notes:
                
                  - Default codec is VP9 WebM.
                
                  - Requires ffmpeg available on PATH, or provide -ffmpeg.
                
                  - Accepts both flat directories of PNGs and legacy manifest+frames/ layouts.
                """;
        return KEPPLRTool.super.fullDescription(options, header, footer);
    }

    private static Options defineOptions() {
        Options options = KEPPLRTool.defineOptions();

        options.addOption(Option.builder("seq")
                .longOpt("sequence")
                .hasArg()
                .required()
                .desc("PNG sequence directory (flat frame_*.png or legacy manifest.json + frames/).")
                .get());

        options.addOption(Option.builder("out")
                .longOpt("output")
                .hasArg()
                .required()
                .desc("Output video file path (recommended .webm).")
                .get());

        options.addOption(Option.builder("fps")
                .longOpt("playback-fps")
                .hasArg()
                .required()
                .desc("Playback frames-per-second for the output video (ffmpeg -framerate).")
                .get());

        options.addOption(Option.builder("ffmpeg")
                .hasArg()
                .desc("Path to ffmpeg executable. If omitted, uses 'ffmpeg' from PATH.")
                .get());

        options.addOption(Option.builder("codec")
                .hasArg()
                .desc("Codec preset: vp9 (default). (Future: av1, h264).")
                .get());

        options.addOption(Option.builder("crf")
                .hasArg()
                .desc("Quality factor (CRF). VP9 typical: 20-40. Default 30.")
                .get());

        options.addOption(Option.builder("print")
                .longOpt("print-cmd")
                .desc("Print the ffmpeg command and exit (do not encode).")
                .get());

        options.addOption(Option.builder("verify")
                .desc("Verify sequence inputs and exit (no encoding).")
                .get());

        return options;
    }

    public static int run(String[] args) {
        PngToMovie tool = new PngToMovie();
        Options options = defineOptions();
        CommandLine cl = tool.parseArgs(args, options);

        Map<MessageLabel, String> startupMessages = tool.startupMessages(cl);
        for (MessageLabel ml : startupMessages.keySet()) {
            logger.info("{} {}", ml.label, startupMessages.get(ml));
        }

        try {
            Path seqDir = Path.of(cl.getOptionValue("seq")).toAbsolutePath().normalize();
            Path outFile = Path.of(cl.getOptionValue("out")).toAbsolutePath().normalize();

            int playbackFps = parseRequiredPositiveInt(cl.getOptionValue("fps"), "fps");

            String ffmpegExe = cl.getOptionValue("ffmpeg", "ffmpeg");
            String codec = cl.getOptionValue("codec", "vp9").toLowerCase(Locale.ROOT);
            int crf = parseIntOrDefault(cl.getOptionValue("crf"), 30);

            // Print capture_info.json if present (informational only)
            printCaptureInfo(seqDir);

            SequenceInfo info = loadAndValidateSequence(seqDir);

            if (cl.hasOption("verify")) {
                logger.info(
                        "Verified OK: framesDir={}, frameCount={}, padWidth={}",
                        info.framesDir,
                        info.frameCount,
                        info.padWidth);
                logger.info("Finished");
                return 0;
            }

            List<String> cmd = buildFfmpegCommand(ffmpegExe, codec, crf, playbackFps, info.framesDir, outFile, info);

            if (cl.hasOption("print")) {
                logger.info("ffmpeg command:\n{}", String.join(" ", cmd));
                logger.info("Finished");
                return 0;
            }

            ensureFfmpegAvailable(ffmpegExe);

            int exit = runProcess(cmd, seqDir);
            if (exit != 0) {
                throw new IllegalStateException("ffmpeg exited with code " + exit);
            }

            logger.info("Wrote video: {}", outFile);
            logger.info("Finished");
        } catch (Exception e) {
            logger.error("Failed", e);
            return 2;
        }

        return 0;
    }

    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) System.exit(code);
    }

    private static void ensureFfmpegAvailable(String ffmpegExe) throws Exception {
        List<String> cmd = List.of(ffmpegExe, "-version");
        int exit = runProcess(cmd, Path.of("."));
        if (exit != 0) {
            throw new IllegalStateException("ffmpeg not available (exit " + exit + "): " + ffmpegExe);
        }
    }

    private static int runProcess(List<String> cmd, Path workDir) throws Exception {
        logger.info("Running: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Process p = pb.start();
        try (BufferedReader br =
                new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                logger.info("[ffmpeg] {}", line);
            }
        }
        return p.waitFor();
    }

    private static List<String> buildFfmpegCommand(
            String ffmpegExe, String codec, int crf, int playbackFps, Path framesDir, Path outFile, SequenceInfo info) {

        // Input pattern: frame_%0Nd.png (must match KEPPLR naming)
        String pattern = framesDir
                .resolve(String.format(Locale.ROOT, "frame_%%0%dd.png", info.padWidth))
                .toString();

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegExe);
        cmd.add("-y");

        // Playback fps comes from CLI, not any sidecar
        cmd.add("-framerate");
        cmd.add(Integer.toString(playbackFps));

        cmd.add("-i");
        cmd.add(pattern);

        if (codec.equals("vp9")) {
            cmd.add("-c:v");
            cmd.add("libvpx-vp9");
            cmd.add("-pix_fmt");
            cmd.add("yuv420p");
            cmd.add("-b:v");
            cmd.add("0");
            cmd.add("-crf");
            cmd.add(Integer.toString(crf));
        } else {
            throw new IllegalArgumentException("Unsupported codec: " + codec + " (supported: vp9)");
        }

        cmd.add(outFile.toString());
        return cmd;
    }

    /**
     * Detect and validate the sequence directory layout. Supports both flat PNG directories and legacy manifest+frames/
     * layouts.
     */
    private static SequenceInfo loadAndValidateSequence(Path seqDir) throws Exception {
        Objects.requireNonNull(seqDir, "seqDir");

        if (!Files.isDirectory(seqDir)) {
            throw new IllegalArgumentException("Sequence path is not a directory: " + seqDir);
        }

        Path manifestPath = seqDir.resolve("manifest.json");
        Path framesDir = seqDir.resolve("frames");

        // Legacy layout: manifest.json + frames/ subdirectory
        if (Files.isRegularFile(manifestPath) && Files.isDirectory(framesDir)) {
            return loadLegacySequence(seqDir, manifestPath, framesDir);
        }

        // Flat layout: frame_*.png directly in seqDir
        return loadFlatSequence(seqDir);
    }

    /**
     * Load sequence info from a flat directory of PNG files. Scans for frame_*.png, sorts alphabetically, counts them,
     * and infers pad width.
     */
    private static SequenceInfo loadFlatSequence(Path seqDir) throws Exception {
        List<String> frameFiles;
        try (Stream<Path> stream = Files.list(seqDir)) {
            frameFiles = stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("frame_") && n.endsWith(".png"))
                    .sorted()
                    .collect(Collectors.toList());
        }

        if (frameFiles.isEmpty()) {
            throw new IllegalArgumentException("No frame_*.png files found in directory: " + seqDir);
        }

        int frameCount = frameFiles.size();
        int padWidth = extractPadWidth(frameFiles.get(0))
                .orElseThrow(() ->
                        new IllegalArgumentException("Cannot infer pad width from filename: " + frameFiles.get(0)));
        padWidth = Math.max(1, padWidth);

        logger.info("Flat layout: {} frames, padWidth={}", frameCount, padWidth);

        // Validate first and last frames
        Path first = seqDir.resolve(String.format(Locale.ROOT, "frame_%0" + padWidth + "d.png", 0));
        Path last = seqDir.resolve(String.format(Locale.ROOT, "frame_%0" + padWidth + "d.png", frameCount - 1));

        if (!Files.isRegularFile(first)) {
            throw new IllegalArgumentException("Missing first frame: " + first);
        }
        if (!Files.isRegularFile(last)) {
            throw new IllegalArgumentException("Missing last frame: " + last);
        }

        return new SequenceInfo(frameCount, padWidth, seqDir);
    }

    /** Load sequence info from the legacy manifest.json + frames/ layout. */
    private static SequenceInfo loadLegacySequence(Path seqDir, Path manifestPath, Path framesDir) throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));

        int frameCount = requireInt(root, "frameCount");
        if (frameCount <= 0) throw new IllegalArgumentException("manifest.frameCount must be > 0");

        Integer padWidth = optInt(root, "padWidth").orElse(null);

        // Prefer padWidth from manifest, else infer from numFrames, else infer from filenames.
        if (padWidth == null) {
            Integer numFrames = optInt(root, "numFrames").orElse(null);
            if (numFrames != null && numFrames > 0) {
                padWidth = digits(Math.max(0, numFrames - 1));
            } else {
                padWidth = inferPadWidthFromFirstFrame(framesDir)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Cannot infer padWidth (manifest missing padWidth/numFrames and no frames found)."));
            }
        }
        padWidth = Math.max(1, padWidth);

        // Validate first/last frames exist based on inferred naming.
        Path first = framesDir.resolve(String.format(Locale.ROOT, "frame_%0" + padWidth + "d.png", 0));
        Path last = framesDir.resolve(String.format(Locale.ROOT, "frame_%0" + padWidth + "d.png", frameCount - 1));

        if (!Files.isRegularFile(first)) {
            throw new IllegalArgumentException("Missing first frame: " + first);
        }
        if (!Files.isRegularFile(last)) {
            throw new IllegalArgumentException("Missing last frame: " + last);
        }

        return new SequenceInfo(frameCount, padWidth, framesDir);
    }

    /** Print capture_info.json contents if present (informational only — no tool depends on it). */
    private static void printCaptureInfo(Path seqDir) {
        Path infoPath = seqDir.resolve("capture_info.json");
        if (!Files.isRegularFile(infoPath)) return;

        try {
            JsonNode root = MAPPER.readTree(Files.readString(infoPath, StandardCharsets.UTF_8));
            logger.info("capture_info.json found:");
            if (root.has("startEt")) logger.info("  startEt: {}", root.get("startEt"));
            if (root.has("etStep")) logger.info("  etStep: {}", root.get("etStep"));
            if (root.has("frameCount")) logger.info("  frameCount: {}", root.get("frameCount"));
            if (root.has("width")) logger.info("  width: {}", root.get("width"));
            if (root.has("height")) logger.info("  height: {}", root.get("height"));
            if (root.has("captureTimestamp")) logger.info("  captureTimestamp: {}", root.get("captureTimestamp"));
        } catch (Exception e) {
            logger.warn("Could not read capture_info.json: {}", e.getMessage());
        }
    }

    private static Optional<Integer> inferPadWidthFromFirstFrame(Path framesDir) throws Exception {
        // Look for files like frame_0000.png and infer number of digits.
        try (var stream = Files.list(framesDir)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("frame_") && n.endsWith(".png"))
                    .map(PngToMovie::extractPadWidth)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .findFirst();
        }
    }

    private static Optional<Integer> extractPadWidth(String filename) {
        // frame_0000.png -> 4
        // frame_0.png -> 1
        int us = filename.indexOf('_');
        int dot = filename.lastIndexOf('.');
        if (us < 0 || dot < 0 || dot <= us + 1) return Optional.empty();
        String num = filename.substring(us + 1, dot);
        for (int i = 0; i < num.length(); i++) {
            if (!Character.isDigit(num.charAt(i))) return Optional.empty();
        }
        return Optional.of(num.length());
    }

    private static int digits(int n) {
        if (n <= 0) return 1;
        int d = 0;
        while (n > 0) {
            n /= 10;
            d++;
        }
        return Math.max(1, d);
    }

    private static int requireInt(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isNumber()) {
            throw new IllegalArgumentException("manifest missing numeric field: " + field);
        }
        return n.asInt();
    }

    private static Optional<Integer> optInt(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isNumber()) return Optional.empty();
        return Optional.of(n.asInt());
    }

    private static int parseRequiredPositiveInt(String s, String name) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(name + " is required");
        try {
            int v = Integer.parseInt(s.trim());
            if (v <= 0) throw new IllegalArgumentException(name + " must be > 0");
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static int parseIntOrDefault(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Sequence metadata: frame count, zero-pad width, and the directory containing the actual PNG files.
     *
     * @param frameCount total number of frames
     * @param padWidth number of digits in the zero-padded frame index
     * @param framesDir directory containing the frame_*.png files (may be the seqDir itself or a frames/ subdir)
     */
    private record SequenceInfo(int frameCount, int padWidth, Path framesDir) {}
}
