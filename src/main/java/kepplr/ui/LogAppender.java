package kepplr.ui;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import kepplr.config.KEPPLRConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * A log4j2 appender that buffers layout-formatted log lines (including ANSI color codes from {@code %highlight}) in a
 * {@link ConcurrentLinkedQueue} for consumption by the JavaFX log window.
 *
 * <p>The queue is polled by {@link LogWindow} on the FX thread via the existing {@code AnimationTimer} drain pattern,
 * avoiding {@code Platform.runLater()} per CLAUDE.md Rule 2.
 *
 * <p>Registration follows the same programmatic pattern used by {@link kepplr.util.Log4j2Configurator#addFile}.
 */
final class LogAppender extends AbstractAppender {

    private static final String APPENDER_NAME = "KepplrLogWindow";

    private static final ConcurrentLinkedQueue<String> QUEUE = new ConcurrentLinkedQueue<>();

    private LogAppender(String name, Filter filter, Layout<? extends Serializable> layout) {
        super(name, filter, layout, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
        // Layout formats the event using the %highlight pattern, producing ANSI escape sequences
        QUEUE.add(getLayout().toSerializable(event).toString());
    }

    /** Returns the shared queue. Polled by {@link LogWindow} on the FX thread. */
    static ConcurrentLinkedQueue<String> queue() {
        return QUEUE;
    }

    /**
     * Install this appender on all loggers via the log4j2 {@link Configuration}, following the same registration
     * pattern as {@link kepplr.util.Log4j2Configurator#addFile}.
     */
    static void install() {
        LoggerContext loggerContext = LoggerContext.getContext(false);
        Configuration config = loggerContext.getConfiguration();

        String pattern = KEPPLRConfiguration.getInstance().logFormat();
        PatternLayout layout = PatternLayout.newBuilder()
                .withConfiguration(config)
                .withPattern(pattern)
                .withDisableAnsi(false)
                .build();

        LogAppender appender = new LogAppender(APPENDER_NAME, null, layout);
        appender.start();

        Map<String, LoggerConfig> loggerMap = config.getLoggers();
        LoggerConfig rootConfig =
                config.getLoggerConfig(LogManager.getRootLogger().getName());
        rootConfig.addAppender(appender, null, null);
        for (LoggerConfig loggerConfig : loggerMap.values()) {
            loggerConfig.addAppender(appender, null, null);
        }
        loggerContext.updateLoggers();
    }
}
