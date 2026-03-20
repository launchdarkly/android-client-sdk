package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Builds an {@link FDv2DataSource} and resolves the mode table from
 * {@link ComponentConfigurer} factories into zero-arg {@link FDv2DataSource.DataSourceFactory}
 * instances. The resolved table is stored and exposed via {@link #getResolvedModeTable()}
 * so that {@link ConnectivityManager} can perform mode-to-definition lookups when switching modes.
 * <p>
 * Package-private — not part of the public SDK API.
 */
class FDv2DataSourceBuilder implements ComponentConfigurer<DataSource> {

    private final Map<ConnectionMode, ModeDefinition> modeTable;
    private final ConnectionMode startingMode;
    private final ModeResolutionTable resolutionTable;
    private final boolean automaticModeSwitching;

    private ConnectionMode activeMode;
    private boolean includeInitializers = true; // false during mode switches to skip initializers (CONNMODE 2.0.1)
    private ScheduledExecutorService sharedExecutor;

    FDv2DataSourceBuilder() {
        this(makeDefaultModeTable(), ConnectionMode.STREAMING, ModeResolutionTable.MOBILE, true);
    }

    FDv2DataSourceBuilder(
            @NonNull Map<ConnectionMode, ModeDefinition> modeTable,
            @NonNull ConnectionMode startingMode
    ) {
        this(modeTable, startingMode, ModeResolutionTable.MOBILE, true);
    }

    FDv2DataSourceBuilder(
            @NonNull Map<ConnectionMode, ModeDefinition> modeTable,
            @NonNull ConnectionMode startingMode,
            @NonNull ModeResolutionTable resolutionTable
    ) {
        this(modeTable, startingMode, resolutionTable, true);
    }

    FDv2DataSourceBuilder(
            @NonNull Map<ConnectionMode, ModeDefinition> modeTable,
            @NonNull ConnectionMode startingMode,
            @NonNull ModeResolutionTable resolutionTable,
            boolean automaticModeSwitching
    ) {
        this.modeTable = Collections.unmodifiableMap(new LinkedHashMap<>(modeTable));
        this.startingMode = startingMode;
        this.resolutionTable = resolutionTable;
        this.automaticModeSwitching = automaticModeSwitching;
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

    /**
     * Returns whether automatic mode switching is enabled.
     *
     * @return true if automatic mode switching is enabled
     */
    boolean isAutomaticModeSwitching() {
        return automaticModeSwitching;
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
        ConnectionMode mode = activeMode != null ? activeMode : startingMode;

        ModeDefinition modeDef = modeTable.get(mode);
        if (modeDef == null) {
            throw new IllegalStateException(
                    "Mode " + mode + " not found in mode table");
        }

        ResolvedModeDefinition resolved = resolve(modeDef, clientContext);

        DataSourceUpdateSink baseSink = clientContext.getDataSourceUpdateSink();
        if (!(baseSink instanceof DataSourceUpdateSinkV2)) {
            throw new IllegalStateException(
                    "FDv2DataSource requires a DataSourceUpdateSinkV2 implementation");
        }

        if (sharedExecutor == null) {
            sharedExecutor = Executors.newScheduledThreadPool(2);
        }

        List<FDv2DataSource.DataSourceFactory<Initializer>> initFactories =
                includeInitializers ? resolved.getInitializerFactories() : Collections.<FDv2DataSource.DataSourceFactory<Initializer>>emptyList();

        // Reset includeInitializers to default after each build to prevent stale state.
        includeInitializers = true;

        return new FDv2DataSource(
                clientContext.getEvaluationContext(),
                initFactories,
                resolved.getSynchronizerFactories(),
                (DataSourceUpdateSinkV2) baseSink,
                sharedExecutor,
                clientContext.getBaseLogger()
        );
    }

    private static ResolvedModeDefinition resolve(
            ModeDefinition def, ClientContext clientContext
    ) {
        List<FDv2DataSource.DataSourceFactory<Initializer>> initFactories = new ArrayList<>();
        for (ComponentConfigurer<Initializer> configurer : def.getInitializers()) {
            initFactories.add(() -> configurer.build(clientContext));
        }
        List<FDv2DataSource.DataSourceFactory<Synchronizer>> syncFactories = new ArrayList<>();
        for (ComponentConfigurer<Synchronizer> configurer : def.getSynchronizers()) {
            syncFactories.add(() -> configurer.build(clientContext));
        }
        return new ResolvedModeDefinition(initFactories, syncFactories);
    }

    private static Map<ConnectionMode, ModeDefinition> makeDefaultModeTable() {
        return new com.launchdarkly.sdk.android.integrations.DataSystemBuilder()
                .buildModeTable(false);
    }
}
