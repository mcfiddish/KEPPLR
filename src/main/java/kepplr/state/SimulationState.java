package kepplr.state;

import java.util.List;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import kepplr.camera.CameraFrame;

/**
 * Single source of truth for all UI-visible simulation state (REDESIGN.md §4, §10).
 *
 * <p>Properties are updated on the <b>JME thread</b>. The JavaFX bridge layer marshals reads to the FX thread via
 * {@code Platform.runLater(...)} at the boundary — never scattered through UI code (CLAUDE.md Rule 2, Rule 4).
 *
 * <p>Body references use NAIF IDs ({@code int}). A value of {@code -1} means "no body" for that slot.
 */
public interface SimulationState {

    // ── Interaction state (§4.2) ──

    /** NAIF ID of the currently selected body, or -1 if none. */
    ReadOnlyIntegerProperty selectedBodyIdProperty();

    /** NAIF ID of the focused body (orbit-camera pivot), or -1 if none. */
    ReadOnlyIntegerProperty focusedBodyIdProperty();

    /** NAIF ID of the targeted body (point-at), or -1 if none. */
    ReadOnlyIntegerProperty targetedBodyIdProperty();

    /** NAIF ID of the tracked body (screen-position lock), or -1 if none. */
    ReadOnlyIntegerProperty trackedBodyIdProperty();

    // ── Time state (§1.2, §2.3) ──

    /** Current simulation epoch as ET (TDB seconds past J2000). */
    ReadOnlyDoubleProperty currentEtProperty();

    /** Simulation seconds per wall-clock second (§2.3). */
    ReadOnlyDoubleProperty timeRateProperty();

    /** Whether the simulation clock is paused (§1.2). */
    ReadOnlyBooleanProperty pausedProperty();

    /**
     * Signed simulation seconds elapsed in the most recent frame (§1.2, §2.3).
     *
     * <p>Positive when time is advancing forward, negative when running in reverse. Zero on the first frame and after
     * an explicit {@code setET()} or {@code setUTC()} jump. Updated each frame by
     * {@link kepplr.core.SimulationClock#advance()} on the JME thread.
     */
    ReadOnlyDoubleProperty deltaSimSecondsProperty();

    // ── Camera state (§1.4, §1.5, §10.2) ──

    /** The active camera frame (§1.5). */
    ReadOnlyObjectProperty<CameraFrame> cameraFrameProperty();

    /**
     * Heliocentric J2000 camera position in km as {@code [x, y, z]}, or {@code {0,0,0}} if not yet set (§1.4).
     *
     * <p>A {@code double[]} is used (not {@code VectorIJK}) to keep the state interface free of Picante types. The
     * array is always length 3.
     */
    ReadOnlyObjectProperty<double[]> cameraPositionJ2000Property();

    // ── Tracking state (§4.6, §10.2) ──

    // ── Render state (§7.3, §10.2) ──

    /**
     * Bodies currently visible in the scene, sorted by ascending camera distance (§7.3, §10.2).
     *
     * <p>Populated each frame by {@code BodySceneManager}. Contains only non-culled bodies
     * (apparent radius &ge; 2 px, or spacecraft). Empty list when nothing is visible.
     */
    ReadOnlyObjectProperty<List<BodyInView>> bodiesInViewProperty();

    /**
     * Normalized screen coordinates {@code [x, y]} of the tracked body's center (§4.6).
     *
     * <p>{@code null} when no body is tracked, or when tracking has started but the JME render loop has not yet
     * computed the first frame position. Non-null means tracking is active with a known anchor.
     *
     * <p>Updated each frame by the JME render thread. Read by the JavaFX status window to display whether a body is
     * being tracked (§10.2).
     */
    ReadOnlyObjectProperty<double[]> trackingAnchorProperty();
}
