package io.github.jchejarla.debugonfailure.autoconfigure;

import io.github.jchejarla.debugonfailure.core.DebugOnFailure;
import io.github.jchejarla.debugonfailure.core.Scope;
import io.github.jchejarla.debugonfailure.core.ScopeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class RouteNameInterceptorTest {

    private final RouteNameInterceptor interceptor = new RouteNameInterceptor();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void namesTheRequestByItsRouteWhileItRuns() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/42");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");

        try (Scope scope = DebugOnFailure.begin("GET /orders/42", ScopeSettings.HTTP)) {
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();

            assertThat(scope.name()).isEqualTo("GET /orders/{id}");
        }
    }

    @Test
    void doesNothingOutsideARequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/42");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }
}
