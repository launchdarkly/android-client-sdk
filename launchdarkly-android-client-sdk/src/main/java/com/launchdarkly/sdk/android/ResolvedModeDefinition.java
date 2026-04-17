package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.Collections;
import java.util.List;

/**
 * A fully resolved mode definition containing pre-built initializers and zero-arg
 * factories for synchronizers. This is the result of resolving a {@link ModeDefinition}'s
 * {@link com.launchdarkly.sdk.android.subsystems.DataSourceBuilder} entries against
 * a {@link com.launchdarkly.sdk.android.subsystems.DataSourceBuildInputs}.
 * <p>
 * Initializers are built eagerly so that {@link FDv2DataSource} can run pre-startup
 * initializers synchronously before dispatching to the executor.
 * <p>
 * Instances are immutable and created by {@code FDv2DataSourceBuilder} at build time.
 * <p>
 * Package-private — not part of the public SDK API.
 *
 * @see ModeDefinition
 */
final class ResolvedModeDefinition {

    private final List<Initializer> initializers;
    private final List<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizerFactories;
    private final FDv2DataSource.DataSourceFactory<Synchronizer> fdv1FallbackSynchronizerFactory;

    ResolvedModeDefinition(
            @NonNull List<Initializer> initializers,
            @NonNull List<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizerFactories,
            @Nullable FDv2DataSource.DataSourceFactory<Synchronizer> fdv1FallbackSynchronizerFactory
    ) {
        this.initializers = Collections.unmodifiableList(initializers);
        this.synchronizerFactories = Collections.unmodifiableList(synchronizerFactories);
        this.fdv1FallbackSynchronizerFactory = fdv1FallbackSynchronizerFactory;
    }

    @NonNull
    List<Initializer> getInitializers() {
        return initializers;
    }

    @NonNull
    List<FDv2DataSource.DataSourceFactory<Synchronizer>> getSynchronizerFactories() {
        return synchronizerFactories;
    }

    @Nullable
    FDv2DataSource.DataSourceFactory<Synchronizer> getFdv1FallbackSynchronizerFactory() {
        return fdv1FallbackSynchronizerFactory;
    }
}
