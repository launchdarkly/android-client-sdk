package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/**
 * FDv2 cache initializer: loads persisted flag data from the local cache as the first
 * step in the initializer chain.
 * <p>
 * Per CONNMODE 4.1.2, the cache initializer returns data with {@code persist=false}
 * and {@link Selector#EMPTY} (no selector), so the orchestrator continues to the next
 * initializer (polling) to obtain a verified selector from the server. This provides
 * immediate flag values from cache while the network initializer fetches fresh data.
 * <p>
 * A cache miss is reported as an {@link FDv2SourceResult.Status#interrupted} status,
 * causing the orchestrator to move to the next initializer without delay.
 */
final class FDv2CacheInitializer implements Initializer {

    @Nullable
    private final PersistentDataStoreWrapper.ReadOnlyPerEnvironmentData envData;
    private final LDContext context;
    private final Executor executor;
    private final LDLogger logger;
    private final LDAwaitFuture<FDv2SourceResult> shutdownFuture = new LDAwaitFuture<>();

    FDv2CacheInitializer(
            @Nullable PersistentDataStoreWrapper.ReadOnlyPerEnvironmentData envData,
            @NonNull LDContext context,
            @NonNull Executor executor,
            @NonNull LDLogger logger
    ) {
        this.envData = envData;
        this.context = context;
        this.executor = executor;
        this.logger = logger;
    }

    @Override
    @NonNull
    public Future<FDv2SourceResult> run() {
        LDAwaitFuture<FDv2SourceResult> resultFuture = new LDAwaitFuture<>();

        executor.execute(() -> {
            try {
                if (envData == null) {
                    logger.debug("No persistent store configured; skipping cache");
                    resultFuture.set(FDv2SourceResult.status(
                            FDv2SourceResult.Status.interrupted(
                                    new LDFailure("No persistent store", LDFailure.FailureType.UNKNOWN_ERROR)),
                            false));
                    return;
                }
                String hashedContextId = LDUtil.urlSafeBase64HashedContextId(context);
                EnvironmentData stored = envData.getContextData(hashedContextId);
                if (stored == null) {
                    logger.debug("Cache miss for context");
                    resultFuture.set(FDv2SourceResult.status(
                            FDv2SourceResult.Status.interrupted(
                                    new LDFailure("No cached data", LDFailure.FailureType.UNKNOWN_ERROR)),
                            false));
                    return;
                }
                Map<String, Flag> flags = stored.getAll();
                ChangeSet<Map<String, Flag>> changeSet = new ChangeSet<>(
                        ChangeSetType.Full,
                        Selector.EMPTY,
                        flags,
                        null,
                        false);
                logger.debug("Cache hit: loaded {} flags for context", flags.size());
                resultFuture.set(FDv2SourceResult.changeSet(changeSet, false));
            } catch (Exception e) {
                logger.warn("Cache initializer failed: {}", e.toString());
                resultFuture.set(FDv2SourceResult.status(
                        FDv2SourceResult.Status.interrupted(e), false));
            }
        });

        return LDFutures.anyOf(shutdownFuture, resultFuture);
    }

    @Override
    public void close() {
        shutdownFuture.set(FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false));
    }
}
