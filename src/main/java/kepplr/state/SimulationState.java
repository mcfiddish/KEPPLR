package kepplr.state;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;

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

    // ── Camera state (§1.4, §1.5, §10.2) ──

    /** Name of the active camera frame (e.g., "J2000", "BODY_FIXED", "SYNODIC"). */
    ReadOnlyObjectProperty<String> cameraFrameProperty();

    /**
     * Heliocentric J2000 camera position in km as {@code [x, y, z]}, or {@code {0,0,0}} if not yet set (§1.4).
     *
     * <p>A {@code double[]} is used (not {@code VectorIJK}) to keep the state interface free of Picante types. The
     * array is always length 3.
     */
    ReadOnlyObjectProperty<double[]> cameraPositionJ2000Property();
}
