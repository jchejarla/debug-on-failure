package io.github.jchejarla.debugonfailure.log4j2;

import io.github.jchejarla.debugonfailure.core.DebugOnFailure;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderControl;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.impl.LocationAware;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wraps existing appenders. Events below {@code bufferBelow} (DEBUG and TRACE by default) are held per
 * unit of work (an HTTP request, a message, a job item) instead of being written; everything else
 * passes straight through. When an event at {@code flushAt} or above (ERROR by default) is logged
 * during a unit of work, its held events are written first, then the event itself.
 *
 * <pre>{@code
 * <DebugOnFailure name="DEBUG_ON_FAILURE">
 *     <AppenderRef ref="Console"/>
 * </DebugOnFailure>
 * }</pre>
 *
 * Events logged outside a unit of work below {@code bufferBelow} are dropped, which matches what an
 * INFO-level configuration would have done with them.
 * <p>
 * When created by {@link Log4j2Setup}, only events from the collected packages are held; DEBUG lines from
 * other loggers the user set to DEBUG are written as before.
 * <p>
 * This appender must receive events on the thread that logged them. Asynchronous loggers deliver
 * events later, on another thread, possibly after the unit of work has ended, so they are not supported.
 */
@Plugin(name = "DebugOnFailure", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE, printObject = true)
public final class DebugOnFailureAppender extends AbstractAppender {

    private final Level bufferBelow;
    private final Level flushAt;
    private final Configuration configuration;
    private final AppenderRef[] appenderRefs;
    private final List<AppenderControl> targets = new CopyOnWriteArrayList<>();
    private volatile List<String> heldPackages;
    private volatile boolean targetsNeedLocation;

    private DebugOnFailureAppender(String name, Filter filter, Level bufferBelow, Level flushAt,
                                   Configuration configuration, AppenderRef[] appenderRefs) {
        super(name, filter, null, true, null);
        this.bufferBelow = bufferBelow;
        this.flushAt = flushAt;
        this.configuration = configuration;
        this.appenderRefs = appenderRefs == null ? new AppenderRef[0] : appenderRefs;
    }

    /** Called by Log4j2 for a {@code <DebugOnFailure>} element in the configuration file. */
    @PluginFactory
    public static DebugOnFailureAppender createAppender(
            @PluginAttribute("name") String name,
            @PluginAttribute(value = "bufferBelow", defaultString = "INFO") String bufferBelow,
            @PluginAttribute(value = "flushAt", defaultString = "ERROR") String flushAt,
            @PluginElement("AppenderRef") AppenderRef[] appenderRefs,
            @PluginElement("Filter") Filter filter,
            @PluginConfiguration Configuration configuration) {
        if (name == null) {
            LOGGER.error("No name provided for DebugOnFailure appender");
            return null;
        }
        return new DebugOnFailureAppender(name, filter, Level.toLevel(bufferBelow, Level.INFO),
                Level.toLevel(flushAt, Level.ERROR), configuration, appenderRefs);
    }

    /** Creates a wrapper around appenders that already exist, as {@link Log4j2Setup} does at startup. */
    static DebugOnFailureAppender wrapping(String name, List<AppenderControl> targets) {
        DebugOnFailureAppender appender =
                new DebugOnFailureAppender(name, null, Level.INFO, Level.ERROR, null, null);
        appender.targets.addAll(targets);
        return appender;
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
        if (configuration != null) {
            for (AppenderRef ref : appenderRefs) {
                Appender target = configuration.getAppender(ref.getRef());
                if (target == null) {
                    LOGGER.error("DebugOnFailure appender {} cannot find appender {}", getName(), ref.getRef());
                } else {
                    targets.add(new AppenderControl(target, ref.getLevel(), ref.getFilter()));
                }
            }
        }
        if (targets.isEmpty()) {
            LOGGER.warn("No appenders attached to DebugOnFailure appender {}; events will not be written anywhere.",
                    getName());
        }
        targetsNeedLocation = targets.stream().anyMatch(control -> control.getAppender().getLayout()
                instanceof LocationAware layout && layout.requiresLocation());
        super.start();
    }

    List<Appender> targetAppenders() {
        List<Appender> appenders = new ArrayList<>();
        for (AppenderControl control : targets) {
            appenders.add(control.getAppender());
        }
        return appenders;
    }

    @Override
    public void append(LogEvent event) {
        String scopeId = event.getContextData().getValue(DebugOnFailure.SCOPE_ID_KEY);
        Level level = event.getLevel();

        if (!level.isMoreSpecificThan(bufferBelow) && isHeld(event.getLoggerName())) {
            if (scopeId != null) {
                LogEvent copy = freeze(event);
                DebugOnFailure.store().buffer(scopeId, () -> write(copy), EventSize.estimate(copy));
            }
            return;
        }

        if (scopeId != null && level.isMoreSpecificThan(flushAt)) {
            DebugOnFailure.store().flush(scopeId);
        }
        write(event);
    }

    private boolean isHeld(String loggerName) {
        List<String> packages = heldPackages;
        if (packages == null) {
            return true;
        }
        for (String pkg : packages) {
            if (loggerName != null && (loggerName.equals(pkg) || loggerName.startsWith(pkg + "."))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copies the event and fills in everything Log4j2 would otherwise work out when the event is written:
     * the message text, the thread, and the source location if a layout prints it. A held event is written
     * later, possibly from another thread, when those would come out wrong.
     */
    private LogEvent freeze(LogEvent event) {
        LogEvent copy = event.toImmutable();
        copy.getMessage().getFormattedMessage();
        copy.getThreadName();
        copy.getThreadId();
        copy.getThreadPriority();
        if (targetsNeedLocation && copy.isIncludeLocation()) {
            copy.getSource();
        }
        return copy;
    }

    private void write(LogEvent event) {
        for (AppenderControl target : targets) {
            target.callAppender(event);
        }
    }
}
