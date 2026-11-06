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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /**
     * Default fallback timeout of 2 minutes.
     */
    private static final long DEFAULT_FALLBACK_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(2);

    /**
     * Default recovery timeout of 5 minutes.
     */
    private static final long DEFAULT_RECOVERY_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(5);

    /**
     * Default base of the park delay that follows an unexpected failure.
     */
    private static final long DEFAULT_PARK_INITIAL_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(5);

    /**
     * Default maximum of the park delay.
     */
    private static final long DEFAULT_PARK_MAX_DELAY_MILLIS = TimeUnit.HOURS.toMillis(1);

    /**
     * Default base of the failover delay that follows a park.
     */
    private static final long DEFAULT_FAILOVER_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Default duration of a fallback to FDv1, when the directive does not give one.
     */
    private static final long DEFAULT_FDV1_FALLBACK_TTL_MILLIS = TimeUnit.HOURS.toMillis(1);

    private final List<DataSourceFactory<Initializer>> cacheInitializers;
    private final SourceManager sourceManager;
    private final long fallbackTimeoutMillis;
    private final long recoveryTimeoutMillis;
    private final long parkInitialDelayMillis;
    private final long parkMaxDelayMillis;
    private final long failoverDelayMillis;
    private final long defaultFDv1FallbackTtlMillis;
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
     * Convenience constructor using default timeouts and delays.
     * See {@link #FDv2DataSource(LDContext, List, List, DataSourceFactory, DataSourceUpdateSinkV2,
     * ScheduledExecutorService, LDLogger, long, long, long, long, long, long)} for parameter documentation.
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
                DEFAULT_FALLBACK_TIMEOUT_MILLIS,
                DEFAULT_RECOVERY_TIMEOUT_MILLIS,
                DEFAULT_PARK_INITIAL_DELAY_MILLIS,
                DEFAULT_PARK_MAX_DELAY_MILLIS,
                DEFAULT_FAILOVER_DELAY_MILLIS,
                DEFAULT_FDV1_FALLBACK_TTL_MILLIS);
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
     * @param fallbackTimeoutMillis      milliseconds of INTERRUPTED state before falling back to the
     *                                   next synchronizer
     * @param recoveryTimeoutMillis      milliseconds before attempting to recover to the primary
     *                                   synchronizer
     * @param parkInitialDelayMillis     base of the park delay, in milliseconds
     * @param parkMaxDelayMillis         maximum of the park delay, in milliseconds
     * @param failoverDelayMillis        base of the failover delay, in milliseconds
     * @param defaultFDv1FallbackTtlMillis duration of a fallback to FDv1, in milliseconds
     */
    FDv2DataSource(
            @NonNull LDContext evaluationContext,
            @NonNull List<DataSourceFactory<Initializer>> initializers,
            @NonNull List<DataSourceFactory<Synchronizer>> synchronizers,
            @Nullable DataSourceFactory<Synchronizer> fdv1FallbackSynchronizer,
            @NonNull DataSourceUpdateSinkV2 dataSourceUpdateSink,
            @NonNull ScheduledExecutorService sharedExecutor,
            @NonNull LDLogger logger,
            long fallbackTimeoutMillis,
            long recoveryTimeoutMillis,
            long parkInitialDelayMillis,
            long parkMaxDelayMillis,
            long failoverDelayMillis,
            long defaultFDv1FallbackTtlMillis
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
        this.fallbackTimeoutMillis = fallbackTimeoutMillis;
        this.recoveryTimeoutMillis = recoveryTimeoutMillis;
        this.parkInitialDelayMillis = parkInitialDelayMillis;
        this.parkMaxDelayMillis = parkMaxDelayMillis;
        this.failoverDelayMillis = failoverDelayMillis;
        this.defaultFDv1FallbackTtlMillis = defaultFDv1FallbackTtlMillis;
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

                if (!sourceManager.hasFDv2Synchronizers() && !sourceManager.hasAvailableSynchronizers()) {
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
                if (result.isFdv1Fallback()) {
                    if (result.getResultType() == SourceResultType.CHANGE_SET) {
                        ChangeSet<Map<String, DataModel.Flag>> changeSet = result.getChangeSet();
                        if (changeSet != null) {
                            sink.apply(context, changeSet);
                            anyDataReceived = true;
                        }
                    }
                    if (anyDataReceived) {
                        sink.setStatus(DataSourceState.VALID, null);
                        tryCompleteStart(true, null);
                    }
                    fallBackToFDv1(
                            sink,
                            jittered(defaultFDv1FallbackTtlMillis),
                            result.getStatus() != null ? result.getStatus().getError() : null);
                    return;
                }

                switch (result.getResultType()) {
                    case CHANGE_SET:
                        ChangeSet<Map<String, DataModel.Flag>> changeSet = result.getChangeSet();
                        if (changeSet != null) {
                            sink.apply(context, changeSet);
                            if (changeSet.getType() != ChangeSetType.None) {
                                anyDataReceived = true;
                            }
                            // A non-empty selector means the payload is fully current; the
                            // initializer is done and synchronizers can take over from here.
                            if (!changeSet.getSelector().isEmpty()) {
                                sink.setStatus(DataSourceState.VALID, null);
                                tryCompleteStart(true, null);
                                logger.info("Initialized via '{}'.", initializerName);
                                return;
                            }
                            // Empty selector: partial data received, keep trying remaining initializers.
                            logger.debug(
                                    "Initializer '{}' returned data with an empty selector; continuing with remaining initializers.",
                                    initializerName
                            );
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

    /**
     * Waits for the future to complete, or until the given time.
     *
     * @param future the future to wait for
     * @param time   the time to stop waiting, or null to wait until the future completes
     * @return true if the future completed
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    private static boolean awaitUntil(@NonNull Future<?> future, @Nullable MonotonicTime time)
            throws InterruptedException {
        try {
            if (time == null) {
                future.get();
            } else {
                future.get(Math.max(0, MonotonicTime.now().nanosUntil(time)), TimeUnit.NANOSECONDS);
            }
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException | CancellationException e) {
            // The future completed with an error, which the caller gets from the future.
            return true;
        }
    }

    /**
     * Gets the earliest of the given times.
     *
     * @param times the times, any of which may be null
     * @return the earliest time, or null if every time is null
     */
    @Nullable
    private static MonotonicTime earliest(MonotonicTime... times) {
        MonotonicTime earliest = null;
        for (MonotonicTime time : times) {
            if (time != null && (earliest == null || time.isBefore(earliest))) {
                earliest = time;
            }
        }
        return earliest;
    }

    /**
     * Waits for the given time, or until the data source is stopped.
     *
     * @param millis the time to wait, in milliseconds
     * @return true if the full time passed, or false if the data source was stopped
     */
    private boolean waitUnlessStopped(long millis) {
        try {
            shutdownCause.get(Math.max(0, millis), TimeUnit.MILLISECONDS);
            return false;
        } catch (TimeoutException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    /**
     * Subtracts a random jitter of up to half of the given delay.
     *
     * @param delayMillis the delay before jitter, in milliseconds
     * @return the delay after jitter, in milliseconds
     */
    private static long jittered(long delayMillis) {
        return delayMillis - ThreadLocalRandom.current().nextLong(delayMillis / 2 + 1);
    }

    /**
     * Computes the park delay for a synchronizer.
     *
     * @param parkCount the park count of the synchronizer, including the current failure
     * @return the park delay, in milliseconds
     */
    private long parkDelayMillis(int parkCount) {
        double delayMillis = Math.min(
                parkInitialDelayMillis * Math.pow(2, parkCount - 1),
                parkMaxDelayMillis);
        return jittered((long) delayMillis);
    }

    /**
     * Parks the active synchronizer after an unexpected failure.
     *
     * @param synchronizerName the name of the active synchronizer, or null if it failed to build
     * @param error            the error that caused the park
     */
    private void parkActiveSynchronizer(@Nullable String synchronizerName, @Nullable Throwable error) {
        long parkDelayMillis = parkDelayMillis(sourceManager.incrementCurrentSynchronizerParkCount());
        sourceManager.parkCurrentSynchronizer(MonotonicTime.now().plusMillis(parkDelayMillis));
        logger.warn(
                "{} is backing off for {}s after an unexpected error ({}).",
                synchronizerName != null ? "Synchronizer '" + synchronizerName + "'"
                        : "A synchronizer that failed to build",
                TimeUnit.MILLISECONDS.toSeconds(parkDelayMillis),
                detailForThrowable(error));
    }

    /**
     * Falls back to FDv1 for the given time. If no FDv1 fallback synchronizer is configured, no synchronizer runs
     * until the fallback ends.
     *
     * @param sink             the sink for status updates
     * @param fallbackTtlMillis how long the fallback lasts, in milliseconds
     * @param error            the error that came with the directive, or null
     */
    private void fallBackToFDv1(
            @NonNull DataSourceUpdateSinkV2 sink,
            long fallbackTtlMillis,
            @Nullable Throwable error
    ) {
        sourceManager.startFDv1Fallback(MonotonicTime.now().plusMillis(fallbackTtlMillis));
        if (sourceManager.hasFDv1Fallback()) {
            logger.info(FDV1_FALLBACK_MESSAGE);
        } else {
            logger.warn("Fallback to FDv1 was requested, but no FDv1 fallback synchronizer is configured.");
            sink.setStatus(DataSourceState.INTERRUPTED, error);
        }
        logger.info("FDv2 retry scheduled in {}s.", TimeUnit.MILLISECONDS.toSeconds(fallbackTtlMillis));
    }

    /**
     * Gets the next available synchronizer, builds it, and sets it active. If the FDv1 fallback has expired, this
     * first makes the FDv2 synchronizers available again. While no synchronizer is available, this waits for the
     * earliest unpark time or the end of the FDv1 fallback, whichever comes first. A synchronizer that fails to build
     * is parked, and the next available synchronizer is tried after the failover delay.
     *
     * @param sink the sink for status updates
     * @return the active synchronizer, or null if the data source was stopped
     */
    @Nullable
    private Synchronizer buildNextAvailableSynchronizer(@NonNull DataSourceUpdateSinkV2 sink) {
        while (!stopCalled.get()) {
            if (sourceManager.endFDv1FallbackIfExpired()) {
                logger.info("Fallback TTL elapsed, restarting FDv2 data sources.");
            }
            Synchronizer synchronizer;
            try {
                synchronizer = sourceManager.getNextAvailableSynchronizerAndSetActive();
            } catch (RuntimeException e) {
                sink.setStatus(DataSourceState.INTERRUPTED, e);
                parkActiveSynchronizer(null, e);
                if (!waitUnlessStopped(jittered(failoverDelayMillis))) {
                    return null;
                }
                continue;
            }
            if (synchronizer != null) {
                return synchronizer;
            }
            if (stopCalled.get()) {
                return null;
            }

            MonotonicTime earliestUnparkTime = sourceManager.getEarliestUnparkTime();
            MonotonicTime fallbackEndTime = sourceManager.getFDv1FallbackEndTime();
            long waitMillis;
            if (earliestUnparkTime == null && fallbackEndTime == null) {
                // This should never happen.
                waitMillis = parkInitialDelayMillis;
                logger.error("No synchronizer can be started; checking again in {}s.",
                        TimeUnit.MILLISECONDS.toSeconds(waitMillis));
            } else if (earliestUnparkTime == null) {
                waitMillis = TimeUnit.NANOSECONDS.toMillis(MonotonicTime.now().nanosUntil(fallbackEndTime));
                logger.warn("No synchronizer is available until the FDv1 fallback ends in {}s.",
                        Math.max(0, TimeUnit.MILLISECONDS.toSeconds(waitMillis)));
            } else {
                MonotonicTime earliest = earliest(earliestUnparkTime, fallbackEndTime);
                waitMillis = TimeUnit.NANOSECONDS.toMillis(MonotonicTime.now().nanosUntil(earliest));
                logger.warn("All synchronizers are backing off for at least {}s.",
                        Math.max(0, TimeUnit.MILLISECONDS.toSeconds(waitMillis)));
            }
            // No synchronizer is running until the wait ends.
            sink.setStatus(DataSourceState.INTERRUPTED, null);
            if (!waitUnlessStopped(waitMillis)) {
                return null;
            }
            // Start the most-preferred synchronizer that is no longer parked.
            sourceManager.resetSourceIndex();
        }
        return null;
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
            Synchronizer synchronizer = buildNextAvailableSynchronizer(sink);
            while (synchronizer != null) {
                String synchronizerName = synchronizer.name();
                logger.info("Synchronizer '{}' is starting.", synchronizerName);
                resetSynchronizerStatusDedupe();
                boolean parked = false;
                try {
                    boolean running = true;
                    // Fallback and recovery apply only when another synchronizer can take over.
                    boolean canFallBack = sourceManager.getAvailableSynchronizerCount() > 1;
                    boolean canRecover = canFallBack && !sourceManager.isPrimeSynchronizer();
                    MonotonicTime recoveryTime = canRecover
                            ? MonotonicTime.now().plusMillis(recoveryTimeoutMillis) : null;
                    MonotonicTime fallbackTime = null;
                    // True once the loop has seen the deadline pass.
                    boolean recoveryMet = false;
                    boolean fallbackMet = false;
                    // The result the loop is waiting for, or null if it has not asked the synchronizer for one.
                    Future<FDv2SourceResult> nextFuture = null;
                    while (running) {
                        if (nextFuture == null) {
                            nextFuture = synchronizer.next();
                        }

                        // Wait for the next result, or for the next deadline that the loop has not seen pass.
                        MonotonicTime wakeTime = earliest(
                                recoveryMet ? null : recoveryTime,
                                fallbackMet ? null : fallbackTime,
                                sourceManager.getEarliestUnparkTime(),
                                sourceManager.getFDv1FallbackEndTime());
                        if (awaitUntil(nextFuture, wakeTime)) {
                            FDv2SourceResult result = nextFuture.get();
                            // The pending result has arrived.
                            nextFuture = null;

                            if (result.isResetThresholdMet()) {
                                sourceManager.resetCurrentSynchronizerParkCount();
                            }

                            switch (result.getResultType()) {
                                case CHANGE_SET:
                                    resetSynchronizerStatusDedupe();
                                    // The synchronizer is healthy, so it no longer needs to fall back.
                                    fallbackTime = null;
                                    fallbackMet = false;
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
                                                // The fallback timeout runs from the first interruption.
                                                if (canFallBack && fallbackTime == null) {
                                                    fallbackTime = MonotonicTime.now().plusMillis(fallbackTimeoutMillis);
                                                }
                                                sink.setStatus(DataSourceState.INTERRUPTED, status.getError());
                                                break;
                                            case SHUTDOWN:
                                                maybeLogSynchronizerStatusChange(
                                                        synchronizer.name(),
                                                        status.getState()
                                                );
                                                if (stopCalled.get()) {
                                                    // Stopping the data source shut down the synchronizer.
                                                    logger.debug("Synchronizer shutdown.");
                                                    return;
                                                }
                                                // The synchronizer shut down by itself, so park it.
                                                parkActiveSynchronizer(synchronizer.name(), status.getError());
                                                parked = true;
                                                running = false;
                                                sink.setStatus(DataSourceState.INTERRUPTED, status.getError());
                                                break;
                                            case TERMINAL_ERROR:
                                                maybeLogSynchronizerStatusChange(
                                                        synchronizer.name(),
                                                        status.getState()
                                                );
                                                parkActiveSynchronizer(synchronizer.name(), status.getError());
                                                parked = true;
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
                            if (result.isFdv1Fallback()) {
                                if (sourceManager.isCurrentSynchronizerFDv1Fallback()) {
                                    // A directive during the FDv1 fallback replaces its TTL, and FDv1 keeps running.
                                    long fallbackTtlMillis = jittered(defaultFDv1FallbackTtlMillis);
                                    sourceManager.startFDv1Fallback(MonotonicTime.now().plusMillis(fallbackTtlMillis));
                                    logger.info("FDv2 retry scheduled in {}s.",
                                            TimeUnit.MILLISECONDS.toSeconds(fallbackTtlMillis));
                                } else {
                                    // A directive to fall back to FDv1 ends the run of an FDv2 synchronizer.
                                    fallBackToFDv1(
                                            sink,
                                            jittered(defaultFDv1FallbackTtlMillis),
                                            result.getStatus() != null ? result.getStatus().getError() : null);
                                    // The directive takes over, so there is no failover delay after a park.
                                    parked = false;
                                    running = false;
                                }
                            }
                        }
                        if (!running) {
                            break;
                        }

                        // Check every deadline against the current time.
                        MonotonicTime now = MonotonicTime.now();
                        sourceManager.unparkExpiredSynchronizers();
                        if (sourceManager.endFDv1FallbackIfExpired()) {
                            logger.info("Fallback TTL elapsed, restarting FDv2 data sources.");
                            break;
                        }
                        boolean recoveryJustMet = !recoveryMet && recoveryTime != null && !now.isBefore(recoveryTime);
                        boolean fallbackJustMet = !fallbackMet && fallbackTime != null && !now.isBefore(fallbackTime);
                        recoveryMet = recoveryMet || recoveryJustMet;
                        fallbackMet = fallbackMet || fallbackJustMet;

                        // Recovery takes precedence over fallback.
                        if (recoveryMet && sourceManager.hasMorePreferredAvailableSynchronizer()) {
                            // For recovery, start the search for the next synchronizer at the first one.
                            sourceManager.resetSourceIndex();
                            logger.info(
                                    "Recovery condition met, moving from synchronizer '{}' to primary synchronizer.",
                                    synchronizer.name()
                            );
                            break;
                        }
                        if (fallbackMet && sourceManager.hasOtherAvailableSynchronizer()) {
                            logger.info(
                                    "Fallback condition met, falling back from synchronizer '{}'.",
                                    synchronizer.name()
                            );
                            break;
                        }

                        // Keep running this synchronizer.
                        if (fallbackJustMet) {
                            logger.info(
                                    "Fallback condition met, but no other synchronizer is available; continuing with synchronizer '{}'.",
                                    synchronizer.name()
                            );
                        }
                        if (recoveryJustMet) {
                            logger.info(
                                    "Recovery condition met, but no more-preferred synchronizer is available; continuing with synchronizer '{}'.",
                                    synchronizer.name()
                            );
                        }
                    }
                } catch (ExecutionException | RuntimeException e) {
                    Throwable failure = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
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
                    parkActiveSynchronizer(synchronizer.name(), failure);
                    parked = true;
                } catch (InterruptedException e) {
                    sink.setStatus(DataSourceState.INTERRUPTED, e);
                    Thread.currentThread().interrupt();
                    return;
                }
                // The run of this synchronizer has ended, so stop it.
                sourceManager.closeActiveSource();

                if (parked && !waitUnlessStopped(jittered(failoverDelayMillis))) {
                    break;
                }
                synchronizer = buildNextAvailableSynchronizer(sink);
            }
        } catch (Exception e) {
            logger.error("Unexpected error in data source: {}", e.toString());
        } finally {
            sourceManager.close();
        }
    }
}
