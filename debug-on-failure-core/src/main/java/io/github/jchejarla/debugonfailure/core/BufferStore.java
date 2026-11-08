package io.github.jchejarla.debugonfailure.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds buffered events per unit of work (a request, a message, a job item) until it either fails
 * (flush) or completes (close).
 * <p>
 * Events are stored as {@link Runnable}s that know how to write themselves, along with an estimate of
 * their size, so this class does not depend on any logging backend. Each backend adapter decides what
 * "writing an event" means and how large an event is.
 * <p>
 * Memory is bounded twice: each unit of work by its {@link ScopeSettings#maxBytes()}, and everything
 * together by {@link #maxTotalBytes()}.
 * <p>
 * Thread-safe. A unit of work may log from several threads as long as they share the same scope id.
 */
public final class BufferStore {

    private static final long DEFAULT_TOTAL_CEILING = 64L * 1024 * 1024;

    private final long maxTotalBytes;
    private final ConcurrentHashMap<String, ScopeBuffer> buffers = new ConcurrentHashMap<>();
    private final AtomicLong totalBytes = new AtomicLong();

    /** Uses {@link #defaultMaxTotalBytes()}. */
    public BufferStore() {
        this(defaultMaxTotalBytes());
    }

    /**
     * @param maxTotalBytes most memory, roughly, held across everything in flight; new lines are dropped beyond this
     */
    public BufferStore(long maxTotalBytes) {
        if (maxTotalBytes < 1) {
            throw new IllegalArgumentException("maxTotalBytes must be at least 1");
        }
        this.maxTotalBytes = maxTotalBytes;
    }

    /** 5% of the maximum heap, and never more than 64 MB. */
    public static long defaultMaxTotalBytes() {
        return Math.min(DEFAULT_TOTAL_CEILING, Runtime.getRuntime().maxMemory() / 20);
    }

    public long maxTotalBytes() {
        return maxTotalBytes;
    }

    /**
     * Starts buffering for a unit of work. Replaces any buffer already open under the same id.
     *
     * @param id       unique id of this unit of work
     * @param name     what to call it in log messages, for example {@code GET /orders/42}
     * @param settings limits for this kind of work
     */
    public void open(String id, String name, ScopeSettings settings) {
        Objects.requireNonNull(settings, "settings");
        ScopeBuffer previous = buffers.put(id, new ScopeBuffer(name, settings, System.nanoTime()));
        if (previous != null) {
            release(previous);
        }
    }

    /**
     * Holds an event for later. If the unit of work has already been flushed because it failed, the
     * event is written straight away instead.
     *
     * @param sizeBytes estimated memory the event holds on to
     * @return {@code true} if the event was held or written; {@code false} if the id has no open buffer
     * or a limit was reached, and the event was dropped
     */
    public boolean buffer(String id, Runnable replay, long sizeBytes) {
        ScopeBuffer buffer = buffers.get(id);
        if (buffer == null) {
            return false;
        }
        if (buffer.isPassThrough()) {
            replay.run();
            return true;
        }
        if (sizeBytes > buffer.settings.maxBytes()) {
            buffer.markDropped();
            return false;
        }
        if (totalBytes.addAndGet(sizeBytes) > maxTotalBytes) {
            totalBytes.addAndGet(-sizeBytes);
            buffer.markDropped();
            return false;
        }
        long released = buffer.add(replay, sizeBytes);
        if (released < 0) {
            // The buffer was closed meanwhile.
            totalBytes.addAndGet(-sizeBytes);
            return false;
        }
        totalBytes.addAndGet(-released);
        return true;
    }

    /**
     * Writes every event held for the id, oldest first, because the unit of work failed. From then on,
     * events logged for the id are written as they arrive instead of being held: once a unit of work has
     * failed, everything it logs afterwards is wanted.
     *
     * @return the number of events written
     */
    public int flush(String id) {
        ScopeBuffer buffer = buffers.get(id);
        if (buffer == null) {
            return 0;
        }
        return write(buffer, buffer.drain(System.nanoTime(), true));
    }

    /** The name used for the id in WARN lines, or {@code null} if it has no open buffer. */
    public String nameOf(String id) {
        ScopeBuffer buffer = buffers.get(id);
        return buffer == null ? null : buffer.name;
    }

    /**
     * Changes the name used for the id in WARN lines. Call it on the thread doing the work, for example
     * from a Spring MVC interceptor once the request's route is known.
     */
    public void rename(String id, String name) {
        ScopeBuffer buffer = buffers.get(id);
        if (buffer != null) {
            buffer.name = name;
        }
    }

    /** Discards whatever is held for the id and stops buffering for it. */
    public void close(String id) {
        ScopeBuffer buffer = buffers.remove(id);
        if (buffer != null) {
            release(buffer);
        }
    }

    /** Number of events currently held for the id. */
    public int bufferedCount(String id) {
        ScopeBuffer buffer = buffers.get(id);
        return buffer == null ? 0 : buffer.size();
    }

    /** Number of events dropped for the id because a limit was reached. */
    public int droppedCount(String id) {
        ScopeBuffer buffer = buffers.get(id);
        return buffer == null ? 0 : buffer.dropped();
    }

    /** Estimated memory held across everything in flight. */
    public long totalBytes() {
        return totalBytes.get();
    }

    /** Number of units of work with an open buffer. */
    public int openScopes() {
        return buffers.size();
    }

    /**
     * Writes the held events of every unit of work that has run longer than its
     * {@link ScopeSettings#flushAfter() flushAfter}, without waiting for it to end. A unit of work that
     * runs that long is usually stuck or about to time out, and may never get to log an error itself.
     * The buffers stay open and keep holding new events. Called periodically by {@link DebugOnFailure}.
     *
     * @return the number of events written
     */
    public int sweep() {
        return sweep(System.nanoTime());
    }

    int sweep(long nowNanos) {
        int written = 0;
        for (ScopeBuffer buffer : buffers.values()) {
            Drained drained = buffer.drainIfDue(nowNanos);
            if (drained.isEmpty()) {
                continue;
            }
            Notices.warn("{} still running after {}; writing {}",
                    buffer.name, Durations.format(nowNanos - buffer.openedAtNanos), Notices.bufferedLines(drained.count()));
            written += write(buffer, drained);
        }
        return written;
    }

    private int write(ScopeBuffer buffer, Drained drained) {
        totalBytes.addAndGet(-drained.bytes());
        drained.head().forEach(Runnable::run);
        if (drained.omitted() > 0) {
            Notices.warn("{}: debug lines omitted here to stay within its memory limit: {}",
                    buffer.name, drained.omitted());
        }
        drained.tail().forEach(Runnable::run);
        if (drained.skipped() > 0) {
            Notices.warn("{}: debug lines not kept, to stay within memory limits: {}",
                    buffer.name, drained.skipped());
        }
        return drained.count();
    }

    private void release(ScopeBuffer buffer) {
        totalBytes.addAndGet(-buffer.close());
    }

    private record Held(Runnable event, long size) {
    }

    /**
     * @param omitted events dropped from the middle to stay within the unit of work's limit
     * @param skipped events never held, because they alone exceeded the limit or the total limit was reached
     */
    private record Drained(List<Runnable> head, int omitted, List<Runnable> tail, int skipped, long bytes) {

        static final Drained EMPTY = new Drained(List.of(), 0, List.of(), 0, 0);

        int count() {
            return head.size() + tail.size();
        }

        boolean isEmpty() {
            return count() == 0;
        }
    }

    /**
     * Holds the events of one unit of work in two parts. The first tenth of the limit is kept for the
     * earliest events, which usually say what the work was. The rest holds the latest events; when it is
     * full, its oldest events are dropped. So a long unit of work loses its middle, not its start.
     */
    private static final class ScopeBuffer {

        private volatile String name;
        private final ScopeSettings settings;
        private final long openedAtNanos;
        private final long headLimit;
        private final List<Held> head = new ArrayList<>();
        private final ArrayDeque<Held> tail = new ArrayDeque<>();
        private long headBytes;
        private long tailBytes;
        private int omitted;
        private int skipped;
        private int dropped;
        private long lastWrittenNanos;
        private boolean passThrough;
        private boolean closed;

        ScopeBuffer(String name, ScopeSettings settings, long openedAtNanos) {
            this.name = name;
            this.settings = settings;
            this.openedAtNanos = openedAtNanos;
            this.lastWrittenNanos = openedAtNanos;
            this.headLimit = settings.maxBytes() / 10;
        }

        /** @return bytes released by dropping older events to make room, or -1 if the buffer is closed */
        synchronized long add(Runnable event, long size) {
            if (closed) {
                return -1;
            }
            if (omitted == 0 && tail.isEmpty() && headBytes + size <= headLimit) {
                head.add(new Held(event, size));
                headBytes += size;
                return 0;
            }
            tail.addLast(new Held(event, size));
            tailBytes += size;
            long released = 0;
            while (headBytes + tailBytes > settings.maxBytes() && tail.size() > 1) {
                Held oldest = tail.pollFirst();
                tailBytes -= oldest.size();
                released += oldest.size();
                omitted++;
                dropped++;
            }
            return released;
        }

        synchronized boolean isPassThrough() {
            return passThrough;
        }

        synchronized Drained drain(long nowNanos, boolean thenPassThrough) {
            lastWrittenNanos = nowNanos;
            passThrough |= thenPassThrough;
            if (head.isEmpty() && tail.isEmpty()) {
                omitted = 0;
                skipped = 0;
                return Drained.EMPTY;
            }
            Drained drained = new Drained(events(head), omitted, events(tail), skipped, headBytes + tailBytes);
            head.clear();
            tail.clear();
            headBytes = 0;
            tailBytes = 0;
            omitted = 0;
            skipped = 0;
            return drained;
        }

        /** Drains the buffer if it has held events for longer than its flush-after setting. */
        synchronized Drained drainIfDue(long nowNanos) {
            long flushAfter = settings.flushAfter().toNanos();
            if (closed || flushAfter == 0 || size() == 0 || nowNanos - lastWrittenNanos < flushAfter) {
                return Drained.EMPTY;
            }
            return drain(nowNanos, false);
        }

        /** Closes the buffer and returns the bytes it was still holding. */
        synchronized long close() {
            closed = true;
            long held = headBytes + tailBytes;
            head.clear();
            tail.clear();
            headBytes = 0;
            tailBytes = 0;
            return held;
        }

        synchronized void markDropped() {
            skipped++;
            dropped++;
        }

        synchronized int size() {
            return head.size() + tail.size();
        }

        synchronized int dropped() {
            return dropped;
        }

        private static List<Runnable> events(Iterable<Held> held) {
            List<Runnable> events = new ArrayList<>();
            for (Held h : held) {
                events.add(h.event());
            }
            return events;
        }
    }
}
