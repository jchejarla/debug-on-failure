package io.github.jchejarla.debugonfailure.core;

import java.time.Duration;

/**
 * Settings for one kind of unit of work, such as an HTTP request or a queue message. Different kinds
 * of work fail in different ways, so each gets its own settings: {@link #HTTP} for web requests and
 * {@link #TASK} for jobs, messages and other code run through {@link DebugOnFailure#run}.
 *
 * @param maxBytes      most memory, roughly, that the DEBUG lines of one unit of work may hold. Beyond it,
 *                      lines are dropped from the middle: the first lines (what the work was) and the
 *                      latest lines (what happened last) are kept
 * @param slowThreshold a unit of work that takes longer than this counts as failed; zero disables the check
 * @param flushAfter    a unit of work still running after this long has its held lines written without
 *                      waiting for it to end, and anything it logs later is written at the same interval;
 *                      zero disables the check
 */
public record ScopeSettings(long maxBytes, Duration slowThreshold, Duration flushAfter) {

    public static final long DEFAULT_MAX_BYTES = 1024 * 1024;

    /** Web requests: a request still running after five minutes is almost certainly stuck. */
    public static final ScopeSettings HTTP =
            new ScopeSettings(DEFAULT_MAX_BYTES, Duration.ZERO, Duration.ofMinutes(5));

    /** Jobs and messages: run times vary widely, so a longer flush-after. */
    public static final ScopeSettings TASK =
            new ScopeSettings(DEFAULT_MAX_BYTES, Duration.ZERO, Duration.ofMinutes(30));

    public ScopeSettings {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be at least 1");
        }
        slowThreshold = zeroIfNull(slowThreshold, "slowThreshold");
        flushAfter = zeroIfNull(flushAfter, "flushAfter");
    }

    public ScopeSettings withMaxBytes(long maxBytes) {
        return new ScopeSettings(maxBytes, slowThreshold, flushAfter);
    }

    public ScopeSettings withSlowThreshold(Duration slowThreshold) {
        return new ScopeSettings(maxBytes, slowThreshold, flushAfter);
    }

    public ScopeSettings withFlushAfter(Duration flushAfter) {
        return new ScopeSettings(maxBytes, slowThreshold, flushAfter);
    }

    boolean isSlow(long elapsedNanos) {
        return !slowThreshold.isZero() && elapsedNanos > slowThreshold.toNanos();
    }

    private static Duration zeroIfNull(Duration value, String name) {
        if (value == null) {
            return Duration.ZERO;
        }
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
