# debug-on-failure

Buffer DEBUG logs in memory per request and write them only when the request fails. Full context for every failure, at close to the cost of INFO.

## Why

Production services usually log at INFO, because writing DEBUG lines for every request costs too much in log volume and storage. So when a request fails, the DEBUG lines that would explain it were never written. You are left trying to reproduce the problem, or redeploying with DEBUG on and waiting for it to happen again.

debug-on-failure keeps each request's DEBUG lines in memory while the request runs:

- If the request **succeeds**, its DEBUG lines are thrown away. Your log looks the same as it does at INFO today.
- If the request **fails**, its DEBUG lines are written to your log, just before the error.

## What it looks like

Take this code:

```java
log.info("Checkout started for cart {}", cartId);
log.debug("Cart has {} items, total {}", items.size(), total);
log.debug("Calling payment service");
PaymentResult result = payments.charge(total);
log.debug("Payment response: {}", result);
log.info("Checkout done");
```

A request that succeeds writes only the INFO lines, as it would today:

```
10:00:00.100 INFO  Checkout started for cart 42
10:00:00.350 INFO  Checkout done
```

A request where the payment fails writes its DEBUG lines too, in order, with the time each one was logged:

```
10:00:00.100 INFO  Checkout started for cart 42
10:00:00.120 DEBUG Cart has 3 items, total 109.00
10:00:00.130 DEBUG Calling payment service
10:00:00.340 DEBUG Payment response: DECLINED code=51
10:00:00.341 ERROR Payment declined
```

## How it works

Every `log.debug(...)` call goes through two steps:

1. **The level decides whether the line is created.** If the logger is at INFO, the call does nothing. If it is at DEBUG, the line is created.
2. **The appender decides what happens to the line.** Appenders are the parts of Logback and Log4j2 that write to the console, a file or a log service. Normally they write every line they receive.

That is why "DEBUG on" usually means "DEBUG lines in the logs". debug-on-failure changes only the second step: DEBUG lines are still created, but they are held in memory (buffered) instead of being written.

| | DEBUG lines created? | DEBUG lines written? |
| --- | --- | --- |
| Usual production setup (level INFO) | No | No |
| DEBUG on, without debug-on-failure | Yes | Yes, every one |
| **With debug-on-failure** | **Yes** | **Only for failed requests** |

debug-on-failure groups DEBUG lines by piece of work: one HTTP request, one scheduled job run, one async call. Each group is written if that piece of work fails, and thrown away if it succeeds.

For these, it knows where each piece of work starts and ends without any code from you:

- HTTP requests
- `@Scheduled` methods
- `@Async` methods

For anything else, such as a message listener, wrap the work in `DebugOnFailure.run(...)`; see [Work that is not covered automatically](#work-that-is-not-covered-automatically).

## Getting started with Spring Boot

Requirements: Java 17 or later, Spring Boot 4, and either Logback, which is Spring Boot's default, or Log4j2. The starter does not support Spring Boot 3. The Logback, Log4j2 and servlet modules do not depend on Spring Boot, so on Spring Boot 3 they can be set up by hand, as described in [Without Spring Boot](#without-spring-boot), but this has not been tested.

Add the starter:

```xml
<dependency>
    <groupId>io.github.jchejarla</groupId>
    <artifactId>debug-on-failure-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

No code changes are needed, and the defaults suit most applications; see [Configuration](#configuration) to change them. When the application starts, the starter:

1. Puts itself in front of your existing log output (console, file, or whatever you have configured), so every line passes through it first.
2. Sets your application's package to DEBUG, so DEBUG lines are created. It does this only after step 1 has worked, so DEBUG lines can never go straight to your log output.
3. Starts grouping DEBUG lines by HTTP request, by `@Scheduled` call and by `@Async` call.

At startup it logs what it set up:

```
INFO  debug-on-failure: collecting DEBUG lines from [com.acme.shop]; they are written only when a request or task fails
INFO  debug-on-failure: covering the @Scheduled and @Async methods of [NightlyReport, ReceiptMailer]
```

If it cannot collect anything, for example because the application uses a logging library other than Logback or Log4j2, it logs a WARN instead, saying why and what to change.

To see it working before adding it to your own application, run the small demo shop in [`examples/demo`](examples/demo). Its README lists a command for each kind of failure and what the console shows.

## What it does

This part describes what happens to DEBUG lines in each situation. Every value mentioned here, such as the 5-minute limit for hung requests, can be changed; see [Configuration](#configuration).

### When a request counts as failed

- An ERROR is logged during the request.
- An exception escapes the request.
- The response status is 5xx.
- The code calls `DebugOnFailure.flush()`. Use this when the code handles a problem itself without logging an ERROR, such as falling back to a backup service.
- The request takes longer than a time you choose. This is off by default; see [All settings](#all-settings).

If an ERROR is logged, the request's DEBUG lines are written immediately, just before the ERROR line. The same happens when `flush()` is called. After that, any further DEBUG lines from the request are written straight away, since the request has already failed.

The other failures are only known when the request ends: an exception, a 5xx status or a slow response. In those cases the DEBUG lines are written when the request ends, after a WARN line that names the request and the reason:

```
WARN  POST /checkout failed (status 503); writing 3 buffered debug lines
```

Requests handled by Spring MVC are named by their route, such as `GET /orders/{id}`, so ids and other values in the path stay out of WARN lines. Other requests are named by their path.

DEBUG lines logged outside any piece of work that debug-on-failure knows about, for example during startup, from a message listener, or from a thread your code started itself, are thrown away, exactly as they would be at INFO. To collect them, see [Work that is not covered automatically](#work-that-is-not-covered-automatically).

### Requests that hang

A request that hangs may never log an error, and so would never have its DEBUG lines written. If a request is still running after 5 minutes (`http.flush-after`), its DEBUG lines so far are written without waiting for it to end:

```
WARN  POST /checkout still running after 5m 0s; writing 2 buffered debug lines
```

The request keeps running. If it fails later, the lines it logged after that point are written then.

### Scheduled jobs and async methods

`@Scheduled` methods work automatically when scheduling is on (`@EnableScheduling`), and `@Async` methods when async is on (`@EnableAsync`). Your code does not change. Each call is named after its class and method, for example `NightlyReport.build`, and it counts as failed when it:

- throws an exception,
- logs an ERROR,
- calls `DebugOnFailure.flush()`, or
- for `@Async` methods, returns a `CompletableFuture` that has already failed.

An `@Async` method is its own piece of work, separate from the request that called it. Its DEBUG lines are written if it fails, whether or not the request did.

A call that is still running after 30 minutes (`task.flush-after`) has its DEBUG lines so far written, as for hung requests. The limit is longer than for requests, because a job taking several minutes is often normal.

Classes that are `final`, or that have `final` methods, are skipped, with a WARN at startup. The starter has to wrap a class to see when its methods start and end, and final classes and methods cannot be wrapped safely. Use `DebugOnFailure.run` inside them instead.

### Work that is not covered automatically

For anything else, such as message listeners or threads your code starts itself, wrap each piece of work with `DebugOnFailure.run`, or with `DebugOnFailure.call` if it returns a value:

```java
@KafkaListener(topics = "payments")
void onPayment(Payment payment) {
    DebugOnFailure.run(() -> handle(payment));
}
```

The work is named after the method that called `run`, here `PaymentListener.onPayment`. That name appears in the WARN line if the work fails. To make it more specific, pass a name first:

```java
DebugOnFailure.run("payment " + payment.id(), () -> handle(payment));
```

The work counts as failed when it throws an exception, logs an ERROR, or calls `DebugOnFailure.flush()`.

To cover every listener of one kind without wrapping each one, for example every `@KafkaListener` method, you can write a small integration. See [Writing your own integration](docs/writing-integrations.md).

Wrap each item, message or chunk, not a whole long-running job. If a two-hour job fails on order 8,000, you want the DEBUG lines for order 8,000, not two hours of history.

Wrapped work can be nested, for example one wrapped item per order inside a scheduled job. A failed item writes only its own lines. The lines of an item that succeeded are thrown away when that item ends, even if the job fails later.

```java
@Scheduled(cron = "0 0 2 * * *")
public void importOrders() {
    for (Order order : orders()) {
        DebugOnFailure.run("order " + order.id(), () -> process(order));
    }
}
```

## Configuration

Every setting has a recommended default, so none are required. This part explains the ones you are most likely to change, then lists them all.

### Which packages are collected

By default, DEBUG lines are collected from the package of your `@SpringBootApplication` class and everything under it. If that class is `com.acme.shop.ShopApplication`, DEBUG lines from `com.acme.shop` and all its sub-packages are collected.

To collect other packages, list them. The list replaces the default, so include your application's package if you still want it:

```properties
debug-on-failure.logging.debug-packages=com.acme.shop,com.acme.common
```

Loggers you set to DEBUG yourself, such as `logging.level.org.hibernate.SQL=DEBUG`, keep working as before: their DEBUG lines are written straight away. Adding debug-on-failure never hides a line you see today. To collect them instead, add them to `debug-on-failure.logging.debug-packages`.

A package is not collected, and the startup log says so, when:

- You set its level to INFO or higher yourself, for example `logging.level.com.acme.shop=INFO`. Your setting wins.
- A logger in it, or above it, has appenders of its own. Its DEBUG lines would be written directly, so the starter leaves it alone.

### Memory limits

Held DEBUG lines are limited by size, at two levels:

- **Per request, job run or other piece of work: 1 MB** (`http.max-size`, `task.max-size`). If a request logs more than that, lines from the middle are dropped. The first lines, which usually say what the request was, and the latest lines, which say what happened last, are kept. A WARN line marks where lines were left out. A single line larger than 1 MB is dropped.
- **For the whole application: 5% of the heap, and never more than 64 MB** (`max-total-size`). If the total is reached, new DEBUG lines are dropped until other requests finish. This is the guarantee that holding DEBUG lines cannot run the application out of memory.

Sizes are estimated from the text each line holds: the message, string arguments, stack trace and MDC values (the extra fields attached to log lines, such as trace ids). When a failed request's DEBUG lines are written, a WARN line says how many of its lines were dropped, if any.

### All settings

| Property | Default | What it does |
| --- | --- | --- |
| `debug-on-failure.max-total-size` | 5% of heap, at most `64MB` | Most memory held across the whole application |
| `debug-on-failure.logging.auto-setup` | `true` | Sets up Logback or Log4j2 at startup, as described in [Getting started](#getting-started-with-spring-boot) |
| `debug-on-failure.logging.debug-packages` | your application's package | Packages whose DEBUG lines are collected |
| `debug-on-failure.http.enabled` | `true` | Covers HTTP requests |
| `debug-on-failure.http.max-size` | `1MB` | Most memory held per request |
| `debug-on-failure.http.slow-threshold` | `0` (off) | Requests slower than this count as failed, for example `3s` |
| `debug-on-failure.http.flush-after` | `5m` | Requests still running after this have their DEBUG lines written; `0` turns it off |
| `debug-on-failure.http.flush-on-server-error` | `true` | Whether a 5xx response counts as failed |
| `debug-on-failure.scheduled.enabled` | `true` | Covers `@Scheduled` methods |
| `debug-on-failure.async.enabled` | `true` | Covers `@Async` methods |
| `debug-on-failure.task.max-size` | `1MB` | Most memory held per scheduled job run, async call or wrapped piece of work |
| `debug-on-failure.task.slow-threshold` | `0` (off) | Work slower than this counts as failed |
| `debug-on-failure.task.flush-after` | `30m` | Work still running after this has its DEBUG lines written; `0` turns it off |

The `task.*` settings apply to scheduled jobs, async methods and work wrapped with `DebugOnFailure.run`. They are separate from the `http.*` settings because this kind of work behaves differently: a request running for five minutes is almost certainly stuck, but a job might not be.

### Turning it off

Set these, or remove the dependency:

```properties
debug-on-failure.logging.auto-setup=false
debug-on-failure.http.enabled=false
debug-on-failure.scheduled.enabled=false
debug-on-failure.async.enabled=false
```

With `auto-setup=false`, your log levels and appenders are left exactly as you configured them.

### Setting up logging yourself

If you prefer to control logging in your own configuration file, set `debug-on-failure.logging.auto-setup=false` and wrap your appenders.

Logback, in `logback-spring.xml`:

```xml
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    <include resource="org/springframework/boot/logging/logback/console-appender.xml"/>

    <appender name="DEBUG_ON_FAILURE" class="io.github.jchejarla.debugonfailure.logback.DebugOnFailureAppender">
        <appender-ref ref="CONSOLE"/>
    </appender>

    <logger name="com.acme.shop" level="DEBUG"/>

    <root level="INFO">
        <appender-ref ref="DEBUG_ON_FAILURE"/>
    </root>
</configuration>
```

Log4j2, in `log4j2-spring.xml`:

```xml
<Configuration>
    <Appenders>
        <Console name="Console">
            <PatternLayout pattern="%d %-5level %logger - %msg%n"/>
        </Console>
        <DebugOnFailure name="DEBUG_ON_FAILURE">
            <AppenderRef ref="Console"/>
        </DebugOnFailure>
    </Appenders>
    <Loggers>
        <Logger name="com.acme.shop" level="DEBUG"/>
        <Root level="INFO">
            <AppenderRef ref="DEBUG_ON_FAILURE"/>
        </Root>
    </Loggers>
</Configuration>
```

In this setup you set the DEBUG levels yourself, and every DEBUG line that reaches `DEBUG_ON_FAILURE` is held, whichever logger it comes from. Make sure every appender that receives DEBUG lines is inside `DEBUG_ON_FAILURE`; any appender outside it writes them directly. Asynchronous appenders go inside `DEBUG_ON_FAILURE`, never around it.

### Without Spring Boot

- **Logback:** call `LogbackSetup.install(packages)` at startup, or configure the appender in `logback.xml` as above.
- **Log4j2:** call `Log4j2Setup.install(packages)` at startup, or configure the appender in `log4j2.xml` as above.
- **HTTP requests:** register `DebugOnFailureFilter` as a servlet filter.
- **Scheduled jobs, async methods and anything else:** wrap the work with `DebugOnFailure.run`; the automatic coverage of `@Scheduled` and `@Async` comes with the Spring Boot starter.
- **Settings:** pass a `ScopeSettings` to the filter or to `run`, and call `DebugOnFailure.setTaskSettings` and `DebugOnFailure.setMaxTotalBytes` at startup.

## Before you use it in production

- **Check what your DEBUG lines contain.** They are now written to production logs whenever a request fails. If any DEBUG line logs request bodies, tokens, passwords or personal data, change it first.
- **Expect some CPU cost.** Every `log.debug` call in collected packages now creates a log event, even when it is never written. An early benchmark that measured logging alone handled 327k requests per second with INFO only, 202k with DEBUG lines held, and 35k with DEBUG always written. Real services spend most of each request waiting on databases and other services, so the difference there is much smaller. Measure it on yours.
- **Log files show held lines when they are written, not when they were logged.** In a plain log file, a failed request's DEBUG lines appear together just before its error, possibly after lines from other requests. Each line keeps its original timestamp and MDC values, such as trace ids, so log tools that sort or filter by those show them in order.
- **Held lines reach your appenders as the original log events.** Asynchronous appenders and JSON encoders receive them like any other line, and each line keeps the thread that logged it.
- **Line numbers and method names are kept if your pattern prints them** (`%line`, `%method`). Automatic setup detects this from your pattern. If you configure Logback yourself, add `<includeCallerData>true</includeCallerData>` to the `DEBUG_ON_FAILURE` appender; otherwise those fields show `?` for held lines.
- **Lines logged during a request, job run or other piece of work carry an extra MDC value, `debugOnFailure.scopeId`.** You will see it if your log pattern prints the whole MDC, or in JSON logs that include every MDC field. It can be handy for grouping a request's lines; if you don't want it, exclude it in your encoder's settings.
- **Held lines live only in memory.** If the process is killed, they are lost.
- **Log4j2 asynchronous loggers are not supported.** They deliver log lines on another thread, possibly after the request has ended. Asynchronous appenders are fine.

## Limitations in this version

- Logback and Log4j2 are supported; other logging libraries are not.
- Work is tracked only on the thread that started it. If a request hands part of its work to another thread, for example with `CompletableFuture.supplyAsync`, DEBUG lines logged on that thread are not part of the request and are thrown away. `@Async` methods are the exception: they are covered as separate pieces of work. WebFlux is not supported.
- Message listeners, such as `@KafkaListener` methods, need wrapping with `DebugOnFailure.run`, or an integration of your own.
- If Logback or Log4j2 reloads its configuration while running, for example because `scan` or `monitorInterval` is set, the automatic setup is lost until the application restarts. Its DEBUG levels are lost with it, so DEBUG lines are not written by mistake; they are simply no longer collected.
- An `@Async` method whose `CompletableFuture` fails after the method has returned is not counted as failed.
- The slow threshold is the same for every endpoint.
- Requests are checked against `flush-after` every 30 seconds, so their lines may be written up to 30 seconds late.
- There are no metrics yet for how much memory is held or how many lines were dropped; the WARN lines are the only record.

## What this project covers

This project covers HTTP requests, `@Scheduled` methods and `@Async` methods, with Logback and Log4j2. To keep it small enough to maintain well, it does not include integrations for individual frameworks such as Kafka, RabbitMQ or Quartz. `DebugOnFailure.run`, or a few lines of your own code, covers those; see [Writing your own integration](docs/writing-integrations.md).

## License

Apache License 2.0
