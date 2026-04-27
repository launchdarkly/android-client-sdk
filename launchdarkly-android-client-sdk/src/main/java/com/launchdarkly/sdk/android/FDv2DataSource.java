package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.android.subsystems.DataSourceState;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.InitializerFromCache;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.io.IOException;
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
            "Falling back to an FDv1 fallback synchronizer.";
    private static final String INITIALIZER_ERROR = "Initializer error: {}";
    private static final String INITIALIZER_CANCELLED = "Initializer cancelled: {}";
    private static final String INITIALIZER_INTERRUPTED = "Initializer interrupted: {}";

    private final List<DataSourceFactory<Initializer>> cacheInitializers;
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

    /**
     * Avoid duplicate orchestration logs for the same synchronizer and {@link SourceSignal}.
     */
    private String lastLoggedSynchronizerDedupeName;

    private SourceSignal lastLoggedSynchronizerDedupeStatus;

    // This future is set by either the worker thread terminating or stop() being called.
    private final LDAwaitFuture<Throwable> shutdownCause = new LDAwaitFuture<>();
    /**
     * Convenience constructor using default fallback and recovery timeouts.
     * See {@link #FDv2DataSource(LDContext, List, List, DataSourceFactory, DataSourceUpdateSinkV2,
     * ScheduledExecutorService, LDLogger, long, long)} for parameter documentation.
     */
    FDv2DataSource(
            @NonNull LDContext evaluationContext,
            @NonNull List<DataSourceFactory<Initializer>> initializers,
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
     * @param initializers               factories for one-shot initializers, tried in order
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
            @NonNull List<DataSourceFactory<Initializer>> initializers,
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

        // here we find the index of the first general initializer so we can split the list into cache and general initializers
        int startOfGeneralInitializers = 0;
        while (startOfGeneralInitializers < initializers.size() && initializers.get(startOfGeneralInitializers) instanceof InitializerFromCache) {
            startOfGeneralInitializers++;
        }
        this.cacheInitializers = new ArrayList<>(initializers.subList(0, startOfGeneralInitializers));
        List<DataSourceFactory<Initializer>> generalInitializers = new ArrayList<>(initializers.subList(startOfGeneralInitializers, initializers.size()));

        List<SynchronizerFactoryWithState> allSynchronizers = new ArrayList<>();
        for (DataSourceFactory<Synchronizer> factory : synchronizers) {
            allSynchronizers.add(new SynchronizerFactoryWithState(factory));
        }
        if (fdv1FallbackSynchronizer != null) {
            SynchronizerFactoryWithState fdv1 = new SynchronizerFactoryWithState(fdv1FallbackSynchronizer, true);
            fdv1.block();
            allSynchronizers.add(fdv1);
        }

        // note that the source manager only uses the initializers after the cache initializers and not the cache initializers
        this.sourceManager = new SourceManager(allSynchronizers, generalInitializers);
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

        // This ensures cached data is available before the startup timeout begins,
        // matching FDv1 behavior where cache was loaded in ContextDataManager's constructor.
        // We assume cache initializers cannot return a selector.  If this assumption is invalid in the future,
        // the code in this class must be modified to complete start in such a case.
        runCacheInitializers(context, dataSourceUpdateSink, cacheInitializers);

        sharedExecutor.execute(() -> {
            try {
                if (!sourceManager.hasAvailableSources()) {
                    logger.warn(
                            "LaunchDarkly client will not connect to LaunchDarkly for feature flag data due to no initializers or synchronizers configured."
                    );
                    dataSourceUpdateSink.setStatus(DataSourceState.VALID, null);
                    tryCompleteStart(true, null);
                    return; // this will go to the finally block and block until stop sets shutdownCause
                }

                if (sourceManager.hasInitializers()) {
                    runGeneralInitializers(context, dataSourceUpdateSink);
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

    /**
     * Runs cache initializers that must run before start returns.
     *
     * This was added to maintain parity with Android SDK versions that load cached data
     * synchronously during startup.  When the Android SDK is major versioned and supports
     * specifying what types of data (cached, network) to wait for, this can be removed.
     */
    private void runCacheInitializers(
        @NonNull LDContext context,
        @NonNull DataSourceUpdateSinkV2 sink,
        @NonNull List<DataSourceFactory<Initializer>> cacheInitializers
    ) {
        for (DataSourceFactory<Initializer> factory : cacheInitializers) {
            Initializer initializer = factory.build();
            try {
                FDv2SourceResult result = initializer.run().get();

                switch (result.getResultType()) {
                    case CHANGE_SET:
                        ChangeSet<Map<String, DataModel.Flag>> changeSet = result.getChangeSet();
                        if (changeSet != null) {
                            sink.apply(context, changeSet);
                        }
                        break;
                    case STATUS:
                        // intentionally ignored from cache initializers
                }
            } catch (ExecutionException e) {
                logger.warn(INITIALIZER_ERROR, e.getCause() != null ? e.getCause().toString() : e.toString());
            } catch (CancellationException e) {
                logger.warn(INITIALIZER_CANCELLED, e.toString());
            } catch (InterruptedException e) {
                logger.warn(INITIALIZER_INTERRUPTED, e.toString());
                return;
            } finally {
                try {
                    initializer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void runGeneralInitializers(
            @NonNull LDContext context,
            @NonNull DataSourceUpdateSinkV2 sink
    ) {
        boolean anyDataReceived = false;
        Initializer initializer = sourceManager.getNextInitializerAndSetActive();
        while (initializer != null) {
            String initializerName = initializer.name();
            logger.info("Initializer '{}' is starting.", initializerName);
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
                    return;
                }

                switch (result.getResultType()) {
                    case CHANGE_SET:
                        ChangeSet<Map<String, DataModel.Flag>> changeSet = result.getChangeSet();
                        if (changeSet != null) {
                            sink.apply(context, changeSet);
                            logger.info("Initialized via '{}'.", initializerName);
                            if (changeSet.getType() != ChangeSetType.None) {
                                anyDataReceived = true;
                            }
                            // A non-empty selector means the payload is fully current; the
                            // initializer is done and synchronizers can take over from here.
                            if (!changeSet.getSelector().isEmpty()) {
                                sink.setStatus(DataSourceState.VALID, null);
                                tryCompleteStart(true, null);
                                return;
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
                                    logger.warn(
                                        "Initializer '{}' failed: {}",
                                        initializerName,
                                        detailForThrowable(status.getError())
                                    );
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
                Throwable failure = e.getCause() != null ? e.getCause() : e;
                sink.setStatus(DataSourceState.INTERRUPTED, failure);
                if (!stopCalled.get()
                        && !(failure instanceof CancellationException)
                        && !(failure instanceof InterruptedException)) {
                    logger.error(
                            "Error running initializer '{}': {}",
                            initializerName,
                            failure.getMessage() != null ? failure.getMessage() : LogValues.exceptionSummary(failure)
                    );
                }
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            } catch (CancellationException e) {
                sink.setStatus(DataSourceState.INTERRUPTED, e);
            } catch (InterruptedException e) {
                sink.setStatus(DataSourceState.INTERRUPTED, e);
                Thread.currentThread().interrupt();
                return;
            }
            initializer = sourceManager.getNextInitializerAndSetActive();
        }
        // All initializers exhausted. If any gave us data (even without a final selector),
        // consider initialization successful and let synchronizers keep the data current.
        if (anyDataReceived) {
            sink.setStatus(DataSourceState.VALID, null);
            tryCompleteStart(true, null);
        }
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

    private static String detailForThrowable(@Nullable Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        if (message != null && !message.isEmpty()) {
            return message;
        }
        return error.toString();
    }

    private void resetSynchronizerStatusDedupe() {
        lastLoggedSynchronizerDedupeName = null;
        lastLoggedSynchronizerDedupeStatus = null;
    }

    private void maybeLogSynchronizerStatusChange(@Nullable String sourceName, @NonNull SourceSignal state) {
        if (state == SourceSignal.GOODBYE) {
            return;
        }
        if (sourceName != null
                && sourceName.equals(lastLoggedSynchronizerDedupeName)
                && state == lastLoggedSynchronizerDedupeStatus) {
            return;
        }
        lastLoggedSynchronizerDedupeName = sourceName;
        lastLoggedSynchronizerDedupeStatus = state;
        logger.info("Synchronizer '{}' reported status: {}.", sourceName, state.name());
    }

    private void runSynchronizers(
            @NonNull LDContext context,
            @NonNull DataSourceUpdateSinkV2 sink
    ) {
        try {
            Synchronizer synchronizer = sourceManager.getNextAvailableSynchronizerAndSetActive();
            while (synchronizer != null) {
                String synchronizerName = synchronizer.name();
                logger.info("Synchronizer '{}' is starting.", synchronizerName);
                resetSynchronizerStatusDedupe();
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
                                        logger.info(
                                                "Fallback condition met, falling back from synchronizer '{}'.",
                                                synchronizer.name()
                                        );
                                        break;
                                    case RECOVERY:
                                        sourceManager.resetSourceIndex();
                                        logger.info(
                                                "Recovery condition met, moving from synchronizer '{}' to primary synchronizer.",
                                                synchronizer.name()
                                        );
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
                                    resetSynchronizerStatusDedupe();
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
                                                maybeLogSynchronizerStatusChange(
                                                        synchronizer.name(),
                                                        status.getState()
                                                );
                                                sink.setStatus(DataSourceState.INTERRUPTED, status.getError());
                                                break;
                                            case SHUTDOWN:
                                                maybeLogSynchronizerStatusChange(
                                                        synchronizer.name(),
                                                        status.getState()
                                                );
                                                logger.debug("Synchronizer shutdown.");
                                                // Android: advance to the next synchronizer (differs from server,
                                                // which exits the synchronizer phase on SHUTDOWN).
                                                running = false;
                                                break;
                                            case TERMINAL_ERROR:
                                                maybeLogSynchronizerStatusChange(
                                                        synchronizer.name(),
                                                        status.getState()
                                                );
                                                sourceManager.blockCurrentSynchronizer();
                                                logger.warn(
                                                        "Synchronizer '{}' permanently failed and will not be used again until application restart.",
                                                        synchronizer.name()
                                                );
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
                    Throwable failure = e.getCause() != null ? e.getCause() : e;
                    sink.setStatus(DataSourceState.INTERRUPTED, failure);
                    if (!stopCalled.get()
                            && !(failure instanceof CancellationException)
                            && !(failure instanceof InterruptedException)) {
                        logger.error(
                                "Error running synchronizer '{}': {}",
                                synchronizer.name(),
                                failure.getMessage() != null ? failure.getMessage() : LogValues.exceptionSummary(failure)
                        );
                    }
                    if (failure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                } catch (CancellationException e) {
                    sink.setStatus(DataSourceState.INTERRUPTED, e);
                } catch (InterruptedException e) {
                    sink.setStatus(DataSourceState.INTERRUPTED, e);
                    Thread.currentThread().interrupt();
                    return;
                }
                synchronizer = sourceManager.getNextAvailableSynchronizerAndSetActive();
            }
            if (!stopCalled.get()) {
                logger.warn("No more synchronizers available.");
            }
        } catch (Exception e) {
            logger.error("Unexpected error in data source: {}", e.toString());
        } finally {
            sourceManager.close();
        }
    }
}
