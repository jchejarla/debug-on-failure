package io.github.jchejarla.debugonfailure.autoconfigure;

import io.github.jchejarla.debugonfailure.core.DebugOnFailure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Names the current request by its route, such as {@code GET /orders/{id}}, as soon as Spring MVC has
 * matched it, so ids and other values in the path stay out of every WARN line, including the one for a
 * request that is still running. Runs on the request's own thread, before the controller.
 */
final class RouteNameInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String scopeId = MDC.get(DebugOnFailure.SCOPE_ID_KEY);
        if (scopeId != null && request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String route) {
            DebugOnFailure.store().rename(scopeId, request.getMethod() + " " + route);
        }
        return true;
    }
}
