package io.github.jchejarla.debugonfailure.autoconfigure;

import io.github.jchejarla.debugonfailure.log4j2.Log4j2Setup;
import io.github.jchejarla.debugonfailure.logback.LogbackSetup;
import org.apache.commons.logging.LogFactory;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Sets up whichever logging backend SLF4J is bound to. Each backend is reached through its own nested
 * class, so the other backend's classes are never loaded and need not be on the class path.
 */
final class LoggingSetup {

    private LoggingSetup() {
    }

    static void apply(List<String> debugPackages) {
        String factory = LoggerFactory.getILoggerFactory().getClass().getName();
        if (factory.startsWith("ch.qos.logback.")) {
            Logback.install(debugPackages);
        } else if (factory.startsWith("org.apache.logging.slf4j.")) {
            Log4j2.install(debugPackages);
        } else {
            LogFactory.getLog("io.github.jchejarla.debugonfailure").warn(
                    "debug-on-failure: logging is using " + factory + ", but only Logback and Log4j2 are supported; "
                            + "no DEBUG lines are collected.");
        }
    }

    private static final class Logback {
        static void install(List<String> debugPackages) {
            LogbackSetup.install(debugPackages);
        }
    }

    private static final class Log4j2 {
        static void install(List<String> debugPackages) {
            Log4j2Setup.install(debugPackages);
        }
    }
}
