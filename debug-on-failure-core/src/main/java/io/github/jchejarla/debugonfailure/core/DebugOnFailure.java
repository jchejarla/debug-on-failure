package io.github.jchejarla.debugonfailure.core;

import org.slf4j.MDC;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point shared by the logging adapters, the request filter and application code.
 * <p>
 * Wrap a unit of work so its DEBUG lines are written only if it fails:
 * <pre>{@code
 * for (Order order : orders) {
 *     DebugOnFailure.run(() -> process(order));
 * }
 * }</pre>
 * Without a name, the work is named after the method that called {@code run}, such as
 * {@code OrderImport.importAll}. Pass a name to make it more specific, such as {@code "order " + order.id()}.
 * Wrap each item, message or chunk rather than a whole long-running job, so a failure writes the
 * lines for the item that failed.
 * <p>
 * The current unit of work is identified through the SLF4J MDC under {@link #SCOPE_ID_KEY}.
 */
public final class DebugOnFailure {

    /** MDC key holding the id of the unit of work whose events are being buffered. */
    public static final String SCOPE_ID_KEY = "debugOnFailure.scopeId";

    /** How often running scopes are checked against their flush-after setting. */
    private static final long SWEEP_INTERVAL_SECONDS = 30;

    private static volatile BufferStore store = new BufferStore();
    private static volatile ScopeSettings taskSettings = ScopeSettings.TASK;
    private static final AtomicBoolean sweeperStarted = new AtomicBoolean();

    private DebugOnFailure() {
    }

    public static BufferStore store() {
        return store;
    }

    /**
     * Sets the most memory, roughly, that held DEBUG lines may take across everything in flight.
     * The default is 5% of the maximum heap, and never more than 64 MB. Call this once at startup;
     * buffers open at the time of the call are discarded.
     */
    public static void setMaxTotalBytes(long maxTotalBytes) {
        store = new BufferStore(maxTotalBytes);
    }

    /** Settings used by {@link #run(String, Runnable)} and {@link #call(String, Callable)}. */
    public static ScopeSettings taskSettings() {
        return taskSettings;
    }

    public static void setTaskSettings(ScopeSettings settings) {
        taskSettings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Starts a unit of work on this thread. Close it on the same thread, and call
     * {@link Scope#fail(String)} first if it failed. Prefer {@link #run} or {@link #call}, which do both.
     */
    public static Scope begin(String name, ScopeSettings settings) {
        startSweeper();
        return new Scope(store, name, settings);
    }

    /**
     * Runs the work with the task settings; its DEBUG lines are written only if it fails. It is named
     * after the method that called this one.
     */
    public static void run(Runnable work) {
        run(callerName(), taskSettings, work);
    }

    /** Runs the work with the task settings; its DEBUG lines are written only if it fails. */
    public static void run(String name, Runnable work) {
        run(name, taskSettings, work);
    }

    public static void run(String name, ScopeSettings settings, Runnable work) {
        try (Scope scope = begin(name, settings)) {
            try {
                work.run();
            } catch (RuntimeException | Error e) {
                scope.fail("exception " + e.getClass().getSimpleName());
                throw e;
            }
        }
    }

    /** Like {@link #run(Runnable)}, for work that returns a value or throws checked exceptions. */
    public static <T> T call(Callable<T> work) throws Exception {
        return call(callerName(), taskSettings, work);
    }

    /** Like {@link #run(String, Runnable)}, for work that returns a value or throws checked exceptions. */
    public static <T> T call(String name, Callable<T> work) throws Exception {
        return call(name, taskSettings, work);
    }

    public static <T> T call(String name, ScopeSettings settings, Callable<T> work) throws Exception {
        try (Scope scope = begin(name, settings)) {
            try {
                return work.call();
            } catch (Exception | Error e) {
                scope.fail("exception " + e.getClass().getSimpleName());
                throw e;
            }
        }
    }

    /**
     * Writes the held events of the current unit of work now, and writes anything it logs afterwards
     * straight away. Use it for failures the code handles itself, such as falling back to another
     * provider, where nothing is logged at ERROR.
     *
     * @return the number of events written; 0 when no unit of work is active on this thread
     */
    public static int flush() {
        String id = MDC.get(SCOPE_ID_KEY);
        return id == null ? 0 : store.flush(id);
    }

    /** {@code ClassName.method} of the first caller outside this class, used when no name is given. */
    private static String callerName() {
        return StackWalker.getInstance().walk(frames -> frames
                        .filter(frame -> !frame.getClassName().equals(DebugOnFailure.class.getName()))
                        .findFirst()
                        .map(DebugOnFailure::describe)
                        .orElse("work"));
    }

    private static String describe(StackWalker.StackFrame frame) {
        String className = frame.getClassName();
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        String method = frame.getMethodName();
        // Inside a lambda, javac names the method lambda$enclosingMethod$0; report the enclosing method.
        if (method.startsWith("lambda$")) {
            int end = method.indexOf('$', "lambda$".length());
            method = end > 0 ? method.substring("lambda$".length(), end) : method;
        }
        return simpleName + "." + method;
    }

    private static void startSweeper() {
        if (!sweeperStarted.compareAndSet(false, true)) {
            return;
        }
        ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "debug-on-failure-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleWithFixedDelay(DebugOnFailure::sweep,
                SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private static void sweep() {
        try {
            store.sweep();
        } catch (RuntimeException e) {
            // An exception would cancel all future sweeps; a failing appender is reported by the
            // logging backend itself, so keep going.
        }
    }
}
