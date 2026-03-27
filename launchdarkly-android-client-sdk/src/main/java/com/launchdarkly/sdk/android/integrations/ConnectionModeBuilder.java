package com.launchdarkly.sdk.android.integrations;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.ConnectionMode;
import com.launchdarkly.sdk.android.DataSystemComponents;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

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

    private final List<DataSourceBuilder<Initializer>> initializers = new ArrayList<>();
    private final List<DataSourceBuilder<Synchronizer>> synchronizers = new ArrayList<>();

    /**
     * Sets the initializers for this connection mode.
     * <p>
     * Initializers run in order. The SDK advances to the next initializer if one
     * fails or returns partial data. Any previously configured initializers are
     * replaced.
     * <p>
     * Use factory methods in {@link DataSystemComponents} to obtain builder instances:
     * <pre><code>
     *     builder.initializers(DataSystemComponents.pollingInitializer())
     * </code></pre>
     *
     * @param initializers the initializer builders, in priority order
     * @return this builder
     */
    @SafeVarargs
    public final ConnectionModeBuilder initializers(@NonNull DataSourceBuilder<Initializer>... initializers) {
        this.initializers.clear();
        this.initializers.addAll(Arrays.asList(initializers));
        return this;
    }

    /**
     * Sets the synchronizers for this connection mode.
     * <p>
     * Synchronizers keep data up to date after initialization. The SDK uses the
     * first synchronizer and falls back to subsequent ones on error. Any previously
     * configured synchronizers are replaced.
     * <p>
     * Use factory methods in {@link DataSystemComponents} to obtain builder instances:
     * <pre><code>
     *     builder.synchronizers(
     *         DataSystemComponents.streamingSynchronizer(),
     *         DataSystemComponents.pollingSynchronizer())
     * </code></pre>
     *
     * @param synchronizers the synchronizer builders, in priority order
     * @return this builder
     */
    @SafeVarargs
    public final ConnectionModeBuilder synchronizers(@NonNull DataSourceBuilder<Synchronizer>... synchronizers) {
        this.synchronizers.clear();
        this.synchronizers.addAll(Arrays.asList(synchronizers));
        return this;
    }

    /**
     * Returns the configured initializers as an unmodifiable list.
     *
     * @return the initializer builders
     */
    @NonNull
    public List<DataSourceBuilder<Initializer>> getInitializers() {
        return Collections.unmodifiableList(initializers);
    }

    /**
     * Returns the configured synchronizers as an unmodifiable list.
     *
     * @return the synchronizer builders
     */
    @NonNull
    public List<DataSourceBuilder<Synchronizer>> getSynchronizers() {
        return Collections.unmodifiableList(synchronizers);
    }
}
