package io.github.jchejarla.debugonfailure.core;

import org.slf4j.MDC;

import java.util.Objects;
import java.util.UUID;

/**
 * One unit of work whose DEBUG events are being held: an HTTP request, a queue message, a job item.
 * Created by {@link DebugOnFailure#begin}. While it is open, DEBUG events logged on this thread are
 * held for it. When it closes, they are written if it failed or was slow, and discarded otherwise.
 * <p>
 * Open and close a scope on the same thread. Scopes can be nested; closing the inner one makes the
 * outer one current again.
 */
public final class Scope implements AutoCloseable {

    private final BufferStore store;
    private final String id = UUID.randomUUID().toString();
    private String name;
    private final ScopeSettings settings;
    private final String outerId;
    private final long startNanos;
    private String failureReason;
    private boolean closed;

    Scope(BufferStore store, String name, ScopeSettings settings) {
        this.store = store;
        this.name = Objects.requireNonNull(name, "name");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.outerId = MDC.get(DebugOnFailure.SCOPE_ID_KEY);
        store.open(id, name, settings);
        MDC.put(DebugOnFailure.SCOPE_ID_KEY, id);
        this.startNanos = System.nanoTime();
    }

    /** The name used in WARN lines, including any change made through the store since the scope began. */
    public String name() {
        String current = store.nameOf(id);
        return current != null ? current : name;
    }

    /**
     * Changes the name used in WARN lines, for example once a request's route is known, so that
     * {@code GET /orders/42} can be reported as {@code GET /orders/{id}}.
     */
    public void rename(String name) {
        this.name = Objects.requireNonNull(name, "name");
        store.rename(id, name);
    }

    /**
     * Marks this unit of work as failed; its held events are written when it closes.
     * If it is marked more than once, the first reason is kept.
     */
    public void fail(String reason) {
        if (failureReason == null) {
            failureReason = Objects.requireNonNull(reason, "reason");
        }
    }

    public boolean isFailed() {
        return failureReason != null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            long elapsedNanos = System.nanoTime() - startNanos;
            String reason = failureReason;
            if (reason == null && settings.isSlow(elapsedNanos)) {
                reason = "slow, " + Durations.format(elapsedNanos);
            }
            if (reason != null) {
                writeHeldEvents(reason);
            }
        } finally {
            store.close(id);
            if (outerId == null) {
                MDC.remove(DebugOnFailure.SCOPE_ID_KEY);
            } else {
                MDC.put(DebugOnFailure.SCOPE_ID_KEY, outerId);
            }
        }
    }

    private void writeHeldEvents(String reason) {
        int held = store.bufferedCount(id);
        if (held == 0) {
            return;
        }
        // Lines dropped to stay within memory limits are reported by the store as it writes.
        Notices.warn("{} failed ({}); writing {}", name(), reason, Notices.bufferedLines(held));
        store.flush(id);
    }
}
