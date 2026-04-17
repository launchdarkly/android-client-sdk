package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.integrations.AutomaticModeSwitchingConfig;
import com.launchdarkly.sdk.android.integrations.ConnectionModeBuilder;
import com.launchdarkly.sdk.android.integrations.DataSystemBuilder;
import com.launchdarkly.sdk.android.integrations.PollingInitializerBuilder;
import com.launchdarkly.sdk.android.integrations.PollingSynchronizerBuilder;
import com.launchdarkly.sdk.android.integrations.StreamingSynchronizerBuilder;
import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuildInputs;
import com.launchdarkly.sdk.android.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;
import com.launchdarkly.sdk.internal.http.HttpProperties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Factory methods for FDv2 data source components used with the
 * {@link com.launchdarkly.sdk.android.integrations.DataSystemBuilder}.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * <p>
 * Most factory methods return a builder that implements
 * {@link com.launchdarkly.sdk.android.subsystems.DataSourceBuilder} for the
 * appropriate type ({@link Initializer} or {@link Synchronizer}). You may
 * configure properties on the builder and then pass it to
 * {@link com.launchdarkly.sdk.android.integrations.ConnectionModeBuilder#initializers}
 * or {@link com.launchdarkly.sdk.android.integrations.ConnectionModeBuilder#synchronizers}.
 * <p>
 * <b>Example:</b>
 * <pre><code>
 *     LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
 *         .mobileKey("my-key")
 *         .dataSystem(
 *             Components.dataSystem()
 *                 .customizeConnectionMode(ConnectionMode.STREAMING,
 *                     DataSystemComponents.customMode()
 *                         .initializers(DataSystemComponents.pollingInitializer())
 *                         .synchronizers(
 *                             DataSystemComponents.streamingSynchronizer()
 *                                 .initialReconnectDelayMillis(500),
 *                             DataSystemComponents.pollingSynchronizer()
 *                                 .pollIntervalMillis(300_000))))
 *         .build();
 * </code></pre>
 *
 * @see com.launchdarkly.sdk.android.integrations.DataSystemBuilder
 * @see com.launchdarkly.sdk.android.integrations.ConnectionModeBuilder
 */
public abstract class DataSystemComponents {

    private DataSystemComponents() {}

    static final class PollingInitializerBuilderImpl extends PollingInitializerBuilder {
        @Override
        public Initializer build(DataSourceBuildInputs inputs) {
            HttpProperties httpProps = LDUtil.makeHttpProperties(inputs.getHttp());
            ServiceEndpoints endpoints = serviceEndpointsOverride != null
                    ? serviceEndpointsOverride
                    : inputs.getServiceEndpoints();
            FDv2Requestor requestor = makePollingRequestor(inputs, endpoints, httpProps);
            return new FDv2PollingInitializer(requestor, inputs.getSelectorSource(),
                    inputs.getSharedExecutor(), inputs.getBaseLogger());
        }
    }

    static final class PollingSynchronizerBuilderImpl extends PollingSynchronizerBuilder {
        @Override
        public Synchronizer build(DataSourceBuildInputs inputs) {
            HttpProperties httpProps = LDUtil.makeHttpProperties(inputs.getHttp());
            ServiceEndpoints endpoints = serviceEndpointsOverride != null
                    ? serviceEndpointsOverride
                    : inputs.getServiceEndpoints();
            FDv2Requestor requestor = makePollingRequestor(inputs, endpoints, httpProps);
            return new FDv2PollingSynchronizer(requestor, inputs.getSelectorSource(),
                    inputs.getSharedExecutor(),
                    0, pollIntervalMillis, inputs.getBaseLogger());
        }
    }

    static final class StreamingSynchronizerBuilderImpl extends StreamingSynchronizerBuilder {
        @Override
        public Synchronizer build(DataSourceBuildInputs inputs) {
            HttpProperties httpProps = LDUtil.makeHttpProperties(inputs.getHttp());
            ServiceEndpoints endpoints = serviceEndpointsOverride != null
                    ? serviceEndpointsOverride
                    : inputs.getServiceEndpoints();
            FDv2Requestor requestor = makePollingRequestor(inputs, endpoints, httpProps);
            URI streamBase = StandardEndpoints.selectBaseUri(
                    endpoints.getStreamingBaseUri(),
                    StandardEndpoints.DEFAULT_STREAMING_BASE_URI,
                    "streaming", inputs.getBaseLogger());
            return new FDv2StreamingSynchronizer(
                    inputs.getEvaluationContext(), inputs.getSelectorSource(), streamBase,
                    StandardEndpoints.FDV2_STREAMING_REQUEST_BASE_PATH,
                    requestor,
                    initialReconnectDelayMillis,
                    inputs.isEvaluationReasons(), inputs.getHttp().isUseReport(),
                    httpProps, inputs.getSharedExecutor(),
                    inputs.getBaseLogger(), null);
        }
    }

    static final class FDv1PollingSynchronizerBuilderImpl implements DataSourceBuilder<Synchronizer> {

        protected int pollIntervalMillis = LDConfig.DEFAULT_POLL_INTERVAL_MILLIS;

        public FDv1PollingSynchronizerBuilderImpl pollIntervalMillis(int pollIntervalMillis) {
            this.pollIntervalMillis = pollIntervalMillis <= LDConfig.DEFAULT_POLL_INTERVAL_MILLIS ?
                    LDConfig.DEFAULT_POLL_INTERVAL_MILLIS : pollIntervalMillis;
            return this;
        }

        @Override
        public Synchronizer build(DataSourceBuildInputs inputs) {
            FeatureFetcher fetcher = new HttpFeatureFlagFetcher(
                inputs.getServiceEndpoints().getPollingBaseUri(),
                inputs.isEvaluationReasons(),
                inputs.getHttp().isUseReport(),
                LDUtil.makeHttpProperties(inputs.getHttp()),
                inputs.getCacheDir(),
                inputs.getBaseLogger()
            );
            return new FDv1PollingSynchronizer(
                inputs.getEvaluationContext(), fetcher,
                inputs.getSharedExecutor(), 0,
                pollIntervalMillis,
                inputs.getBaseLogger()
            );
        }
    }

    static final class CacheInitializerBuilderImpl implements DataSourceBuilder<Initializer> {
        @Nullable
        private final PersistentDataStoreWrapper.ReadOnlyPerEnvironmentData envData;

        CacheInitializerBuilderImpl() {
            this.envData = null;
        }

        CacheInitializerBuilderImpl(
                @Nullable PersistentDataStoreWrapper.ReadOnlyPerEnvironmentData envData
        ) {
            this.envData = envData;
        }

        @Override
        public Initializer build(DataSourceBuildInputs inputs) {
            return new FDv2CacheInitializer(
                    envData,
                    inputs.getEvaluationContext(),
                    inputs.getBaseLogger()
            );
        }
    }

    /**
     * Returns a builder for a polling initializer.
     * <p>
     * A polling initializer makes a single poll request to obtain the initial feature
     * flag data set.
     *
     * @return a polling initializer builder
     */
    public static PollingInitializerBuilder pollingInitializer() {
        return new PollingInitializerBuilderImpl();
    }

    /**
     * Returns a builder for a polling synchronizer.
     * <p>
     * A polling synchronizer periodically polls LaunchDarkly for feature flag updates.
     * The poll interval can be configured via
     * {@link PollingSynchronizerBuilder#pollIntervalMillis(int)}.
     *
     * @return a polling synchronizer builder
     */
    public static PollingSynchronizerBuilder pollingSynchronizer() {
        return new PollingSynchronizerBuilderImpl();
    }

    /**
     * Returns a builder for a streaming synchronizer.
     * <p>
     * A streaming synchronizer maintains a persistent connection to LaunchDarkly
     * and receives real-time feature flag updates. The initial reconnect delay
     * can be configured via
     * {@link StreamingSynchronizerBuilder#initialReconnectDelayMillis(int)}.
     *
     * @return a streaming synchronizer builder
     */
    public static StreamingSynchronizerBuilder streamingSynchronizer() {
        return new StreamingSynchronizerBuilderImpl();
    }

    /**
     * Produces the default mode table used by {@link DataSystemBuilder#buildModeTable}.
     * Defined here (rather than in {@code DataSystemBuilder}) because the FDv1 fallback
     * synchronizer references package-private types that are not visible from the
     * {@code integrations} package.
     * <p>
     * This method is public only for cross-package access within the SDK; it is not
     * intended for use by application code.
     */
    @NonNull
    public static Map<ConnectionMode, ModeDefinition> makeDefaultModeTable() {
        DataSourceBuilder<Initializer> cacheInitializer = new CacheInitializerBuilderImpl();
        DataSourceBuilder<Initializer> pollingInitializer = pollingInitializer();
        DataSourceBuilder<Synchronizer> pollingSynchronizer = pollingSynchronizer();
        DataSourceBuilder<Synchronizer> streamingSynchronizer = streamingSynchronizer();
        DataSourceBuilder<Synchronizer> backgroundPollingSynchronizer =
                pollingSynchronizer()
                        .pollIntervalMillis(LDConfig.DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS);
        DataSourceBuilder<Synchronizer> fdv1FallbackPollingSynchronizerForeground =
                new FDv1PollingSynchronizerBuilderImpl();

        DataSourceBuilder<Synchronizer> fdv1FallbackPollingSynchronizerBackground =
                new FDv1PollingSynchronizerBuilderImpl().pollIntervalMillis(LDConfig.DEFAULT_BACKGROUND_POLL_INTERVAL_MILLIS);

        Map<ConnectionMode, ModeDefinition> table = new LinkedHashMap<>();
        table.put(ConnectionMode.STREAMING, new ModeDefinition(
                Arrays.asList(cacheInitializer, pollingInitializer),
                Arrays.asList(streamingSynchronizer, pollingSynchronizer),
                fdv1FallbackPollingSynchronizerForeground
        ));
        table.put(ConnectionMode.POLLING, new ModeDefinition(
                Collections.singletonList(cacheInitializer),
                Collections.singletonList(pollingSynchronizer),
                fdv1FallbackPollingSynchronizerForeground
        ));
        table.put(ConnectionMode.OFFLINE, new ModeDefinition(
                Collections.singletonList(cacheInitializer),
                Collections.<DataSourceBuilder<Synchronizer>>emptyList(),
                null
        ));
        table.put(ConnectionMode.ONE_SHOT, new ModeDefinition(
                // TODO: streamingInitializer — add once implemented
                Arrays.asList(cacheInitializer, pollingInitializer /*, streamingInitializer */),
                Collections.<DataSourceBuilder<Synchronizer>>emptyList(),
                null
        ));
        table.put(ConnectionMode.BACKGROUND, new ModeDefinition(
                Collections.singletonList(cacheInitializer),
                Collections.singletonList(backgroundPollingSynchronizer),
                fdv1FallbackPollingSynchronizerBackground
        ));
        return table;
    }

    /**
     * Returns a builder for configuring automatic connection mode switching in response to
     * platform events (foreground/background and network availability).
     * <p>
     * Pass the result of {@link AutomaticModeSwitchingConfig.Builder#build()} to
     * {@link DataSystemBuilder#automaticModeSwitching(AutomaticModeSwitchingConfig)}.
     * <pre><code>
     *     Components.dataSystem()
     *         .automaticModeSwitching(
     *             DataSystemComponents.automaticModeSwitching()
     *                 .lifecycle(false)
     *                 .network(true)
     *                 .build())
     * </code></pre>
     * <p>
     * For all-on or all-off behavior, you may use {@link AutomaticModeSwitchingConfig#enabled()}
     * or {@link AutomaticModeSwitchingConfig#disabled()} instead of this builder.
     *
     * @return a builder for automatic mode switching (both lifecycle and network enabled by default)
     * @see AutomaticModeSwitchingConfig
     * @see DataSystemBuilder#automaticModeSwitching(AutomaticModeSwitchingConfig)
     */
    public static AutomaticModeSwitchingConfig.Builder automaticModeSwitching() {
        return new AutomaticModeSwitchingConfig.Builder();
    }

    /**
     * Returns a builder for configuring a custom data pipeline for a connection mode.
     * <p>
     * Use this to specify which initializers and synchronizers should run when the
     * SDK is operating in a particular {@link ConnectionMode}. Pass the result to
     * {@link DataSystemBuilder#customizeConnectionMode(ConnectionMode, ConnectionModeBuilder)}.
     * <pre><code>
     *     LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
     *         .mobileKey("my-key")
     *         .dataSystem(
     *             Components.dataSystem()
     *                 .customizeConnectionMode(ConnectionMode.BACKGROUND,
     *                     DataSystemComponents.customMode()
     *                         .synchronizers(
     *                             DataSystemComponents.pollingSynchronizer()
     *                                 .pollIntervalMillis(21_600_000))))
     *         .build();
     * </code></pre>
     *
     * @return a builder for configuring a custom connection mode pipeline
     * @see ConnectionModeBuilder
     * @see DataSystemBuilder#customizeConnectionMode(ConnectionMode, ConnectionModeBuilder)
     */
    public static ConnectionModeBuilder customMode() {
        return new ConnectionModeBuilder();
    }

    /**
     * Builds a {@link DefaultFDv2Requestor} configured for polling endpoints. Used
     * directly by polling components and as the fallback requestor for the streaming
     * synchronizer (which needs it for internal polling fallback when the stream cannot
     * be established).
     */
    private static FDv2Requestor makePollingRequestor(DataSourceBuildInputs inputs,
            ServiceEndpoints endpoints, HttpProperties httpProps) {
        URI pollingBase = StandardEndpoints.selectBaseUri(
                endpoints.getPollingBaseUri(),
                StandardEndpoints.DEFAULT_POLLING_BASE_URI,
                "polling", inputs.getBaseLogger());
        return new DefaultFDv2Requestor(
                inputs.getEvaluationContext(), pollingBase,
                StandardEndpoints.FDV2_POLLING_REQUEST_GET_BASE_PATH,
                StandardEndpoints.FDV2_POLLING_REQUEST_REPORT_BASE_PATH,
                httpProps, inputs.getHttp().isUseReport(),
                inputs.isEvaluationReasons(), null, inputs.getBaseLogger());
    }
}
