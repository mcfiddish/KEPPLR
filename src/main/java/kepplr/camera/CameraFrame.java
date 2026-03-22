package kepplr.camera;

/**
 * Camera reference frames supported by KEPPLR (REDESIGN.md §1.5).
 *
 * <p>The active frame is stored in {@link kepplr.state.SimulationState#cameraFrameProperty()} and changed via
 * {@link kepplr.commands.SimulationCommands#setCameraFrame(CameraFrame)}.
 */
public enum CameraFrame {

    /** Inertial frame — J2000 / ICRF (§1.3). Camera axes do not rotate with any body. */
    INERTIAL,

    /**
     * Focus-body-fixed frame (§1.5).
     *
     * <p>Each frame the camera co-rotates with the focused body's spin so that the body surface appears stationary. If
     * the focused body has no PCK orientation data, the frame falls back to {@link #INERTIAL} and
     * {@link kepplr.state.SimulationState#cameraFrameFallbackActiveProperty()} becomes {@code true}.
     */
    BODY_FIXED,

    /**
     * Synodic frame defined by the focus body and the currently selected body (§5).
     *
     * <ul>
     *   <li>+X = normalized vector from focus body center → selected body center
     *   <li>+Z = J2000 +Z (or Ecliptic J2000 +Z if degenerate, §5.2)
     *   <li>+Y = Z × X (right-handed)
     * </ul>
     */
    SYNODIC
}
