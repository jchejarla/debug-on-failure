package io.github.jchejarla.debugonfailure.core;

import java.time.Duration;

final class Durations {

    private Durations() {
    }

    /** Formats an elapsed time for log messages: {@code 850 ms}, {@code 12 s}, {@code 5m 3s}. */
    static String format(long nanos) {
        Duration duration = Duration.ofNanos(nanos);
        if (duration.toSeconds() < 1) {
            return duration.toMillis() + " ms";
        }
        if (duration.toMinutes() < 1) {
            return duration.toSeconds() + " s";
        }
        return duration.toMinutes() + "m " + duration.toSecondsPart() + "s";
    }
}
