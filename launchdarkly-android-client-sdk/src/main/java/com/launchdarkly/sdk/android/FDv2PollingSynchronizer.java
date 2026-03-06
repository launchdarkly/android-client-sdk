package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * FDv2 polling synchronizer: polls at a fixed interval and delivers each result via
 * {@link #next()}.
 * <p>
 * An optional initial delay can defer the first poll (e.g. when switching from streaming to
 * background polling, the SDK may want to wait before issuing the first request). Each poll
 * result is placed in an {@link LDAsyncQueue}; {@link #next()} races the queue against a
 * shutdown future so that {@link #close()} always unblocks the caller.
 * <p>
 * On TERMINAL_ERROR the scheduled task is cancelled and the shutdown future is completed so
 * that subsequent {@link #next()} calls also return the terminal result immediately.
 */
final class FDv2PollingSynchronizer extends FDv2PollingBase implements Synchronizer {
    private final SelectorSource selectorSource;

    private final LDAsyncQueue<FDv2SourceResult> resultQueue = new LDAsyncQueue<>();
    private final LDAwaitFuture<FDv2SourceResult> shutdownFuture = new LDAwaitFuture<>();

    private volatile ScheduledFuture<?> scheduledTask;
    private final Object taskLock = new Object();

    /**
     * @param requestor           the FDv2 requestor used to perform each poll
     * @param selectorSource      source of the current selector, sent as the {@code basis} param
     * @param executor            scheduler for recurring poll tasks; should use background-priority
     *                            threads to match the behaviour of {@link FDv2PollingInitializer}
     * @param initialDelayMillis  delay before the first poll in milliseconds; use {@code 0} to
     *                            poll immediately (e.g. foreground polling). A non-zero value is
     *                            used when transitioning from streaming to background polling so
     *                            the first request is not issued straight away.
     * @param pollIntervalMillis  delay between the completion of one poll and the start of the next,
     *                            in milliseconds
     * @param logger              logger
     */
    FDv2PollingSynchronizer(
            @NonNull FDv2Requestor requestor,
            @NonNull SelectorSource selectorSource,
            @NonNull ScheduledExecutorService executor,
            long initialDelayMillis,
            long pollIntervalMillis,
            @NonNull LDLogger logger) {
        super(requestor, logger);
        this.selectorSource = selectorSource;

        synchronized (taskLock) {
            scheduledTask = executor.scheduleWithFixedDelay(
                    this::pollAndEnqueue,
                    initialDelayMillis,
                    pollIntervalMillis,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void pollAndEnqueue() {
        try {
            FDv2SourceResult result = doPoll(selectorSource.getSelector(), false);

            if (result.getResultType() == SourceResultType.STATUS) {
                FDv2SourceResult.Status status = result.getStatus();
                if (status != null && status.getState() == SourceSignal.TERMINAL_ERROR) {
                    synchronized (taskLock) {
                        if (scheduledTask != null) {
                            scheduledTask.cancel(false);
                            scheduledTask = null;
                        }
                    }
                    closeRequestor();
                    // Deliver terminal result via shutdown future so all current and future
                    // next() calls return immediately.
                    shutdownFuture.set(result);
                    return;
                }
            }

            resultQueue.put(result);
        } catch (RuntimeException e) {
            // An unexpected exception must not escape: ScheduledExecutorService silently
            // cancels the task on any unchecked exception, ending all future polls with no
            // error signal. Log the exception and return normally so the next interval fires.
            LDUtil.logExceptionAtErrorLevel(logger, e, "Unexpected exception in FDv2 polling synchronizer task");
        }
    }

    @Override
    @NonNull
    public Future<FDv2SourceResult> next() {
        return LDFutures.anyOf(shutdownFuture, resultQueue.take());
    }

    @Override
    public void close() {
        synchronized (taskLock) {
            if (scheduledTask != null) {
                scheduledTask.cancel(false);
                scheduledTask = null;
            }
        }
        closeRequestor();
        shutdownFuture.set(FDv2SourceResult.status(FDv2SourceResult.Status.shutdown()));
    }
}
