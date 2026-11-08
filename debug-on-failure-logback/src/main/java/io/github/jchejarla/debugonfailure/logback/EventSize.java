package io.github.jchejarla.debugonfailure.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;

import java.util.Map;

/**
 * Estimates how much memory a held event keeps alive. It is an estimate, not a measurement: it counts
 * the text the event carries (message, string arguments, stack trace, MDC) plus a fixed overhead, which
 * is what dominates in practice. Arguments that are not strings are counted at a small fixed size.
 */
final class EventSize {

    private static final long EVENT_OVERHEAD = 256;
    private static final long FRAME_SIZE = 120;
    private static final int MAX_CAUSE_DEPTH = 16;

    private EventSize() {
    }

    static long estimate(ILoggingEvent event) {
        long size = EVENT_OVERHEAD;
        size += chars(event.getFormattedMessage());
        Object[] arguments = event.getArgumentArray();
        if (arguments != null) {
            for (Object argument : arguments) {
                size += argument(argument);
            }
        }
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            for (Map.Entry<String, String> entry : mdc.entrySet()) {
                size += 32 + chars(entry.getKey()) + chars(entry.getValue());
            }
        }
        size += throwable(event.getThrowableProxy(), 0);
        return size;
    }

    private static long argument(Object argument) {
        if (argument instanceof CharSequence text) {
            return 40 + 2L * text.length();
        }
        if (argument instanceof byte[] bytes) {
            return 16 + bytes.length;
        }
        return 16;
    }

    private static long throwable(IThrowableProxy proxy, int depth) {
        if (proxy == null || depth > MAX_CAUSE_DEPTH) {
            return 0;
        }
        long size = 128 + chars(proxy.getClassName()) + chars(proxy.getMessage());
        if (proxy.getStackTraceElementProxyArray() != null) {
            size += FRAME_SIZE * proxy.getStackTraceElementProxyArray().length;
        }
        size += throwable(proxy.getCause(), depth + 1);
        IThrowableProxy[] suppressed = proxy.getSuppressed();
        if (suppressed != null) {
            for (IThrowableProxy s : suppressed) {
                size += throwable(s, depth + 1);
            }
        }
        return size;
    }

    private static long chars(String text) {
        return text == null ? 0 : 2L * text.length();
    }
}
