package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Builds a mode-aware {@link FDv2DataSource} from a {@link ModeDefinition} table.
 * <p>
 * At build time, each {@link ComponentConfigurer} in the mode table is resolved into a
 * {@link FDv2DataSource.DataSourceFactory} by partially applying the {@link ClientContext}:
 * <pre>{@code
 * DataSourceFactory<T> factory = () -> configurer.build(clientContext);
 * }</pre>
 * This bridges the SDK's {@link ComponentConfigurer} pattern (used in the mode table) with
 * the {@link FDv2DataSource.DataSourceFactory} pattern (used inside {@link FDv2DataSource}).
 * <p>
 * The configurers in {@link ModeDefinition#DEFAULT_MODE_TABLE} are currently stubbed. A
 * subsequent commit will replace them with real implementations that create
 * {@link FDv2PollingInitializer}, {@link FDv2PollingSynchronizer},
 * {@link FDv2StreamingSynchronizer}, etc.
 * <p>
 * Package-private — not part of the public SDK API.
 *
 * @see ModeDefinition
 * @see FDv2DataSource.ResolvedModeDefinition
 */
final class FDv2DataSourceBuilder implements ComponentConfigurer<DataSource> {

    private final Map<ConnectionMode, ModeDefinition> modeTable;
    private final ConnectionMode startingMode;

    /**
     * Creates a builder using the {@link ModeDefinition#DEFAULT_MODE_TABLE} and
     * {@link ConnectionMode#STREAMING} as the starting mode.
     */
    FDv2DataSourceBuilder() {
        this(ModeDefinition.DEFAULT_MODE_TABLE, ConnectionMode.STREAMING);
    }

    /**
     * @param modeTable    the mode definitions to resolve at build time
     * @param startingMode the initial connection mode for the data source
     */
    FDv2DataSourceBuilder(
            @NonNull Map<ConnectionMode, ModeDefinition> modeTable,
            @NonNull ConnectionMode startingMode
    ) {
        this.modeTable = Collections.unmodifiableMap(new EnumMap<>(modeTable));
        this.startingMode = startingMode;
    }

    @Override
    public DataSource build(ClientContext clientContext) {
        Map<ConnectionMode, FDv2DataSource.ResolvedModeDefinition> resolved =
                resolveModeTable(clientContext);

        DataSourceUpdateSinkV2 sinkV2 =
                (DataSourceUpdateSinkV2) clientContext.getDataSourceUpdateSink();

        // TODO: executor lifecycle — FDv2DataSource does not shut down its executor.
        // In a future commit, this should be replaced with an executor obtained from
        // ClientContextImpl or managed by ConnectivityManager.
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

        return new FDv2DataSource(
                clientContext.getEvaluationContext(),
                resolved,
                startingMode,
                sinkV2,
                executor,
                clientContext.getBaseLogger()
        );
    }

    /**
     * Resolves every {@link ComponentConfigurer} in the mode table into a
     * {@link FDv2DataSource.DataSourceFactory} by capturing the {@code clientContext}.
     * The actual component is not created until the factory's {@code build()} is called.
     */
    private Map<ConnectionMode, FDv2DataSource.ResolvedModeDefinition> resolveModeTable(
            ClientContext clientContext
    ) {
        Map<ConnectionMode, FDv2DataSource.ResolvedModeDefinition> resolved =
                new EnumMap<>(ConnectionMode.class);

        for (Map.Entry<ConnectionMode, ModeDefinition> entry : modeTable.entrySet()) {
            ModeDefinition def = entry.getValue();

            List<FDv2DataSource.DataSourceFactory<Initializer>> initFactories = new ArrayList<>();
            for (ComponentConfigurer<Initializer> configurer : def.getInitializers()) {
                initFactories.add(() -> configurer.build(clientContext));
            }

            List<FDv2DataSource.DataSourceFactory<Synchronizer>> syncFactories = new ArrayList<>();
            for (ComponentConfigurer<Synchronizer> configurer : def.getSynchronizers()) {
                syncFactories.add(() -> configurer.build(clientContext));
            }

            resolved.put(entry.getKey(), new FDv2DataSource.ResolvedModeDefinition(
                    initFactories, syncFactories));
        }

        return resolved;
    }
}
