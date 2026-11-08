package io.github.jchejarla.debugonfailure.autoconfigure;

import io.github.jchejarla.debugonfailure.core.ScopeSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings under {@code debug-on-failure.*}. Every setting has a recommended default, so none are
 * required. HTTP requests and other work (jobs, messages) are configured separately because they fail
 * in different ways.
 */
@ConfigurationProperties(prefix = "debug-on-failure")
public class DebugOnFailureProperties {

    /**
     * Most memory held DEBUG lines may take across everything in flight; new lines are dropped beyond it.
     * Defaults to 5% of the maximum heap, and never more than 64MB.
     */
    private DataSize maxTotalSize;

    /** How Logback is set up. */
    private final Logging logging = new Logging();

    /** Settings for HTTP requests. */
    private final Http http = new Http();

    /** Settings for @Scheduled methods, @Async methods and work wrapped with DebugOnFailure.run or call. */
    private final Task task = new Task();

    /** Covering @Scheduled methods. */
    private final Switch scheduled = new Switch();

    /** Covering @Async methods. */
    private final Switch async = new Switch();

    public DataSize getMaxTotalSize() {
        return maxTotalSize;
    }

    public void setMaxTotalSize(DataSize maxTotalSize) {
        this.maxTotalSize = maxTotalSize;
    }

    public Logging getLogging() {
        return logging;
    }

    public Http getHttp() {
        return http;
    }

    public Task getTask() {
        return task;
    }

    public Switch getScheduled() {
        return scheduled;
    }

    public Switch getAsync() {
        return async;
    }

    public static class Switch {

        /** Whether to treat each call as a unit of work, so its DEBUG lines are written if it fails. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Logging {

        /**
         * Whether to wrap the root logger's appenders and set the debug packages to DEBUG at startup.
         * Turn off to configure the DebugOnFailure appender in logback-spring.xml or log4j2-spring.xml yourself.
         */
        private boolean autoSetup = true;

        /** Packages whose DEBUG lines are collected. Defaults to the package of the @SpringBootApplication class. */
        private List<String> debugPackages = new ArrayList<>();

        public boolean isAutoSetup() {
            return autoSetup;
        }

        public void setAutoSetup(boolean autoSetup) {
            this.autoSetup = autoSetup;
        }

        public List<String> getDebugPackages() {
            return debugPackages;
        }

        public void setDebugPackages(List<String> debugPackages) {
            this.debugPackages = debugPackages;
        }
    }

    public static class Http {

        /** Whether to register the request filter. */
        private boolean enabled = true;

        /** Most memory the DEBUG lines of one request may take; lines from the middle are dropped beyond it. */
        private DataSize maxSize = DataSize.ofBytes(ScopeSettings.HTTP.maxBytes());

        /** Requests slower than this count as failed. 0 (the default) disables the check. */
        private Duration slowThreshold = ScopeSettings.HTTP.slowThreshold();

        /** Requests still running after this long have their DEBUG lines written. Set to 0 to disable. */
        private Duration flushAfter = ScopeSettings.HTTP.flushAfter();

        /** Whether a 5xx response counts as a failed request. */
        private boolean flushOnServerError = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public DataSize getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(DataSize maxSize) {
            this.maxSize = maxSize;
        }

        public Duration getSlowThreshold() {
            return slowThreshold;
        }

        public void setSlowThreshold(Duration slowThreshold) {
            this.slowThreshold = slowThreshold;
        }

        public Duration getFlushAfter() {
            return flushAfter;
        }

        public void setFlushAfter(Duration flushAfter) {
            this.flushAfter = flushAfter;
        }

        public boolean isFlushOnServerError() {
            return flushOnServerError;
        }

        public void setFlushOnServerError(boolean flushOnServerError) {
            this.flushOnServerError = flushOnServerError;
        }

        ScopeSettings toScopeSettings() {
            return new ScopeSettings(maxSize.toBytes(), slowThreshold, flushAfter);
        }
    }

    public static class Task {

        /** Most memory the DEBUG lines of one unit of work may take; lines from the middle are dropped beyond it. */
        private DataSize maxSize = DataSize.ofBytes(ScopeSettings.TASK.maxBytes());

        /** Work slower than this counts as failed. 0 (the default) disables the check. */
        private Duration slowThreshold = ScopeSettings.TASK.slowThreshold();

        /** Work still running after this long has its DEBUG lines written. Set to 0 to disable. */
        private Duration flushAfter = ScopeSettings.TASK.flushAfter();

        public DataSize getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(DataSize maxSize) {
            this.maxSize = maxSize;
        }

        public Duration getSlowThreshold() {
            return slowThreshold;
        }

        public void setSlowThreshold(Duration slowThreshold) {
            this.slowThreshold = slowThreshold;
        }

        public Duration getFlushAfter() {
            return flushAfter;
        }

        public void setFlushAfter(Duration flushAfter) {
            this.flushAfter = flushAfter;
        }

        ScopeSettings toScopeSettings() {
            return new ScopeSettings(maxSize.toBytes(), slowThreshold, flushAfter);
        }
    }
}
