package io.github.jchejarla.debugonfailure.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.jchejarla.debugonfailure.core.BufferStore;
import io.github.jchejarla.debugonfailure.core.DebugOnFailure;
import io.github.jchejarla.debugonfailure.core.ScopeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DebugOnFailureAppenderTest {

    private ListAppender<ILoggingEvent> output;
    private DebugOnFailureAppender appender;
    private Logger logger;
    private BufferStore store;

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        output = new ListAppender<>();
        output.setContext(context);
        output.start();

        appender = new DebugOnFailureAppender();
        appender.setContext(context);
        appender.setName("debug-on-failure");
        appender.addAppender(output);
        appender.start();

        logger = context.getLogger("test." + getClass().getSimpleName());
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        logger.addAppender(appender);

        DebugOnFailure.setMaxTotalBytes(BufferStore.defaultMaxTotalBytes());
        store = DebugOnFailure.store();
    }

    @AfterEach
    void tearDown() {
        logger.detachAndStopAllAppenders();
        MDC.clear();
    }

    private void startRequest(String id) {
        MDC.put(DebugOnFailure.SCOPE_ID_KEY, id);
        store.open(id, "POST /checkout", ScopeSettings.HTTP);
    }

    private List<String> writtenMessages() {
        return output.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
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
    void lineLargerThanTheRequestLimitIsDropped() {
        MDC.put(DebugOnFailure.SCOPE_ID_KEY, "r1");
        store.open("r1", "POST /checkout", ScopeSettings.HTTP.withMaxBytes(10_000));
        logger.debug("small line");
        logger.debug("body {}", "x".repeat(50_000));
        logger.error("failed");

        assertThat(writtenMessages()).containsExactly("small line", "failed");
        assertThat(store.droppedCount("r1")).isEqualTo(1);
    }

    @Test
    void debugFromLoggersOutsideTheHeldPackagesIsWrittenAsBefore() {
        appender.setHeldPackages(List.of("com.acme.shop"));
        Logger shop = (Logger) LoggerFactory.getLogger("com.acme.shop.Checkout");
        shop.setLevel(Level.DEBUG);
        shop.setAdditive(false);
        shop.addAppender(appender);
        try {
            startRequest("r1");
            logger.debug("other logger, set to DEBUG by the user");
            shop.debug("collected");
            store.close("r1");

            assertThat(writtenMessages()).containsExactly("other logger, set to DEBUG by the user");
        } finally {
            shop.detachAppender(appender);
        }
    }

    @Test
    void heldEventsKeepWhereTheyWereLoggedWhenAskedTo() {
        appender.setIncludeCallerData(true);
        startRequest("r1");
        logger.debug("step");
        logger.error("failed");

        ILoggingEvent flushed = output.list.get(0);
        assertThat(flushed.getCallerData()).isNotEmpty();
        assertThat(flushed.getCallerData()[0].getMethodName()).isEqualTo("heldEventsKeepWhereTheyWereLoggedWhenAskedTo");
    }

    @Test
    void flushedEventsKeepTheirOriginalTimestamps() throws InterruptedException {
        startRequest("r1");
        logger.debug("early step");
        long loggedBefore = System.currentTimeMillis();
        Thread.sleep(20);
        logger.error("failed");

        ILoggingEvent flushed = output.list.get(0);
        assertThat(flushed.getFormattedMessage()).isEqualTo("early step");
        assertThat(flushed.getTimeStamp()).isLessThanOrEqualTo(loggedBefore);
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
}
