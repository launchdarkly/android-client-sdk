package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.json.SerializationException;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * FDv1 polling synchronizer used as a fallback when the server signals that FDv2 endpoints
 * are unavailable via the {@code x-ld-fd-fallback} response header.
 * <p>
 * Delegates the actual HTTP fetch to a {@link FeatureFetcher} (the same transport used by the
 * production FDv1 polling data source) and converts the response into {@link FDv2SourceResult}
 * objects so it can be used as a drop-in synchronizer within the FDv2 data source pipeline.
 */
final class FDv1PollingSynchronizer implements Synchronizer {

    private final LDContext evaluationContext;
    private final FeatureFetcher fetcher;
    private final LDLogger logger;

    private final LDAsyncQueue<FDv2SourceResult> resultQueue = new LDAsyncQueue<>();
    private final LDAwaitFuture<FDv2SourceResult> shutdownFuture = new LDAwaitFuture<>();

    private volatile ScheduledFuture<?> scheduledTask;
    private final Object taskLock = new Object();

    /**
     * @param evaluationContext  the context to evaluate flags for
     * @param fetcher            the HTTP transport for FDv1 polling requests
     * @param executor           scheduler for recurring poll tasks
     * @param initialDelayMillis delay before the first poll in milliseconds
     * @param pollIntervalMillis delay between the end of one poll and the start of the next
     * @param logger             logger
     */
    FDv1PollingSynchronizer(
            @NonNull LDContext evaluationContext,
            @NonNull FeatureFetcher fetcher,
            @NonNull ScheduledExecutorService executor,
            long initialDelayMillis,
            long pollIntervalMillis,
            @NonNull LDLogger logger) {
        this.evaluationContext = evaluationContext;
        this.fetcher = fetcher;
        this.logger = logger;

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
            FDv2SourceResult result = doPoll();

            if (result.getResultType() == SourceResultType.STATUS) {
                FDv2SourceResult.Status status = result.getStatus();
                if (status != null && status.getState() == SourceSignal.TERMINAL_ERROR) {
                    synchronized (taskLock) {
                        if (scheduledTask != null) {
                            scheduledTask.cancel(false);
                            scheduledTask = null;
                        }
                    }
                    shutdownFuture.set(result);
                    return;
                }
            }

            resultQueue.put(result);
        } catch (RuntimeException e) {
            LDUtil.logExceptionAtErrorLevel(logger, e, "Unexpected exception in FDv1 polling synchronizer task");
            resultQueue.put(FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(e), false));
        }
    }

    /**
     * Fetches flags via FDv1 polling and converts the result to an {@link FDv2SourceResult}.
     * <p>
     * All result/error processing happens inside the {@link Callback} so the future carries a
     * fully-formed {@link FDv2SourceResult}. This keeps application-level error classification
     * (e.g. {@link LDInvalidResponseCodeFailure}) at the callback layer rather than unwrapping
     * it from an {@link ExecutionException}.
     */
    private FDv2SourceResult doPoll() {
        LDAwaitFuture<FDv2SourceResult> resultFuture = new LDAwaitFuture<>();

        fetcher.fetch(evaluationContext, new Callback<String>() {
            @Override
            public void onSuccess(String json) {
                try {
                    logger.debug("FDv1 fallback polling response received");
                    EnvironmentData envData = EnvironmentData.fromJson(json);
                    Map<String, Flag> flags = envData.getAll();

                    ChangeSet<Map<String, Flag>> changeSet = new ChangeSet<>(
                            ChangeSetType.Full,
                            Selector.EMPTY,
                            flags,
                            null,
                            true);
                    resultFuture.set(FDv2SourceResult.changeSet(changeSet, false));
                } catch (SerializationException e) {
                    LDUtil.logExceptionAtErrorLevel(logger, e, "FDv1 fallback polling failed to parse response");
                    LDFailure failure = new LDFailure(
                            "FDv1 fallback: invalid JSON response", e, LDFailure.FailureType.INVALID_RESPONSE_BODY);
                    resultFuture.set(FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(failure), false));
                }
            }

            @Override
            public void onError(Throwable e) {
                if (e instanceof LDInvalidResponseCodeFailure) {
                    int code = ((LDInvalidResponseCodeFailure) e).getResponseCode();
                    if (code == 400) {
                        logger.error("Received 400 response when fetching flag values. " +
                                "Please check recommended R8 and/or ProGuard settings");
                    }
                    boolean recoverable = LDUtil.isHttpErrorRecoverable(code);
                    logger.warn("FDv1 fallback polling failed with HTTP {}", code);
                    LDFailure failure = new LDInvalidResponseCodeFailure(
                            "FDv1 fallback polling request failed", e, code, recoverable);
                    if (!recoverable) {
                        resultFuture.set(FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(failure), false));
                    } else {
                        resultFuture.set(FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(failure), false));
                    }
                } else if (e instanceof IOException) {
                    LDUtil.logExceptionAtErrorLevel(logger, e, "FDv1 fallback polling failed with network error");
                    resultFuture.set(FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(e), false));
                } else {
                    LDUtil.logExceptionAtErrorLevel(logger, e, "FDv1 fallback polling failed");
                    resultFuture.set(FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(e), false));
                }
            }
        });

        try {
            return resultFuture.get();
        } catch (InterruptedException e) {
            return FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(e), false);
        } catch (ExecutionException e) {
            // Should not happen — all callback paths call set(), never setException()
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LDUtil.logExceptionAtErrorLevel(logger, cause, "FDv1 fallback polling failed unexpectedly");
            return FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(cause), false);
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
        shutdownFuture.set(FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false));
        try {
            fetcher.close();
        } catch (IOException ignored) {
        }
    }
}
