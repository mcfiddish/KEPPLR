package kepplr.scripting;

import kepplr.state.SimulationState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Blocking scripting primitive: waits until the active camera transition completes (Step 18).
 *
 * <p>Polls {@link SimulationState#transitionActiveProperty()} at 50 ms intervals until it becomes {@code false}. An
 * initial sleep of one polling interval is applied so that any transition request queued immediately before this call
 * has time to be picked up by the JME render thread.
 *
 * <p>This class exists here so that step 20 (Groovy scripting layer) can expose {@code waitTransition()} without
 * changes to this file. It is not yet wired into the Groovy API.
 *
 * <p><b>Threading:</b> must be called from the Groovy scripting thread (or any non-JME, non-FX thread). Must
 * <em>not</em> be called from the JME render thread or the JavaFX application thread.
 */
public final class WaitTransition {

    private static final Logger logger = LogManager.getLogger();

    /** Polling interval in milliseconds. */
    private static final long POLL_INTERVAL_MS = 50L;

    private final SimulationState state;

    /** @param state simulation state to poll; must not be null */
    public WaitTransition(SimulationState state) {
        this.state = state;
    }

    /**
     * Block until {@link SimulationState#transitionActiveProperty()} is {@code false}.
     *
     * <p>An initial sleep of {@value #POLL_INTERVAL_MS} ms is applied to allow the JME render thread to pick up any
     * transition request that was enqueued immediately before this call. If no transition is active after the first
     * sleep, this method returns immediately.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void waitTransition() throws InterruptedException {
        // Allow at least one JME render frame to run so any queued transition request is processed
        Thread.sleep(POLL_INTERVAL_MS);
        while (state.transitionActiveProperty().get()) {
            Thread.sleep(POLL_INTERVAL_MS);
        }
        logger.debug("waitTransition: transition complete");
    }
}
