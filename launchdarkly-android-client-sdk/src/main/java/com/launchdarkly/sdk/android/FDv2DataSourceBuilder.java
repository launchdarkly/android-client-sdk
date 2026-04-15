package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuildInputs;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Builds an {@link FDv2DataSource} and resolves the mode table from
 * {@link DataSourceBuilder} factories into zero-arg {@link FDv2DataSource.DataSourceFactory}
 * instances. The resolved table is stored and exposed via {@link #getResolvedModeTable()}
 * so that {@link ConnectivityManager} can perform mode-to-definition lookups when switching modes.
 * <p>
 * Package-private — not part of the public SDK API.
 */
class FDv2DataSourceBuilder implements ComponentConfigurer<DataSource>, Closeable {

    private final Map<ConnectionMode, ModeDefinition> modeTable;
    private final ConnectionMode startingMode;
    private final ModeResolutionTable resolutionTable;

    private ConnectionMode activeMode;
    private boolean includeInitializers = true; // false during mode switches to skip initializers (CONNMODE 2.0.1)
    private ScheduledExecutorService sharedExecutor;

    FDv2DataSourceBuilder() {
        this(DataSystemComponents.makeDefaultModeTable(), ConnectionMode.STREAMING, ModeResolutionTable.MOBILE);
    }

    FDv2DataSourceBuilder(
            @NonNull Map<ConnectionMode, ModeDefinition> modeTable,
            @NonNull ConnectionMode startingMode
    ) {
        this(modeTable, startingMode, ModeResolutionTable.MOBILE);
    }

    FDv2DataSourceBuilder(
            @NonNull Map<ConnectionMode, ModeDefinition> modeTable,
            @NonNull ConnectionMode startingMode,
            @NonNull ModeResolutionTable resolutionTable
    ) {
        this.modeTable = modeTable;
        this.startingMode = startingMode;
        this.resolutionTable = resolutionTable;
    }

    /**
     * Returns the mode resolution table used to map platform state to connection modes.
     *
     * @return the resolution table
     */
    @NonNull
    ModeResolutionTable getResolutionTable() {
        return resolutionTable;
    }

    @NonNull
    ConnectionMode getStartingMode() {
        return startingMode;
    }

    /**
     * Configures the mode to build for and whether to include initializers.
     * Called by {@link ConnectivityManager} before each {@link #build} call.
     *
     * @param mode               the target connection mode
     * @param includeInitializers true for initial startup / identify, false for mode switches
     *                           (per CONNMODE 2.0.1: mode switches only transition synchronizers)
     */
    void setActiveMode(@NonNull ConnectionMode mode, boolean includeInitializers) {
        this.activeMode = mode;
        this.includeInitializers = includeInitializers;
    }

    /**
     * Returns the raw {@link ModeDefinition} for the given mode, used by
     * {@link ConnectivityManager} for the CSFDV2 5.3.8 equivalence check.
     */
    ModeDefinition getModeDefinition(@NonNull ConnectionMode mode) {
        return modeTable.get(mode);
    }

    @Override
    public DataSource build(ClientContext clientContext) {
        if (sharedExecutor == null) {
            // Pool size 4: supports the FDv2DataSource main loop (1), an active synchronizer
            // such as the streaming event loop (1), FDv2DataSource condition timers for
            // fallback/recovery (up to 2 short-lived scheduled tasks). Only one mode is active
            // at a time (teardown/rebuild), so this pool serves all components.
            sharedExecutor = Executors.newScheduledThreadPool(4);
        }

        ConnectionMode mode = activeMode != null ? activeMode : startingMode;

        ModeDefinition modeDef = modeTable.get(mode);
        if (modeDef == null) {
            throw new IllegalStateException(
                    "Mode " + mode + " not found in mode table");
        }

        DataSourceBuildInputs inputs = makeInputs(clientContext);
        ResolvedModeDefinition resolved = resolve(modeDef, inputs);

        DataSourceUpdateSink baseSink = clientContext.getDataSourceUpdateSink();
        if (!(baseSink instanceof DataSourceUpdateSinkV2)) {
            throw new IllegalStateException(
                    "FDv2DataSource requires a DataSourceUpdateSinkV2 implementation");
        }

        List<FDv2DataSource.DataSourceFactory<Initializer>> initFactories =
                includeInitializers ? resolved.getInitializerFactories() : Collections.<FDv2DataSource.DataSourceFactory<Initializer>>emptyList();

        // Reset includeInitializers to default after each build to prevent stale state.
        includeInitializers = true;

        return new FDv2DataSource(
                clientContext.getEvaluationContext(),
                initFactories,
                resolved.getSynchronizerFactories(),
                resolved.getFdv1FallbackSynchronizerFactory(),
                (DataSourceUpdateSinkV2) baseSink,
                sharedExecutor,
                clientContext.getBaseLogger()
        );
    }

    @Override
    public void close() {
        if (sharedExecutor != null) {
            sharedExecutor.shutdownNow();
            sharedExecutor = null;
        }
    }

    private DataSourceBuildInputs makeInputs(ClientContext clientContext) {
        SelectorSource selectorSource = ClientContextImpl.get(clientContext).getSelectorSource();
        if (selectorSource == null) {
            selectorSource = () -> com.launchdarkly.sdk.fdv2.Selector.EMPTY;
        }
        return new DataSourceBuildInputs(
                clientContext.getEvaluationContext(),
                clientContext.getServiceEndpoints(),
                clientContext.getHttp(),
                clientContext.isEvaluationReasons(),
                selectorSource,
                sharedExecutor,
                ClientContextImpl.get(clientContext).getPlatformState().getCacheDir(),
                clientContext.getBaseLogger()
        );
    }

    private static ResolvedModeDefinition resolve(
            ModeDefinition def, DataSourceBuildInputs inputs
    ) {
        List<FDv2DataSource.DataSourceFactory<Initializer>> initFactories = new ArrayList<>();
        for (DataSourceBuilder<Initializer> builder : def.getInitializers()) {
            initFactories.add(() -> builder.build(inputs));
        }
        List<FDv2DataSource.DataSourceFactory<Synchronizer>> syncFactories = new ArrayList<>();
        for (DataSourceBuilder<Synchronizer> builder : def.getSynchronizers()) {
            syncFactories.add(() -> builder.build(inputs));
        }
        DataSourceBuilder<Synchronizer> fdv1FallbackSynchronizer = def.getFdv1FallbackSynchronizer();
        FDv2DataSource.DataSourceFactory<Synchronizer> fdv1Factory =
                fdv1FallbackSynchronizer != null ? () -> fdv1FallbackSynchronizer.build(inputs) : null;
        return new ResolvedModeDefinition(initFactories, syncFactories, fdv1Factory);
    }
}
