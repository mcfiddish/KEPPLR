package kepplr.render.util;

import com.jme3.math.Quaternion;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * GLB/glTF utility methods used by the KEPPLR rendering pipeline.
 *
 * <p>This class provides low-level GLB binary parsing without relying on any third-party JSON library. It is
 * intentionally narrow: it reads only the fields that KEPPLR embeds in GLB extras, and delegates all model loading to
 * JME's {@code GlbLoader}.
 *
 * <p>All methods are static; this class is not instantiated.
 *
 * <p><b>GLB binary format recap (glTF 2.0 §4):</b>
 *
 * <pre>
 * header: magic(4) version(4) length(4)
 * chunks: chunkLength(4) chunkType(4) chunkData(chunkLength)
 *   JSON chunk type = 0x4E4F534A ('JSON' in little-endian)
 *   BIN  chunk type = 0x004E4942 ('BIN\0' in little-endian)
 * </pre>
 *
 * <p>All multi-byte integers in the GLB header and chunk headers are little-endian.
 *
 * <p><b>KEPPLR extras convention:</b> GLB files intended for use as KEPPLR shape models embed a quaternion in the asset
 * extras at the following JSON path:
 *
 * <pre>
 * asset.extras.kepplr.modelToBodyFixedQuat.value = [x, y, z, w]
 * </pre>
 *
 * The quaternion follows the glTF convention, which matches JME's {@link Quaternion} component order: {@code [x, y, z,
 * w]}. It encodes the constant rotation that maps glTF model-space axes into the body-fixed frame expected by SPICE.
 *
 * <p>Algorithm derived from prototype: {@code kepplr.visualization.jme.util.GLTFUtils} — reimplemented for new
 * architecture.
 */
public final class GLTFUtils {

    private static final Logger logger = LogManager.getLogger();

    /** GLB magic number: ASCII "glTF" in little-endian. */
    private static final int GLB_MAGIC = 0x46546C67;

    /** JSON chunk type: ASCII "JSON" in little-endian. */
    private static final int CHUNK_TYPE_JSON = 0x4E4F534A;

    private GLTFUtils() {}

    /**
     * Reads the {@code modelToBodyFixedQuat} quaternion from the JSON chunk of a GLB file.
     *
     * <p>The quaternion is stored at:
     *
     * <pre>
     * asset.extras.kepplr.modelToBodyFixedQuat.value = [x, y, z, w]
     * </pre>
     *
     * Component order follows the glTF 2.0 convention ({@code [x, y, z, w]}), which is also JME's {@link Quaternion}
     * constructor order. The quaternion is normalized before being returned.
     *
     * <p>If the field is absent, malformed, or the file cannot be read, the method logs at WARN and returns the
     * identity quaternion {@code (0, 0, 0, 1)}.
     *
     * <p><b>Usage example:</b>
     *
     * <pre>{@code
     * Path glbPath = Path.of("/resources/shapes/eros.glb");
     * Quaternion q = GLTFUtils.readModelToBodyFixedQuatFromGlb(glbPath);
     * glbModelRoot.setLocalRotation(q);
     * }</pre>
     *
     * @param glbPath absolute path to the {@code .glb} file
     * @return the {@code modelToBodyFixedQuat} quaternion, normalized; identity if absent or invalid
     */
    public static Quaternion readModelToBodyFixedQuatFromGlb(Path glbPath) {
        try {
            byte[] bytes = Files.readAllBytes(glbPath);
            ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

            if (bb.remaining() < 12) {
                logger.warn("GLB too small to contain a valid header: {}", glbPath);
                return identity();
            }

            int magic = bb.getInt();
            int version = bb.getInt();
            int length = bb.getInt();

            if (magic != GLB_MAGIC) {
                logger.warn("Not a GLB file (unexpected magic 0x{} in {})", Integer.toHexString(magic), glbPath);
                return identity();
            }
            if (version != 2) {
                logger.warn("Unexpected GLB version {} in {} (expected 2)", version, glbPath);
            }
            if (length != bytes.length) {
                logger.debug("GLB header length {} != file length {} in {}", length, bytes.length, glbPath);
            }

            // Walk chunks: [chunkLength (u32), chunkType (u32), chunkData ...]
            while (bb.remaining() >= 8) {
                int chunkLen = bb.getInt();
                int chunkType = bb.getInt();

                if (chunkLen < 0 || bb.remaining() < chunkLen) {
                    logger.warn("Truncated GLB chunk in {}", glbPath);
                    return identity();
                }

                byte[] chunk = new byte[chunkLen];
                bb.get(chunk);

                if (chunkType == CHUNK_TYPE_JSON) {
                    String json = new String(chunk, StandardCharsets.UTF_8).trim();
                    Quaternion q = extractQuatFromAssetExtras(json);
                    if (q != null) {
                        logger.info("Read asset.extras.kepplr.modelToBodyFixedQuat = {} from {}", q, glbPath);
                        return q;
                    }
                    // JSON chunk found but field absent — return identity without extra WARN;
                    // this is a normal case for GLBs not authored for KEPPLR.
                    return identity();
                }
            }

            logger.warn("No JSON chunk found in {}", glbPath);
            return identity();

        } catch (IOException e) {
            logger.warn("Failed to read GLB {}: {}", glbPath, e.toString());
            return identity();
        } catch (Exception e) {
            logger.warn("Failed to parse GLB extras in {}: {}", glbPath, e.toString());
            return identity();
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────────────────────────

    /**
     * Extracts {@code modelToBodyFixedQuat.value = [x,y,z,w]} from the JSON string using a regex.
     *
     * <p>The regex matches the specific structure KEPPLR embeds:
     * {@code "modelToBodyFixedQuat":{...,"value":[x,y,z,w],...}}. It handles arbitrary whitespace and scientific
     * notation. The {@link Pattern#DOTALL} flag lets {@code [^}]*?} cross newlines.
     *
     * @return normalized quaternion, or {@code null} if the field is absent or values are invalid
     */
    private static Quaternion extractQuatFromAssetExtras(String json) {
        Pattern p = Pattern.compile(
                "\"modelToBodyFixedQuat\"\\s*:\\s*\\{[^}]*?\"value\"\\s*:\\s*\\[\\s*"
                        + "([-+0-9.eE]+)\\s*,\\s*([-+0-9.eE]+)\\s*,\\s*"
                        + "([-+0-9.eE]+)\\s*,\\s*([-+0-9.eE]+)\\s*\\]",
                Pattern.DOTALL);

        Matcher m = p.matcher(json);
        if (!m.find()) {
            return null;
        }

        try {
            float x = Float.parseFloat(m.group(1));
            float y = Float.parseFloat(m.group(2));
            float z = Float.parseFloat(m.group(3));
            float w = Float.parseFloat(m.group(4));
            Quaternion q = new Quaternion(x, y, z, w);
            q.normalizeLocal();
            return q;
        } catch (NumberFormatException e) {
            logger.warn("Invalid quaternion values in asset.extras.kepplr.modelToBodyFixedQuat: {}", e.toString());
            return null;
        }
    }

    private static Quaternion identity() {
        return new Quaternion(0f, 0f, 0f, 1f);
    }
}
