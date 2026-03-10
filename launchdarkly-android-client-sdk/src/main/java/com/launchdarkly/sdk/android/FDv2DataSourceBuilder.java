package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.integrations.PollingDataSourceBuilder;
import com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;
import com.launchdarkly.sdk.android.subsystems.TransactionalDataStore;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.http.HttpProperties;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Builds a mode-aware {@link FDv2DataSource} from either a custom {@link ModeDefinition} table
 * or the built-in default mode definitions.
 * <p>
 * When no custom table is supplied, the builder creates concrete {@link FDv2PollingInitializer},
 * {@link FDv2PollingSynchronizer}, and {@link FDv2StreamingSynchronizer} factories using
 * dependencies extracted from the {@link ClientContext}. Shared dependencies (executor,
 * {@link SelectorSource}) are created once and captured by all factory closures. Each factory
 * call creates fresh instances of requestors and concrete sources to ensure proper lifecycle
 * management.
 * <p>
 * When a custom table is supplied (for testing), each {@link ComponentConfigurer} is resolved
 * into a {@link FDv2DataSource.DataSourceFactory} by partially applying the
 * {@link ClientContext}.
 * <p>
 * Package-private — not part of the public SDK API.
 *
 * @see ModeDefinition
 * @see FDv2DataSource.ResolvedModeDefinition
 */
final class FDv2DataSourceBuilder implements ComponentConfigurer<DataSource> {

    @Nullable
    private final Map<ConnectionMode, ModeDefinition> modeTable;
    private final ConnectionMode startingMode;

    /**
     * Creates a builder using the built-in default mode definitions and
     * {@link ConnectionMode#STREAMING} as the starting mode.
     */
    FDv2DataSourceBuilder() {
        this(null, ConnectionMode.STREAMING);
    }

    /**
     * @param modeTable    custom mode definitions to resolve at build time, or {@code null}
     *                     to use the built-in defaults
     * @param startingMode the initial connection mode for the data source
     */
    FDv2DataSourceBuilder(
            @Nullable Map<ConnectionMode, ModeDefinition> modeTable,
            @NonNull ConnectionMode startingMode
    ) {
        this.modeTable = modeTable != null
                ? Collections.unmodifiableMap(new EnumMap<>(modeTable))
                : null;
        this.startingMode = startingMode;
    }

    @Override
    public DataSource build(ClientContext clientContext) {
        // TODO: executor lifecycle — FDv2DataSource does not shut down its executor.
        // In a future commit, this should be replaced with an executor obtained from
        // ClientContextImpl or managed by ConnectivityManager.
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

        Map<ConnectionMode, FDv2DataSource.ResolvedModeDefinition> resolved;
        if (modeTable != null) {
            resolved = resolveCustomModeTable(clientContext);
        } else {
            resolved = buildDefaultModeTable(clientContext, executor);
        }

        DataSourceUpdateSinkV2 sinkV2 =
                (DataSourceUpdateSinkV2) clientContext.getDataSourceUpdateSink();

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
     * Builds the default mode table with real factories. Shared dependencies are created
     * once and captured by factory closures; each factory call creates fresh instances of
     * requestors and concrete sources.
     */
    private Map<ConnectionMode, FDv2DataSource.ResolvedModeDefinition> buildDefaultModeTable(
            ClientContext clientContext,
            ScheduledExecutorService executor
    ) {
        ClientContextImpl impl = ClientContextImpl.get(clientContext);
        HttpProperties httpProperties = LDUtil.makeHttpProperties(clientContext);
        LDContext evalContext = clientContext.getEvaluationContext();
        LDLogger logger = clientContext.getBaseLogger();
        boolean useReport = clientContext.getHttp().isUseReport();
        boolean evaluationReasons = clientContext.isEvaluationReasons();
        URI pollingBaseUri = clientContext.getServiceEndpoints().getPollingBaseUri();
        URI streamingBaseUri = clientContext.getServiceEndpoints().getStreamingBaseUri();
        DiagnosticStore diagnosticStore = impl.getDiagnosticStore();
        TransactionalDataStore txnStore = impl.getTransactionalDataStore();
        SelectorSource selectorSource = new SelectorSourceFacade(txnStore);

        // Each factory creates a fresh requestor so that lifecycle (close/shutdown) is isolated
        // per initializer/synchronizer instance.
        FDv2DataSource.DataSourceFactory<Initializer> pollingInitFactory = () ->
                new FDv2PollingInitializer(
                        newRequestor(evalContext, pollingBaseUri, httpProperties,
                                useReport, evaluationReasons, logger),
                        selectorSource, executor, logger);

        FDv2DataSource.DataSourceFactory<Synchronizer> streamingSyncFactory = () ->
                new FDv2StreamingSynchronizer(
                        httpProperties, streamingBaseUri,
                        StandardEndpoints.FDV2_STREAMING_REQUEST_BASE_PATH,
                        evalContext, useReport, evaluationReasons, selectorSource,
                        newRequestor(evalContext, pollingBaseUri, httpProperties,
                                useReport, evaluationReasons, logger),
                        StreamingDataSourceBuilder.DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS,
                        diagnosticStore, logger);

        FDv2DataSource.DataSourceFactory<Synchronizer> foregroundPollSyncFactory = () ->
                new FDv2PollingSynchronizer(
                        newRequestor(evalContext, pollingBaseUri, httpProperties,
                                useReport, evaluationReasons, logger),
                        selectorSource, executor,
                        0, PollingDataSourceBuilder.DEFAULT_POLL_INTERVAL_MILLIS, logger);

        FDv2DataSource.DataSourceFactory<Synchronizer> backgroundPollSyncFactory = () ->
                new FDv2PollingSynchronizer(
                        newRequestor(evalContext, pollingBaseUri, httpProperties,
                                useReport, evaluationReasons, logger),
                        selectorSource, executor,
                        0, LDConfig.DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS, logger);

        Map<ConnectionMode, FDv2DataSource.ResolvedModeDefinition> resolved =
                new EnumMap<>(ConnectionMode.class);

        // STREAMING: poll once for initial data, then stream (with polling fallback)
        resolved.put(ConnectionMode.STREAMING, new FDv2DataSource.ResolvedModeDefinition(
                Collections.singletonList(pollingInitFactory),
                Arrays.asList(streamingSyncFactory, foregroundPollSyncFactory)));

        // POLLING: poll once for initial data, then poll periodically
        resolved.put(ConnectionMode.POLLING, new FDv2DataSource.ResolvedModeDefinition(
                Collections.singletonList(pollingInitFactory),
                Collections.singletonList(foregroundPollSyncFactory)));

        // OFFLINE: no network activity
        resolved.put(ConnectionMode.OFFLINE, new FDv2DataSource.ResolvedModeDefinition(
                Collections.<FDv2DataSource.DataSourceFactory<Initializer>>emptyList(),
                Collections.<FDv2DataSource.DataSourceFactory<Synchronizer>>emptyList()));

        // ONE_SHOT: poll once, then stop
        resolved.put(ConnectionMode.ONE_SHOT, new FDv2DataSource.ResolvedModeDefinition(
                Collections.singletonList(pollingInitFactory),
                Collections.<FDv2DataSource.DataSourceFactory<Synchronizer>>emptyList()));

        // BACKGROUND: poll at reduced frequency (no re-initialization)
        resolved.put(ConnectionMode.BACKGROUND, new FDv2DataSource.ResolvedModeDefinition(
                Collections.<FDv2DataSource.DataSourceFactory<Initializer>>emptyList(),
                Collections.singletonList(backgroundPollSyncFactory)));

        return resolved;
    }

    /**
     * Resolves a custom {@link ModeDefinition} table by wrapping each {@link ComponentConfigurer}
     * in a {@link FDv2DataSource.DataSourceFactory} that defers to
     * {@code configurer.build(clientContext)}.
     */
    private Map<ConnectionMode, FDv2DataSource.ResolvedModeDefinition> resolveCustomModeTable(
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

    private static DefaultFDv2Requestor newRequestor(
            LDContext evalContext,
            URI pollingBaseUri,
            HttpProperties httpProperties,
            boolean useReport,
            boolean evaluationReasons,
            LDLogger logger
    ) {
        return new DefaultFDv2Requestor(
                evalContext, pollingBaseUri,
                StandardEndpoints.FDV2_POLLING_REQUEST_GET_BASE_PATH,
                StandardEndpoints.FDV2_POLLING_REQUEST_REPORT_BASE_PATH,
                httpProperties, useReport, evaluationReasons,
                null, logger);
    }
}
