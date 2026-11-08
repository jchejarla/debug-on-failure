package io.github.jchejarla.debugonfailure.autoconfigure;

import org.apache.commons.logging.LogFactory;
import org.springframework.aop.Pointcut;
import org.springframework.aop.framework.autoproxy.AbstractBeanFactoryAwareAdvisingPostProcessor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Wraps {@code @Scheduled} and {@code @Async} methods in {@link UnitOfWorkInterceptor}, so each call is
 * covered like an HTTP request, without any change to the application's code.
 * <p>
 * Only when the application has switched the feature on, with {@code @EnableScheduling} or
 * {@code @EnableAsync}. Otherwise those methods are ordinary methods, and their beans are left as they are.
 * <p>
 * Ordering matters in two ways:
 * <ul>
 *   <li>This post-processor runs before Spring's scheduling post-processor, so scheduled tasks are
 *   registered against the proxy and go through the interceptor.</li>
 *   <li>For {@code @Async}, Spring puts its own interceptor in front of all others, so this one runs
 *   after the hand-off, on the thread that does the work.</li>
 * </ul>
 * Classes that are final, or that have non-private final methods, are left alone: the proxy needed to
 * intercept them would either fail to be created, or break calls to those final methods.
 */
final class UnitOfWorkMethodPostProcessor extends AbstractBeanFactoryAwareAdvisingPostProcessor
        implements SmartInitializingSingleton {

    private final boolean scheduled;
    private final boolean async;
    private final Set<String> covered = new ConcurrentSkipListSet<>();
    private final Set<String> skipped = new ConcurrentSkipListSet<>();

    UnitOfWorkMethodPostProcessor(boolean scheduled, boolean async) {
        this.scheduled = scheduled;
        this.async = async;
        setProxyTargetClass(true);
        setBeforeExistingAdvisors(false);
        setOrder(Ordered.LOWEST_PRECEDENCE - 1);
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        super.setBeanFactory(beanFactory);
        Pointcut pointcut = pointcut(
                scheduled && beanFactory.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME),
                async && beanFactory.containsBean(TaskManagementConfigUtils.ASYNC_ANNOTATION_PROCESSOR_BEAN_NAME));
        this.advisor = pointcut == null ? null : new DefaultPointcutAdvisor(pointcut, new UnitOfWorkInterceptor());
    }

    private static Pointcut pointcut(boolean scheduled, boolean async) {
        ComposablePointcut pointcut = null;
        if (scheduled) {
            pointcut = new ComposablePointcut(methodsAnnotatedWith(Scheduled.class))
                    .union(methodsAnnotatedWith(Schedules.class));
        }
        if (async) {
            Pointcut asyncMethods = new ComposablePointcut(methodsAnnotatedWith(Async.class))
                    .union(new AnnotationMatchingPointcut(Async.class, true));
            pointcut = pointcut == null ? new ComposablePointcut(asyncMethods) : pointcut.union(asyncMethods);
        }
        return pointcut;
    }

    /** Also finds the annotation on interface methods and overridden methods, as Spring does. */
    private static Pointcut methodsAnnotatedWith(Class<? extends Annotation> annotation) {
        return new AnnotationMatchingPointcut(null, annotation, true);
    }

    @Override
    protected boolean isEligible(Class<?> targetClass) {
        boolean eligible = super.isEligible(targetClass);
        if (eligible && (Modifier.isFinal(targetClass.getModifiers()) || hasFinalMethods(targetClass))) {
            if (skipped.add(targetClass.getName())) {
                LogFactory.getLog("io.github.jchejarla.debugonfailure").warn("debug-on-failure: not covering the "
                        + "@Scheduled and @Async methods of " + targetClass.getName() + ", because the class or one of "
                        + "its methods is final. Wrap them with DebugOnFailure.run instead.");
            }
            return false;
        }
        if (eligible) {
            covered.add(targetClass.getSimpleName());
        }
        return eligible;
    }

    /** Once all beans exist, says which classes' @Scheduled and @Async methods are covered. */
    @Override
    public void afterSingletonsInstantiated() {
        if (!covered.isEmpty()) {
            LogFactory.getLog("io.github.jchejarla.debugonfailure").info("debug-on-failure: covering the "
                    + "@Scheduled and @Async methods of " + covered);
        }
    }

    private static boolean hasFinalMethods(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                int modifiers = method.getModifiers();
                if (Modifier.isFinal(modifiers) && !Modifier.isPrivate(modifiers) && !Modifier.isStatic(modifiers)
                        && !method.isSynthetic()) {
                    return true;
                }
            }
        }
        return false;
    }
}
