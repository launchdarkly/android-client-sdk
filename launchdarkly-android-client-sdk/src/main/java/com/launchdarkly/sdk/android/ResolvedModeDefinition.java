package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.Collections;
import java.util.List;

/**
 * A fully resolved mode definition containing zero-arg factories for initializers
 * and synchronizers. This is the result of resolving a {@link ModeDefinition}'s
 * {@link com.launchdarkly.sdk.android.subsystems.ComponentConfigurer} entries against
 * a {@link com.launchdarkly.sdk.android.subsystems.ClientContext}.
 * <p>
 * Instances are immutable and created by {@code FDv2DataSourceBuilder} at build time.
 * {@link ConnectivityManager} passes these to {@link ModeAware#switchMode} when the
 * resolved connection mode changes.
 * <p>
 * Package-private — not part of the public SDK API.
 *
 * @see ModeDefinition
 * @see ModeAware
 */
final class ResolvedModeDefinition {

    private final List<FDv2DataSource.DataSourceFactory<Initializer>> initializerFactories;
    private final List<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizerFactories;

    ResolvedModeDefinition(
            @NonNull List<FDv2DataSource.DataSourceFactory<Initializer>> initializerFactories,
            @NonNull List<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizerFactories
    ) {
        this.initializerFactories = Collections.unmodifiableList(initializerFactories);
        this.synchronizerFactories = Collections.unmodifiableList(synchronizerFactories);
    }

    @NonNull
    List<FDv2DataSource.DataSourceFactory<Initializer>> getInitializerFactories() {
        return initializerFactories;
    }

    @NonNull
    List<FDv2DataSource.DataSourceFactory<Synchronizer>> getSynchronizerFactories() {
        return synchronizerFactories;
    }
}
