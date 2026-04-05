package kepplr.ui;

import java.util.concurrent.ConcurrentLinkedQueue;
import javafx.animation.AnimationTimer;

/**
 * Lightweight FX-thread task queue drained from an {@link AnimationTimer}.
 *
 * <p>This lets non-FX threads enqueue UI work without calling {@code Platform.runLater()}, preserving the project rule
 * that the normal FX boundary lives in {@link SimulationStateFxBridge}.
 */
public final class FxDispatch {

    private static final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private static volatile boolean started = false;

    private FxDispatch() {}

    /** Start draining the queue. Must be called on the JavaFX application thread. Safe to call more than once. */
    public static void start() {
        if (started) {
            return;
        }
        started = true;
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                Runnable task;
                while ((task = queue.poll()) != null) {
                    task.run();
                }
            }
        }.start();
    }

    /** Enqueue a task to run on the JavaFX thread. */
    public static void dispatch(Runnable task) {
        queue.add(task);
    }
}
