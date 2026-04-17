package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.android.subsystems.DataSourceState;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FDv2 data source that runs initializers then synchronizers, applying change sets
 * via {@link DataSourceUpdateSinkV2}. Supports fallback (after INTERRUPTED timeout,
 * switch to next synchronizer) and recovery (when on non-prime synchronizer, try
 * to return to the first after timeout).
 */
final class FDv2DataSource implements DataSource {

    /**
     * Factory for creating Initializer or Synchronizer instances.
     */
    public interface DataSourceFactory<T> {
        T build();
    }

    private final LDLogger logger;
    private final LDContext evaluationContext;
    private final DataSourceUpdateSinkV2 dataSourceUpdateSink;
    private static final String FDV1_FALLBACK_MESSAGE =
            "Server signaled FDv1 fallback; switching to FDv1 polling synchronizer.";

    private final SourceManager sourceManager;
    private final long fallbackTimeoutSeconds;
    private final long recoveryTimeoutSeconds;
    private final ScheduledExecutorService sharedExecutor;

    private final AtomicBoolean startCalled = new AtomicBoolean(false);
    private final AtomicBoolean startCompleted = new AtomicBoolean(false);
    private volatile Boolean startResult = null;
    private volatile Throwable startError = null;
    private final List<Callback<Boolean>> pendingStartCallbacks = new ArrayList<>();
    private final AtomicBoolean stopCalled = new AtomicBoolean(false);
    private final AtomicBoolean stopCompleted = new AtomicBoolean(false);
    private final List<Callback<Void>> pendingStopCallbacks = new ArrayList<>();

    // This future is set by either the worker thread terminating or stop() being called.
    private final LDAwaitFuture<Throwable> shutdownCause = new LDAwaitFuture<>();
    /**
     * Convenience constructor using default fallback and recovery timeouts.
     * See {@link #FDv2DataSource(LDContext, List, List, DataSourceFactory, DataSourceUpdateSinkV2,
     * ScheduledExecutorService, LDLogger, long, long)} for parameter documentation.
     */
    FDv2DataSource(
            @NonNull LDContext evaluationContext,
            @NonNull List<Initializer> initializers,
            @NonNull List<DataSourceFactory<Synchronizer>> synchronizers,
            @Nullable DataSourceFactory<Synchronizer> fdv1FallbackSynchronizer,
            @NonNull DataSourceUpdateSinkV2 dataSourceUpdateSink,
            @NonNull ScheduledExecutorService sharedExecutor,
            @NonNull LDLogger logger
    ) {
        this(evaluationContext, initializers, synchronizers, fdv1FallbackSynchronizer,
                dataSourceUpdateSink, sharedExecutor, logger,
                FDv2DataSourceConditions.DEFAULT_FALLBACK_TIMEOUT_SECONDS,
                FDv2DataSourceConditions.DEFAULT_RECOVERY_TIMEOUT_SECONDS);
    }

    /**
     * @param evaluationContext          the context to evaluate flags for
     * @param initializers               pre-built initializers, tried in order
     * @param synchronizers              factories for recurring synchronizers, tried in order
     * @param fdv1FallbackSynchronizer   factory for the FDv1 fallback synchronizer, or null if none;
     *                                   appended after the regular synchronizers in a blocked state
     *                                   and only activated when the server sends the
     *                                   {@code x-ld-fd-fallback} header
     * @param dataSourceUpdateSink       sink to apply changesets and status updates to
     * @param sharedExecutor             executor used for internal background tasks; must have at least
     *                                   2 threads available for this data source to run properly.
     * @param logger                     logger
     * @param fallbackTimeoutSeconds     seconds of INTERRUPTED state before falling back to the
     *                                   next synchronizer
     * @param recoveryTimeoutSeconds     seconds before attempting to recover to the primary
     *                                   synchronizer
     */
    FDv2DataSource(
            @NonNull LDContext evaluationContext,
            @NonNull List<Initializer> initializers,
            @NonNull List<DataSourceFactory<Synchronizer>> synchronizers,
            @Nullable DataSourceFactory<Synchronizer> fdv1FallbackSynchronizer,
            @NonNull DataSourceUpdateSinkV2 dataSourceUpdateSink,
            @NonNull ScheduledExecutorService sharedExecutor,
            @NonNull LDLogger logger,
            long fallbackTimeoutSeconds,
            long recoveryTimeoutSeconds
    ) {
        this.evaluationContext = evaluationContext;
        this.dataSourceUpdateSink = dataSourceUpdateSink;
        this.logger = logger;

        List<SynchronizerFactoryWithState> allSynchronizers = new ArrayList<>();
        for (DataSourceFactory<Synchronizer> factory : synchronizers) {
            allSynchronizers.add(new SynchronizerFactoryWithState(factory));
        }
        if (fdv1FallbackSynchronizer != null) {
            SynchronizerFactoryWithState fdv1 = new SynchronizerFactoryWithState(fdv1FallbackSynchronizer, true);
            fdv1.block();
            allSynchronizers.add(fdv1);
        }

        this.sourceManager = new SourceManager(allSynchronizers, new ArrayList<>(initializers));
        this.fallbackTimeoutSeconds = fallbackTimeoutSeconds;
        this.recoveryTimeoutSeconds = recoveryTimeoutSeconds;
        this.sharedExecutor = sharedExecutor;
    }

    @Override
    public void start(@NonNull Callback<Boolean> resultCallback) {
        synchronized (pendingStartCallbacks) {
            // Late caller: the first start already finished, so replay its result immediately.
            if (startResult != null) {
                if (startResult) {
                    resultCallback.onSuccess(true);
                } else {
                    resultCallback.onError(startError);
                }
                return;
            }

            // Start is still in progress; queue the callback to be fired by tryCompleteStart.
            pendingStartCallbacks.add(resultCallback);
        }

        // Only the first caller spawns the background thread; subsequent callers just queued above.
        if (!startCalled.compareAndSet(false, true)) {
            return;
        }
        // Do not reset stopped here: it is initialized false and start() runs once. Resetting would
        // race with a concurrent stop() and could undo it, causing a spurious OFF/exhaustion report.
        LDContext context = evaluationContext;

        // Eager pass: run pre-startup initializers synchronously on the calling thread.
        // This ensures cached data is available before the startup timeout begins,
        // matching FDv1 behavior where cache was loaded in ContextDataManager's constructor.
        boolean initializerDataReceived = runInitializers(context, dataSourceUpdateSink, true, false);
        sourceManager.resetInitializerIndex();

        sharedExecutor.execute(() -> {
            try {
                if (!sourceManager.hasAvailableSources()) {
                    logger.info("No initializers or synchronizers; data source will not connect.");
                    dataSourceUpdateSink.setStatus(DataSourceState.VALID, null);
                    tryCompleteStart(true, null);
                    return; // this will go to the finally block and block until stop sets shutdownCause
                }

                // Deferred pass: run non-eager initializers on the executor thread.
                if (sourceManager.hasInitializers()) {
                    runInitializers(context, dataSourceUpdateSink, false, initializerDataReceived);
                }

                if (!sourceManager.hasAvailableSynchronizers()) {
                    if (!startCompleted.get()) {
                        // try to claim this is the cause of the shutdown, but it might have already been set by an intentional stop().
                        shutdownCause.set(new LDFailure("All initializers exhausted and there are no available synchronizers.", LDFailure.FailureType.UNKNOWN_ERROR));
                    }
                    return;
                }

                runSynchronizers(context, dataSourceUpdateSink);
                // try to claim this is the cause of the shutdown, but it might have already been set by an intentional stop().
                shutdownCause.set(new LDFailure("All data source acquisition methods have been exhausted.", LDFailure.FailureType.UNKNOWN_ERROR));
            } catch (Throwable t) {
                logger.warn("FDv2DataSource error: {}", t.toString());
                shutdownCause.set(t);
            } finally {

                // Here we grab the cause of shutdown to report with the OFF status. This is done to ensure that
                // all status callbacks are handled by the worker thread. This future may have been set
                // by this thread itself, but it may have also been set by the stop() call via another thread.
                //
                // This intentionally blocks on this future in certain configurations and that may seem 
                // inefficient, but it simplifies the implementation. Such cases are rare in practice.
                Throwable cause;
                try {
                    cause = shutdownCause.get();
                } catch (Exception e) {
                    cause = e;
                }

                boolean intentional = cause instanceof CancellationException;
                dataSourceUpdateSink.setStatus(DataSourceState.OFF, intentional ? null : cause);
                tryCompleteStart(false, cause); // must always provide cause with false success
                tryCompleteStop();
            }
        });
    }

    /**
     * Records the start result and notifies all callbacks (first and any subsequent start() callers).
     * No-op if start has already completed. If success is false, error is not null.
     */
    private void tryCompleteStart(boolean success, Throwable error) {
        // Idempotent: only the first call wins. Later calls (e.g. from runSynchronizers after
        // start already completed via an initializer) are silently ignored.
        if (!startCompleted.compareAndSet(false, true)) {
            return;
        }
        List<Callback<Boolean>> toNotify;
        synchronized (pendingStartCallbacks) {
            startResult = success;
            startError = error;
            toNotify = new ArrayList<>(pendingStartCallbacks);
            pendingStartCallbacks.clear();
        }
        for (Callback<Boolean> c : toNotify) {
            if (success) {
                c.onSuccess(true);
            } else {
                c.onError(error);
            }
        }
    }

    @Override
    public void stop(@NonNull Callback<Void> completionCallback) {
        synchronized (pendingStopCallbacks) {
            if (stopCompleted.get()) {
                // we have already stopped
                completionCallback.onSuccess(null);
                return;
            }

            // stopping is still in progress; queue the callback to be fired by tryCompleteStop.
            pendingStopCallbacks.add(completionCallback);
        }

        // Only the first call to stop does anything
        if (!stopCalled.compareAndSet(false, true)) {
            return;
        }

        shutdownCause.set(new CancellationException("Data source was stopped intentionally."));
        sourceManager.close(); // unblocks worker thread so it can shutdown

        // If the data source had never started, we need to complete the stop here
        if (!startCalled.get()) {
            tryCompleteStop();
        }
    }

    /**
     * Notifies all stop callbacks (if there are any).
     * No-op if stop has already completed.
     */
    private void tryCompleteStop() {
        // Idempotent: only the first call wins.
        if (!stopCompleted.compareAndSet(false, true)) {
            return;
        }

        List<Callback<Void>> toNotify;
        synchronized (pendingStopCallbacks) {
            toNotify = new ArrayList<>(pendingStopCallbacks);
            pendingStopCallbacks.clear();
        }

        for (Callback<Void> c : toNotify) {
            c.onSuccess(null);
        }
    }

    @Override
    public boolean needsRefresh(boolean newInBackground, @NonNull LDContext newEvaluationContext) {
        // FDv2 background/foreground transitions are handled externally by ConnectivityManager
        // via teardown/rebuild, so only request a rebuild when the evaluation context changes.
        return !evaluationContext.equals(newEvaluationContext);
    }

    private boolean runInitializers(
            @NonNull LDContext context,
            @NonNull DataSourceUpdateSinkV2 sink,
            boolean isRequiredBeforeStartup,
            boolean previousDataReceived
    ) {
        boolean anyDataReceived = previousDataReceived;
        Initializer initializer = sourceManager.getNextInitializerAndSetActive(isRequiredBeforeStartup);
        while (initializer != null) {
            try {
                FDv2SourceResult result = initializer.run().get();

                // FDv1 fallback takes priority over all other result processing.
                // The spec requires honoring the fallback signal from any response,
                // regardless of whether data was included or what the selector state is.
                if (result.isFdv1Fallback() && sourceManager.hasFDv1Fallback()) {
                    if (result.getResultType() == SourceResultType.CHANGE_SET) {
                        ChangeSet<Map<String, DataModel.Flag>> changeSet = result.getChangeSet();
                        if (changeSet != null) {
                            sink.apply(context, changeSet);
                            anyDataReceived = true;
                        }
                    }
                    logger.info(FDV1_FALLBACK_MESSAGE);
                    sourceManager.fdv1Fallback();
                    if (anyDataReceived) {
                        sink.setStatus(DataSourceState.VALID, null);
                        tryCompleteStart(true, null);
                    }
                    return anyDataReceived;
                }

                switch (result.getResultType()) {
                    case CHANGE_SET:
                        ChangeSet<Map<String, DataModel.Flag>> changeSet = result.getChangeSet();
                        if (changeSet != null) {
                            sink.apply(context, changeSet);
                            anyDataReceived = true;
                            // A non-empty selector means the payload is fully current; the
                            // initializer is done and synchronizers can take over from here.
                            if (!changeSet.getSelector().isEmpty()) {
                                sink.setStatus(DataSourceState.VALID, null);
                                tryCompleteStart(true, null);
                                return anyDataReceived;
                            }
                            // Empty selector: partial data received, keep trying remaining initializers.
                        }
                        break;
                    case STATUS:
                        FDv2SourceResult.Status status = result.getStatus();
                        if (status != null) {
                            switch (status.getState()) {
                                case INTERRUPTED:
                                case TERMINAL_ERROR:
                                    sink.setStatus(DataSourceState.INTERRUPTED, status.getError());
                                    break;
                                case SHUTDOWN:
                                case GOODBYE:
                                    break;
                                default:
                                    break;
                            }
                        }
                        break;
                }
            } catch (ExecutionException e) {
                logger.warn("Initializer error: {}", e.getCause() != null ? e.getCause().toString() : e.toString());
                sink.setStatus(DataSourceState.INTERRUPTED, e.getCause() != null ? e.getCause() : e);
            } catch (CancellationException e) {
                logger.warn("Initializer cancelled: {}", e.toString());
                sink.setStatus(DataSourceState.INTERRUPTED, e);
            } catch (InterruptedException e) {
                logger.warn("Initializer interrupted: {}", e.toString());
                sink.setStatus(DataSourceState.INTERRUPTED, e);
                return anyDataReceived;
            }
            initializer = sourceManager.getNextInitializerAndSetActive(isRequiredBeforeStartup);
        }
        // All matching initializers exhausted. If data was received and no synchronizers will
        // follow, consider initialization successful. When synchronizers are available, defer
        // init completion to the synchronizer loop — the synchronizer is the authority on
        // whether the SDK has a verified, up-to-date payload.
        if (anyDataReceived && !sourceManager.hasAvailableSynchronizers()) {
            sink.setStatus(DataSourceState.VALID, null);
            tryCompleteStart(true, null);
        }
        return anyDataReceived;
    }

    private List<FDv2DataSourceConditions.Condition> getConditions(int synchronizerCount, boolean isPrime) {
        if (synchronizerCount <= 1) {
            return Collections.emptyList();
        }
        List<FDv2DataSourceConditions.Condition> list = new ArrayList<>();
        list.add(new FDv2DataSourceConditions.FallbackCondition(sharedExecutor, fallbackTimeoutSeconds));
        if (!isPrime) {
            list.add(new FDv2DataSourceConditions.RecoveryCondition(sharedExecutor, recoveryTimeoutSeconds));
        }
        return list;
    }

    private void runSynchronizers(
            @NonNull LDContext context,
            @NonNull DataSourceUpdateSinkV2 sink
    ) {
        try {
            Synchronizer synchronizer = sourceManager.getNextAvailableSynchronizerAndSetActive();
            while (synchronizer != null) {
                int synchronizerCount = sourceManager.getAvailableSynchronizerCount();
                boolean isPrime = sourceManager.isPrimeSynchronizer();
                try {
                    boolean running = true;
                    try (FDv2DataSourceConditions.Conditions conditions =
                                 new FDv2DataSourceConditions.Conditions(getConditions(synchronizerCount, isPrime))) {
                        while (running) {
                            Future<FDv2SourceResult> nextFuture = synchronizer.next();
                            // Race the next synchronizer result against any active conditions
                            // (fallback/recovery timers). Whichever resolves first wins.
                            Object res = LDFutures.anyOf(conditions.getFuture(), nextFuture).get();

                            if (res instanceof FDv2DataSourceConditions.ConditionType) {
                                FDv2DataSourceConditions.ConditionType ct = (FDv2DataSourceConditions.ConditionType) res;
                                switch (ct) {
                                    case FALLBACK:
                                        logger.debug("Synchronizer {} experienced an interruption; falling back to next synchronizer.",
                                                synchronizer.getClass().getSimpleName());
                                        break;
                                    case RECOVERY:
                                        logger.debug("The data source is attempting to recover to a higher priority synchronizer.");
                                        sourceManager.resetSourceIndex();
                                        break;
                                }
                                running = false;
                                break;
                            }

                            if (!(res instanceof FDv2SourceResult)) {
                                logger.error("Unexpected result type from synchronizer: {}", res != null ? res.getClass().getName() : "null");
                                continue;
                            }

                            FDv2SourceResult result = (FDv2SourceResult) res;
                            // Let conditions observe the result before we act on it so
                            // they can update their internal state (e.g. reset interruption timers).
                            conditions.inform(result);

                            switch (result.getResultType()) {
                                case CHANGE_SET:
                                    ChangeSet<Map<String, DataModel.Flag>> changeSet = result.getChangeSet();
                                    if (changeSet != null) {
                                        sink.apply(context, changeSet);
                                        sink.setStatus(DataSourceState.VALID, null);
                                        tryCompleteStart(true, null);
                                    }
                                    break;
                                case STATUS:
                                    FDv2SourceResult.Status status = result.getStatus();
                                    if (status != null) {
                                        switch (status.getState()) {
                                            case INTERRUPTED:
                                                sink.setStatus(DataSourceState.INTERRUPTED, status.getError());
                                                break;
                                            case SHUTDOWN:
                                                // This synchronizer is shutting down cleanly/intentionally
                                                running = false;
                                                break;
                                            case TERMINAL_ERROR:
                                                // This synchronizer cannot recover; block it so the outer
                                                // loop advances to the next available synchronizer.
                                                sourceManager.blockCurrentSynchronizer();
                                                running = false;
                                                sink.setStatus(DataSourceState.INTERRUPTED, status.getError());
                                                break;
                                            case GOODBYE:
                                                // We let the synchronizer handle this internally.
                                                break;
                                            default:
                                                break;
                                        }
                                    }
                                    break;
                            }

                            // After processing the result, check whether the server signaled
                            // that this environment should fall back to FDv1 (via the
                            // x-ld-fd-fallback response header). We check regardless of
                            // whether the synchronizer is still running — a terminal error
                            // response can still carry the fallback header, and the server's
                            // instruction to use FDv1 should take precedence.
                            if (result.isFdv1Fallback()
                                    && sourceManager.hasFDv1Fallback()
                                    && !sourceManager.isCurrentSynchronizerFDv1Fallback()) {
                                logger.info(FDV1_FALLBACK_MESSAGE);
                                sourceManager.fdv1Fallback();
                                running = false;
                            }
                        }
                    }
                } catch (ExecutionException e) {
                    logger.warn("Synchronizer error: {}", e.getCause() != null ? e.getCause().toString() : e.toString());
                    sink.setStatus(DataSourceState.INTERRUPTED, e.getCause() != null ? e.getCause() : e);
                } catch (CancellationException e) {
                    logger.warn("Synchronizer cancelled: {}", e.toString());
                    sink.setStatus(DataSourceState.INTERRUPTED, e);
                } catch (InterruptedException e) {
                    logger.warn("Synchronizer interrupted: {}", e.toString());
                    sink.setStatus(DataSourceState.INTERRUPTED, e);
                    return;
                }
                synchronizer = sourceManager.getNextAvailableSynchronizerAndSetActive();
            }
        } finally {
            sourceManager.close();
        }
    }
}
