package io.github.jchejarla.debugonfailure.core;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebugOnFailureTest {

    private final List<String> written = new ArrayList<>();

    @BeforeEach
    void setUp() {
        DebugOnFailure.setMaxTotalBytes(BufferStore.defaultMaxTotalBytes());
        DebugOnFailure.setTaskSettings(ScopeSettings.TASK);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    /** Simulates the logging adapter holding a DEBUG line for the current unit of work. */
    private void holdDebugLine(String text) {
        String id = MDC.get(DebugOnFailure.SCOPE_ID_KEY);
        DebugOnFailure.store().buffer(id, () -> written.add(text), 100);
    }

    @Test
    void successfulWorkDiscardsHeldLines() {
        DebugOnFailure.run("order 1", () -> holdDebugLine("step"));

        assertThat(written).isEmpty();
        assertThat(DebugOnFailure.store().openScopes()).isZero();
        assertThat(MDC.get(DebugOnFailure.SCOPE_ID_KEY)).isNull();
    }

    @Test
    void failingWorkWritesHeldLinesAndRethrows() {
        assertThatThrownBy(() -> DebugOnFailure.run("order 1", () -> {
            holdDebugLine("step");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(written).containsExactly("step");
        assertThat(DebugOnFailure.store().openScopes()).isZero();
    }

    @Test
    void callReturnsTheResultAndDiscardsHeldLines() throws Exception {
        String result = DebugOnFailure.call("order 1", () -> {
            holdDebugLine("step");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(written).isEmpty();
    }

    @Test
    void callWritesHeldLinesWhenACheckedExceptionIsThrown() {
        assertThatThrownBy(() -> DebugOnFailure.call("order 1", () -> {
            holdDebugLine("step");
            throw new IOException("disk full");
        })).isInstanceOf(IOException.class);

        assertThat(written).containsExactly("step");
    }

    @Test
    void slowWorkWritesHeldLinesWhenASlowThresholdIsSet() {
        ScopeSettings settings = ScopeSettings.TASK.withSlowThreshold(Duration.ofMillis(5));

        DebugOnFailure.run("order 1", settings, () -> {
            holdDebugLine("step");
            sleep(30);
        });

        assertThat(written).containsExactly("step");
    }

    @Test
    void taskSettingsHaveNoSlowThresholdByDefault() {
        DebugOnFailure.run("order 1", () -> {
            holdDebugLine("step");
            sleep(30);
        });

        assertThat(written).isEmpty();
    }

    @Test
    void explicitFlushWritesHeldLinesOfTheCurrentWork() {
        DebugOnFailure.run("order 1", () -> {
            holdDebugLine("primary provider timed out");
            assertThat(DebugOnFailure.flush()).isEqualTo(1);
        });

        assertThat(written).containsExactly("primary provider timed out");
    }

    @Test
    void linesLoggedAfterAnExplicitFlushAreWrittenStraightAway() {
        DebugOnFailure.run("order 1", () -> {
            holdDebugLine("primary provider timed out");
            DebugOnFailure.flush();
            holdDebugLine("using backup provider");
            assertThat(written).containsExactly("primary provider timed out", "using backup provider");
        });
    }

    @Test
    void flushOutsideAnyWorkDoesNothing() {
        assertThat(DebugOnFailure.flush()).isZero();
    }

    @Test
    void nestedWorkRestoresTheOuterScope() {
        DebugOnFailure.run("batch", () -> {
            String outerId = MDC.get(DebugOnFailure.SCOPE_ID_KEY);
            DebugOnFailure.run("item 1", () -> holdDebugLine("inner"));
            assertThat(MDC.get(DebugOnFailure.SCOPE_ID_KEY)).isEqualTo(outerId);
        });

        assertThat(MDC.get(DebugOnFailure.SCOPE_ID_KEY)).isNull();
    }

    @Test
    void workWithoutANameIsNamedAfterTheMethodThatRanIt() {
        List<String> warnings = captureWarnings(() -> {
            try {
                DebugOnFailure.run(() -> {
                    holdDebugLine("step");
                    throw new IllegalStateException("boom");
                });
            } catch (IllegalStateException expected) {
                // the failure is what makes the WARN line appear
            }
        });

        assertThat(warnings).singleElement();
        assertThat(warnings.get(0)).startsWith("DebugOnFailureTest.workWithoutANameIsNamedAfterTheMethodThatRanIt failed");
    }

    @Test
    void callWithoutANameReturnsTheResult() throws Exception {
        assertThat(DebugOnFailure.call(() -> "done")).isEqualTo("done");
    }

    @Test
    void renamingThroughTheStoreChangesTheNameInTheWarning() {
        List<String> warnings = captureWarnings(() -> {
            try (Scope scope = DebugOnFailure.begin("GET /orders/42", ScopeSettings.HTTP)) {
                holdDebugLine("step");
                DebugOnFailure.store().rename(MDC.get(DebugOnFailure.SCOPE_ID_KEY), "GET /orders/{id}");
                scope.fail("status 500");
            }
        });

        assertThat(warnings).singleElement();
        assertThat(warnings.get(0)).startsWith("GET /orders/{id} failed (status 500)");
    }

    private static List<String> captureWarnings(Runnable action) {
        Logger notices = (Logger) LoggerFactory.getLogger("io.github.jchejarla.debugonfailure");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(notices.getLoggerContext());
        appender.start();
        notices.addAppender(appender);
        try {
            action.run();
        } finally {
            notices.detachAppender(appender);
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void scopeKeepsTheFirstFailureReason() {
        try (Scope scope = DebugOnFailure.begin("GET /orders", ScopeSettings.HTTP)) {
            holdDebugLine("step");
            scope.fail("exception IllegalStateException");
            scope.fail("status 500");
            assertThat(scope.isFailed()).isTrue();
        }

        assertThat(written).containsExactly("step");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
