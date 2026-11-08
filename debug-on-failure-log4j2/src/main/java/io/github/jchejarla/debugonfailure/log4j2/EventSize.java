package io.github.jchejarla.debugonfailure.log4j2;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;

import java.util.Map;

/**
 * Estimates how much memory a held event keeps alive. It is an estimate, not a measurement: it counts
 * the text the event carries (message, string parameters, stack trace, context data) plus a fixed
 * overhead, which is what dominates in practice. Parameters that are not strings are counted at a
 * small fixed size.
 */
final class EventSize {

    private static final long EVENT_OVERHEAD = 256;
    private static final long FRAME_SIZE = 120;
    private static final int MAX_CAUSE_DEPTH = 16;

    private EventSize() {
    }

    static long estimate(LogEvent event) {
        long size = EVENT_OVERHEAD;
        Message message = event.getMessage();
        if (message != null) {
            size += chars(message.getFormattedMessage());
            Object[] parameters = message.getParameters();
            if (parameters != null) {
                for (Object parameter : parameters) {
                    size += parameter(parameter);
                }
            }
        }
        for (Map.Entry<String, String> entry : event.getContextData().toMap().entrySet()) {
            size += 32 + chars(entry.getKey()) + chars(entry.getValue());
        }
        size += throwable(event.getThrown(), 0);
        return size;
    }

    private static long parameter(Object parameter) {
        if (parameter instanceof CharSequence text) {
            return 40 + 2L * text.length();
        }
        if (parameter instanceof byte[] bytes) {
            return 16 + bytes.length;
        }
        return 16;
    }

    private static long throwable(Throwable thrown, int depth) {
        if (thrown == null || depth > MAX_CAUSE_DEPTH) {
            return 0;
        }
        long size = 128 + chars(thrown.getClass().getName()) + chars(thrown.getMessage());
        size += FRAME_SIZE * thrown.getStackTrace().length;
        size += throwable(thrown.getCause(), depth + 1);
        for (Throwable suppressed : thrown.getSuppressed()) {
            size += throwable(suppressed, depth + 1);
        }
        return size;
    }

    private static long chars(String text) {
        return text == null ? 0 : 2L * text.length();
    }
}
