package kepplr.render.vector;

import com.jme3.math.ColorRGBA;

/**
 * Immutable data object describing a single active vector overlay.
 *
 * <p>A {@code VectorDefinition} binds a human-readable label, a {@link VectorType} strategy, an origin body, a color,
 * and a physical scale. It contains no rendering logic; {@link VectorRenderer} consumes it.
 *
 * <h3>Identity semantics</h3>
 *
 * <p>This class intentionally does <em>not</em> override {@code equals} or {@code hashCode}. {@link VectorManager} uses
 * reference-equality (via {@code IdentityHashMap}) to distinguish vector definitions, so two separate
 * {@code VectorDefinition} objects with identical field values are treated as distinct overlays. Callers must pass the
 * <em>same instance</em> to {@link VectorManager#disableVector} that they passed to {@link VectorManager#enableVector}.
 */
public final class VectorDefinition {

    private final String label;
    private final VectorType vectorType;
    private final int originNaifId;
    private final ColorRGBA color;
    private final double scaleFactor;

    /**
     * @param label human-readable name shown in diagnostics and future UI
     * @param vectorType strategy that computes the direction of this vector
     * @param originNaifId NAIF integer ID of the body at whose centre the vector originates
     * @param color RGBA color for the rendered line
     * @param scaleFactor dimensionless multiplier applied on top of the focus-body-radius base length; {@code 1.0}
     *     produces an arrow tip at {@link kepplr.util.KepplrConstants#VECTOR_ARROW_FOCUS_BODY_RADIUS_MULTIPLE} ×
     *     focused-body mean radius from the body centre
     */
    public VectorDefinition(
            String label, VectorType vectorType, int originNaifId, ColorRGBA color, double scaleFactor) {
        this.label = label;
        this.vectorType = vectorType;
        this.originNaifId = originNaifId;
        this.color = color;
        this.scaleFactor = scaleFactor;
    }

    /** @return human-readable label */
    public String getLabel() {
        return label;
    }

    /** @return strategy used to compute the direction of this vector */
    public VectorType getVectorType() {
        return vectorType;
    }

    /** @return NAIF ID of the body at whose centre this vector originates */
    public int getOriginNaifId() {
        return originNaifId;
    }

    /** @return RGBA color for the rendered line */
    public ColorRGBA getColor() {
        return color;
    }

    /**
     * @return dimensionless relative multiplier; the actual arrow length in km is focused-body mean radius ×
     *     {@link kepplr.util.KepplrConstants#VECTOR_ARROW_FOCUS_BODY_RADIUS_MULTIPLE} × this value
     */
    public double getScaleFactor() {
        return scaleFactor;
    }
}
