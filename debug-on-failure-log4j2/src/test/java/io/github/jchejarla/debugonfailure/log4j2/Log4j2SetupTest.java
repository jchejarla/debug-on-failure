package io.github.jchejarla.debugonfailure.log4j2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Log4j2SetupTest {

    private LoggerContext context;
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        // A separate context, so these tests never touch the one SLF4J is bound to.
        context = new LoggerContext("setup-test");
        context.start(new DefaultConfiguration());
        configuration = context.getConfiguration();
    }

    @AfterEach
    void tearDown() {
        context.stop();
    }

    private void addLogger(String name, Level level, Appender appender) {
        LoggerConfig logger = new LoggerConfig(name, level, true);
        logger.setParent(configuration.getLoggerConfig(name));
        if (appender != null) {
            logger.addAppender(appender, null, null);
        }
        configuration.addLogger(name, logger);
        context.updateLoggers();
    }

    private Appender anotherAppender() {
        return configuration.getRootLogger().getAppenders().values().iterator().next();
    }

    @Test
    void wrapsRootAppendersAndTurnsOnDebugForThePackage() {
        Appender console = anotherAppender();

        List<String> collected = Log4j2Setup.install(context, List.of("com.shop"));

        assertThat(collected).containsExactly("com.shop");
        assertThat(context.getLogger("com.shop.Checkout").getLevel()).isEqualTo(Level.DEBUG);
        assertThat(configuration.getRootLogger().getAppenders().values())
                .singleElement().isInstanceOf(DebugOnFailureAppender.class);
        DebugOnFailureAppender wrapper =
                (DebugOnFailureAppender) configuration.getRootLogger().getAppenders().values().iterator().next();
        assertThat(wrapper.targetAppenders()).containsExactly(console);
        assertThat(wrapper.isStarted()).isTrue();
    }

    @Test
    void holdsOnlyTheCollectedPackages() {
        LoggerConfig root = configuration.getRootLogger();
        for (String name : List.copyOf(root.getAppenders().keySet())) {
            root.removeAppender(name);
        }
        DebugOnFailureAppenderTest.Capture output = new DebugOnFailureAppenderTest.Capture();
        output.start();
        configuration.addAppender(output);
        root.addAppender(output, null, null);
        context.updateLoggers();

        Log4j2Setup.install(context, List.of("com.shop"));
        addLogger("org.hibernate.SQL", Level.DEBUG, null);
        context.getLogger("org.hibernate.SQL").debug("select 1");
        context.getLogger("com.shop.Checkout").debug("held, and outside any unit of work, so dropped");

        assertThat(output.events).singleElement();
        assertThat(output.events.get(0).getMessage().getFormattedMessage()).isEqualTo("select 1");
    }

    @Test
    void installingTwiceDoesNotWrapTwice() {
        Log4j2Setup.install(context, List.of("com.shop"));
        Log4j2Setup.install(context, List.of("com.shop"));

        assertThat(configuration.getRootLogger().getAppenders().values())
                .singleElement().isInstanceOf(DebugOnFailureAppender.class);
    }

    @Test
    void leavesAnExplicitlySetLevelAlone() {
        addLogger("com.shop", Level.WARN, null);

        assertThat(Log4j2Setup.install(context, List.of("com.shop"))).isEmpty();
        assertThat(context.getLogger("com.shop.Checkout").getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void collectsAPackageTheUserAlreadySetToDebug() {
        addLogger("com.shop", Level.DEBUG, null);

        assertThat(Log4j2Setup.install(context, List.of("com.shop"))).containsExactly("com.shop");
    }

    @Test
    void skipsABlankPackage() {
        assertThat(Log4j2Setup.install(context, List.of("", "com.shop"))).containsExactly("com.shop");
    }

    @Test
    void doesNotTurnOnDebugWhenALoggerInThePackageHasItsOwnAppender() {
        addLogger("com.shop.audit", Level.INFO, anotherAppender());

        assertThat(Log4j2Setup.install(context, List.of("com.shop"))).isEmpty();
        assertThat(configuration.getLoggers()).doesNotContainKey("com.shop");
    }

    @Test
    void doesNotTurnOnDebugWhenAParentLoggerHasItsOwnAppender() {
        addLogger("com", Level.INFO, anotherAppender());

        assertThat(Log4j2Setup.install(context, List.of("com.shop"))).isEmpty();
    }

    @Test
    void doesNothingWhenTheRootLoggerHasNoAppenders() {
        LoggerConfig root = configuration.getRootLogger();
        for (String name : List.copyOf(root.getAppenders().keySet())) {
            root.removeAppender(name);
        }

        assertThat(Log4j2Setup.install(context, List.of("com.shop"))).isEmpty();
        assertThat(configuration.getLoggers()).doesNotContainKey("com.shop");
    }
}
