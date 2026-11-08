package io.github.jchejarla.debugonfailure.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BufferStoreTest {

    private static final ScopeSettings SETTINGS = ScopeSettings.HTTP;
    private static final long SIZE = 100;

    private final List<String> written = Collections.synchronizedList(new ArrayList<>());

    private Runnable event(String text) {
        return () -> written.add(text);
    }

    private static long minutesFromNow(long minutes) {
        return System.nanoTime() + Duration.ofMinutes(minutes).toNanos();
    }

    @Test
    void flushWritesEventsInOrder() {
        BufferStore store = new BufferStore();
        store.open("r1", "r1", SETTINGS);
        store.buffer("r1", event("a"), SIZE);
        store.buffer("r1", event("b"), SIZE);
        store.buffer("r1", event("c"), SIZE);

        assertThat(store.flush("r1")).isEqualTo(3);
        assertThat(written).containsExactly("a", "b", "c");
        assertThat(store.totalBytes()).isZero();
    }

    @Test
    void closeDiscardsEventsWithoutWritingThem() {
        BufferStore store = new BufferStore();
        store.open("r1", "r1", SETTINGS);
        store.buffer("r1", event("a"), SIZE);

        store.close("r1");

        assertThat(written).isEmpty();
        assertThat(store.openScopes()).isZero();
        assertThat(store.totalBytes()).isZero();
    }

    @Test
    void bufferingWithoutAnOpenScopeIsRejected() {
        BufferStore store = new BufferStore();

        assertThat(store.buffer("unknown", event("a"), SIZE)).isFalse();
        assertThat(store.totalBytes()).isZero();
    }

    @Test
    void eventsAfterAFlushAreWrittenStraightAway() {
        BufferStore store = new BufferStore();
        store.open("r1", "r1", SETTINGS);
        store.buffer("r1", event("a"), SIZE);
        store.flush("r1");

        store.buffer("r1", event("b"), SIZE);

        assertThat(written).containsExactly("a", "b");
        assertThat(store.bufferedCount("r1")).isZero();
        assertThat(store.totalBytes()).isZero();
    }

    @Test
    void overTheScopeLimitTheFirstAndLatestEventsAreKept() {
        // 1000 bytes: the first 100 bytes hold the earliest events, the rest the latest ones.
        BufferStore store = new BufferStore();
        store.open("r1", "r1", SETTINGS.withMaxBytes(1_000));
        for (int i = 0; i < 50; i++) {
            store.buffer("r1", event(String.valueOf(i)), 50);
        }

        assertThat(store.bufferedCount("r1")).isEqualTo(20);
        assertThat(store.droppedCount("r1")).isEqualTo(30);
        assertThat(store.totalBytes()).isEqualTo(1_000);

        store.flush("r1");
        assertThat(written).startsWith("0", "1").endsWith("48", "49").hasSize(20);
        assertThat(written.get(2)).isEqualTo("32");
    }

    @Test
    void eventLargerThanTheScopeLimitIsDropped() {
        BufferStore store = new BufferStore();
        store.open("r1", "r1", SETTINGS.withMaxBytes(1_000));
        store.buffer("r1", event("small"), SIZE);

        assertThat(store.buffer("r1", event("huge"), 5_000)).isFalse();

        assertThat(store.droppedCount("r1")).isEqualTo(1);
        store.flush("r1");
        assertThat(written).containsExactly("small");
    }

    @Test
    void eachScopeUsesItsOwnLimit() {
        BufferStore store = new BufferStore();
        store.open("small", "small", SETTINGS.withMaxBytes(200));
        store.open("large", "large", SETTINGS.withMaxBytes(10_000));
        for (int i = 0; i < 5; i++) {
            store.buffer("small", event("s"), SIZE);
            store.buffer("large", event("l"), SIZE);
        }

        assertThat(store.bufferedCount("small")).isEqualTo(2);
        assertThat(store.bufferedCount("large")).isEqualTo(5);
    }

    @Test
    void totalLimitStopsBufferingAcrossScopes() {
        BufferStore store = new BufferStore(2 * SIZE);
        store.open("r1", "r1", SETTINGS);
        store.open("r2", "r2", SETTINGS);

        assertThat(store.buffer("r1", event("a"), SIZE)).isTrue();
        assertThat(store.buffer("r2", event("b"), SIZE)).isTrue();
        assertThat(store.buffer("r2", event("c"), SIZE)).isFalse();
        assertThat(store.droppedCount("r2")).isEqualTo(1);

        store.close("r1");
        assertThat(store.buffer("r2", event("d"), SIZE)).isTrue();
    }

    @Test
    void reopeningAnIdReleasesItsPreviousEvents() {
        BufferStore store = new BufferStore();
        store.open("r1", "r1", SETTINGS);
        store.buffer("r1", event("a"), SIZE);

        store.open("r1", "r1", SETTINGS);

        assertThat(store.totalBytes()).isZero();
        assertThat(store.bufferedCount("r1")).isZero();
    }

    @Test
    void defaultTotalLimitIsAtMost64Megabytes() {
        assertThat(BufferStore.defaultMaxTotalBytes())
                .isPositive()
                .isLessThanOrEqualTo(64L * 1024 * 1024);
    }

    @Test
    void sweepWritesEventsOfScopesRunningLongerThanFlushAfter() {
        BufferStore store = new BufferStore();
        store.open("stuck", "GET /stuck", SETTINGS.withFlushAfter(Duration.ofMinutes(5)));
        store.open("fresh", "GET /fresh", SETTINGS.withFlushAfter(Duration.ofMinutes(10)));
        store.buffer("stuck", event("waiting for inventory"), SIZE);
        store.buffer("fresh", event("loading cart"), SIZE);

        assertThat(store.sweep(minutesFromNow(6))).isEqualTo(1);

        assertThat(written).containsExactly("waiting for inventory");
        // The work has not failed, so later events are held again rather than written straight away.
        assertThat(store.openScopes()).isEqualTo(2);
        store.buffer("stuck", event("retrying"), SIZE);
        assertThat(store.bufferedCount("stuck")).isEqualTo(1);
    }

    @Test
    void sweepWritesLaterEventsOnlyAfterAnotherFlushAfterInterval() {
        BufferStore store = new BufferStore();
        store.open("stuck", "GET /stuck", SETTINGS.withFlushAfter(Duration.ofMinutes(5)));
        store.buffer("stuck", event("first"), SIZE);
        store.sweep(minutesFromNow(6));
        store.buffer("stuck", event("second"), SIZE);

        assertThat(store.sweep(minutesFromNow(7))).isZero();
        assertThat(store.sweep(minutesFromNow(12))).isEqualTo(1);
        assertThat(written).containsExactly("first", "second");
    }

    @Test
    void sweepSkipsScopesWithFlushAfterDisabled() {
        BufferStore store = new BufferStore();
        store.open("job", "nightly import", SETTINGS.withFlushAfter(Duration.ZERO));
        store.buffer("job", event("a"), SIZE);

        assertThat(store.sweep(minutesFromNow(600))).isZero();
        assertThat(written).isEmpty();
    }

    @Test
    void concurrentBufferingKeepsEveryEvent() throws InterruptedException {
        int threads = 8;
        int perThread = 1_000;
        BufferStore store = new BufferStore(Long.MAX_VALUE);
        store.open("r1", "r1", SETTINGS.withMaxBytes(Long.MAX_VALUE));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    store.buffer("r1", event("x"), SIZE);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(store.totalBytes()).isEqualTo(threads * perThread * SIZE);
        assertThat(store.flush("r1")).isEqualTo(threads * perThread);
        assertThat(store.totalBytes()).isZero();
    }
}
