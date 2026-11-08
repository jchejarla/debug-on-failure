package io.github.jchejarla.debugonfailure.autoconfigure;

import io.github.jchejarla.debugonfailure.core.DebugOnFailure;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebugOnFailureAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DebugOnFailureAutoConfiguration.class))
            // Leave the test JVM's logging configuration alone.
            .withPropertyValues("debug-on-failure.logging.auto-setup=false");

    /** Records the scope id seen inside each method, which shows whether it ran as a unit of work. */
    public static class Jobs {

        private volatile String scopeIdInJob;

        // Read through a method: a field read on the proxy would see the proxy's own, empty field.
        public String scopeIdInJob() {
            return scopeIdInJob;
        }

        @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
        public void importOrders() {
            scopeIdInJob = MDC.get(DebugOnFailure.SCOPE_ID_KEY);
        }

        @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
        public void failingJob() {
            throw new IllegalStateException("import failed");
        }
    }

    public static class AsyncWork {

        @Async
        public CompletableFuture<String> scopeIdAndThread() {
            return CompletableFuture.completedFuture(
                    MDC.get(DebugOnFailure.SCOPE_ID_KEY) + "|" + Thread.currentThread().getName());
        }
    }

    public static final class FinalJobs {

        private volatile String scopeIdInJob;

        public String scopeIdInJob() {
            return scopeIdInJob;
        }

        @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
        public void importOrders() {
            scopeIdInJob = MDC.get(DebugOnFailure.SCOPE_ID_KEY);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class JobsConfiguration {
        @Bean
        Jobs jobs() {
            return new Jobs();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class AsyncConfiguration {
        @Bean
        AsyncWork asyncWork() {
            return new AsyncWork();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class JobsWithoutSchedulingConfiguration {
        @Bean
        Jobs jobs() {
            return new Jobs();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class FinalJobsConfiguration {
        @Bean
        FinalJobs finalJobs() {
            return new FinalJobs();
        }
    }

    @Test
    void scheduledMethodRunsAsAUnitOfWork() {
        runner.withUserConfiguration(JobsConfiguration.class).run(context -> {
            Jobs jobs = context.getBean(Jobs.class);
            jobs.importOrders();

            assertThat(jobs.scopeIdInJob()).isNotNull();
            assertThat(MDC.get(DebugOnFailure.SCOPE_ID_KEY)).isNull();
            assertThat(DebugOnFailure.store().openScopes()).isZero();
        });
    }

    @Test
    void failingScheduledMethodStillThrows() {
        runner.withUserConfiguration(JobsConfiguration.class).run(context -> {
            Jobs jobs = context.getBean(Jobs.class);

            assertThatThrownBy(jobs::failingJob).isInstanceOf(IllegalStateException.class);
            assertThat(DebugOnFailure.store().openScopes()).isZero();
        });
    }

    @Test
    void scheduledMethodsAreLeftAloneWhenDisabled() {
        runner.withUserConfiguration(JobsConfiguration.class)
                .withPropertyValues("debug-on-failure.scheduled.enabled=false")
                .run(context -> {
                    Jobs jobs = context.getBean(Jobs.class);
                    jobs.importOrders();

                    assertThat(jobs.scopeIdInJob()).isNull();
                });
    }

    @Test
    void scheduledMethodsAreLeftAloneWithoutEnableScheduling() {
        runner.withUserConfiguration(JobsWithoutSchedulingConfiguration.class).run(context -> {
            Jobs jobs = context.getBean(Jobs.class);
            jobs.importOrders();

            assertThat(jobs.scopeIdInJob()).isNull();
            assertThat(jobs.getClass()).isEqualTo(Jobs.class);
        });
    }

    @Test
    void finalClassesAreLeftAlone() {
        runner.withUserConfiguration(FinalJobsConfiguration.class).run(context -> {
            FinalJobs jobs = context.getBean(FinalJobs.class);
            jobs.importOrders();

            assertThat(jobs.scopeIdInJob()).isNull();
        });
    }

    @Test
    void asyncMethodRunsAsAUnitOfWorkOnTheWorkerThread() {
        runner.withUserConfiguration(AsyncConfiguration.class).run(context -> {
            String caller = Thread.currentThread().getName();

            String[] result = context.getBean(AsyncWork.class).scopeIdAndThread()
                    .get(5, TimeUnit.SECONDS).split("\\|");

            assertThat(result[0]).isNotEqualTo("null");
            assertThat(result[1]).isNotEqualTo(caller);
        });
    }

    @Test
    void taskSettingsComeFromProperties() {
        runner.withPropertyValues("debug-on-failure.task.flush-after=2h", "debug-on-failure.task.max-size=2MB")
                .run(context -> {
                    assertThat(DebugOnFailure.taskSettings().flushAfter()).isEqualTo(Duration.ofHours(2));
                    assertThat(DebugOnFailure.taskSettings().maxBytes()).isEqualTo(2L * 1024 * 1024);
                });
    }

    @Test
    void registersTheRequestFilterInServletApplications() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DebugOnFailureAutoConfiguration.class))
                .withPropertyValues("debug-on-failure.logging.auto-setup=false")
                .run(context -> assertThat(context).hasBean("debugOnFailureFilter")
                        .getBean("debugOnFailureFilter").isInstanceOf(FilterRegistrationBean.class));
    }

    @Test
    void noRequestFilterOutsideWebApplications() {
        runner.run(context -> assertThat(context).doesNotHaveBean("debugOnFailureFilter"));
    }
}
