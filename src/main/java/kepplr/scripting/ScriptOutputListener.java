package kepplr.scripting;

/**
 * Callback for script output and status events.
 *
 * <p>Implementations must be thread-safe — callbacks are invoked from the script thread, not the UI thread.
 */
@FunctionalInterface
public interface ScriptOutputListener {

    /**
     * Called when the script produces output or a status event occurs.
     *
     * @param line a single line of output or status message
     */
    void onOutput(String line);
}
