package kepplr.render.vector;

import picante.math.vectorspace.VectorIJK;

/**
 * Strategy interface for computing the direction of a vector overlay in J2000 coordinates.
 *
 * <p>Each implementation encapsulates one kind of instantaneous vector (velocity, body-fixed axis, direction toward
 * another body, etc.). Adding a new vector type requires only a new implementation of this interface plus a factory
 * method in {@link VectorTypes}; no changes to {@link VectorRenderer} or {@link VectorManager} are needed.
 *
 * <h3>Threading</h3>
 *
 * <p>Implementations must be callable on the JME render thread only. Ephemeris access must always go through
 * {@code KEPPLRConfiguration.getInstance().getEphemeris()} at point-of-use (REDESIGN.md §3.3).
 *
 * <h3>Return contract</h3>
 *
 * <ul>
 *   <li>The returned vector is a <b>unit vector</b> in J2000.
 *   <li>Returns {@code null} if the direction cannot be computed (no ephemeris coverage, degenerate state, etc.).
 *   <li>Never throws; all failure modes must return {@code null} after logging a warning.
 * </ul>
 */
public interface VectorType {

    /**
     * Compute the unit direction vector for this vector type at the given time.
     *
     * @param originNaifId NAIF integer ID of the body at whose centre the vector originates
     * @param et ephemeris time (TDB seconds past J2000)
     * @return unit direction vector in J2000, or {@code null} if unavailable
     */
    VectorIJK computeDirection(int originNaifId, double et);

    /**
     * Return a Groovy expression that recreates this {@code VectorType} instance via the {@link VectorTypes} factory.
     *
     * <p>Used by {@link kepplr.scripting.CommandRecorder} to serialize {@code setVectorVisible} calls into runnable
     * Groovy scripts. Each built-in implementation returns the exact factory call that would produce an equivalent
     * instance.
     *
     * <p>Examples:
     *
     * <ul>
     *   <li>{@code VectorTypes.velocity().toScript()} → {@code "VectorTypes.velocity()"}
     *   <li>{@code VectorTypes.bodyAxisX().toScript()} → {@code "VectorTypes.bodyAxisX()"}
     *   <li>{@code VectorTypes.towardBody(10).toScript()} → {@code "VectorTypes.towardBody(10)"}
     * </ul>
     *
     * @return Groovy expression string; never null
     */
    String toScript();
}
