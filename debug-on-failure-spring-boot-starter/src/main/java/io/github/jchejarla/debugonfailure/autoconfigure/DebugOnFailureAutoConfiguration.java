package io.github.jchejarla.debugonfailure.autoconfigure;

import io.github.jchejarla.debugonfailure.core.DebugOnFailure;
import io.github.jchejarla.debugonfailure.servlet.DebugOnFailureFilter;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Applies the {@code debug-on-failure.*} settings, sets up Logback or Log4j2, and marks the start and end
 * of HTTP requests in servlet applications and of {@code @Scheduled} and {@code @Async} methods. With the defaults,
 * adding the dependency is all that is needed.
 */
@AutoConfiguration
@EnableConfigurationProperties(DebugOnFailureProperties.class)
public class DebugOnFailureAutoConfiguration {

    public DebugOnFailureAutoConfiguration(DebugOnFailureProperties properties) {
        if (properties.getMaxTotalSize() != null) {
            DebugOnFailure.setMaxTotalBytes(properties.getMaxTotalSize().toBytes());
        }
        DebugOnFailure.setTaskSettings(properties.getTask().toScopeSettings());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "debug-on-failure.logging", name = "auto-setup", havingValue = "true", matchIfMissing = true)
    static class LoggingConfiguration {

        /** Never lazy: with spring.main.lazy-initialization, nothing would ask for it and setup would not happen. */
        @Bean
        @Lazy(false)
        InitializingBean debugOnFailureLoggingSetup(DebugOnFailureProperties properties, BeanFactory beanFactory) {
            return () -> LoggingSetup.apply(debugPackages(properties, beanFactory));
        }

        private static List<String> debugPackages(DebugOnFailureProperties properties, BeanFactory beanFactory) {
            List<String> configured = properties.getLogging().getDebugPackages();
            if (!configured.isEmpty()) {
                return configured;
            }
            return AutoConfigurationPackages.has(beanFactory) ? AutoConfigurationPackages.get(beanFactory) : List.of();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MethodConfiguration {

        /** Static, as bean post-processors must be, so it is created before ordinary beans. */
        @Bean
        static UnitOfWorkMethodPostProcessor debugOnFailureMethodPostProcessor(Environment environment) {
            return new UnitOfWorkMethodPostProcessor(
                    environment.getProperty("debug-on-failure.scheduled.enabled", Boolean.class, true),
                    environment.getProperty("debug-on-failure.async.enabled", Boolean.class, true));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    @ConditionalOnProperty(prefix = "debug-on-failure.http", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class ServletConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "debugOnFailureFilter")
        FilterRegistrationBean<DebugOnFailureFilter> debugOnFailureFilter(DebugOnFailureProperties properties) {
            DebugOnFailureProperties.Http http = properties.getHttp();
            DebugOnFailureFilter filter = new DebugOnFailureFilter(http.toScopeSettings(), http.isFlushOnServerError());
            FilterRegistrationBean<DebugOnFailureFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setName("debugOnFailureFilter");
            registration.addUrlPatterns("/*");
            // Run early so the scope id is in the MDC before other filters log anything.
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return registration;
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = "org.springframework.web.servlet.config.annotation.WebMvcConfigurer")
        static class SpringMvcConfiguration {

            @Bean
            WebMvcConfigurer debugOnFailureRouteNames() {
                return new WebMvcConfigurer() {
                    @Override
                    public void addInterceptors(InterceptorRegistry registry) {
                        registry.addInterceptor(new RouteNameInterceptor());
                    }
                };
            }
        }
    }
}
