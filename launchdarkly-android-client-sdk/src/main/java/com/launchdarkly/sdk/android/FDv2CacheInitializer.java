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

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/**
 * FDv2 cache initializer: loads persisted flag data from the local cache.
 * <p>
 * Per CONNMODE 4.1.2, a cache hit returns data with {@code persist=false} and
 * {@link Selector#EMPTY} (no selector).
 * <p>
 * All non-hit outcomes — cache miss, missing persistent store, and exceptions during
 * cache read — are returned as a {@link ChangeSetType#None} changeset, signaling
 * "no data available" rather than an error. A corrupt or unreadable cache is
 * semantically equivalent to an empty cache: neither provides usable data.
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
                    resultFuture.set(FDv2SourceResult.changeSet(new ChangeSet<>(
                            ChangeSetType.None,
                            Selector.EMPTY,
                            Collections.emptyMap(),
                            null,
                            false), false));
                    return;
                }
                String hashedContextId = LDUtil.urlSafeBase64HashedContextId(context);
                EnvironmentData stored = envData.getContextData(hashedContextId);
                if (stored == null) {
                    logger.debug("Cache miss for context");
                    resultFuture.set(FDv2SourceResult.changeSet(new ChangeSet<>(
                            ChangeSetType.None,
                            Selector.EMPTY,
                            Collections.emptyMap(),
                            null,
                            false), false));
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
                resultFuture.set(FDv2SourceResult.changeSet(new ChangeSet<>(
                        ChangeSetType.None,
                        Selector.EMPTY,
                        Collections.emptyMap(),
                        null,
                        false), false));
            }
        });

        return LDFutures.anyOf(shutdownFuture, resultFuture);
    }

    @Override
    public void close() {
        shutdownFuture.set(FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false));
    }
}
