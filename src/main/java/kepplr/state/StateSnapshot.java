package kepplr.state;

import kepplr.camera.CameraFrame;

/**
 * Immutable snapshot of the simulation state fields needed for a shareable state string (Step 26).
 *
 * <p>Contains the minimal field set per KEPPLR_Roadmap.md §26: ET, time rate, paused flag, camera position and
 * orientation in heliocentric J2000, camera frame, focused/targeted/selected NAIF IDs, and FOV.
 *
 * @param et current simulation epoch (TDB seconds past J2000)
 * @param timeRate simulation seconds per wall-clock second
 * @param paused whether the simulation is paused
 * @param camPosJ2000 camera position in km [x, y, z]; heliocentric J2000 if {@code camPosRelativeToFocus} is
 *     {@code false}, or body-relative offset from the focused body if {@code true}
 * @param camOrientJ2000 camera orientation quaternion [x, y, z, w]
 * @param cameraFrame active camera frame enum
 * @param focusedBodyId NAIF ID of the focused body, or -1
 * @param targetedBodyId NAIF ID of the targeted body, or -1
 * @param selectedBodyId NAIF ID of the selected body, or -1
 * @param fovDeg camera field of view in degrees
 * @param camPosRelativeToFocus {@code true} if {@code camPosJ2000} is a body-relative offset from the focused body's
 *     heliocentric J2000 position at {@code et}; {@code false} if it is an absolute heliocentric J2000 position
 */
public record StateSnapshot(
        double et,
        double timeRate,
        boolean paused,
        double[] camPosJ2000,
        float[] camOrientJ2000,
        CameraFrame cameraFrame,
        int focusedBodyId,
        int targetedBodyId,
        int selectedBodyId,
        double fovDeg,
        boolean camPosRelativeToFocus) {

    /**
     * Capture a snapshot from the current simulation state.
     *
     * <p>Camera position is stored as absolute heliocentric J2000 ({@code camPosRelativeToFocus = false}). For
     * body-relative encoding (which keeps the camera near the focused body across time), use
     * {@link kepplr.commands.DefaultSimulationCommands#getStateString()}.
     */
    public static StateSnapshot capture(SimulationState state) {
        double[] pos = state.cameraPositionJ2000Property().get();
        float[] orient = state.cameraOrientationJ2000Property().get();
        return new StateSnapshot(
                state.currentEtProperty().get(),
                state.timeRateProperty().get(),
                state.pausedProperty().get(),
                pos != null ? pos.clone() : new double[] {0, 0, 0},
                orient != null ? orient.clone() : new float[] {0, 0, 0, 1},
                state.cameraFrameProperty().get(),
                state.focusedBodyIdProperty().get(),
                state.targetedBodyIdProperty().get(),
                state.selectedBodyIdProperty().get(),
                state.fovDegProperty().get(),
                false);
    }
}
