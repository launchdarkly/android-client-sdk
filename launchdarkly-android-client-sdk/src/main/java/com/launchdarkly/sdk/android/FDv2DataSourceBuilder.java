package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;
import com.launchdarkly.sdk.android.integrations.PollingDataSourceBuilder;
import com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.TransactionalDataStore;
import com.launchdarkly.sdk.internal.http.HttpProperties;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Builds an {@link FDv2DataSource} by resolving {@link ComponentConfigurer} factories
 * into zero-arg {@link FDv2DataSource.DataSourceFactory} instances. The builder is the
 * sole owner of mode resolution; {@link ConnectivityManager} configures the target mode
 * via {@link #setActiveMode} before calling the standard {@link #build}.
 * <p>
 * Package-private — not part of the public SDK API.
 */
class FDv2DataSourceBuilder implements ComponentConfigurer<DataSource> {

    private final Map<ConnectionMode, ModeDefinition> modeTable;
    private final ConnectionMode startingMode;

    private ConnectionMode activeMode;
    private boolean includeInitializers = true; // false during mode switches to skip initializers (CONNMODE 2.0.1)
    private ScheduledExecutorService sharedExecutor;

    FDv2DataSourceBuilder() {
        this(makeDefaultModeTable(), ConnectionMode.STREAMING);
    }

    private static Map<ConnectionMode, ModeDefinition> makeDefaultModeTable() {
        ComponentConfigurer<Initializer> pollingInitializer = ctx -> {
            ClientContextImpl impl = ClientContextImpl.get(ctx);
            TransactionalDataStore store = impl.getTransactionalDataStore();
            SelectorSource selectorSource = store != null
                    ? new SelectorSourceFacade(store)
                    : () -> com.launchdarkly.sdk.fdv2.Selector.EMPTY;
            URI pollingBase = StandardEndpoints.selectBaseUri(
                    ctx.getServiceEndpoints().getPollingBaseUri(),
                    StandardEndpoints.DEFAULT_POLLING_BASE_URI,
                    "polling", ctx.getBaseLogger());
            HttpProperties httpProps = LDUtil.makeHttpProperties(ctx);
            FDv2Requestor requestor = new DefaultFDv2Requestor(
                    ctx.getEvaluationContext(), pollingBase,
                    StandardEndpoints.FDV2_POLLING_REQUEST_GET_BASE_PATH,
                    StandardEndpoints.FDV2_POLLING_REQUEST_REPORT_BASE_PATH,
                    httpProps, ctx.getHttp().isUseReport(),
                    ctx.isEvaluationReasons(), null, ctx.getBaseLogger());
            return new FDv2PollingInitializer(requestor, selectorSource,
                    Executors.newSingleThreadExecutor(), ctx.getBaseLogger());
        };

        ComponentConfigurer<Synchronizer> pollingSynchronizer = ctx -> {
            ClientContextImpl impl = ClientContextImpl.get(ctx);
            TransactionalDataStore store = impl.getTransactionalDataStore();
            SelectorSource selectorSource = store != null
                    ? new SelectorSourceFacade(store)
                    : () -> com.launchdarkly.sdk.fdv2.Selector.EMPTY;
            URI pollingBase = StandardEndpoints.selectBaseUri(
                    ctx.getServiceEndpoints().getPollingBaseUri(),
                    StandardEndpoints.DEFAULT_POLLING_BASE_URI,
                    "polling", ctx.getBaseLogger());
            HttpProperties httpProps = LDUtil.makeHttpProperties(ctx);
            FDv2Requestor requestor = new DefaultFDv2Requestor(
                    ctx.getEvaluationContext(), pollingBase,
                    StandardEndpoints.FDV2_POLLING_REQUEST_GET_BASE_PATH,
                    StandardEndpoints.FDV2_POLLING_REQUEST_REPORT_BASE_PATH,
                    httpProps, ctx.getHttp().isUseReport(),
                    ctx.isEvaluationReasons(), null, ctx.getBaseLogger());
            ScheduledExecutorService exec = Executors.newScheduledThreadPool(1);
            return new FDv2PollingSynchronizer(requestor, selectorSource, exec,
                    0, PollingDataSourceBuilder.DEFAULT_POLL_INTERVAL_MILLIS, ctx.getBaseLogger());
        };

        ComponentConfigurer<Synchronizer> streamingSynchronizer = ctx -> {
            ClientContextImpl impl = ClientContextImpl.get(ctx);
            TransactionalDataStore store = impl.getTransactionalDataStore();
            SelectorSource selectorSource = store != null
                    ? new SelectorSourceFacade(store)
                    : () -> com.launchdarkly.sdk.fdv2.Selector.EMPTY;
            URI streamBase = StandardEndpoints.selectBaseUri(
                    ctx.getServiceEndpoints().getStreamingBaseUri(),
                    StandardEndpoints.DEFAULT_STREAMING_BASE_URI,
                    "streaming", ctx.getBaseLogger());
            URI pollingBase = StandardEndpoints.selectBaseUri(
                    ctx.getServiceEndpoints().getPollingBaseUri(),
                    StandardEndpoints.DEFAULT_POLLING_BASE_URI,
                    "polling", ctx.getBaseLogger());
            HttpProperties httpProps = LDUtil.makeHttpProperties(ctx);
            FDv2Requestor requestor = new DefaultFDv2Requestor(
                    ctx.getEvaluationContext(), pollingBase,
                    StandardEndpoints.FDV2_POLLING_REQUEST_GET_BASE_PATH,
                    StandardEndpoints.FDV2_POLLING_REQUEST_REPORT_BASE_PATH,
                    httpProps, ctx.getHttp().isUseReport(),
                    ctx.isEvaluationReasons(), null, ctx.getBaseLogger());
            return new FDv2StreamingSynchronizer(
                    ctx.getEvaluationContext(), selectorSource, streamBase,
                    StandardEndpoints.FDV2_STREAMING_REQUEST_BASE_PATH,
                    requestor,
                    StreamingDataSourceBuilder.DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS,
                    ctx.isEvaluationReasons(), ctx.getHttp().isUseReport(),
                    httpProps, Executors.newSingleThreadExecutor(),
                    ctx.getBaseLogger(), null);
        };

        ComponentConfigurer<Synchronizer> backgroundPollingSynchronizer = ctx -> {
            ClientContextImpl impl = ClientContextImpl.get(ctx);
            TransactionalDataStore store = impl.getTransactionalDataStore();
            SelectorSource selectorSource = store != null
                    ? new SelectorSourceFacade(store)
                    : () -> com.launchdarkly.sdk.fdv2.Selector.EMPTY;
            URI pollingBase = StandardEndpoints.selectBaseUri(
                    ctx.getServiceEndpoints().getPollingBaseUri(),
                    StandardEndpoints.DEFAULT_POLLING_BASE_URI,
                    "polling", ctx.getBaseLogger());
            HttpProperties httpProps = LDUtil.makeHttpProperties(ctx);
            FDv2Requestor requestor = new DefaultFDv2Requestor(
                    ctx.getEvaluationContext(), pollingBase,
                    StandardEndpoints.FDV2_POLLING_REQUEST_GET_BASE_PATH,
                    StandardEndpoints.FDV2_POLLING_REQUEST_REPORT_BASE_PATH,
                    httpProps, ctx.getHttp().isUseReport(),
                    ctx.isEvaluationReasons(), null, ctx.getBaseLogger());
            ScheduledExecutorService exec = Executors.newScheduledThreadPool(1);
            return new FDv2PollingSynchronizer(requestor, selectorSource, exec,
                    0, LDConfig.DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS, ctx.getBaseLogger());
        };

        Map<ConnectionMode, ModeDefinition> table = new LinkedHashMap<>();
        table.put(ConnectionMode.STREAMING, new ModeDefinition(
                Arrays.asList(pollingInitializer, pollingInitializer),
                Arrays.asList(streamingSynchronizer, pollingSynchronizer)
        ));
        table.put(ConnectionMode.POLLING, new ModeDefinition(
                Collections.singletonList(pollingInitializer),
                Collections.singletonList(pollingSynchronizer)
        ));
        table.put(ConnectionMode.OFFLINE, new ModeDefinition(
                Collections.singletonList(pollingInitializer),
                Collections.<ComponentConfigurer<Synchronizer>>emptyList()
        ));
        table.put(ConnectionMode.ONE_SHOT, new ModeDefinition(
                Arrays.asList(pollingInitializer, pollingInitializer, pollingInitializer),
                Collections.<ComponentConfigurer<Synchronizer>>emptyList()
        ));
        table.put(ConnectionMode.BACKGROUND, new ModeDefinition(
                Collections.singletonList(pollingInitializer),
                Collections.singletonList(backgroundPollingSynchronizer)
        ));
        return table;
    }

    FDv2DataSourceBuilder(
            @NonNull Map<ConnectionMode, ModeDefinition> modeTable,
            @NonNull ConnectionMode startingMode
    ) {
        this.modeTable = Collections.unmodifiableMap(new LinkedHashMap<>(modeTable));
        this.startingMode = startingMode;
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
}
