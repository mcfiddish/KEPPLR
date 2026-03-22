package kepplr.scripting;

import java.io.Writer;

/**
 * A {@link Writer} that splits output on newlines and forwards each complete line to a {@link ScriptOutputListener}.
 *
 * <p>If no listener is set, output is silently discarded.
 */
final class LineForwardingWriter extends Writer {

    private final ScriptOutputListener listener;
    private final StringBuilder buffer = new StringBuilder();

    LineForwardingWriter(ScriptOutputListener listener) {
        this.listener = listener;
    }

    @Override
    public void write(char[] cbuf, int off, int len) {
        if (listener == null) return;
        for (int i = off; i < off + len; i++) {
            char c = cbuf[i];
            if (c == '\n') {
                listener.onOutput(buffer.toString());
                buffer.setLength(0);
            } else if (c != '\r') {
                buffer.append(c);
            }
        }
    }

    @Override
    public void flush() {
        // Flush any partial line
        if (listener != null && buffer.length() > 0) {
            listener.onOutput(buffer.toString());
            buffer.setLength(0);
        }
    }

    @Override
    public void close() {
        flush();
    }
}
