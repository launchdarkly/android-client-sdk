package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.DataModel;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DataSourceState;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.ArrayList;
import java.util.Map;
import java.util.Collections;
import java.util.List;
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
    private final SourceManager sourceManager;
    private final long fallbackTimeoutSeconds;
    private final long recoveryTimeoutSeconds;
    private final ScheduledExecutorService sharedExecutor;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean startCompleted = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /** Result of the first start (null = not yet completed). Used so second start() gets the same result. */
    private volatile Boolean startResult = null;
    private volatile Throwable startError = null;
    private final Object startResultLock = new Object();
    private final List<Callback<Boolean>> pendingStartCallbacks = new ArrayList<>();

    FDv2DataSource(
            @NonNull LDContext evaluationContext,
            @NonNull List<DataSourceFactory<Initializer>> initializers,
            @NonNull List<DataSourceFactory<Synchronizer>> synchronizers,
            @NonNull DataSourceUpdateSinkV2 dataSourceUpdateSink,
            @NonNull ScheduledExecutorService sharedExecutor,
            @NonNull LDLogger logger
    ) {
        this(evaluationContext, initializers, synchronizers, dataSourceUpdateSink, sharedExecutor, logger,
                FDv2DataSourceConditions.DEFAULT_FALLBACK_TIMEOUT_SECONDS,
                FDv2DataSourceConditions.DEFAULT_RECOVERY_TIMEOUT_SECONDS);
    }

    FDv2DataSource(
            @NonNull LDContext evaluationContext,
            @NonNull List<DataSourceFactory<Initializer>> initializers,
            @NonNull List<DataSourceFactory<Synchronizer>> synchronizers,
            @NonNull DataSourceUpdateSinkV2 dataSourceUpdateSink,
            @NonNull ScheduledExecutorService sharedExecutor,
            @NonNull LDLogger logger,
            long fallbackTimeoutSeconds,
            long recoveryTimeoutSeconds
    ) {
        this.evaluationContext = evaluationContext;
        this.dataSourceUpdateSink = dataSourceUpdateSink;
        this.logger = logger;
        List<SynchronizerFactoryWithState> synchronizerFactoriesWithState = new ArrayList<>();
        for (DataSourceFactory<Synchronizer> factory : synchronizers) {
            synchronizerFactoriesWithState.add(new SynchronizerFactoryWithState(factory));
        }
        this.sourceManager = new SourceManager(synchronizerFactoriesWithState, new ArrayList<>(initializers));
        this.fallbackTimeoutSeconds = fallbackTimeoutSeconds;
        this.recoveryTimeoutSeconds = recoveryTimeoutSeconds;
        this.sharedExecutor = sharedExecutor;
    }

    @Override
    public void start(@NonNull Callback<Boolean> resultCallback) {
        synchronized (startResultLock) {
            // Late caller: the first start already finished, so replay its result immediately.
            if (startResult != null) {
                if (startResult) {
                    resultCallback.onSuccess(true);
                } else if (startError != null) {
                    resultCallback.onError(startError);
                } else {
                    resultCallback.onSuccess(false);
                }
                return;
            }

            // Start is still in progress; queue the callback to be fired by tryCompleteStart.
            pendingStartCallbacks.add(resultCallback);
        }

        // Only the first caller spawns the background thread; subsequent callers just queued above.
        if (!started.compareAndSet(false, true)) {
            return;
        }
        stopped.set(false);
        LDContext context = evaluationContext;

        new Thread(() -> {
            try {
                if (!sourceManager.hasAvailableSources()) {
                    logger.info("No initializers or synchronizers; data source will not connect.");
                    dataSourceUpdateSink.setStatus(DataSourceState.VALID, null);
                    tryCompleteStart(true, null);
                    return;
                }

                if (sourceManager.hasInitializers()) {
                    runInitializers(context, dataSourceUpdateSink);
                }

                if (!sourceManager.hasAvailableSynchronizers()) {
                    if (!startCompleted.get()) {
                        maybeReportUnexpectedExhaustion("All initializers exhausted and there are no available synchronizers.");
                    }
                    tryCompleteStart(false, null);
                    return;
                }

                runSynchronizers(context, dataSourceUpdateSink);
                maybeReportUnexpectedExhaustion("All data source acquisition methods have been exhausted.");
                tryCompleteStart(false, null);
            } catch (Throwable t) {
                logger.warn("FDv2DataSource error: {}", t.toString());
                tryCompleteStart(false, t);
            }
        }, "LaunchDarkly-FDv2DataSource").start();
    }

    /**
     * If not stopped, reports OFF with the given message (e.g. exhaustion). Matches java-core maybeReportUnexpectedExhaustion.
     */
    private void maybeReportUnexpectedExhaustion(String message) {
        if (!stopped.get()) {
            dataSourceUpdateSink.setStatus(DataSourceState.OFF, new LDFailure(message, LDFailure.FailureType.UNKNOWN_ERROR));
        }
    }

    /**
     * Records the start result and notifies all callbacks (first and any subsequent start() callers).
     * No-op if start has already completed.
     */
    private void tryCompleteStart(boolean success, Throwable error) {
        // Idempotent: only the first call wins. Later calls (e.g. from runSynchronizers after
        // start already completed via an initializer) are silently ignored.
        if (!startCompleted.compareAndSet(false, true)) {
            return;
        }
        List<Callback<Boolean>> toNotify;
        synchronized (startResultLock) {
            startResult = success;
            startError = error;
            toNotify = new ArrayList<>(pendingStartCallbacks);
            pendingStartCallbacks.clear();
        }
        for (Callback<Boolean> c : toNotify) {
            if (success) {
                c.onSuccess(true);
            } else if (error != null) {
                c.onError(error);
            } else {
                c.onSuccess(false);
            }
        }
    }

    @Override
    public void stop(@NonNull Callback<Void> completionCallback) {
        stopped.set(true);
        sourceManager.close();
        // Caller owns sharedExecutor; we do not shut it down (match java-core).
        dataSourceUpdateSink.setStatus(DataSourceState.OFF, null);
        completionCallback.onSuccess(null);
    }

    private void runInitializers(
            @NonNull LDContext context,
            @NonNull DataSourceUpdateSinkV2 sink
    ) {
        boolean anyDataReceived = false;
        Initializer initializer = sourceManager.getNextInitializerAndSetActive();
        while (initializer != null) {
            if (stopped.get()) {
                return;
            }
            try {
                FDv2SourceResult result = initializer.run().get();

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
                Thread.currentThread().interrupt();
                logger.warn("Initializer interrupted: {}", e.toString());
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

    /** Same as java-core getConditions(): empty if 1 sync; Fallback only if prime; Fallback+Recovery if non-prime. */
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
                                                // Server is shutting down cleanly; exit the entire synchronizer loop.
                                                return;
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
                        }
                    }
                } catch (ExecutionException e) {
                    logger.warn("Synchronizer error: {}", e.getCause() != null ? e.getCause().toString() : e.toString());
                    sink.setStatus(DataSourceState.INTERRUPTED, e.getCause() != null ? e.getCause() : e);
                } catch (CancellationException e) {
                    logger.warn("Synchronizer cancelled: {}", e.toString());
                    sink.setStatus(DataSourceState.INTERRUPTED, e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Synchronizer interrupted: {}", e.toString());
                }
                synchronizer = sourceManager.getNextAvailableSynchronizerAndSetActive();
            }
        } finally {
            sourceManager.close();
        }
    }
}
