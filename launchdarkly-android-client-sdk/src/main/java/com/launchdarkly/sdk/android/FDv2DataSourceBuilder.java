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

    private Map<ConnectionMode, ResolvedModeDefinition> resolvedModeTable;

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

    @NonNull
    ConnectionMode getStartingMode() {
        return startingMode;
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

    /**
     * Returns the resolved mode table after {@link #build} has been called.
     * Each entry maps a {@link ConnectionMode} to a {@link ResolvedModeDefinition}
     * containing zero-arg factories that capture the {@link ClientContext}.
     *
     * @return unmodifiable map of resolved mode definitions
     * @throws IllegalStateException if called before {@link #build}
     */
    @NonNull
    Map<ConnectionMode, ResolvedModeDefinition> getResolvedModeTable() {
        if (resolvedModeTable == null) {
            throw new IllegalStateException("build() must be called before getResolvedModeTable()");
        }
        return resolvedModeTable;
    }

    @Override
    public DataSource build(ClientContext clientContext) {
        Map<ConnectionMode, ResolvedModeDefinition> resolved = new LinkedHashMap<>();
        for (Map.Entry<ConnectionMode, ModeDefinition> entry : modeTable.entrySet()) {
            resolved.put(entry.getKey(), resolve(entry.getValue(), clientContext));
        }
        this.resolvedModeTable = Collections.unmodifiableMap(resolved);

        ResolvedModeDefinition startDef = resolvedModeTable.get(startingMode);
        if (startDef == null) {
            throw new IllegalStateException(
                    "Starting mode " + startingMode + " not found in mode table");
        }

        DataSourceUpdateSink baseSink = clientContext.getDataSourceUpdateSink();
        if (!(baseSink instanceof DataSourceUpdateSinkV2)) {
            throw new IllegalStateException(
                    "FDv2DataSource requires a DataSourceUpdateSinkV2 implementation");
        }

        ScheduledExecutorService sharedExecutor = Executors.newScheduledThreadPool(2);

        return new FDv2DataSource(
                clientContext.getEvaluationContext(),
                startDef.getInitializerFactories(),
                startDef.getSynchronizerFactories(),
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
