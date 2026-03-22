package kepplr.camera;

import com.jme3.math.Matrix3f;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.VectorIJK;

/**
 * Applies per-frame synodic co-rotation to the camera (REDESIGN.md §5).
 *
 * <p>Each frame, this class computes the incremental rotation delta between the synodic frame basis at the previous and
 * current ET. That delta is applied to both the camera's position offset (relative to the focus body) and orientation,
 * causing the camera to co-rotate with the focus→selected direction. From the camera's perspective, the selected body
 * remains at a fixed screen position.
 *
 * <p>Effective-target rule: if {@code targetId == -1} or {@code targetId == focusId}, NAIF 10 (Sun) is used as the
 * effective synodic target.
 *
 * <p>Implementation note: the synodic basis is stored as a {@code double[3][3]} defensive copy (row = axis index, col =
 * IJK component), parallel to the approach used in {@link BodyFixedFrame} for Picante buffer safety.
 *
 * <p>Fallback to inertial: if {@code focusId == -1} or {@link SynodicFrame#compute} returns {@code null}, the pose is
 * returned unchanged and {@link ApplyResult#fallbackActive()} is {@code true}.
 */
public class SynodicFrameApplier {

    private static final Logger logger = LogManager.getLogger();

    /** Result of a single {@link #apply} call. */
    public record ApplyResult(double[] newCamHelioJ2000, Quaternion newOrientation, boolean fallbackActive) {}

    /**
     * Previous-frame synodic basis, copied element-by-element to avoid Picante buffer reuse. Row = axis index (0=X,
     * 1=Y, 2=Z), col = IJK component. {@code null} on first frame or after {@link #reset()}.
     */
    private double[][] prevBasis = null;

    /** Focus NAIF ID for which {@link #prevBasis} was computed; -1 if unknown. */
    private int prevFocusId = -1;

    /** Effective target NAIF ID for which {@link #prevBasis} was computed; -1 if unknown. */
    private int prevEffectiveTargetId = -1;

    /**
     * Apply one frame of synodic co-rotation.
     *
     * <p>Ephemeris acquired at point-of-use (CLAUDE.md Rule 3).
     *
     * @param cameraHelioJ2000 current camera heliocentric J2000 position in km (length 3; not modified)
     * @param camOrientation current camera orientation quaternion
     * @param focusId NAIF ID of the focused body, or -1 if none
     * @param targetId NAIF ID of the selected body, or -1 if none
     * @param et current simulation ET
     * @return updated pose and fallback flag
     */
    public ApplyResult apply(
            double[] cameraHelioJ2000, Quaternion camOrientation, int focusId, int targetId, double et) {
        if (focusId == -1) {
            return fallback(cameraHelioJ2000, camOrientation);
        }

        int effectiveTargetId = (targetId == -1 || targetId == focusId) ? KepplrConstants.SUN_NAIF_ID : targetId;

        SynodicFrame.Basis basis = SynodicFrame.compute(focusId, effectiveTargetId, et);
        if (basis == null) {
            logger.warn(
                    "SynodicFrame.compute({}, {}, {}) returned null — falling back to INERTIAL",
                    focusId,
                    effectiveTargetId,
                    et);
            return fallback(cameraHelioJ2000, camOrientation);
        }

        double[][] curr = basisToMatrix(basis);

        // Reset on focus or effective-target change
        if (prevFocusId != focusId || prevEffectiveTargetId != effectiveTargetId) {
            prevBasis = null;
            prevFocusId = focusId;
            prevEffectiveTargetId = effectiveTargetId;
        }

        // First frame for this focus/target pair — store basis and return unchanged pose
        if (prevBasis == null) {
            prevBasis = curr;
            return new ApplyResult(cameraHelioJ2000.clone(), new Quaternion(camOrientation), false);
        }

        // Get focus body heliocentric position at current ET
        KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
        VectorIJK focusPos;
        try {
            focusPos = eph.getHeliocentricPositionJ2000(focusId, et);
        } catch (Exception ex) {
            logger.warn("getHeliocentricPositionJ2000({}, {}) threw: {}", focusId, et, ex.getMessage());
            return fallback(cameraHelioJ2000, camOrientation);
        }
        if (focusPos == null) {
            logger.warn("getHeliocentricPositionJ2000({}, {}) returned null — falling back", focusId, et);
            return fallback(cameraHelioJ2000, camOrientation);
        }

        // Camera offset from focus body in J2000 (double precision)
        double ox = cameraHelioJ2000[0] - focusPos.getI();
        double oy = cameraHelioJ2000[1] - focusPos.getJ();
        double oz = cameraHelioJ2000[2] - focusPos.getK();

        // Delta rotation: dR = R_curr^T * R_prev
        // dR[i][j] = sum_k curr[k][i] * prevBasis[k][j]
        // prevBasis is a defensive copy (double[][]) so Picante buffer reuse cannot corrupt it.
        Matrix3f mat = new Matrix3f();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                float sum = 0f;
                for (int k = 0; k < 3; k++) {
                    sum += (float) (curr[k][i] * prevBasis[k][j]);
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

        prevBasis = curr;
        return new ApplyResult(newCamPos, newOrientation, false);
    }

    /**
     * Reset accumulated state.
     *
     * <p>Call when leaving {@link CameraFrame#SYNODIC} mode so the next entry starts fresh.
     */
    public void reset() {
        prevBasis = null;
        prevFocusId = -1;
        prevEffectiveTargetId = -1;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private ApplyResult fallback(double[] cameraHelioJ2000, Quaternion camOrientation) {
        reset();
        return new ApplyResult(cameraHelioJ2000.clone(), new Quaternion(camOrientation), true);
    }

    /**
     * Convert a {@link SynodicFrame.Basis} to a {@code double[3][3]}. Row = axis index (0=X, 1=Y, 2=Z), col = IJK
     * component.
     */
    private static double[][] basisToMatrix(SynodicFrame.Basis b) {
        return new double[][] {
            {b.xAxis().getI(), b.xAxis().getJ(), b.xAxis().getK()},
            {b.yAxis().getI(), b.yAxis().getJ(), b.yAxis().getK()},
            {b.zAxis().getI(), b.zAxis().getJ(), b.zAxis().getK()}
        };
    }
}
