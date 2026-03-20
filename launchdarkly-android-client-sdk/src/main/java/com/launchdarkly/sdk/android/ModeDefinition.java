package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.Collections;
import java.util.List;

/**
 * Defines the initializers and synchronizers for a single {@link ConnectionMode}.
 * Each instance is a pure data holder — it stores {@link ComponentConfigurer} factories
 * but does not create any concrete initializer or synchronizer objects.
 * <p>
 * At build time, {@code FDv2DataSourceBuilder} resolves each {@link ComponentConfigurer}
 * into a {@link FDv2DataSource.DataSourceFactory} by partially applying the
 * {@link com.launchdarkly.sdk.android.subsystems.ClientContext}.
 *
 * @see ConnectionMode
 * @see ResolvedModeDefinition
 */
public final class ModeDefinition {

    private final List<ComponentConfigurer<Initializer>> initializers;
    private final List<ComponentConfigurer<Synchronizer>> synchronizers;

    /**
     * Constructs a mode definition with the given initializers and synchronizers.
     *
     * @param initializers  the initializer configurers, in priority order
     * @param synchronizers the synchronizer configurers, in priority order
     */
    public ModeDefinition(
            @NonNull List<ComponentConfigurer<Initializer>> initializers,
            @NonNull List<ComponentConfigurer<Synchronizer>> synchronizers
    ) {
        this.initializers = Collections.unmodifiableList(initializers);
        this.synchronizers = Collections.unmodifiableList(synchronizers);
    }

    /**
     * Returns the initializer configurers for this mode.
     *
     * @return an unmodifiable list of initializer configurers
     */
    @NonNull
    public List<ComponentConfigurer<Initializer>> getInitializers() {
        return initializers;
    }

    /**
     * Returns the synchronizer configurers for this mode.
     *
     * @return an unmodifiable list of synchronizer configurers
     */
    @NonNull
    public List<ComponentConfigurer<Synchronizer>> getSynchronizers() {
        return synchronizers;
    }
}
