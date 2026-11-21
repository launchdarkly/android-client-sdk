package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.ConnectionMode;
import com.launchdarkly.sdk.android.DataSystemComponents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Configures the data pipeline (initializers and synchronizers) for a single
 * {@link ConnectionMode}.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * <p>
 * <b>Initializers</b> are one-shot data sources that run in order at startup to
 * obtain an initial set of feature flag data. The SDK tries each initializer in
 * sequence until one succeeds.
 * <p>
 * <b>Synchronizers</b> are long-lived data sources that keep the feature flag data
 * up to date after initialization. The SDK uses the first synchronizer and falls
 * back to subsequent ones if it encounters errors.
 * <p>
 * The <b>FDv1 fallback synchronizer</b> is an FDv1 polling synchronizer that the SDK
 * switches to when LaunchDarkly directs it to fall back to FDv1. A customized mode keeps
 * the SDK's default FDv1 fallback for that mode unless you configure it with
 * {@link #fdv1FallbackSynchronizer(FDv1FallbackSynchronizerEntry)} or remove it with
 * {@link #disableFdv1Fallback()}.
 * <p>
 * Obtain an instance from {@link com.launchdarkly.sdk.android.DataSystemComponents#customMode()},
 * configure it, and pass it to
 * {@link DataSystemBuilder#customizeConnectionMode(ConnectionMode, ConnectionModeBuilder)}:
 * <pre><code>
 *     DataSystemComponents.customMode()
 *         .initializers(DataSystemComponents.pollingInitializer())
 *         .synchronizers(
 *             DataSystemComponents.streamingSynchronizer(),
 *             DataSystemComponents.pollingSynchronizer())
 * </code></pre>
 *
 * @see DataSystemBuilder
 * @see DataSystemComponents
 */
public class ConnectionModeBuilder {

    private final List<InitializerEntry> initializerEntries = new ArrayList<>();
    private final List<SynchronizerEntry> synchronizerEntries = new ArrayList<>();
    @Nullable
    private FDv1FallbackSynchronizerEntry fdv1FallbackSynchronizerEntry;
    private boolean fdv1FallbackDisabled;

    /**
     * Sets the initializers for this connection mode.
     * <p>
     * Initializers run in order. The SDK advances to the next initializer if one
     * fails or returns partial data. Any previously configured initializers are
     * replaced.
     * <p>
     * Use factory methods in {@link DataSystemComponents} to obtain entry instances:
     * <pre><code>
     *     builder.initializers(DataSystemComponents.pollingInitializer())
     * </code></pre>
     *
     * @param initializers the initializer entries, in priority order
     * @return this builder
     */
    @SafeVarargs
    public final ConnectionModeBuilder initializers(@NonNull InitializerEntry... initializers) {
        this.initializerEntries.clear();
        this.initializerEntries.addAll(Arrays.asList(initializers));
        return this;
    }

    /**
     * Sets the synchronizers for this connection mode.
     * <p>
     * Synchronizers keep data up to date after initialization. The SDK uses the
     * first synchronizer and falls back to subsequent ones on error. Any previously
     * configured synchronizers are replaced.
     * <p>
     * Use factory methods in {@link DataSystemComponents} to obtain entry instances:
     * <pre><code>
     *     builder.synchronizers(
     *         DataSystemComponents.streamingSynchronizer(),
     *         DataSystemComponents.pollingSynchronizer())
     * </code></pre>
     *
     * @param synchronizers the synchronizer entries, in priority order
     * @return this builder
     */
    @SafeVarargs
    public final ConnectionModeBuilder synchronizers(@NonNull SynchronizerEntry... synchronizers) {
        this.synchronizerEntries.clear();
        this.synchronizerEntries.addAll(Arrays.asList(synchronizers));
        return this;
    }

    /**
     * Configures the FDv1 fallback synchronizer for this connection mode, replacing the
     * SDK's default for the mode. This undoes any earlier call to {@link #disableFdv1Fallback()}.
     * <p>
     * Use {@link DataSystemComponents#fdv1FallbackSynchronizer()} to obtain an entry:
     * <pre><code>
     *     builder.fdv1FallbackSynchronizer(
     *         DataSystemComponents.fdv1FallbackSynchronizer()
     *             .pollIntervalMillis(600_000))
     * </code></pre>
     *
     * @param fdv1FallbackSynchronizer the FDv1 fallback synchronizer entry
     * @return this builder
     */
    public ConnectionModeBuilder fdv1FallbackSynchronizer(
            @NonNull FDv1FallbackSynchronizerEntry fdv1FallbackSynchronizer) {
        this.fdv1FallbackSynchronizerEntry = fdv1FallbackSynchronizer;
        this.fdv1FallbackDisabled = false;
        return this;
    }

    /**
     * Removes the FDv1 fallback synchronizer from this connection mode, so the SDK does not
     * fall back to FDv1 in this mode. This undoes any earlier call to
     * {@link #fdv1FallbackSynchronizer(FDv1FallbackSynchronizerEntry)}.
     * <p>
     * If you neither call this method nor configure an FDv1 fallback synchronizer, the mode
     * keeps the SDK's default FDv1 fallback.
     *
     * @return this builder
     */
    public ConnectionModeBuilder disableFdv1Fallback() {
        this.fdv1FallbackSynchronizerEntry = null;
        this.fdv1FallbackDisabled = true;
        return this;
    }

    /**
     * Returns the configured initializer entries as an unmodifiable list.
     *
     * @return the initializer entries
     */
    @NonNull
    public List<InitializerEntry> getInitializerEntries() {
        return Collections.unmodifiableList(initializerEntries);
    }

    /**
     * Returns the configured synchronizer entries as an unmodifiable list.
     *
     * @return the synchronizer entries
     */
    @NonNull
    public List<SynchronizerEntry> getSynchronizerEntries() {
        return Collections.unmodifiableList(synchronizerEntries);
    }

    /**
     * Returns the configured FDv1 fallback synchronizer entry, or null if none was configured.
     *
     * @return the FDv1 fallback synchronizer entry, or null
     */
    @Nullable
    public FDv1FallbackSynchronizerEntry getFdv1FallbackSynchronizerEntry() {
        return fdv1FallbackSynchronizerEntry;
    }

    /**
     * Returns true if {@link #disableFdv1Fallback()} removed the FDv1 fallback from this mode.
     *
     * @return true if the FDv1 fallback is disabled for this mode
     */
    public boolean isFdv1FallbackDisabled() {
        return fdv1FallbackDisabled;
    }
}
