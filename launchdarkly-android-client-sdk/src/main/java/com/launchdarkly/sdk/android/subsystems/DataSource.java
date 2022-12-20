package com.launchdarkly.sdk.android.subsystems;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.LDContext;
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
 * {@link DataSourceUpdateSink} component (as part of {@link ClientContext}, which is a write-only
 * abstraction of the SDK state. The SDK never requests feature flag data from the
 * {@link DataSource}-- it only looks at the last known data that was pushed into the state.
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
     * @param resultCallback called when the data source has successfully acquired the initial data,
     *                       or if an error has occurred
     */
    void start(@NonNull Callback<Boolean> resultCallback);

    /**
     * Tells the data source to stop.
     * @param completionCallback called once it has completely stopped (this is allowed to be
     *                           asynchronous because it might involve network operations that can't
     *                           be done on the main thread)
     */
    void stop(@NonNull Callback<Void> completionCallback);

    /**
     * The SDK calls this method to determine whether it needs to stop the current data source and
     * start a new one after a state transition.
     * <p>
     * State transitions include going from foreground to background or vice versa, or changing the
     * evaluation context. The SDK will not call this method unless at least one of those types of
     * transitions has happened.
     * <p>
     * If this method returns true, the SDK considers the current data source to be no longer valid,
     * stops it, and asks the ComponentConfigurer to create a new one.
     * <p>
     * If this method returns false, the SDK retains the current data source.
     *
     * @param newInBackground true if the application is now in the background
     * @param newEvaluationContext the new evaluation context
     * @return true if the data source should be recreated
     * @since 4.1.0
     */
    default boolean needsRefresh(
            boolean newInBackground,
            LDContext newEvaluationContext
    ) {
        return true;
    }
}
