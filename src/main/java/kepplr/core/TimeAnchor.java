package kepplr.core;

/**
 * Immutable snapshot of the time model's reference point (REDESIGN.md §1.2).
 *
 * <p>The anchor encodes a consistent triple: the ET value at a known wall-clock moment, together with the time rate in
 * effect. From it, the current ET is always computable as:
 *
 * <pre>
 *   currentET = anchorET + timeRate * (wallNow - anchorWall)
 * </pre>
 *
 * <p>The entire record is replaced atomically via {@link java.util.concurrent.atomic.AtomicReference} whenever the rate
 * changes, ET is set explicitly, or the simulation resumes from pause. No field is ever mutated in place.
 *
 * @param anchorET ET (TDB seconds past J2000) at the anchor wall-clock moment
 * @param anchorWall wall-clock time in seconds at the anchor moment (from {@link System#nanoTime()} / 1e9)
 * @param timeRate simulation seconds per wall-clock second in effect at this anchor (§2.3)
 */
record TimeAnchor(double anchorET, double anchorWall, double timeRate) {}
