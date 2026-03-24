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

import java.io.Closeable;
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
class FDv2DataSourceBuilder implements ComponentConfigurer<DataSource>, Closeable {

    private Map<ConnectionMode, ModeDefinition> modeTable;
    private final ConnectionMode startingMode;

    private ConnectionMode activeMode;
    private boolean includeInitializers = true; // false during mode switches to skip initializers (CONNMODE 2.0.1)
    private ScheduledExecutorService sharedExecutor;

    FDv2DataSourceBuilder() {
        this.modeTable = null; // built lazily in build() so lambdas can capture sharedExecutor
        this.startingMode = ConnectionMode.STREAMING;
    }

    private Map<ConnectionMode, ModeDefinition> makeDefaultModeTable() {
        ComponentConfigurer<Initializer> pollingInitializer = ctx -> {
            DataSourceSetup s = new DataSourceSetup(ctx);
            FDv2Requestor requestor = makePollingRequestor(ctx, s.httpProps);
            return new FDv2PollingInitializer(requestor, s.selectorSource,
                    sharedExecutor, ctx.getBaseLogger());
        };

        ComponentConfigurer<Synchronizer> pollingSynchronizer = ctx -> {
            DataSourceSetup s = new DataSourceSetup(ctx);
            FDv2Requestor requestor = makePollingRequestor(ctx, s.httpProps);
            return new FDv2PollingSynchronizer(requestor, s.selectorSource,
                    sharedExecutor,
                    0, PollingDataSourceBuilder.DEFAULT_POLL_INTERVAL_MILLIS, ctx.getBaseLogger());
        };

        ComponentConfigurer<Synchronizer> streamingSynchronizer = ctx -> {
            DataSourceSetup s = new DataSourceSetup(ctx);
            // The streaming synchronizer uses a polling requestor for its internal
            // polling fallback (e.g. when the stream cannot be established).
            FDv2Requestor pollingRequestor = makePollingRequestor(ctx, s.httpProps);
            URI streamBase = StandardEndpoints.selectBaseUri(
                    ctx.getServiceEndpoints().getStreamingBaseUri(),
                    StandardEndpoints.DEFAULT_STREAMING_BASE_URI,
                    "streaming", ctx.getBaseLogger());
            return new FDv2StreamingSynchronizer(
                    ctx.getEvaluationContext(), s.selectorSource, streamBase,
                    StandardEndpoints.FDV2_STREAMING_REQUEST_BASE_PATH,
                    pollingRequestor,
                    StreamingDataSourceBuilder.DEFAULT_INITIAL_RECONNECT_DELAY_MILLIS,
                    ctx.isEvaluationReasons(), ctx.getHttp().isUseReport(),
                    s.httpProps, sharedExecutor,
                    ctx.getBaseLogger(), null);
        };

        ComponentConfigurer<Synchronizer> backgroundPollingSynchronizer = ctx -> {
            DataSourceSetup s = new DataSourceSetup(ctx);
            FDv2Requestor requestor = makePollingRequestor(ctx, s.httpProps);
            return new FDv2PollingSynchronizer(requestor, s.selectorSource,
                    sharedExecutor,
                    0, LDConfig.DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS, ctx.getBaseLogger());
        };

        Map<ConnectionMode, ModeDefinition> table = new LinkedHashMap<>();
        table.put(ConnectionMode.STREAMING, new ModeDefinition(
                // TODO: cacheInitializer — add once implemented
                Arrays.asList(/* cacheInitializer, */ pollingInitializer),
                Arrays.asList(streamingSynchronizer, pollingSynchronizer)
        ));
        table.put(ConnectionMode.POLLING, new ModeDefinition(
                // TODO: Arrays.asList(cacheInitializer) — add once implemented
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.singletonList(pollingSynchronizer)
        ));
        table.put(ConnectionMode.OFFLINE, new ModeDefinition(
                // TODO: Arrays.asList(cacheInitializer) — add once implemented
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.<ComponentConfigurer<Synchronizer>>emptyList()
        ));
        table.put(ConnectionMode.ONE_SHOT, new ModeDefinition(
                // TODO: cacheInitializer and streamingInitializer — add once implemented
                Arrays.asList(/* cacheInitializer, */ pollingInitializer /*, streamingInitializer, */),
                Collections.<ComponentConfigurer<Synchronizer>>emptyList()
        ));
        table.put(ConnectionMode.BACKGROUND, new ModeDefinition(
                // TODO: Arrays.asList(cacheInitializer) — add once implemented
                Collections.<ComponentConfigurer<Initializer>>emptyList(),
                Collections.singletonList(backgroundPollingSynchronizer)
        ));
        return table;
    }

    FDv2DataSourceBuilder(
            @NonNull Map<ConnectionMode, ModeDefinition> modeTable,
            @NonNull ConnectionMode startingMode
    ) {
        this.modeTable = modeTable;
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
        if (sharedExecutor == null) {
            // Pool size 4: supports the FDv2DataSource main loop (1), an active synchronizer
            // such as the streaming event loop (1), FDv2DataSource condition timers for
            // fallback/recovery (up to 2 short-lived scheduled tasks). Only one mode is active
            // at a time (teardown/rebuild), so this pool serves all components.
            sharedExecutor = Executors.newScheduledThreadPool(4);
        }

        if (modeTable == null) {
            modeTable = makeDefaultModeTable();
        }

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

    @Override
    public void close() {
        if (sharedExecutor != null) {
            sharedExecutor.shutdownNow();
            sharedExecutor = null;
        }
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

    /**
     * Holds the shared infrastructure needed by all FDv2 data source components:
     * a {@link SelectorSource} backed by the {@link TransactionalDataStore} (or an empty
     * fallback if none is configured), and the {@link HttpProperties} for the current
     * client configuration. Polling-specific setup (the {@link FDv2Requestor}) is built
     * separately via {@link #makePollingRequestor}.
     */
    private static final class DataSourceSetup {
        final SelectorSource selectorSource;
        final HttpProperties httpProps;

        DataSourceSetup(ClientContext ctx) {
            TransactionalDataStore store = ClientContextImpl.get(ctx).getTransactionalDataStore();
            this.selectorSource = store != null
                    ? new SelectorSourceFacade(store)
                    : () -> com.launchdarkly.sdk.fdv2.Selector.EMPTY;
            this.httpProps = LDUtil.makeHttpProperties(ctx);
        }
    }

    /**
     * Builds a {@link DefaultFDv2Requestor} configured for polling endpoints. Used
     * directly by polling components and as the fallback requestor for the streaming
     * synchronizer (which needs it for internal polling fallback when the stream cannot
     * be established).
     */
    private static FDv2Requestor makePollingRequestor(ClientContext ctx, HttpProperties httpProps) {
        URI pollingBase = StandardEndpoints.selectBaseUri(
                ctx.getServiceEndpoints().getPollingBaseUri(),
                StandardEndpoints.DEFAULT_POLLING_BASE_URI,
                "polling", ctx.getBaseLogger());
        return new DefaultFDv2Requestor(
                ctx.getEvaluationContext(), pollingBase,
                StandardEndpoints.FDV2_POLLING_REQUEST_GET_BASE_PATH,
                StandardEndpoints.FDV2_POLLING_REQUEST_REPORT_BASE_PATH,
                httpProps, ctx.getHttp().isUseReport(),
                ctx.isEvaluationReasons(), null, ctx.getBaseLogger());
    }
}
