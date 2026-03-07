package kepplr.render.body;

/**
 * Rendering decision for a body based on apparent size (REDESIGN.md §7.3).
 *
 * <ul>
 *   <li>{@link #DRAW_FULL} — body apparent radius &ge; 2 px; render as full ellipsoid geometry.
 *   <li>{@link #DRAW_SPRITE} — apparent radius &lt; 2 px and body is not a satellite; render as
 *       point sprite.
 *   <li>{@link #CULL} — apparent radius &lt; 2 px and body is a natural satellite; must not be
 *       drawn.
 * </ul>
 */
public enum CullDecision {
    DRAW_FULL,
    DRAW_SPRITE,
    CULL
}
