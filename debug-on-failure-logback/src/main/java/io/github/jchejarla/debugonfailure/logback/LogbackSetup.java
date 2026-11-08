package io.github.jchejarla.debugonfailure.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.pattern.PatternLayoutEncoderBase;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Sets up Logback without any XML changes, in two steps:
 * <ol>
 *   <li>Wraps the root logger's appenders in a {@link DebugOnFailureAppender}, so DEBUG lines reach the
 *   wrapper, which holds them, instead of being written.</li>
 *   <li>Sets the given packages to DEBUG, so their DEBUG lines are created at all. This happens only after
 *   step 1 succeeded, so DEBUG lines can never reach the original appenders directly.</li>
 * </ol>
 * A package is left as configured when its level was set explicitly, and skipped when it, a parent or a
 * child logger has appenders of its own, because DEBUG lines would reach those directly. Every decision
 * is logged at startup.
 */
public final class LogbackSetup {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger("io.github.jchejarla.debugonfailure");

    static final String WRAPPER_NAME = "DEBUG_ON_FAILURE";

    /** Pattern conversion words that print where a line was logged, such as %line or %-20method. */
    private static final Pattern CALLER_DATA = Pattern.compile("%-?\\d*(\\.-?\\d+)?(C|class|F|file|L|line|M|method|caller)(?![A-Za-z])");

    private LogbackSetup() {
    }

    /** Applies the setup to the Logback context SLF4J is bound to, if it is bound to Logback. */
    public static void install(Collection<String> debugPackages) {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            install(context, debugPackages);
        } else {
            log.warn("debug-on-failure: logging is not using Logback, which is the only backend supported in this "
                    + "version; no DEBUG lines are collected.");
        }
    }

    /**
     * @param debugPackages packages whose DEBUG lines should be collected, such as the application's base package
     * @return the packages whose DEBUG lines are being collected
     */
    public static List<String> install(LoggerContext context, Collection<String> debugPackages) {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        RootWrapper wrapper = wrapRootAppenders(context, root);
        if (wrapper == null) {
            return List.of();
        }
        List<String> collected = new ArrayList<>();
        for (String pkg : debugPackages) {
            if (pkg == null || pkg.isBlank()) {
                log.warn("debug-on-failure: the application class is in the default package, so there is no "
                        + "package to collect DEBUG lines from. List your packages in debug-on-failure.logging.debug-packages.");
                continue;
            }
            Logger logger = context.getLogger(pkg);
            Level configured = logger.getLevel();
            if (configured != null && configured.isGreaterOrEqual(Level.INFO)) {
                log.warn("debug-on-failure: {} is set to {} in your configuration, so its DEBUG lines are not created "
                        + "and cannot be collected. Remove that setting to collect them.", pkg, configured);
                continue;
            }
            String ownAppenders = loggerWithOwnAppenders(context, pkg);
            if (ownAppenders != null) {
                log.warn("debug-on-failure: not collecting DEBUG lines from {}, because logger {} has appenders of its own "
                        + "and would write them directly. Wrap those appenders in DebugOnFailureAppender to collect them.",
                        pkg, ownAppenders);
                continue;
            }
            if (configured == null) {
                logger.setLevel(Level.DEBUG);
            }
            collected.add(pkg);
        }
        if (wrapper.createdHere()) {
            // Hold only what was set up here; DEBUG lines from loggers the user set to DEBUG keep being written.
            wrapper.appender().setHeldPackages(collected);
        }
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

    /** @return the wrapper every root appender now goes through, or {@code null} if there is none */
    private static RootWrapper wrapRootAppenders(LoggerContext context, Logger root) {
        List<Appender<ILoggingEvent>> plain = new ArrayList<>();
        DebugOnFailureAppender existing = null;
        for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
            Appender<ILoggingEvent> appender = it.next();
            if (appender instanceof DebugOnFailureAppender wrapper) {
                existing = wrapper;
            } else {
                plain.add(appender);
            }
        }
        if (existing != null && !plain.isEmpty()) {
            log.warn("debug-on-failure: the root logger has both DebugOnFailureAppender and other appenders, "
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

        DebugOnFailureAppender wrapper = new DebugOnFailureAppender();
        wrapper.setContext(context);
        wrapper.setName(WRAPPER_NAME);
        for (Appender<ILoggingEvent> appender : plain) {
            root.detachAppender(appender);
            wrapper.addAppender(appender);
            if (printsCallerData(appender)) {
                wrapper.setIncludeCallerData(true);
            }
        }
        wrapper.start();
        root.addAppender(wrapper);
        return new RootWrapper(wrapper, true);
    }

    private static boolean printsCallerData(Appender<ILoggingEvent> appender) {
        return appender instanceof OutputStreamAppender<ILoggingEvent> output
                && output.getEncoder() instanceof PatternLayoutEncoderBase<?> encoder
                && encoder.getPattern() != null
                && CALLER_DATA.matcher(encoder.getPattern()).find();
    }

    /** Finds a logger on the path to, at or below the package that writes somewhere other than root. */
    private static String loggerWithOwnAppenders(LoggerContext context, String pkg) {
        for (Logger logger : context.getLoggerList()) {
            String name = logger.getName();
            if (Logger.ROOT_LOGGER_NAME.equals(name)) {
                continue;
            }
            boolean related = name.equals(pkg) || name.startsWith(pkg + ".") || pkg.startsWith(name + ".");
            if (related && hasPlainAppenders(logger)) {
                return name;
            }
        }
        return null;
    }

    private static boolean hasPlainAppenders(Logger logger) {
        for (Iterator<Appender<ILoggingEvent>> it = logger.iteratorForAppenders(); it.hasNext(); ) {
            if (!(it.next() instanceof DebugOnFailureAppender)) {
                return true;
            }
        }
        return false;
    }
}
