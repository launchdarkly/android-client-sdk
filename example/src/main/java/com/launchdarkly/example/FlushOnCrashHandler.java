package com.launchdarkly.example;

import com.launchdarkly.sdk.android.LDClient;

import java.util.concurrent.TimeUnit;

import timber.log.Timber;

/**
 * Delivers buffered analytics events from the uncaught exception handler, which is the most an
 * application can do about event loss while the SDK keeps its events only in memory.
 * <p>
 * This is what makes the two instant buttons in {@link MainActivity} an experiment and its control.
 * An uncaught exception runs this handler while the process is still alive and its other threads are
 * still running, so the events recorded a moment earlier can still reach the network.
 * {@code SIGKILL} runs nothing, and neither does an ANR, a native crash, or the system reclaiming a
 * backgrounded process, so those lose the same events. The difference between the two buttons is the
 * ground that on-disk persistence would cover and a crash handler cannot.
 */
final class FlushOnCrashHandler implements Thread.UncaughtExceptionHandler {
    /**
     * How long the crash is held open for the events.
     * <p>
     * The SDK's HTTP timeouts are measured in seconds, and a request that hangs must not hold the
     * process in a half-dead state for all of them: past this point the events are worth less than
     * the delay, and the crash goes on to be reported.
     */
    private static final long DELIVERY_BUDGET_MILLIS = 2_000;

    private final Thread.UncaughtExceptionHandler next;

    private FlushOnCrashHandler(Thread.UncaughtExceptionHandler next) {
        this.next = next;
    }

    /**
     * Installs the handler in front of whatever was already there, which on a real application is
     * the crash reporter, and on this one is the platform handler that prints the trace.
     */
    static void install() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof FlushOnCrashHandler) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(new FlushOnCrashHandler(previous));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            // Waiting here on the crashing thread is safe because the timeout is the SDK's to
            // enforce: it stops waiting on the delivery rather than trusting it to finish. That also
            // covers the case where this crash is the reason the delivery cannot complete, such as
            // an exception thrown while the event buffer was locked.
            boolean delivered = LDClient.get()
                    .flushAndWait(DELIVERY_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
            Timber.w("crash handler: events delivered = %b", delivered);
        } catch (Throwable t) {
            // Nothing that happens in here is worth losing the crash report over.
            Timber.e(t, "Could not deliver events from the crash handler");
        } finally {
            if (next != null) {
                next.uncaughtException(thread, throwable);
            }
        }
    }
}
