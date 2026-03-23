package kepplr.state;

import java.util.List;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import kepplr.camera.CameraFrame;
import kepplr.render.RenderQuality;
import kepplr.render.vector.VectorType;

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

    /** The requested camera frame (§1.5). */
    ReadOnlyObjectProperty<CameraFrame> cameraFrameProperty();

    /**
     * The camera frame actually in use this frame (§1.5).
     *
     * <p>Equals {@link #cameraFrameProperty()} unless {@link CameraFrame#BODY_FIXED} was requested but the focused body
     * has no PCK orientation data, in which case the active frame is {@link CameraFrame#INERTIAL}.
     */
    ReadOnlyObjectProperty<CameraFrame> activeCameraFrameProperty();

    /**
     * {@code true} when {@link CameraFrame#BODY_FIXED} was requested but the focused body has no PCK orientation data,
     * causing automatic fallback to {@link CameraFrame#INERTIAL} (§1.5).
     */
    ReadOnlyBooleanProperty cameraFrameFallbackActiveProperty();

    /**
     * Heliocentric J2000 camera position in km as {@code [x, y, z]}, or {@code {0,0,0}} if not yet set (§1.4).
     *
     * <p>A {@code double[]} is used (not {@code VectorIJK}) to keep the state interface free of Picante types. The
     * array is always length 3.
     */
    ReadOnlyObjectProperty<double[]> cameraPositionJ2000Property();

    /**
     * Camera position in the focused body's body-fixed frame, as {@code [r_km, lat_deg, lon_deg]}.
     *
     * <p>{@code null} when there is no focused body, the body has no PCK orientation data, or the heliocentric position
     * is unavailable. Latitude is geodetic (degrees, −90 to +90); longitude is measured from the body-fixed prime
     * meridian (degrees, −180 to +180). Updated each frame by the JME render thread.
     */
    ReadOnlyObjectProperty<double[]> cameraBodyFixedSphericalProperty();

    // ── Synodic frame overrides (Step 19c) ──

    /**
     * Explicit focus NAIF ID for the synodic frame, or -1 to use {@link #focusedBodyIdProperty()} (Step 19c).
     *
     * <p>Set by {@link kepplr.commands.SimulationCommands#setSynodicFrame(int, int)} to decouple the synodic frame from
     * interaction state. Cleared to -1 by {@link kepplr.commands.SimulationCommands#setCameraFrame}.
     */
    ReadOnlyIntegerProperty synodicFrameFocusIdProperty();

    /**
     * Explicit target NAIF ID for the synodic frame, or -1 to use {@link #targetedBodyIdProperty()} (Step 19c).
     *
     * <p>Set by {@link kepplr.commands.SimulationCommands#setSynodicFrame(int, int)} to decouple the synodic frame from
     * interaction state. Cleared to -1 by {@link kepplr.commands.SimulationCommands#setCameraFrame}.
     */
    ReadOnlyIntegerProperty synodicFrameTargetIdProperty();

    // ── Render quality (§9.4) ──

    /**
     * Current render quality preset (§9.4).
     *
     * <p>Controls shadow fidelity, trail sample density, and star magnitude cutoff. Updated on the JME thread via
     * {@link kepplr.commands.SimulationCommands#setRenderQuality(RenderQuality)}; read each frame by
     * {@link kepplr.render.StarFieldManager} and {@link kepplr.render.trail.TrailManager} on the JME render thread.
     *
     * <p>Default: {@link RenderQuality#HIGH}.
     */
    ReadOnlyObjectProperty<RenderQuality> renderQualityProperty();

    // ── Render state (§7.3, §10.2) ──

    /**
     * Bodies currently visible in the scene, sorted by ascending camera distance (§7.3, §10.2).
     *
     * <p>Populated each frame by {@code BodySceneManager}. Contains only non-culled bodies (apparent radius &ge; 2 px,
     * or spacecraft). Empty list when nothing is visible.
     */
    ReadOnlyObjectProperty<List<BodyInView>> bodiesInViewProperty();

    // ── Transition state (Step 18) ──

    /**
     * {@code true} while a {@code pointAt} or {@code goTo} camera transition is in progress (Step 18).
     *
     * <p>Updated each frame by {@link kepplr.camera.TransitionController} on the JME render thread. Set to
     * {@code false} when no transition is active (including the single frame after completion). Propagated to the
     * JavaFX layer via {@link kepplr.ui.SimulationStateFxBridge}.
     */
    ReadOnlyBooleanProperty transitionActiveProperty();

    /**
     * Progress of the active camera transition as a value in {@code [0.0, 1.0]} (Step 18).
     *
     * <p>{@code 0.0} when no transition is active. Advances monotonically each frame while a transition runs. Set to
     * {@code 1.0} on the frame when the transition completes, then back to {@code 0.0} on the next frame.
     *
     * <p>Updated each frame by {@link kepplr.camera.TransitionController} on the JME render thread.
     */
    ReadOnlyDoubleProperty transitionProgressProperty();

    /**
     * Current camera field of view in degrees.
     *
     * <p>Updated each frame from the JME camera on the JME render thread.
     */
    ReadOnlyDoubleProperty fovDegProperty();

    // ── Overlay state (REDESIGN.md §7.8, §7.9, §7.5, §7.6, Step 19b) ──

    /**
     * Whether the label for the given body is visible (§7.8).
     *
     * <p>Returns a property backed by a per-body map. The property is created lazily on first access, defaulting to
     * {@code false}. Safe to call from any thread.
     *
     * @param naifId NAIF ID of the body
     */
    ReadOnlyBooleanProperty labelVisibleProperty(int naifId);

    /** Whether the HUD time display is visible (§7.9). Default: {@code true}. */
    ReadOnlyBooleanProperty hudTimeVisibleProperty();

    /** Whether the HUD info display is visible (§7.9). Default: {@code true}. */
    ReadOnlyBooleanProperty hudInfoVisibleProperty();

    /**
     * Whether the trail for the given body is visible (§7.5).
     *
     * @param naifId NAIF ID of the body
     */
    ReadOnlyBooleanProperty trailVisibleProperty(int naifId);

    /**
     * Trail duration in simulation seconds for the given body (§7.5).
     *
     * <p>{@code -1.0} means use the default (orbital period or 30 days).
     *
     * @param naifId NAIF ID of the body
     */
    ReadOnlyDoubleProperty trailDurationProperty(int naifId);

    /**
     * Whether the vector overlay of the given type is visible for the given body (§7.6).
     *
     * @param naifId NAIF ID of the body
     * @param type the vector type
     */
    ReadOnlyBooleanProperty vectorVisibleProperty(int naifId, VectorType type);

    /**
     * Whether the instrument frustum overlay is visible for the given instrument NAIF code (Step 22).
     *
     * @param naifCode NAIF code of the instrument (e.g. −98300 for NH_LORRI)
     */
    ReadOnlyBooleanProperty frustumVisibleProperty(int naifCode);

    /**
     * Whether the given body is visible in the scene (Step 28).
     *
     * <p>Default: {@code true}. When set to {@code false}, the body is not rendered (culled before pass 1).
     *
     * @param naifId NAIF ID of the body
     */
    ReadOnlyBooleanProperty bodyVisibleProperty(int naifId);
}
