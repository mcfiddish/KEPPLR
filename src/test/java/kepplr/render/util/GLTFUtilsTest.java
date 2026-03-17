package kepplr.render.util;

import static org.junit.jupiter.api.Assertions.*;

import com.jme3.math.Quaternion;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link GLTFUtils}.
 *
 * <p>All tests construct synthetic GLB byte sequences rather than requiring real GLB files on disk.
 */
class GLTFUtilsTest {

    @TempDir
    Path tempDir;

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    /** Builds a minimal GLB with the given JSON payload as its only chunk. */
    private Path writeGlb(String json) throws IOException {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        // Pad to 4-byte alignment with spaces (GLB spec requirement for JSON chunk)
        int padded = (jsonBytes.length + 3) & ~3;
        byte[] jsonPadded = new byte[padded];
        System.arraycopy(jsonBytes, 0, jsonPadded, 0, jsonBytes.length);
        for (int i = jsonBytes.length; i < padded; i++) jsonPadded[i] = 0x20; // space

        int totalLength = 12 + 8 + padded; // header + chunk-header + chunk-data
        ByteBuffer bb = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        // GLB header
        bb.putInt(0x46546C67); // magic 'glTF'
        bb.putInt(2); // version
        bb.putInt(totalLength);
        // JSON chunk
        bb.putInt(padded); // chunkLength
        bb.putInt(0x4E4F534A); // chunkType 'JSON'
        bb.put(jsonPadded);

        Path glb = tempDir.resolve("test.glb");
        Files.write(glb, bb.array());
        return glb;
    }

    /** Builds a GLB with no chunks (header only). */
    private Path writeHeaderOnlyGlb() throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0x46546C67);
        bb.putInt(2);
        bb.putInt(12);
        Path glb = tempDir.resolve("header-only.glb");
        Files.write(glb, bb.array());
        return glb;
    }

    // ── tests ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void returnsIdentityForNonExistentFile() {
        Path missing = tempDir.resolve("missing.glb");
        Quaternion q = GLTFUtils.readModelToBodyFixedQuatFromGlb(missing);
        assertIdentity(q);
    }

    @Test
    void returnsIdentityForBadMagic() throws IOException {
        byte[] data = new byte[16];
        data[0] = 0x00; // wrong magic
        Path f = tempDir.resolve("bad.glb");
        Files.write(f, data);
        Quaternion q = GLTFUtils.readModelToBodyFixedQuatFromGlb(f);
        assertIdentity(q);
    }

    @Test
    void returnsIdentityForFileTooSmall() throws IOException {
        Path f = tempDir.resolve("tiny.glb");
        Files.write(f, new byte[] {0x01, 0x02});
        Quaternion q = GLTFUtils.readModelToBodyFixedQuatFromGlb(f);
        assertIdentity(q);
    }

    @Test
    void returnsIdentityWhenJsonChunkAbsent() throws IOException {
        Path glb = writeHeaderOnlyGlb();
        Quaternion q = GLTFUtils.readModelToBodyFixedQuatFromGlb(glb);
        assertIdentity(q);
    }

    @Test
    void returnsIdentityWhenFieldAbsentFromJson() throws IOException {
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0}";
        Path glb = writeGlb(json);
        Quaternion q = GLTFUtils.readModelToBodyFixedQuatFromGlb(glb);
        assertIdentity(q);
    }

    @Test
    void readsQuaternionFromMinimalExtras() throws IOException {
        String json = "{"
                + "\"asset\":{"
                + "  \"version\":\"2.0\","
                + "  \"extras\":{"
                + "    \"kepplr\":{"
                + "      \"modelToBodyFixedQuat\":{"
                + "        \"value\":[0.0,0.0,0.7071068,0.7071068]"
                + "      }"
                + "    }"
                + "  }"
                + "}"
                + "}";
        Path glb = writeGlb(json);
        Quaternion q = GLTFUtils.readModelToBodyFixedQuatFromGlb(glb);

        // 90° rotation around Z: x=0, y=0, z=sin(45°)≈0.7071, w=cos(45°)≈0.7071
        assertEquals(0.0f, q.getX(), 1e-5f, "x");
        assertEquals(0.0f, q.getY(), 1e-5f, "y");
        assertEquals(0.7071068f, q.getZ(), 1e-5f, "z");
        assertEquals(0.7071068f, q.getW(), 1e-5f, "w");
    }

    @Test
    void readsQuaternionWithScientificNotation() throws IOException {
        String json = "{\"asset\":{\"extras\":{\"kepplr\":{"
                + "\"modelToBodyFixedQuat\":{\"value\":[1.0e-7, 0.0, 0.0, 1.0]}}}}}";
        Path glb = writeGlb(json);
        Quaternion q = GLTFUtils.readModelToBodyFixedQuatFromGlb(glb);
        // Quaternion is normalized; w≈1 after normalization of nearly-identity
        assertEquals(1.0f, q.getW(), 1e-5f);
    }

    @Test
    void returnsIdentityWhenIdentityQuatProvided() throws IOException {
        String json =
                "{\"asset\":{\"extras\":{\"kepplr\":{" + "\"modelToBodyFixedQuat\":{\"value\":[0.0,0.0,0.0,1.0]}}}}}";
        Path glb = writeGlb(json);
        Quaternion q = GLTFUtils.readModelToBodyFixedQuatFromGlb(glb);
        assertIdentity(q);
    }

    @Test
    void returnsIdentityForMalformedNumbers() throws IOException {
        String json =
                "{\"asset\":{\"extras\":{\"kepplr\":{" + "\"modelToBodyFixedQuat\":{\"value\":[NaN,0.0,0.0,1.0]}}}}}";
        Path glb = writeGlb(json);
        Quaternion q = GLTFUtils.readModelToBodyFixedQuatFromGlb(glb);
        assertIdentity(q);
    }

    @Test
    void handlesWhitespaceInJson() throws IOException {
        String json = "{ \"asset\" : { \"extras\" : { \"kepplr\" : {\n"
                + "  \"modelToBodyFixedQuat\" : {\n"
                + "    \"value\" : [ 0.0 , 1.0 , 0.0 , 0.0 ]\n"
                + "  }\n"
                + "} } } }";
        Path glb = writeGlb(json);
        Quaternion q = GLTFUtils.readModelToBodyFixedQuatFromGlb(glb);
        assertEquals(0.0f, q.getX(), 1e-5f);
        assertEquals(1.0f, q.getY(), 1e-5f);
        assertEquals(0.0f, q.getZ(), 1e-5f);
        assertEquals(0.0f, q.getW(), 1e-5f);
    }

    // ── assertion helpers ─────────────────────────────────────────────────────────────────────────

    private static void assertIdentity(Quaternion q) {
        assertNotNull(q, "quaternion must not be null");
        assertEquals(0.0f, q.getX(), 1e-6f, "identity x");
        assertEquals(0.0f, q.getY(), 1e-6f, "identity y");
        assertEquals(0.0f, q.getZ(), 1e-6f, "identity z");
        assertEquals(1.0f, q.getW(), 1e-6f, "identity w");
    }
}
