package io.github.jchejarla.debugonfailure.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackSetupTest {

    private LoggerContext context;
    private Logger root;
    private ListAppender<ILoggingEvent> console;

    @BeforeEach
    void setUp() {
        // A separate context, so these tests never touch the one SLF4J is bound to.
        context = new LoggerContext();
        // A context created by hand has no MDC store; give it the one SLF4J uses, as Logback does for its own.
        context.setMDCAdapter(MDC.getMDCAdapter());
        root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        console = new ListAppender<>();
        console.setName("CONSOLE");
        console.setContext(context);
        console.start();
        root.addAppender(console);
    }

    private List<Appender<ILoggingEvent>> rootAppenders() {
        List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
        root.iteratorForAppenders().forEachRemaining(appenders::add);
        return appenders;
    }

    @Test
    void wrapsRootAppendersAndTurnsOnDebugForThePackage() {
        List<String> enabled = LogbackSetup.install(context, List.of("com.shop"));

        assertThat(enabled).containsExactly("com.shop");
        assertThat(context.getLogger("com.shop").getLevel()).isEqualTo(Level.DEBUG);
        assertThat(rootAppenders()).singleElement().isInstanceOf(DebugOnFailureAppender.class);
        DebugOnFailureAppender wrapper = (DebugOnFailureAppender) rootAppenders().get(0);
        assertThat(wrapper.getAppender("CONSOLE")).isSameAs(console);
        assertThat(wrapper.isStarted()).isTrue();
    }

    @Test
    void holdsOnlyTheCollectedPackages() {
        LogbackSetup.install(context, List.of("com.shop"));
        DebugOnFailureAppender wrapper = (DebugOnFailureAppender) rootAppenders().get(0);
        Logger other = context.getLogger("org.hibernate.SQL");
        other.setLevel(Level.DEBUG);

        other.debug("select 1");

        // Outside any unit of work, a held DEBUG line would be dropped; this one is written as before.
        assertThat(console.list).hasSize(1);
        assertThat(wrapper.isIncludeCallerData()).isFalse();
    }

    @Test
    void keepsCallerDataWhenThePatternPrintsIt() {
        root.detachAppender(console);
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d %-5level %class{0}.%method:%line %msg%n");
        encoder.start();
        ConsoleAppender<ILoggingEvent> lines = new ConsoleAppender<>();
        lines.setContext(context);
        lines.setEncoder(encoder);
        lines.start();
        root.addAppender(lines);

        LogbackSetup.install(context, List.of("com.shop"));

        assertThat(((DebugOnFailureAppender) rootAppenders().get(0)).isIncludeCallerData()).isTrue();
    }

    @Test
    void installingTwiceDoesNotWrapTwice() {
        LogbackSetup.install(context, List.of("com.shop"));
        LogbackSetup.install(context, List.of("com.shop"));

        assertThat(rootAppenders()).singleElement().isInstanceOf(DebugOnFailureAppender.class);
    }

    @Test
    void leavesAnExplicitlySetLevelAlone() {
        context.getLogger("com.shop").setLevel(Level.WARN);

        List<String> enabled = LogbackSetup.install(context, List.of("com.shop"));

        assertThat(enabled).isEmpty();
        assertThat(context.getLogger("com.shop").getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void collectsAPackageTheUserAlreadySetToDebug() {
        context.getLogger("com.shop").setLevel(Level.DEBUG);

        assertThat(LogbackSetup.install(context, List.of("com.shop"))).containsExactly("com.shop");
    }

    @Test
    void skipsABlankPackage() {
        assertThat(LogbackSetup.install(context, List.of("", "com.shop"))).containsExactly("com.shop");
    }

    @Test
    void doesNotTurnOnDebugWhenALoggerInThePackageHasItsOwnAppender() {
        ListAppender<ILoggingEvent> audit = new ListAppender<>();
        audit.setContext(context);
        context.getLogger("com.shop.audit").addAppender(audit);

        List<String> enabled = LogbackSetup.install(context, List.of("com.shop"));

        assertThat(enabled).isEmpty();
        assertThat(context.getLogger("com.shop").getLevel()).isNull();
    }

    @Test
    void doesNotTurnOnDebugWhenAParentLoggerHasItsOwnAppender() {
        ListAppender<ILoggingEvent> all = new ListAppender<>();
        all.setContext(context);
        context.getLogger("com").addAppender(all);

        assertThat(LogbackSetup.install(context, List.of("com.shop"))).isEmpty();
    }

    @Test
    void doesNothingWhenTheRootLoggerHasNoAppenders() {
        root.detachAndStopAllAppenders();

        assertThat(LogbackSetup.install(context, List.of("com.shop"))).isEmpty();
        assertThat(context.getLogger("com.shop").getLevel()).isNull();
    }

    @Test
    void keepsAWrapperConfiguredInXml() {
        root.detachAppender(console);
        DebugOnFailureAppender configured = new DebugOnFailureAppender();
        configured.setContext(context);
        configured.addAppender(console);
        configured.start();
        root.addAppender(configured);

        List<String> enabled = LogbackSetup.install(context, List.of("com.shop"));

        assertThat(enabled).containsExactly("com.shop");
        assertThat(rootAppenders()).containsExactly(configured);
    }
}
