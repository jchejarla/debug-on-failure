package io.github.jchejarla.debugonfailure.autoconfigure;

import io.github.jchejarla.debugonfailure.core.DebugOnFailure;
import io.github.jchejarla.debugonfailure.core.Scope;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Treats each call of a {@code @Scheduled} or {@code @Async} method as one unit of work, named after the
 * class and method, for example {@code OrderJobs.importOrders}. It fails when the method throws, or when
 * it returns a {@link CompletableFuture} that has already failed.
 */
final class UnitOfWorkInterceptor implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        String name = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        Scope scope = DebugOnFailure.begin(name, DebugOnFailure.taskSettings());
        try {
            Object result = invocation.proceed();
            if (result instanceof CompletableFuture<?> future && future.isCompletedExceptionally()) {
                scope.fail("returned a failed future");
            }
            return result;
        } catch (Throwable e) {
            scope.fail("exception " + e.getClass().getSimpleName());
            throw e;
        } finally {
            scope.close();
        }
    }
}
