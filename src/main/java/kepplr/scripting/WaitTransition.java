package kepplr.scripting;

import kepplr.commands.SimulationCommands;
import kepplr.state.SimulationState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Blocking scripting primitive: waits until the active camera transition completes (Step 18).
 *
 * <p>Waits for one rendered frame so any transition request queued immediately before this call is picked up by the JME
 * render thread, then polls {@link SimulationState#transitionActiveProperty()} until it becomes {@code false}.
 *
 * <p>This class exists here so that step 20 (Groovy scripting layer) can expose {@code waitTransition()} without
 * changes to this file. It is not yet wired into the Groovy API.
 *
 * <p><b>Threading:</b> must be called from the Groovy scripting thread (or any non-JME, non-FX thread). Must
 * <em>not</em> be called from the JME render thread or the JavaFX application thread.
 */
public final class WaitTransition {

    private static final Logger logger = LogManager.getLogger();

    private final SimulationCommands commands;
    private final SimulationState state;

    /**
     * @param commands simulation commands, used for the render-frame fence; must not be null
     * @param state simulation state to poll; must not be null
     */
    public WaitTransition(SimulationCommands commands, SimulationState state) {
        this.commands = commands;
        this.state = state;
    }

    /**
     * Block until {@link SimulationState#transitionActiveProperty()} is {@code false}.
     *
     * <p>An initial render-frame fence is applied to allow the JME render thread to pick up any transition request that
     * was enqueued immediately before this call. If no transition is active after that frame, this method returns
     * immediately.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void waitTransition() throws InterruptedException {
        // Allow at least one rendered frame so queued transition requests are reflected in
        // state.transitionActiveProperty() before we start waiting on it.
        commands.waitRenderFrames(1);
        while (state.transitionActiveProperty().get()) {
            commands.waitRenderFrames(1);
        }
        logger.debug("waitTransition: transition complete");
    }
}
