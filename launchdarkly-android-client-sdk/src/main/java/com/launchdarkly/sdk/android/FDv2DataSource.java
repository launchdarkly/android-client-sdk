package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.DataModel;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.android.subsystems.DataSourceState;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
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
final class FDv2DataSource implements ModeAware {

    /**
     * Factory for creating Initializer or Synchronizer instances.
     */
    public interface DataSourceFactory<T> {
        T build();
    }

    /**
     * A resolved mode definition holding factories that are ready to use (already bound to
     * their ClientContext). Produced by FDv2DataSourceBuilder from ComponentConfigurer entries.
     */
    static final class ResolvedModeDefinition {
        private final List<DataSourceFactory<Initializer>> initializers;
        private final List<DataSourceFactory<Synchronizer>> synchronizers;

        ResolvedModeDefinition(
                @NonNull List<DataSourceFactory<Initializer>> initializers,
                @NonNull List<DataSourceFactory<Synchronizer>> synchronizers
        ) {
            this.initializers = Collections.unmodifiableList(new ArrayList<>(initializers));
            this.synchronizers = Collections.unmodifiableList(new ArrayList<>(synchronizers));
        }

        @NonNull
        List<DataSourceFactory<Initializer>> getInitializers() {
            return initializers;
        }

        @NonNull
        List<DataSourceFactory<Synchronizer>> getSynchronizers() {
            return synchronizers;
        }
    }

    private final LDLogger logger;
    private final LDContext evaluationContext;
    private final DataSourceUpdateSinkV2 dataSourceUpdateSink;
    private final Map<ConnectionMode, ResolvedModeDefinition> modeTable;
    private volatile SourceManager sourceManager;
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

    /**
     * Convenience constructor using default fallback and recovery timeouts.
     * See {@link #FDv2DataSource(LDContext, List, List, DataSourceUpdateSinkV2,
     * ScheduledExecutorService, LDLogger, long, long)} for parameter documentation.
     */
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

    /**
     * @param evaluationContext    the context to evaluate flags for
     * @param initializers         factories for one-shot initializers, tried in order
     * @param synchronizers        factories for recurring synchronizers, tried in order
     * @param dataSourceUpdateSink sink to apply changesets and status updates to
     * @param sharedExecutor       executor used for internal background tasks; must have at least
     *                             2 threads
     * @param logger               logger
     * @param fallbackTimeoutSeconds  seconds of INTERRUPTED state before falling back to the
     *                                next synchronizer
     * @param recoveryTimeoutSeconds  seconds before attempting to recover to the primary
     *                                synchronizer
     */
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
        this.modeTable = null;
        List<SynchronizerFactoryWithState> synchronizerFactoriesWithState = new ArrayList<>();
        for (DataSourceFactory<Synchronizer> factory : synchronizers) {
            synchronizerFactoriesWithState.add(new SynchronizerFactoryWithState(factory));
        }
        this.sourceManager = new SourceManager(synchronizerFactoriesWithState, new ArrayList<>(initializers));
        this.fallbackTimeoutSeconds = fallbackTimeoutSeconds;
        this.recoveryTimeoutSeconds = recoveryTimeoutSeconds;
        this.sharedExecutor = sharedExecutor;
    }

    /**
     * Mode-aware convenience constructor using default fallback and recovery timeouts.
     *
     * @param evaluationContext    the context to evaluate flags for
     * @param modeTable            resolved mode definitions keyed by ConnectionMode
     * @param startingMode         the initial connection mode
     * @param dataSourceUpdateSink sink to apply changesets and status updates to
     * @param sharedExecutor       executor used for internal background tasks
     * @param logger               logger
     */
    FDv2DataSource(
            @NonNull LDContext evaluationContext,
            @NonNull Map<ConnectionMode, ResolvedModeDefinition> modeTable,
            @NonNull ConnectionMode startingMode,
            @NonNull DataSourceUpdateSinkV2 dataSourceUpdateSink,
            @NonNull ScheduledExecutorService sharedExecutor,
            @NonNull LDLogger logger
    ) {
        this(evaluationContext, modeTable, startingMode, dataSourceUpdateSink, sharedExecutor, logger,
                FDv2DataSourceConditions.DEFAULT_FALLBACK_TIMEOUT_SECONDS,
                FDv2DataSourceConditions.DEFAULT_RECOVERY_TIMEOUT_SECONDS);
    }

    /**
     * Mode-aware constructor. The mode table maps each {@link ConnectionMode} to a
     * {@link ResolvedModeDefinition} containing pre-built factories. The starting mode
     * determines the initial set of initializers and synchronizers.
     *
     * @param evaluationContext       the context to evaluate flags for
     * @param modeTable               resolved mode definitions keyed by ConnectionMode
     * @param startingMode            the initial connection mode
     * @param dataSourceUpdateSink    sink to apply changesets and status updates to
     * @param sharedExecutor          executor used for internal background tasks; must have
     *                                at least 2 threads
     * @param logger                  logger
     * @param fallbackTimeoutSeconds  seconds of INTERRUPTED state before falling back
     * @param recoveryTimeoutSeconds  seconds before attempting to recover to the primary
     *                                synchronizer
     */
    FDv2DataSource(
            @NonNull LDContext evaluationContext,
            @NonNull Map<ConnectionMode, ResolvedModeDefinition> modeTable,
            @NonNull ConnectionMode startingMode,
            @NonNull DataSourceUpdateSinkV2 dataSourceUpdateSink,
            @NonNull ScheduledExecutorService sharedExecutor,
            @NonNull LDLogger logger,
            long fallbackTimeoutSeconds,
            long recoveryTimeoutSeconds
    ) {
        this.evaluationContext = evaluationContext;
        this.dataSourceUpdateSink = dataSourceUpdateSink;
        this.logger = logger;
        this.modeTable = Collections.unmodifiableMap(new EnumMap<>(modeTable));
        this.fallbackTimeoutSeconds = fallbackTimeoutSeconds;
        this.recoveryTimeoutSeconds = recoveryTimeoutSeconds;
        this.sharedExecutor = sharedExecutor;

        ResolvedModeDefinition startDef = modeTable.get(startingMode);
        if (startDef == null) {
            throw new IllegalArgumentException("No mode definition for starting mode: " + startingMode);
        }
        List<SynchronizerFactoryWithState> syncFactories = new ArrayList<>();
        for (DataSourceFactory<Synchronizer> factory : startDef.getSynchronizers()) {
            syncFactories.add(new SynchronizerFactoryWithState(factory));
        }
        this.sourceManager = new SourceManager(syncFactories, new ArrayList<>(startDef.getInitializers()));
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
        // Do not reset stopped here: it is initialized false and start() runs once. Resetting would
        // race with a concurrent stop() and could undo it, causing a spurious OFF/exhaustion report.
        LDContext context = evaluationContext;

        sharedExecutor.execute(() -> {
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

                SourceManager sm = sourceManager;
                runSynchronizers(sm, context, dataSourceUpdateSink);
                // Only report exhaustion if the SourceManager was NOT replaced by a
                // concurrent switchMode() call; a mode switch is not an error.
                if (sourceManager == sm) {
                    maybeReportUnexpectedExhaustion("All data source acquisition methods have been exhausted.");
                }
                tryCompleteStart(false, null);
            } catch (Throwable t) {
                logger.warn("FDv2DataSource error: {}", t.toString());
                tryCompleteStart(false, t);
            }
        });
    }

    /**
     * If not stopped, reports OFF with the given message (e.g. exhaustion).
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
        // Caller owns sharedExecutor; we do not shut it down.
        dataSourceUpdateSink.setStatus(DataSourceState.OFF, null);
        completionCallback.onSuccess(null);
    }

    @Override
    public boolean needsRefresh(boolean newInBackground, @NonNull LDContext newEvaluationContext) {
        // Mode-aware data sources handle foreground/background transitions via switchMode(),
        // so only a context change requires a full teardown/rebuild (to re-run initializers).
        return !newEvaluationContext.equals(evaluationContext);
    }

    /**
     * Switches to a new connection mode by tearing down the current synchronizers and
     * starting the new mode's synchronizers on the background executor. Initializers are
     * NOT re-run (spec CONNMODE 2.0.1).
     * <p>
     * Expected to be called from a single thread (ConnectivityManager's listener). The
     * field swap is not atomic; concurrent calls from multiple threads could leave an
     * intermediate SourceManager unclosed.
     */
    @Override
    public void switchMode(@NonNull ConnectionMode newMode) {
        if (modeTable == null) {
            logger.warn("switchMode({}) called but no mode table configured", newMode);
            return;
        }
        if (stopped.get()) {
            return;
        }
        ResolvedModeDefinition def = modeTable.get(newMode);
        if (def == null) {
            logger.error("switchMode({}) failed: no definition found", newMode);
            return;
        }

        // Build new SourceManager with the mode's synchronizer factories.
        // Initializers are NOT included — spec 2.0.1: mode switch does not re-run initializers.
        List<SynchronizerFactoryWithState> syncFactories = new ArrayList<>();
        for (DataSourceFactory<Synchronizer> factory : def.getSynchronizers()) {
            syncFactories.add(new SynchronizerFactoryWithState(factory));
        }
        SourceManager newManager = new SourceManager(
                syncFactories, Collections.<DataSourceFactory<Initializer>>emptyList());

        // Swap the source manager and close the old one to interrupt its active source.
        SourceManager oldManager = sourceManager;
        sourceManager = newManager;
        if (oldManager != null) {
            oldManager.close();
        }

        // Run the new mode's synchronizers on the background thread.
        LDContext context = evaluationContext;
        sharedExecutor.execute(() -> {
            try {
                if (!newManager.hasAvailableSynchronizers()) {
                    logger.debug("Mode {} has no synchronizers; data source idle", newMode);
                    return;
                }
                runSynchronizers(newManager, context, dataSourceUpdateSink);
                // Report exhaustion only if we weren't replaced by another switchMode().
                if (sourceManager == newManager && !stopped.get()) {
                    maybeReportUnexpectedExhaustion(
                            "All synchronizers exhausted after mode switch to " + newMode);
                }
            } catch (Throwable t) {
                logger.warn("FDv2DataSource error after mode switch to {}: {}", newMode, t.toString());
            }
        });
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
            @NonNull SourceManager sm,
            @NonNull LDContext context,
            @NonNull DataSourceUpdateSinkV2 sink
    ) {
        try {
            Synchronizer synchronizer = sm.getNextAvailableSynchronizerAndSetActive();
            while (synchronizer != null) {
                if (stopped.get()) {
                    return;
                }
                int synchronizerCount = sm.getAvailableSynchronizerCount();
                boolean isPrime = sm.isPrimeSynchronizer();
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
                                        sm.resetSourceIndex();
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
                                                sm.blockCurrentSynchronizer();
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
                synchronizer = sm.getNextAvailableSynchronizerAndSetActive();
            }
        } finally {
            sm.close();
        }
    }
}
