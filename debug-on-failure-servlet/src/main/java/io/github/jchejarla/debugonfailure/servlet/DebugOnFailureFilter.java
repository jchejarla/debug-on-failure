package io.github.jchejarla.debugonfailure.servlet;

import io.github.jchejarla.debugonfailure.core.DebugOnFailure;
import io.github.jchejarla.debugonfailure.core.Scope;
import io.github.jchejarla.debugonfailure.core.ScopeSettings;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Objects;

/**
 * Marks the start and end of each request and decides whether it failed. A request counts as failed
 * when an exception escapes it, when it ends with a 5xx status, or when it takes longer than the slow
 * threshold. Failed requests have their held DEBUG events written; all others have them discarded.
 * <p>
 * Requests are named by method and path, such as {@code GET /orders/42}. When Spring MVC handled the
 * request, the route is used instead, such as {@code GET /orders/{id}}, which keeps ids and other values
 * from the path out of the WARN lines.
 * <p>
 * Asynchronous requests are covered only on the thread that started them in this version.
 */
public class DebugOnFailureFilter implements Filter {

    /** Request attribute where Spring MVC stores the matched route, such as {@code /orders/{id}}. */
    private static final String ROUTE_ATTRIBUTE = "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

    private final ScopeSettings settings;
    private final boolean flushOnServerError;

    /** Uses {@link ScopeSettings#HTTP} and treats 5xx responses as failed. */
    public DebugOnFailureFilter() {
        this(ScopeSettings.HTTP, true);
    }

    /**
     * @param settings           limits, slow threshold and flush-after for requests
     * @param flushOnServerError whether a 5xx response counts as failed
     */
    public DebugOnFailureFilter(ScopeSettings settings, boolean flushOnServerError) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.flushOnServerError = flushOnServerError;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        Scope scope = DebugOnFailure.begin(describe(request), settings);
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            scope.fail("exception " + originalCause(e).getClass().getSimpleName());
            throw e;
        } finally {
            if (flushOnServerError && response instanceof HttpServletResponse http && http.getStatus() >= 500) {
                scope.fail("status " + http.getStatus());
            }
            if (request instanceof HttpServletRequest http && request.getAttribute(ROUTE_ATTRIBUTE) instanceof String route) {
                scope.rename(http.getMethod() + " " + route);
            }
            scope.close();
        }
    }

    /** Spring MVC wraps exceptions from controllers in a ServletException; report the original. */
    private static Throwable originalCause(Throwable e) {
        if (e instanceof ServletException servletException && servletException.getRootCause() != null) {
            return servletException.getRootCause();
        }
        return e;
    }

    private static String describe(ServletRequest request) {
        if (request instanceof HttpServletRequest http) {
            return http.getMethod() + " " + http.getRequestURI();
        }
        return "Request";
    }
}
