package io.github.jchejarla.debugonfailure.log4j2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.AppenderControl;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Sets up Log4j2 without any XML changes, in two steps:
 * <ol>
 *   <li>Wraps the root logger's appenders in a {@link DebugOnFailureAppender}, so DEBUG lines reach the
 *   wrapper, which holds them, instead of being written.</li>
 *   <li>Sets the given packages to DEBUG, so their DEBUG lines are created at all. This happens only after
 *   step 1 succeeded, so DEBUG lines can never reach the original appenders directly.</li>
 * </ol>
 * A package is left as configured when its level was set explicitly, and skipped when it, a parent or a
 * child logger has appenders of its own, because DEBUG lines would reach those directly. Every decision
 * is logged at startup.
 * <p>
 * If Log4j2 later reloads its configuration, for example because {@code monitorInterval} is set, the
 * setup is lost and has to be applied again.
 */
public final class Log4j2Setup {

    private static final Logger log = LogManager.getLogger("io.github.jchejarla.debugonfailure");

    static final String WRAPPER_NAME = "DEBUG_ON_FAILURE";

    private Log4j2Setup() {
    }

    /** Applies the setup to the current Log4j2 context. */
    public static void install(Collection<String> debugPackages) {
        if (LogManager.getContext(false) instanceof LoggerContext context) {
            install(context, debugPackages);
        } else {
            log.warn("debug-on-failure: Log4j2 Core is not in use; no DEBUG lines are collected.");
        }
    }

    /**
     * @param debugPackages packages whose DEBUG lines should be collected, such as the application's base package
     * @return the packages whose DEBUG lines are being collected
     */
    public static List<String> install(LoggerContext context, Collection<String> debugPackages) {
        Configuration configuration = context.getConfiguration();
        warnIfAsynchronous(context, configuration);
        RootWrapper wrapper = wrapRootAppenders(configuration);
        if (wrapper == null) {
            context.updateLoggers();
            return List.of();
        }
        List<String> collected = new ArrayList<>();
        for (String pkg : debugPackages) {
            if (pkg == null || pkg.isBlank()) {
                log.warn("debug-on-failure: the application class is in the default package, so there is no "
                        + "package to collect DEBUG lines from. List your packages in debug-on-failure.logging.debug-packages.");
                continue;
            }
            LoggerConfig explicit = configuration.getLoggers().get(pkg);
            Level configured = explicit == null ? null : explicit.getLevel();
            if (configured != null && configured.isMoreSpecificThan(Level.INFO)) {
                log.warn("debug-on-failure: {} is set to {} in your configuration, so its DEBUG lines are not created "
                        + "and cannot be collected. Remove that setting to collect them.", pkg, configured);
                continue;
            }
            String ownAppenders = loggerWithOwnAppenders(configuration, pkg);
            if (ownAppenders != null) {
                log.warn("debug-on-failure: not collecting DEBUG lines from {}, because logger {} has appenders of its own "
                        + "and would write them directly. Wrap those appenders in a DebugOnFailure appender to collect them.",
                        pkg, ownAppenders);
                continue;
            }
            if (explicit == null) {
                LoggerConfig loggerConfig = new LoggerConfig(pkg, Level.DEBUG, true);
                loggerConfig.setParent(configuration.getLoggerConfig(pkg));
                configuration.addLogger(pkg, loggerConfig);
            }
            collected.add(pkg);
        }
        if (wrapper.createdHere()) {
            // Hold only what was set up here; DEBUG lines from loggers the user set to DEBUG keep being written.
            wrapper.appender().setHeldPackages(collected);
        }
        context.updateLoggers();
        if (collected.isEmpty()) {
            log.warn("debug-on-failure: no DEBUG lines are being collected. "
                    + "List your packages in debug-on-failure.logging.debug-packages.");
        } else {
            log.info("debug-on-failure: collecting DEBUG lines from {}; they are written only when a request or task fails",
                    collected);
        }
        return collected;
    }

    private record RootWrapper(DebugOnFailureAppender appender, boolean createdHere) {
    }

    /**
     * Asynchronous loggers hand events to another thread, possibly after the unit of work has ended, so
     * its DEBUG lines may be lost. Checked by class name, so the optional LMAX Disruptor is never loaded.
     */
    private static void warnIfAsynchronous(LoggerContext context, Configuration configuration) {
        boolean asyncContext = context.getClass().getName().endsWith(".AsyncLoggerContext");
        boolean asyncRoot = configuration.getRootLogger().getClass().getName().contains("AsyncLoggerConfig");
        if (asyncContext || asyncRoot) {
            log.warn("debug-on-failure: Log4j2 asynchronous loggers are in use. They deliver log lines on another "
                    + "thread, possibly after a request has ended, so some DEBUG lines may be lost. "
                    + "Use synchronous loggers, with asynchronous appenders if needed.");
        }
    }

    /** @return the wrapper every root appender now goes through, or {@code null} if there is none */
    private static RootWrapper wrapRootAppenders(Configuration configuration) {
        LoggerConfig root = configuration.getRootLogger();
        List<AppenderControl> plain = new ArrayList<>();
        List<String> plainNames = new ArrayList<>();
        DebugOnFailureAppender existing = null;
        for (Map.Entry<String, Appender> entry : root.getAppenders().entrySet()) {
            Appender appender = entry.getValue();
            if (appender instanceof DebugOnFailureAppender wrapper) {
                existing = wrapper;
                continue;
            }
            AppenderRef ref = findRef(root, entry.getKey());
            plain.add(new AppenderControl(appender,
                    ref == null ? null : ref.getLevel(), ref == null ? null : ref.getFilter()));
            plainNames.add(entry.getKey());
        }
        if (existing != null && !plain.isEmpty()) {
            log.warn("debug-on-failure: the root logger has both a DebugOnFailure appender and other appenders, "
                    + "so DEBUG lines would also be written directly. Leaving it as configured; no DEBUG lines are collected.");
            return null;
        }
        if (existing != null) {
            return new RootWrapper(existing, false);
        }
        if (plain.isEmpty()) {
            log.warn("debug-on-failure: the root logger has no appenders to wrap; no DEBUG lines are collected.");
            return null;
        }

        DebugOnFailureAppender wrapper = DebugOnFailureAppender.wrapping(WRAPPER_NAME, plain);
        wrapper.start();
        configuration.addAppender(wrapper);
        for (String name : plainNames) {
            root.removeAppender(name);
        }
        root.addAppender(wrapper, null, null);
        return new RootWrapper(wrapper, true);
    }

    private static AppenderRef findRef(LoggerConfig logger, String appenderName) {
        for (AppenderRef ref : logger.getAppenderRefs()) {
            if (appenderName.equals(ref.getRef())) {
                return ref;
            }
        }
        return null;
    }

    /** Finds a logger on the path to, at or below the package that writes somewhere other than root. */
    private static String loggerWithOwnAppenders(Configuration configuration, String pkg) {
        for (LoggerConfig logger : configuration.getLoggers().values()) {
            String name = logger.getName();
            if (name == null || name.isEmpty()) {
                continue;
            }
            boolean related = name.equals(pkg) || name.startsWith(pkg + ".") || pkg.startsWith(name + ".");
            if (related && hasPlainAppenders(logger)) {
                return name;
            }
        }
        return null;
    }

    private static boolean hasPlainAppenders(LoggerConfig logger) {
        for (Appender appender : logger.getAppenders().values()) {
            if (!(appender instanceof DebugOnFailureAppender)) {
                return true;
            }
        }
        return false;
    }
}
