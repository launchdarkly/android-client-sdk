package com.launchdarkly.sdk.android.subsystems;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.LDConfig;
import com.launchdarkly.sdk.android.LDConfig.Builder;

import java.io.Closeable;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Interface for an object that receives updates to feature flags from LaunchDarkly.
 * <p>
 * This component uses a push model. When it is created, the SDK will provide a reference to a
 * {@link DataSourceUpdateSink} component, which is a write-only abstraction of the SDK state.
 * The SDK never requests feature flag data from the {@link DataSource}, it only looks at the last
 * known data that was pushed into the state.
 * <p>
 * Each {@code LDClient} instance maintains exactly one active data source instance. It stops and
 * discards the active data source whenever it needs to create a new one due to a significant state
 * change, such as if the evaluation context is changed with {@code identify()}, or if the SDK goes
 * online after previously being offline, or if the foreground/background state changes.
 *
 * @since 3.3.0
 * @see Components#streamingDataSource()
 * @see Components#pollingDataSource()
 * @see LDConfig.Builder#dataSource(ComponentConfigurer)
 */
public interface DataSource {
    /**
     * Initializes the data source. This is called only once per instance.
     * @param resultCallback
     */
    void start(@NonNull Callback<Boolean> resultCallback);

    void stop(@NonNull Callback<Void> completionCallback);
}
