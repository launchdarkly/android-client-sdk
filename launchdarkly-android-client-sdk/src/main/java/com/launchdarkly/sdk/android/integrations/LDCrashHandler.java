package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.launchdarkly.sdk.android.LDClient;
import com.launchdarkly.sdk.android.LDClientInterface;
import com.launchdarkly.sdk.android.LaunchDarklyException;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * An uncaught exception handler that gives the SDK its last chance to act before the process ends:
 * it delivers the analytics events still in memory.
 * <p>
 * Events are kept in memory until they are sent, so a crash loses the ones recorded since the last
 * flush, and those are often the ones that explain it. An uncaught exception runs its handlers while
 * the process is still alive, and this one uses that time to call
 * {@link LDClientInterface#flushAndWait(long, TimeUnit)} for every configured environment before
 * passing the exception on to the handler that was installed before it.
 * <p>
 * Install it before your crash reporter:
 *
 * <pre><code>
 *     LDClient.init(application, config, context, 0);
 *     LDCrashHandler.install(2, TimeUnit.SECONDS);
 *     SentryAndroid.init(application, options -&gt; { ... });
 * </code></pre>
 * <p>
 * Crash reporters install their own handler in front of the one they find and call it once they
 * have saved their report, so installing this one first lets the report be saved before the events
 * are sent. Installed after the crash reporter, this handler runs first and holds the report back
 * for as long as the delivery takes, and a process that dies in that time loses the report as well.
 * <p>
 * The timeout is how long the crash is held open for the events. The thread that threw waits for
 * it, so when that is the main thread the application stays frozen until the events are delivered
 * or the timeout expires; a couple of seconds is a reasonable budget. That is less than a delivery
 * takes to give up on a network that does not answer, so on such a network the crash is passed on
 * when the timeout expires. A delivery still running then is not canceled, and may finish while the
 * rest of the handlers run.
 * <p>
 * This handler only helps with uncaught Java and Kotlin exceptions. A {@code SIGKILL}, an ANR kill,
 * a native crash, and the system reclaiming a backgrounded process run no handlers, so the events
 * still in memory at that moment are lost.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is experimental.
 *
 * @since 5.17.0
 */
public final class LDCrashHandler implements Thread.UncaughtExceptionHandler {
    private static final Object installLock = new Object();

    private final Callable<LDClientInterface> client;
    private final long timeoutMillis;
    @Nullable
    private final Thread.UncaughtExceptionHandler next;

    @VisibleForTesting
    LDCrashHandler(
            @NonNull Callable<LDClientInterface> client,
            long timeoutMillis,
            @Nullable Thread.UncaughtExceptionHandler next
    ) {
        this.client = client;
        this.timeoutMillis = timeoutMillis;
        this.next = next;
    }

    /**
     * Makes this handler the default uncaught exception handler, in front of whichever handler was
     * the default before.
     * <p>
     * It can be called before {@link LDClient#init}: a crash that happens before the client exists
     * has no events to deliver, and is passed on straight away. Calling it again while this handler
     * is still the default does nothing, so the timeout given first is the one that applies.
     *
     * @param timeout how long a crash waits for the events to be delivered
     * @param unit the time unit of {@code timeout}
     */
    public static void install(long timeout, @NonNull TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        long timeoutMillis = Math.max(0, unit.toMillis(timeout));
        synchronized (installLock) {
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            if (previous instanceof LDCrashHandler) {
                return;
            }
            Thread.setDefaultUncaughtExceptionHandler(
                    new LDCrashHandler(LDClient::get, timeoutMillis, previous));
        }
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        try {
            flush();
        } finally {
            if (next != null) {
                next.uncaughtException(thread, throwable);
            } else {
                throwable.printStackTrace();
            }
        }
    }

    private void flush() {
        try {
            LDClientInterface ldClient = client.call();
            if (ldClient == null) {
                return;
            }
            // Waiting on the crashing thread is safe because the SDK enforces the timeout instead of
            // trusting the delivery to finish, which also covers a crash that is the reason the
            // delivery cannot complete.
            ldClient.flushAndWait(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (LaunchDarklyException notInitialized) {
            // A crash before LDClient.init has no events to deliver.
        } catch (Throwable ignored) {
            // Nothing that goes wrong here is worth losing the crash over.
        }
    }
}
