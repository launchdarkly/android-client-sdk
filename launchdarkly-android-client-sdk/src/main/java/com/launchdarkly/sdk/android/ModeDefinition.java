package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.Collections;
import java.util.List;

/**
 * Defines the initializer and synchronizer pipelines for a {@link ConnectionMode}.
 * Each instance is a pure data holder — it stores {@link ComponentConfigurer} factories
 * but does not create any concrete initializer or synchronizer objects.
 * <p>
 * At build time, {@code FDv2DataSourceBuilder} resolves each {@link ComponentConfigurer}
 * into a {@link FDv2DataSource.DataSourceFactory} by partially applying the
 * {@link com.launchdarkly.sdk.android.subsystems.ClientContext}.
 * <p>
 * Package-private — not part of the public SDK API.
 *
 * @see ConnectionMode
 */
final class ModeDefinition {

    private final List<ComponentConfigurer<Initializer>> initializers;
    private final List<ComponentConfigurer<Synchronizer>> synchronizers;

    ModeDefinition(
            @NonNull List<ComponentConfigurer<Initializer>> initializers,
            @NonNull List<ComponentConfigurer<Synchronizer>> synchronizers
    ) {
        this.initializers = Collections.unmodifiableList(initializers);
        this.synchronizers = Collections.unmodifiableList(synchronizers);
    }

    @NonNull
    List<ComponentConfigurer<Initializer>> getInitializers() {
        return initializers;
    }

    @NonNull
    List<ComponentConfigurer<Synchronizer>> getSynchronizers() {
        return synchronizers;
    }
}
