package io.github.jchejarla.debugonfailure.servlet;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.jchejarla.debugonfailure.core.BufferStore;
import io.github.jchejarla.debugonfailure.core.DebugOnFailure;
import io.github.jchejarla.debugonfailure.core.ScopeSettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebugOnFailureFilterTest {

    private final List<String> written = new ArrayList<>();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void setUp() {
        DebugOnFailure.setMaxTotalBytes(BufferStore.defaultMaxTotalBytes());
    }

    /** Simulates the logging adapter holding a DEBUG line for the current request. */
    private void holdDebugLine(String text) {
        String id = MDC.get(DebugOnFailure.SCOPE_ID_KEY);
        DebugOnFailure.store().buffer(id, () -> written.add(text), 100);
    }

    @Test
    void successfulRequestDiscardsHeldLines() throws Exception {
        FilterChain chain = (req, res) -> holdDebugLine("step");

        new DebugOnFailureFilter().doFilter(request, response, chain);

        assertThat(written).isEmpty();
        assertThat(DebugOnFailure.store().openScopes()).isZero();
    }

    @Test
    void serverErrorWritesHeldLines() throws Exception {
        FilterChain chain = (req, res) -> {
            holdDebugLine("step");
            ((MockHttpServletResponse) res).setStatus(503);
        };

        new DebugOnFailureFilter().doFilter(request, response, chain);

        assertThat(written).containsExactly("step");
    }

    @Test
    void escapingExceptionWritesHeldLinesAndIsRethrown() {
        FilterChain chain = (req, res) -> {
            holdDebugLine("step");
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> new DebugOnFailureFilter().doFilter(request, response, chain))
                .isInstanceOf(IllegalStateException.class);
        assertThat(written).containsExactly("step");
    }

    @Test
    void slowRequestWritesHeldLines() throws Exception {
        FilterChain chain = (req, res) -> {
            holdDebugLine("step");
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        new DebugOnFailureFilter(ScopeSettings.HTTP.withSlowThreshold(Duration.ofMillis(5)), true).doFilter(request, response, chain);

        assertThat(written).containsExactly("step");
    }

    @Test
    void slowRequestIsNotAFailureByDefault() throws Exception {
        FilterChain chain = (req, res) -> {
            holdDebugLine("step");
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        new DebugOnFailureFilter().doFilter(request, response, chain);

        assertThat(written).isEmpty();
    }

    @Test
    void failedRequestIsNamedByItsRouteWhenSpringMvcHandledIt() throws Exception {
        Logger notices = (Logger) LoggerFactory.getLogger("io.github.jchejarla.debugonfailure");
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.setContext(notices.getLoggerContext());
        warnings.start();
        notices.addAppender(warnings);
        try {
            MockHttpServletRequest orderRequest = new MockHttpServletRequest("GET", "/orders/42");
            FilterChain chain = (req, res) -> {
                holdDebugLine("step");
                req.setAttribute("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern", "/orders/{id}");
                ((MockHttpServletResponse) res).setStatus(500);
            };

            new DebugOnFailureFilter().doFilter(orderRequest, response, chain);

            assertThat(warnings.list).singleElement();
            assertThat(warnings.list.get(0).getFormattedMessage()).startsWith("GET /orders/{id} failed (status 500)");
        } finally {
            notices.detachAppender(warnings);
        }
    }

    @Test
    void reportsTheOriginalExceptionWhenSpringMvcWrapsIt() throws Exception {
        Logger notices = (Logger) LoggerFactory.getLogger("io.github.jchejarla.debugonfailure");
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.setContext(notices.getLoggerContext());
        warnings.start();
        notices.addAppender(warnings);
        try {
            FilterChain chain = (req, res) -> {
                holdDebugLine("step");
                throw new ServletException("Request processing failed", new IllegalStateException("declined"));
            };

            assertThatThrownBy(() -> new DebugOnFailureFilter().doFilter(request, response, chain))
                    .isInstanceOf(ServletException.class);

            assertThat(warnings.list).singleElement();
            assertThat(warnings.list.get(0).getFormattedMessage()).contains("(exception IllegalStateException)");
        } finally {
            notices.detachAppender(warnings);
        }
    }

    @Test
    void serverErrorIsIgnoredWhenDisabled() throws Exception {
        FilterChain chain = (req, res) -> {
            holdDebugLine("step");
            ((MockHttpServletResponse) res).setStatus(500);
        };

        new DebugOnFailureFilter(ScopeSettings.HTTP, false).doFilter(request, response, chain);

        assertThat(written).isEmpty();
    }

    @Test
    void scopeIdIsRemovedFromTheMdcAfterTheRequest() throws Exception {
        new DebugOnFailureFilter().doFilter(request, response, (req, res) -> { });

        assertThat(MDC.get(DebugOnFailure.SCOPE_ID_KEY)).isNull();
    }
}
