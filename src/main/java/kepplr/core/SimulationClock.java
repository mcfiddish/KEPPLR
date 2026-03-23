package kepplr.core;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import kepplr.config.KEPPLRConfiguration;
import kepplr.state.DefaultSimulationState;
import kepplr.util.KepplrConstants;

/**
 * Anchor-based simulation time model (REDESIGN.md §1.2, §2.3).
 *
 * <h3>Time formula</h3>
 *
 * <pre>
 *   currentET = anchorET + timeRate × (wallNow − anchorWall)
 * </pre>
 *
 * <p>The {@link TimeAnchor} record holding {@code (anchorET, anchorWall, timeRate)} is stored in an
 * {@link AtomicReference} and replaced atomically on every rate change, {@link #setET}, or resume from pause,
 * guaranteeing that the JME render thread always reads a self-consistent triple.
 *
 * <h3>Pause model</h3>
 *
 * <p>When paused, {@code pausedWallTime} is set to the wall-clock instant at which pause was requested.
 * {@link #wallNow()} returns this clamped value so ET stops advancing. The anchor is not changed during pause; it is
 * replaced atomically on resume using the clamped wall time as the new {@code anchorWall}, so resuming produces no ET
 * jump.
 *
 * <h3>Threading</h3>
 *
 * <ul>
 *   <li>{@link #advance()} — called <em>only</em> from the JME render thread each frame.
 *   <li>{@link #setPaused}, {@link #setTimeRate}, {@link #setET}, {@link #setUTC} — called from the JavaFX thread (via
 *       {@link kepplr.commands.DefaultSimulationCommands}); safe because the anchor {@code AtomicReference} and
 *       {@code volatile pausedWallTime} provide the required memory visibility.
 * </ul>
 */
public final class SimulationClock {

    private final DefaultSimulationState state;
    private final DoubleSupplier wallClock;

    private final AtomicReference<TimeAnchor> anchor;

    /**
     * Wall-clock time (seconds) at the moment pause was requested.
     *
     * <p>{@link Double#NaN} means the simulation is running. A non-NaN value clamps {@link #wallNow()} so ET stops
     * advancing. {@code volatile} ensures the JME thread reads the value written by the FX thread without a data race.
     */
    private volatile double pausedWallTime = Double.NaN;

    /**
     * ET at the end of the previous {@link #advance()} call — used to compute {@code deltaSimSeconds}.
     *
     * <p>Written exclusively by {@link #advance()} on the JME thread; no cross-thread access.
     */
    private double lastET;

    /**
     * Create a clock starting at {@code startET}, using the real system wall clock.
     *
     * <p>The simulation starts unpaused at {@link KepplrConstants#DEFAULT_TIME_RATE}.
     *
     * @param state mutable state object this clock will write to
     * @param startET initial ET (TDB seconds past J2000)
     */
    public SimulationClock(DefaultSimulationState state, double startET) {
        this(state, startET, () -> System.nanoTime() * 1e-9);
    }

    /**
     * Create a clock with an injectable wall-clock supplier — for testing.
     *
     * @param state mutable state object this clock will write to
     * @param startET initial ET
     * @param wallClock supplier returning wall-clock seconds; must be monotonic
     */
    SimulationClock(DefaultSimulationState state, double startET, DoubleSupplier wallClock) {
        this.state = state;
        this.wallClock = wallClock;
        this.lastET = startET;
        double now = wallClock.getAsDouble();
        this.anchor = new AtomicReference<>(new TimeAnchor(startET, now, KepplrConstants.DEFAULT_TIME_RATE));

        // Initialise state to match clock
        state.setCurrentEt(startET);
        state.setTimeRate(KepplrConstants.DEFAULT_TIME_RATE);
        state.setPaused(false);
        state.setDeltaSimSeconds(0.0);
    }

    // ── JME-thread method ──────────────────────────────────────────────────────

    /**
     * Advance the simulation clock by one frame.
     *
     * <p>Reads the current anchor atomically, computes the new ET, and writes {@code currentEt} and
     * {@code deltaSimSeconds} to {@link DefaultSimulationState}. Must be called only from the JME render thread (e.g.,
     * {@code simpleUpdate()}).
     */
    public void advance() {
        TimeAnchor a = anchor.get();
        double wn = wallNow();
        double et = computeET(a, wn);
        double delta = et - lastET;
        lastET = et;
        state.setCurrentEt(et);
        state.setDeltaSimSeconds(delta);
    }

    // ── FX-thread methods ──────────────────────────────────────────────────────

    /**
     * Pause or resume the simulation clock (§1.2).
     *
     * <p><b>Pause:</b> records the wall time at this moment; subsequent {@link #wallNow()} calls return that clamped
     * value so ET stops advancing.
     *
     * <p><b>Resume:</b> replaces the anchor atomically with {@code (etAtPause, pausedWallTime, rate)}, then clears the
     * pause latch. Using the pause wall time — not the resume wall time — as the new {@code anchorWall} ensures that
     * even if {@link #advance()} runs between the anchor update and the volatile write, it still reads the correct
     * frozen ET.
     *
     * @param paused {@code true} to pause, {@code false} to resume
     */
    public void setPaused(boolean paused) {
        if (paused) {
            if (Double.isNaN(pausedWallTime)) {
                pausedWallTime = wallClock.getAsDouble();
            }
        } else {
            double pw = pausedWallTime;
            if (!Double.isNaN(pw)) {
                TimeAnchor old = anchor.get();
                double etAtPause = computeET(old, pw);
                double resumeWall = wallClock.getAsDouble();
                anchor.set(new TimeAnchor(etAtPause, resumeWall, old.timeRate()));
                pausedWallTime = Double.NaN;
            }
        }
        state.setPaused(paused);
    }

    /**
     * Set the simulation time rate as an absolute value (§2.3).
     *
     * <p>Replaces the anchor atomically at the current {@link #wallNow()} moment so that ET is continuous — no jump
     * occurs at the boundary.
     *
     * @param rate simulation seconds per wall-clock second; negative rates are supported
     */
    public void setTimeRate(double rate) {
        double wn = wallNow();
        TimeAnchor old = anchor.get();
        double etNow = computeET(old, wn);
        anchor.set(new TimeAnchor(etNow, wn, rate));
        state.setTimeRate(rate);
    }

    /**
     * Jump the simulation clock to the specified ET.
     *
     * <p>Replaces the anchor atomically. The next {@link #advance()} call will begin advancing from {@code et}.
     *
     * @param et target ET (TDB seconds past J2000)
     */
    public void setET(double et) {
        double wn = wallNow();
        TimeAnchor old = anchor.get();
        anchor.set(new TimeAnchor(et, wn, old.timeRate()));
        // Do NOT call state.setCurrentEt(et) here — this method is called from the script
        // thread, and mutating state mid-frame creates a race with simpleUpdate() on the JME
        // thread: applyFocusTracking() and the synodic frame applier would see different ETs
        // within the same frame, causing the camera offset from the focus body to be computed
        // against the wrong epoch (off by etStep × body velocity).  The atomic anchor update
        // above is sufficient — advance() on the JME thread will read it and set state
        // consistently at the start of the next frame.
    }

    /**
     * Convert a UTC string to ET via {@link picante.time.TimeConversion} and call {@link #setET}.
     *
     * <p>Ephemeris access follows Rule 3 (CLAUDE.md): acquired at point-of-use.
     *
     * @param utcString UTC time string in a format accepted by Picante's {@code utcStringToTDB} (e.g., {@code "2015 Jul
     *     14 07:59:00"})
     */
    public void setUTC(String utcString) {
        double et = KEPPLRConfiguration.getInstance().getTimeConversion().utcStringToTDB(utcString);
        setET(et);
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    /** Returns the wall-clock instant to use for ET computation: clamped when paused. */
    private double wallNow() {
        double pw = pausedWallTime;
        return Double.isNaN(pw) ? wallClock.getAsDouble() : pw;
    }

    private static double computeET(TimeAnchor a, double wallNow) {
        return a.anchorET() + a.timeRate() * (wallNow - a.anchorWall());
    }
}
