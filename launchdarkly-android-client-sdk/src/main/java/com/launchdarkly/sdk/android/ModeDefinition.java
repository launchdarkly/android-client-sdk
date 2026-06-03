package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Defines the initializers and synchronizers for a single {@link ConnectionMode}.
 * Each instance is a pure data holder — it stores {@link DataSourceBuilder} factories
 * but does not create any concrete initializer or synchronizer objects.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * <p>
 * At build time, {@code FDv2DataSourceBuilder} resolves each {@link DataSourceBuilder}
 * into a {@link FDv2DataSource.DataSourceFactory} by partially applying the
 * {@link com.launchdarkly.sdk.android.subsystems.DataSourceBuildInputs}.
 *
 * @see ConnectionMode
 * @see ResolvedModeDefinition
 */
public final class ModeDefinition {

    private final List<DataSourceBuilder<Initializer>> initializers;
    private final List<DataSourceBuilder<Synchronizer>> synchronizers;
    private final DataSourceBuilder<Synchronizer> fdv1FallbackSynchronizer;

    /**
     * Constructs a mode definition with the given initializers, synchronizers,
     * and an optional FDv1 fallback synchronizer.
     *
     * @param initializers  the initializer builders, in priority order
     * @param synchronizers the synchronizer builders, in priority order
     * @param fdv1FallbackSynchronizer the FDv1 fallback synchronizer builder, or null if
     *                                 this mode should not support FDv1 fallback
     */
    public ModeDefinition(
            @NonNull List<DataSourceBuilder<Initializer>> initializers,
            @NonNull List<DataSourceBuilder<Synchronizer>> synchronizers,
            @Nullable DataSourceBuilder<Synchronizer> fdv1FallbackSynchronizer
    ) {
        this.initializers = Collections.unmodifiableList(new ArrayList<>(initializers));
        this.synchronizers = Collections.unmodifiableList(new ArrayList<>(synchronizers));
        this.fdv1FallbackSynchronizer = fdv1FallbackSynchronizer;
    }

    /**
     * Returns the initializer builders for this mode.
     *
     * @return an unmodifiable list of initializer builders
     */
    @NonNull
    public List<DataSourceBuilder<Initializer>> getInitializers() {
        return initializers;
    }

    /**
     * Returns the synchronizer builders for this mode.
     *
     * @return an unmodifiable list of synchronizer builders
     */
    @NonNull
    public List<DataSourceBuilder<Synchronizer>> getSynchronizers() {
        return synchronizers;
    }

    /**
     * Returns the FDv1 fallback synchronizer builder for this mode, or null if this
     * mode does not support FDv1 fallback.
     *
     * @return the FDv1 fallback synchronizer builder, or null
     */
    @Nullable
    public DataSourceBuilder<Synchronizer> getFdv1FallbackSynchronizer() {
        return fdv1FallbackSynchronizer;
    }
}
