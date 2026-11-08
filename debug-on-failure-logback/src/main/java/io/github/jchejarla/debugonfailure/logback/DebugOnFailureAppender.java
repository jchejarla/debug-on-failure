package io.github.jchejarla.debugonfailure.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.AppenderAttachableImpl;
import io.github.jchejarla.debugonfailure.core.DebugOnFailure;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Wraps existing appenders. Events below {@code bufferBelow} (DEBUG and TRACE by default) are held
 * per unit of work (an HTTP request, a message, a job item) instead of being written; everything else
 * passes straight through. When an event at
 * {@code flushAt} or above (ERROR by default) is logged during a unit of work, its held events
 * are written first, then the event itself.
 *
 * <pre>{@code
 * <appender name="DEBUG_ON_FAILURE" class="io.github.jchejarla.debugonfailure.logback.DebugOnFailureAppender">
 *     <appender-ref ref="CONSOLE"/>
 * </appender>
 * }</pre>
 *
 * Events logged outside a unit of work (no scope id in the MDC) below {@code bufferBelow} are dropped,
 * which matches what an INFO-level configuration would have done with them.
 * <p>
 * If the log pattern prints where a line was logged ({@code %line}, {@code %method}, {@code %file},
 * {@code %class} or {@code %caller}), set {@code includeCallerData} to {@code true}; otherwise held lines
 * show {@code ?} there, because that information is worked out when a line is written. {@link LogbackSetup}
 * sets it automatically when a wrapped appender's pattern needs it.
 */
public class DebugOnFailureAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
        implements AppenderAttachable<ILoggingEvent> {

    private final AppenderAttachableImpl<ILoggingEvent> appenders = new AppenderAttachableImpl<>();
    private Level bufferBelow = Level.INFO;
    private Level flushAt = Level.ERROR;
    private volatile boolean includeCallerData;
    private volatile List<String> heldPackages;

    /** Events below this level are held. Default {@code INFO}. */
    public void setBufferBelow(String level) {
        this.bufferBelow = Level.toLevel(level, Level.INFO);
    }

    /** Events at or above this level write the held events of the current unit of work first. Default {@code ERROR}. */
    public void setFlushAt(String level) {
        this.flushAt = Level.toLevel(level, Level.ERROR);
    }

    /** Whether held events keep where they were logged, for patterns that print it. Default {@code false}. */
    public void setIncludeCallerData(boolean includeCallerData) {
        this.includeCallerData = includeCallerData;
    }

    public boolean isIncludeCallerData() {
        return includeCallerData;
    }

    /**
     * Holds DEBUG lines only from these packages and their sub-packages; DEBUG lines from other loggers
     * are written straight away. {@code null}, the default, holds DEBUG lines from every logger.
     */
    void setHeldPackages(Collection<String> packages) {
        this.heldPackages = packages == null ? null : List.copyOf(packages);
    }

    @Override
    public void start() {
        if (!appenders.iteratorForAppenders().hasNext()) {
            addWarn("No appenders attached to [" + getName() + "]; events will not be written anywhere.");
        }
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        appenders.detachAndStopAllAppenders();
    }

    @Override
    protected void append(ILoggingEvent event) {
        String scopeId = scopeIdOf(event);

        if (!event.getLevel().isGreaterOrEqual(bufferBelow) && isHeld(event.getLoggerName())) {
            if (scopeId != null) {
                // Freeze the message, MDC and thread name now; the event may be written much later.
                if (includeCallerData) {
                    event.getCallerData();
                }
                event.prepareForDeferredProcessing();
                DebugOnFailure.store().buffer(scopeId, () -> appenders.appendLoopOnAppenders(event),
                        EventSize.estimate(event));
            }
            return;
        }

        if (scopeId != null && event.getLevel().isGreaterOrEqual(flushAt)) {
            DebugOnFailure.store().flush(scopeId);
        }
        appenders.appendLoopOnAppenders(event);
    }

    /**
     * Reads the scope id without ever failing: if the MDC cannot be read, the event is treated as outside
     * any unit of work and written as usual, rather than lost along with every other line.
     */
    private String scopeIdOf(ILoggingEvent event) {
        try {
            Map<String, String> mdc = event.getMDCPropertyMap();
            return mdc == null ? null : mdc.get(DebugOnFailure.SCOPE_ID_KEY);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isHeld(String loggerName) {
        List<String> packages = heldPackages;
        if (packages == null) {
            return true;
        }
        for (String pkg : packages) {
            if (loggerName.equals(pkg) || loggerName.startsWith(pkg + ".")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void addAppender(Appender<ILoggingEvent> appender) {
        appenders.addAppender(appender);
    }

    @Override
    public Iterator<Appender<ILoggingEvent>> iteratorForAppenders() {
        return appenders.iteratorForAppenders();
    }

    @Override
    public Appender<ILoggingEvent> getAppender(String name) {
        return appenders.getAppender(name);
    }

    @Override
    public boolean isAttached(Appender<ILoggingEvent> appender) {
        return appenders.isAttached(appender);
    }

    @Override
    public void detachAndStopAllAppenders() {
        appenders.detachAndStopAllAppenders();
    }

    @Override
    public boolean detachAppender(Appender<ILoggingEvent> appender) {
        return appenders.detachAppender(appender);
    }

    @Override
    public boolean detachAppender(String name) {
        return appenders.detachAppender(name);
    }
}
