package kepplr.camera;

import com.jme3.math.Matrix3f;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.RotationMatrixIJK;
import picante.math.vectorspace.VectorIJK;

/**
 * Applies per-frame body-fixed co-rotation to the camera (REDESIGN.md §1.5).
 *
 * <p>Each frame, this class computes the incremental rotation delta between the focused body's J2000→bodyFixed
 * orientation at the previous and current ET. That delta is applied to both the camera's position offset (relative to
 * the focus body) and orientation, causing the camera to co-rotate with the body's spin. From the camera's perspective,
 * the body surface appears stationary.
 *
 * <p>Implementation note: Picante may return the same {@link RotationMatrixIJK} buffer object across calls. Storing the
 * reference directly as prevRotation would cause dR = R^T × R = I (identity) every frame. The previous-frame rotation
 * is therefore copied element-by-element into a {@code double[3][3]} immediately after each call.
 *
 * <p>Fallback to inertial: if the focused body has no PCK orientation data, the pose is returned unchanged and
 * {@link ApplyResult#fallbackActive()} is {@code true}.
 */
public class BodyFixedFrame {

    private static final Logger logger = LogManager.getLogger();

    /** Result of a single {@link #apply} call. */
    public record ApplyResult(double[] newCamHelioJ2000, Quaternion newOrientation, boolean fallbackActive) {}

    /**
     * Previous-frame J2000→bodyFixed rotation, copied element-by-element to avoid Picante buffer reuse. {@code null} on
     * the first frame or after {@link #reset()}.
     */
    private double[][] prevRotation = null;

    /** NAIF ID for which {@link #prevRotation} was computed; -1 if unknown. */
    private int prevFocusId = -1;

    /**
     * Apply one frame of body-fixed co-rotation.
     *
     * <p>Ephemeris acquired at point-of-use (CLAUDE.md Rule 3).
     *
     * @param cameraHelioJ2000 current camera heliocentric J2000 position in km (length 3; not modified)
     * @param camOrientation current camera orientation quaternion
     * @param focusBodyId NAIF ID of the focused body, or -1 if none
     * @param et current simulation ET
     * @return updated pose and fallback flag
     */
    public ApplyResult apply(double[] cameraHelioJ2000, Quaternion camOrientation, int focusBodyId, double et) {
        // No focus → nothing to co-rotate with
        if (focusBodyId == -1) {
            return fallback(cameraHelioJ2000, camOrientation);
        }

        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();

        // Body has no PCK orientation data
        boolean hasBf;
        try {
            hasBf = eph.hasBodyFixedFrame(focusBodyId);
        } catch (Exception ex) {
            logger.warn("hasBodyFixedFrame({}) threw: {}", focusBodyId, ex.getMessage());
            return fallback(cameraHelioJ2000, camOrientation);
        }
        if (!hasBf) {
            logger.warn("Body {} has no body-fixed frame; falling back to INERTIAL", focusBodyId);
            return fallback(cameraHelioJ2000, camOrientation);
        }

        // Get current rotation
        RotationMatrixIJK rCurr;
        try {
            rCurr = eph.getJ2000ToBodyFixedRotation(focusBodyId, et);
        } catch (Exception ex) {
            logger.warn("getJ2000ToBodyFixedRotation({}, {}) threw: {}", focusBodyId, et, ex.getMessage());
            return fallback(cameraHelioJ2000, camOrientation);
        }
        if (rCurr == null) {
            logger.warn("getJ2000ToBodyFixedRotation({}, {}) returned null; falling back", focusBodyId, et);
            return fallback(cameraHelioJ2000, camOrientation);
        }

        // Focus changed — reset accumulated state
        if (prevFocusId != focusBodyId) {
            prevRotation = null;
            prevFocusId = focusBodyId;
        }

        // First frame for this body — copy rotation and return unchanged pose
        if (prevRotation == null) {
            prevRotation = copyMatrix(rCurr);
            return new ApplyResult(cameraHelioJ2000.clone(), new Quaternion(camOrientation), false);
        }

        // Get focus body heliocentric position at current ET
        VectorIJK focusPos;
        try {
            focusPos = eph.getHeliocentricPositionJ2000(focusBodyId, et);
        } catch (Exception ex) {
            logger.warn("getHeliocentricPositionJ2000({}, {}) threw: {}", focusBodyId, et, ex.getMessage());
            return fallback(cameraHelioJ2000, camOrientation);
        }
        if (focusPos == null) {
            logger.warn("getHeliocentricPositionJ2000({}, {}) returned null; falling back", focusBodyId, et);
            return fallback(cameraHelioJ2000, camOrientation);
        }

        // Camera offset from focus body in J2000 (double precision)
        double ox = cameraHelioJ2000[0] - focusPos.getI();
        double oy = cameraHelioJ2000[1] - focusPos.getJ();
        double oz = cameraHelioJ2000[2] - focusPos.getK();

        // Delta rotation: dR = R_curr^T * R_prev
        // dR[i][j] = sum_k R_curr.get(k,i) * prevRotation[k][j]
        // This maps J2000 offset from body-orientation-at-prevET to body-orientation-at-currET.
        // prevRotation is a defensive copy (double[][]) so Picante buffer reuse cannot corrupt it.
        Matrix3f mat = new Matrix3f();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                float sum = 0f;
                for (int k = 0; k < 3; k++) {
                    sum += (float) (rCurr.get(k, i) * prevRotation[k][j]);
                }
                mat.set(i, j, sum);
            }
        }

        // Convert delta rotation matrix to quaternion
        Quaternion deltaQuat = new Quaternion();
        deltaQuat.fromRotationMatrix(mat);

        // Rotate offset (float precision for rotation delta; tiny angle per frame — acceptable)
        Vector3f rotatedOffset = deltaQuat.mult(new Vector3f((float) ox, (float) oy, (float) oz));

        // Reconstruct camera position in double precision
        double[] newCamPos = new double[] {
            focusPos.getI() + rotatedOffset.x, focusPos.getJ() + rotatedOffset.y, focusPos.getK() + rotatedOffset.z
        };

        // Apply delta to camera orientation and normalise
        Quaternion newOrientation = deltaQuat.mult(camOrientation).normalizeLocal();

        prevRotation = copyMatrix(rCurr);
        return new ApplyResult(newCamPos, newOrientation, false);
    }

    /**
     * Reset accumulated state.
     *
     * <p>Call when leaving {@link CameraFrame#BODY_FIXED} mode so the next entry starts fresh.
     */
    public void reset() {
        prevRotation = null;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private ApplyResult fallback(double[] cameraHelioJ2000, Quaternion camOrientation) {
        prevRotation = null;
        return new ApplyResult(cameraHelioJ2000.clone(), new Quaternion(camOrientation), true);
    }

    /** Copy all 9 elements of a Picante RotationMatrixIJK into a new double[3][3]. */
    private static double[][] copyMatrix(RotationMatrixIJK src) {
        double[][] m = new double[3][3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                m[r][c] = src.get(r, c);
            }
        }
        return m;
    }
}
