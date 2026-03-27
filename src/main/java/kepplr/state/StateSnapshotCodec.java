package kepplr.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import kepplr.camera.CameraFrame;

/**
 * Encodes and decodes {@link StateSnapshot} as compact, versioned Base64url strings (Step 26).
 *
 * <p>The wire format is a single version byte followed by a packed binary payload, then Base64url-encoded (no padding).
 * This keeps the string short enough to paste into chat, emails, or script comments.
 *
 * <h3>Version 1 layout (74 bytes payload)</h3>
 *
 * <pre>
 * byte    version         (1)
 * double  et              (8)
 * double  timeRate        (8)
 * byte    flags           (1) — bit 0 = paused
 * double  camPosX         (8)
 * double  camPosY         (8)
 * double  camPosZ         (8)
 * float   camOrientX      (4)
 * float   camOrientY      (4)
 * float   camOrientZ      (4)
 * float   camOrientW      (4)
 * byte    cameraFrame     (1) — ordinal of CameraFrame enum
 * int     focusedBodyId   (4)
 * int     targetedBodyId  (4)
 * int     selectedBodyId  (4)
 * double  fovDeg          (8)
 * </pre>
 */
public final class StateSnapshotCodec {

    /** Current format version. */
    static final byte VERSION = 1;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private StateSnapshotCodec() {}

    /**
     * Encode a snapshot to a Base64url string.
     *
     * @param snapshot the snapshot to encode; must not be null
     * @return URL-safe Base64 string
     */
    public static String encode(StateSnapshot snapshot) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(75);
            DataOutputStream out = new DataOutputStream(baos);

            out.writeByte(VERSION);
            out.writeDouble(snapshot.et());
            out.writeDouble(snapshot.timeRate());
            out.writeByte(snapshot.paused() ? 1 : 0);

            double[] pos = snapshot.camPosJ2000();
            out.writeDouble(pos[0]);
            out.writeDouble(pos[1]);
            out.writeDouble(pos[2]);

            float[] orient = snapshot.camOrientJ2000();
            out.writeFloat(orient[0]);
            out.writeFloat(orient[1]);
            out.writeFloat(orient[2]);
            out.writeFloat(orient[3]);

            out.writeByte(snapshot.cameraFrame().ordinal());
            out.writeInt(snapshot.focusedBodyId());
            out.writeInt(snapshot.targetedBodyId());
            out.writeInt(snapshot.selectedBodyId());
            out.writeDouble(snapshot.fovDeg());

            out.flush();
            return ENCODER.encodeToString(baos.toByteArray());
        } catch (IOException e) {
            // Should never happen with ByteArrayOutputStream
            throw new IllegalStateException("Failed to encode state snapshot", e);
        }
    }

    /**
     * Decode a Base64url string to a snapshot.
     *
     * @param encoded the encoded string; must not be null or empty
     * @return the decoded snapshot
     * @throws IllegalArgumentException if the string is malformed, has an unsupported version, or is the wrong length
     */
    public static StateSnapshot decode(String encoded) {
        byte[] bytes;
        try {
            bytes = DECODER.decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid state string: not valid Base64url", e);
        }

        if (bytes.length < 1) {
            throw new IllegalArgumentException("Invalid state string: empty payload");
        }

        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            byte version = in.readByte();

            if (version != VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported state string version: " + version + " (expected " + VERSION + ")");
            }

            double et = in.readDouble();
            double timeRate = in.readDouble();
            boolean paused = in.readByte() != 0;

            double[] pos = new double[] {in.readDouble(), in.readDouble(), in.readDouble()};
            float[] orient = new float[] {in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat()};

            int frameOrdinal = in.readByte() & 0xFF;
            CameraFrame[] frames = CameraFrame.values();
            if (frameOrdinal >= frames.length) {
                throw new IllegalArgumentException("Invalid camera frame ordinal: " + frameOrdinal);
            }
            CameraFrame frame = frames[frameOrdinal];

            int focusId = in.readInt();
            int targetId = in.readInt();
            int selectedId = in.readInt();
            double fovDeg = in.readDouble();

            return new StateSnapshot(et, timeRate, paused, pos, orient, frame, focusId, targetId, selectedId, fovDeg);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid state string: truncated or corrupt payload", e);
        }
    }
}
