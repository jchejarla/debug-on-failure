package io.github.jchejarla.debugonfailure.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Writes the library's own WARN lines, such as the header in front of flushed DEBUG lines. */
final class Notices {

    private static final Logger log = LoggerFactory.getLogger("io.github.jchejarla.debugonfailure");

    private Notices() {
    }

    /** {@code 1 buffered debug line}, {@code 3 buffered debug lines}. */
    static String bufferedLines(int count) {
        return count == 1 ? "1 buffered debug line" : count + " buffered debug lines";
    }

    /**
     * Logs outside the current unit of work, so the line is written straight away and is never
     * held, even when the appender is configured to hold WARN lines too.
     */
    static void warn(String format, Object... arguments) {
        String id = MDC.get(DebugOnFailure.SCOPE_ID_KEY);
        if (id != null) {
            MDC.remove(DebugOnFailure.SCOPE_ID_KEY);
        }
        try {
            log.warn(format, arguments);
        } finally {
            if (id != null) {
                MDC.put(DebugOnFailure.SCOPE_ID_KEY, id);
            }
        }
    }
}
