# Writing your own integration

To decide which DEBUG lines belong together, and when to write or discard them, debug-on-failure needs to know where each piece of work starts and ends. It works this out by itself for HTTP requests, `@Scheduled` methods and `@Async` methods. For anything else, such as message listeners, RPC frameworks or your own job runner, a few lines of code tell it. This guide shows how.

There are two kinds of integration:

1. **Covering a new kind of work**, such as Kafka messages. This is what most people need.
2. **Supporting another logging backend**, besides Logback and Log4j2. This is rarely needed.

Both use the public API in `debug-on-failure-core`.

## 1. Covering a new kind of work

### What an integration has to do

debug-on-failure calls one tracked piece of work, such as one message, one job run or one RPC call, a **scope**. For each one, the integration does three things:

1. **Begin** a scope when the work starts. From then on, DEBUG lines logged on that thread are held for it.
2. **Mark it failed** if it fails.
3. **Close** the scope when the work ends. If it failed, its DEBUG lines are written; otherwise they are thrown away.

### The simplest way: wrap a lambda

If you can put the work inside a lambda, `DebugOnFailure.run` does all three steps:

```java
DebugOnFailure.run(() -> handle(payment));
```

The work is named after the method that called `run`, such as `PaymentListener.onPayment`, and that name appears in the WARN line if it fails. To make the name more specific, pass one first:

```java
DebugOnFailure.run("payment " + payment.id(), () -> handle(payment));
```

Use `DebugOnFailure.call` if the work returns a value or throws checked exceptions. Both mark the work failed when it throws an exception, and rethrow it.

### The general way: begin, fail, close

When the start and end of the work happen in different places, such as a framework's "before" and "after" callbacks, use a `Scope` directly:

```java
Scope scope = DebugOnFailure.begin("payment " + payment.id(), DebugOnFailure.taskSettings());
try {
    handle(payment);
} catch (Exception e) {
    scope.fail("exception " + e.getClass().getSimpleName());
    throw e;
} finally {
    scope.close();
}
```

### Rules

- **Begin and close on the same thread.** A scope is attached to the thread that began it, through the logging MDC. Closing it on another thread leaves the first thread attached to a closed scope.
- **Always close, in a `finally` block.** A scope that is never closed keeps holding lines, written only at its `flush-after` interval, and its thread stays attached to it.
- **Call `fail` for failures that do not throw.** Some frameworks catch the exception themselves, or report failure another way, such as sending a message to a dead-letter queue. Call `scope.fail("sent to dead-letter queue")` for those. The first reason given is the one reported.
- **Choose a short name that identifies the work,** such as `payment 77` or `OrderJobs.importOrders`. It appears in WARN lines. Do not put personal data or secrets in it. If a better name only becomes known later, `scope.rename(...)` changes it.
- **Use `DebugOnFailure.taskSettings()`** unless your kind of work needs different limits. In Spring Boot, those settings come from `debug-on-failure.task.*`. Otherwise, pass your own, for example `ScopeSettings.TASK.withFlushAfter(Duration.ofMinutes(2))`.
- **Scopes can be nested.** If your integration begins a scope inside another one, such as a message handled during an HTTP request, the inner scope holds its own lines, and the outer one becomes current again when the inner one closes.

### Where to hook in

Look for a place in the framework that runs on the same thread just before and just after each unit of work. Most frameworks have one, under a name like interceptor, listener, advice or decorator. Check that it:

- runs on the thread that does the work, not the thread that receives or schedules it, and
- runs after the work too, including when it fails.

### Example: any annotated method, with Spring AOP

Many frameworks call an annotated method of your bean for each unit of work: `@KafkaListener`, `@RabbitListener`, `@JmsListener`, `@SqsListener` and others. If your application has `spring-boot-starter-aop`, one aspect covers every such method:

```java
@Aspect
@Component
class ListenerScopes {

    @Around("@annotation(org.springframework.kafka.annotation.KafkaListener)")
    Object coverListener(ProceedingJoinPoint call) throws Throwable {
        Scope scope = DebugOnFailure.begin(call.getSignature().toShortString(), DebugOnFailure.taskSettings());
        try {
            return call.proceed();
        } catch (Throwable e) {
            scope.fail("exception " + e.getClass().getSimpleName());
            throw e;
        } finally {
            scope.close();
        }
    }
}
```

Change the annotation in the `@Around` expression for other frameworks. If the framework retries a failed message, each attempt is its own unit of work, and each failed attempt writes its own DEBUG lines.

### Testing it

Two checks catch most mistakes:

- Inside the work, `MDC.get(DebugOnFailure.SCOPE_ID_KEY)` is not null.
- After the work, whether it succeeded or failed, `MDC.get(DebugOnFailure.SCOPE_ID_KEY)` is null again and `DebugOnFailure.store().openScopes()` is 0.

## 2. Supporting another logging backend

A backend integration is a wrapper around the backend's writers, called appenders or handlers. The Logback and Log4j2 integrations in this project are the best reference: about 250 lines each, including comments, plus about 180 for the optional automatic setup.

### What the wrapper does with each log event

```
event arrives
  │
  ├─ below the buffer level (DEBUG, TRACE)?
  │     ├─ event has a scope id → copy it, then hold the copy:
  │     │      DebugOnFailure.store().buffer(scopeId, () -> write(copy), estimatedSize)
  │     └─ no scope id         → drop it, as an INFO configuration would
  │
  └─ at or above the buffer level?
        ├─ at or above ERROR and has a scope id → DebugOnFailure.store().flush(scopeId), then write the event
        └─ otherwise                             → write the event
```

### Rules

- **Read the scope id from the event, not from the thread.** It is in the event's MDC or context data under `DebugOnFailure.SCOPE_ID_KEY`. If reading it fails, treat the event as having none and write it as usual; never lose a line because of debug-on-failure.
- **Hold an independent copy, with everything filled in.** Many backends reuse event objects, and work out some details only when an event is written: the message text, the thread name, and where it was logged. A held event may be written later, from another thread, when those would come out wrong. Fill them in before holding the event. Work out where it was logged only if a layout prints it, since that is expensive.
- **Estimate the size** of each held event, mainly from the text it carries: message, string arguments, stack trace and MDC. The store uses the estimate to enforce the memory limits. If `buffer` returns `false`, the event was dropped to stay within a limit; there is nothing else to do.
- **Receive events on the thread that logged them.** If the backend hands events to another thread before your wrapper sees them, a unit of work can end before its events arrive. Put asynchronous writers inside your wrapper, never around it.
- **Write held events through the same writers as everything else,** so they get the same format and destinations.

### Optional: automatic setup

The Logback and Log4j2 integrations also wrap the root logger's writers at startup and set the application's packages to DEBUG. If you add this for another backend, follow the same safety rules:

- Set a package to DEBUG only after the wrapper is in place, so DEBUG lines can never reach a writer directly.
- Hold DEBUG lines only from the packages you set up. Loggers the user set to DEBUG themselves keep writing as before.
- Leave a package alone if its level was set in the user's configuration.
- Skip a package if a logger in it, or above it, has writers of its own.
- Log every decision at startup, so users can see what is collected and why.
