package kepplr.camera;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import kepplr.util.KepplrConstants;

/**
 * Pure-math camera navigation operations: rotate in place, orbit, and zoom.
 *
 * <p>All methods are static and stateless. Positions are {@code double[3]} in heliocentric J2000 km (§1.4). Orientation
 * is a JME {@link Quaternion} (float precision is sufficient for rotations). JME math classes work headlessly, so this
 * class is unit-testable without a GL context.
 *
 * <p>All orbit and zoom methods treat a {@code null} focus position as a silent no-op, returning the original state
 * unchanged.
 */
public final class CameraNavigator {

    private CameraNavigator() {}

    /**
     * Result of an {@link #orbit} operation.
     *
     * @param position new heliocentric J2000 camera position in km
     * @param orientation new camera orientation
     */
    public record OrbitResult(double[] position, Quaternion orientation) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Rotate in place
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Rotate the camera in place (change orientation without moving position).
     *
     * <p>Horizontal delta rotates around the camera's current screen-up axis (yaw). Vertical delta rotates around the
     * camera's current screen-right axis (pitch). Both angles are in radians.
     *
     * @param orientation current camera orientation
     * @param screenRight the camera's current right vector in world space (J2000)
     * @param screenUp the camera's current up vector in world space (J2000)
     * @param deltaRight radians to rotate around the screen-right axis (positive = tilt up)
     * @param deltaUp radians to rotate around the screen-up axis (positive = yaw right)
     * @return new orientation; never null
     */
    public static Quaternion rotateInPlace(
            Quaternion orientation, Vector3f screenRight, Vector3f screenUp, float deltaRight, float deltaUp) {
        Quaternion qRight = new Quaternion().fromAngleNormalAxis(deltaRight, screenRight);
        Quaternion qUp = new Quaternion().fromAngleNormalAxis(deltaUp, screenUp);
        // Apply yaw first, then pitch (order matches Celestia-style navigation)
        return qUp.mult(qRight).mult(orientation).normalizeLocal();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Orbit
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Orbit the camera around a focus body, keeping constant distance.
     *
     * <p>Horizontal delta orbits around the camera's current screen-up axis. Vertical delta orbits around the camera's
     * current screen-right axis. Both angles are in radians. This means orbit axes track the camera's current
     * orientation — after rotating, the orbit axes reflect the new orientation (Celestia-style tumbling).
     *
     * <p>If {@code focusPosJ2000} is {@code null}, returns an {@link OrbitResult} with the original position and
     * orientation unchanged (silent no-op per §4.5).
     *
     * @param camPosJ2000 current camera heliocentric J2000 position in km (length-3 array)
     * @param orientation current camera orientation
     * @param focusPosJ2000 focus body heliocentric J2000 position in km, or {@code null} for no-op
     * @param screenRight the camera's current right vector in world space (J2000)
     * @param screenUp the camera's current up vector in world space (J2000)
     * @param deltaRight radians to orbit around the screen-right axis (positive = orbit upward)
     * @param deltaUp radians to orbit around the screen-up axis (positive = orbit rightward)
     * @return {@link OrbitResult} with updated position and orientation; never null
     */
    public static OrbitResult orbit(
            double[] camPosJ2000,
            Quaternion orientation,
            double[] focusPosJ2000,
            Vector3f screenRight,
            Vector3f screenUp,
            float deltaRight,
            float deltaUp) {
        if (focusPosJ2000 == null) {
            return new OrbitResult(camPosJ2000.clone(), orientation);
        }

        // Build combined rotation from camera-relative axes
        Quaternion qRight = new Quaternion().fromAngleNormalAxis(deltaRight, screenRight);
        Quaternion qUp = new Quaternion().fromAngleNormalAxis(deltaUp, screenUp);
        Quaternion combined = qUp.mult(qRight);

        // Rotate the focus-to-camera vector (double precision for km-scale distances)
        double[] focusToCamera = new double[] {
            camPosJ2000[0] - focusPosJ2000[0], camPosJ2000[1] - focusPosJ2000[1], camPosJ2000[2] - focusPosJ2000[2]
        };
        Vector3f ftcFloat = new Vector3f((float) focusToCamera[0], (float) focusToCamera[1], (float) focusToCamera[2]);
        Vector3f rotated = combined.mult(ftcFloat);

        // Preserve the exact original distance to avoid float-rounding drift
        double origDist = Math.sqrt(focusToCamera[0] * focusToCamera[0]
                + focusToCamera[1] * focusToCamera[1]
                + focusToCamera[2] * focusToCamera[2]);
        double rotatedLen = rotated.length();
        double scale = (rotatedLen > 0.0) ? origDist / rotatedLen : 1.0;

        double[] newCamPos = new double[] {
            focusPosJ2000[0] + rotated.x * scale,
            focusPosJ2000[1] + rotated.y * scale,
            focusPosJ2000[2] + rotated.z * scale
        };

        // Apply the same rotation to the camera's orientation so it stays pointed at focus
        Quaternion newOrientation = combined.mult(orientation).normalizeLocal();

        return new OrbitResult(newCamPos, newOrientation);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Zoom
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Zoom the camera toward or away from the focus body.
     *
     * <p>Distance changes exponentially: {@code newDist = currentDist × zoomFactor^steps}. A negative {@code steps}
     * zooms in; positive zooms out. The resulting distance is clamped to {@code [minDistKm, maxDistKm]}.
     *
     * <p>If {@code focusPosJ2000} is {@code null}, returns the original position unchanged (silent no-op).
     *
     * @param camPosJ2000 current camera heliocentric J2000 position in km (length-3 array)
     * @param focusPosJ2000 focus body heliocentric J2000 position in km, or {@code null} for no-op
     * @param steps zoom steps (may be fractional); positive = apply factor (zoom in when factor&lt;1), negative = zoom
     *     out
     * @param minDistKm minimum allowed camera distance from focus in km (≥ 0)
     * @param maxDistKm maximum allowed camera distance from focus in km
     * @param zoomFactor fractional factor per step (e.g. {@link KepplrConstants#CAMERA_ZOOM_FACTOR_PER_STEP})
     * @return new heliocentric J2000 camera position (never null); same array contents as input if no-op
     */
    public static double[] zoom(
            double[] camPosJ2000,
            double[] focusPosJ2000,
            double steps,
            double minDistKm,
            double maxDistKm,
            double zoomFactor) {
        // steps is double to support fractional rates from continuous keyboard hold
        if (focusPosJ2000 == null) {
            return camPosJ2000.clone();
        }

        double dx = camPosJ2000[0] - focusPosJ2000[0];
        double dy = camPosJ2000[1] - focusPosJ2000[1];
        double dz = camPosJ2000[2] - focusPosJ2000[2];
        double currentDist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (currentDist <= 0.0) {
            // Camera is at focus center — move to minDistKm along +Z
            return new double[] {focusPosJ2000[0], focusPosJ2000[1], focusPosJ2000[2] + minDistKm};
        }

        double newDist = currentDist * Math.pow(zoomFactor, steps);
        newDist = Math.max(minDistKm, Math.min(maxDistKm, newDist));

        double scale = newDist / currentDist;
        return new double[] {focusPosJ2000[0] + dx * scale, focusPosJ2000[1] + dy * scale, focusPosJ2000[2] + dz * scale
        };
    }
}
