package io.github.jchejarla.debugonfailure.log4j2;

import io.github.jchejarla.debugonfailure.core.BufferStore;
import io.github.jchejarla.debugonfailure.core.DebugOnFailure;
import io.github.jchejarla.debugonfailure.core.ScopeSettings;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderControl;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DebugOnFailureAppenderTest {

    private static final String LOGGER_NAME = "test.log4j2";

    /** Records what reaches it, in place of a console or file appender. */
    static final class Capture extends AbstractAppender {

        final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        Capture() {
            super("CAPTURE", null, null, true, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    private LoggerContext context;
    private Capture output;
    private DebugOnFailureAppender wrapper;
    private BufferStore store;
    private Logger logger;

    @BeforeEach
    void setUp() {
        context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();

        output = new Capture();
        output.start();
        wrapper = DebugOnFailureAppender.wrapping("dof", List.of(new AppenderControl(output, null, null)));
        wrapper.start();

        LoggerConfig loggerConfig = new LoggerConfig(LOGGER_NAME, Level.DEBUG, false);
        loggerConfig.addAppender(wrapper, null, null);
        configuration.addLogger(LOGGER_NAME, loggerConfig);
        context.updateLoggers();

        logger = LoggerFactory.getLogger(LOGGER_NAME + ".Checkout");
        DebugOnFailure.setMaxTotalBytes(BufferStore.defaultMaxTotalBytes());
        store = DebugOnFailure.store();
    }

    @AfterEach
    void tearDown() {
        context.getConfiguration().removeLogger(LOGGER_NAME);
        context.updateLoggers();
        MDC.clear();
    }

    private void startRequest(String id) {
        MDC.put(DebugOnFailure.SCOPE_ID_KEY, id);
        store.open(id, "POST /checkout", ScopeSettings.HTTP);
    }

    private List<String> writtenMessages() {
        return output.events.stream().map(event -> event.getMessage().getFormattedMessage()).toList();
    }

    @Test
    void successfulRequestWritesOnlyInfoAndAbove() {
        startRequest("r1");
        logger.info("checkout start");
        logger.debug("cart loaded items={}", 3);
        logger.info("checkout done");
        store.close("r1");

        assertThat(writtenMessages()).containsExactly("checkout start", "checkout done");
    }

    @Test
    void errorWritesHeldDebugEventsBeforeTheError() {
        startRequest("r1");
        logger.info("checkout start");
        logger.debug("cart loaded items={}", 3);
        logger.debug("price computed total={}", "109.00");
        logger.error("payment failed");

        assertThat(writtenMessages()).containsExactly(
                "checkout start",
                "cart loaded items=3",
                "price computed total=109.00",
                "payment failed");
    }

    @Test
    void debugLoggedAfterAnErrorIsWrittenStraightAway() {
        startRequest("r1");
        logger.debug("calling primary provider");
        logger.error("primary provider failed");
        logger.debug("falling back to backup provider");

        assertThat(writtenMessages()).containsExactly(
                "calling primary provider",
                "primary provider failed",
                "falling back to backup provider");
    }

    @Test
    void flushedEventsKeepTheirOriginalTimestamps() throws InterruptedException {
        startRequest("r1");
        logger.debug("early step");
        long loggedBefore = System.currentTimeMillis();
        Thread.sleep(20);
        logger.error("failed");

        LogEvent flushed = output.events.get(0);
        assertThat(flushed.getMessage().getFormattedMessage()).isEqualTo("early step");
        assertThat(flushed.getTimeMillis()).isLessThanOrEqualTo(loggedBefore);
    }

    @Test
    void explicitFlushWritesHeldEvents() {
        startRequest("r1");
        logger.debug("primary provider timed out");

        assertThat(DebugOnFailure.flush()).isEqualTo(1);
        assertThat(writtenMessages()).containsExactly("primary provider timed out");
    }

    @Test
    void debugOutsideARequestIsDropped() {
        logger.debug("background job step");

        assertThat(writtenMessages()).isEmpty();
        assertThat(store.totalBytes()).isZero();
    }

    @Test
    void argumentsAreCapturedWhenLoggedNotWhenFlushed() {
        startRequest("r1");
        StringBuilder state = new StringBuilder("before");
        logger.debug("state={}", state);
        state.replace(0, state.length(), "after");
        logger.error("failed");

        assertThat(writtenMessages()).first().isEqualTo("state=before");
    }

    @Test
    void heldEventsKeepTheThreadThatLoggedThem() throws InterruptedException {
        String[] id = new String[1];
        Thread worker = new Thread(() -> {
            startRequest("r-worker");
            id[0] = "r-worker";
            logger.debug("logged on the worker");
        }, "worker-1");
        worker.start();
        worker.join();

        store.flush(id[0]);

        assertThat(output.events).singleElement();
        assertThat(output.events.get(0).getThreadName()).isEqualTo("worker-1");
    }

    @Test
    void debugFromLoggersOutsideTheHeldPackagesIsWrittenAsBefore() {
        wrapper.setHeldPackages(List.of("com.acme.shop"));
        startRequest("r1");
        logger.debug("other logger, set to DEBUG by the user");
        store.close("r1");

        assertThat(writtenMessages()).containsExactly("other logger, set to DEBUG by the user");
    }

    @Test
    void lineLargerThanTheRequestLimitIsDropped() {
        MDC.put(DebugOnFailure.SCOPE_ID_KEY, "r1");
        store.open("r1", "POST /checkout", ScopeSettings.HTTP.withMaxBytes(10_000));
        logger.debug("small line");
        logger.debug("body {}", "x".repeat(50_000));
        logger.error("failed");

        assertThat(writtenMessages()).containsExactly("small line", "failed");
        assertThat(store.droppedCount("r1")).isEqualTo(1);
    }
}
