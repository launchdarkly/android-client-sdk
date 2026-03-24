package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.integrations.AutomaticModeSwitchingConfig;
import com.launchdarkly.sdk.android.integrations.ConnectionModeBuilder;
import com.launchdarkly.sdk.android.integrations.DataSystemBuilder;
import com.launchdarkly.sdk.android.integrations.PollingInitializerBuilder;
import com.launchdarkly.sdk.android.integrations.PollingSynchronizerBuilder;
import com.launchdarkly.sdk.android.integrations.StreamingSynchronizerBuilder;
import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;
import com.launchdarkly.sdk.android.subsystems.TransactionalDataStore;
import com.launchdarkly.sdk.internal.http.HttpProperties;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Factory methods for FDv2 data source components used with the
 * {@link com.launchdarkly.sdk.android.integrations.DataSystemBuilder}.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * <p>
 * Most factory methods return a builder that implements
 * {@link com.launchdarkly.sdk.android.subsystems.ComponentConfigurer} for the
 * appropriate type ({@link Initializer} or {@link Synchronizer}). You may
 * configure properties on the builder and then pass it to
 * {@link com.launchdarkly.sdk.android.integrations.ConnectionModeBuilder#initializers}
 * or {@link com.launchdarkly.sdk.android.integrations.ConnectionModeBuilder#synchronizers}.
 * {@link #automaticModeSwitching()} instead returns an
 * {@link AutomaticModeSwitchingConfig.Builder} for
 * {@link DataSystemBuilder#automaticModeSwitching(AutomaticModeSwitchingConfig)}.
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
        public Initializer build(ClientContext clientContext) {
            ClientContextImpl impl = ClientContextImpl.get(clientContext);
            SelectorSource selectorSource = makeSelectorSource(impl);
            ServiceEndpoints endpoints = serviceEndpointsOverride != null
                    ? serviceEndpointsOverride
                    : clientContext.getServiceEndpoints();
            URI pollingBase = StandardEndpoints.selectBaseUri(
                    endpoints.getPollingBaseUri(),
                    StandardEndpoints.DEFAULT_POLLING_BASE_URI,
                    "polling", clientContext.getBaseLogger());
            HttpProperties httpProps = LDUtil.makeHttpProperties(clientContext);
            FDv2Requestor requestor = new DefaultFDv2Requestor(
                    clientContext.getEvaluationContext(), pollingBase,
                    StandardEndpoints.FDV2_POLLING_REQUEST_GET_BASE_PATH,
                    StandardEndpoints.FDV2_POLLING_REQUEST_REPORT_BASE_PATH,
                    httpProps, clientContext.getHttp().isUseReport(),
                    clientContext.isEvaluationReasons(), null,
                    clientContext.getBaseLogger());
            return new FDv2PollingInitializer(requestor, selectorSource,
                    Executors.newSingleThreadExecutor(), clientContext.getBaseLogger());
        }
    }

    static final class PollingSynchronizerBuilderImpl extends PollingSynchronizerBuilder {
        @Override
        public Synchronizer build(ClientContext clientContext) {
            ClientContextImpl impl = ClientContextImpl.get(clientContext);
            SelectorSource selectorSource = makeSelectorSource(impl);
            ServiceEndpoints endpoints = serviceEndpointsOverride != null
                    ? serviceEndpointsOverride
                    : clientContext.getServiceEndpoints();
            URI pollingBase = StandardEndpoints.selectBaseUri(
                    endpoints.getPollingBaseUri(),
                    StandardEndpoints.DEFAULT_POLLING_BASE_URI,
                    "polling", clientContext.getBaseLogger());
            HttpProperties httpProps = LDUtil.makeHttpProperties(clientContext);
            FDv2Requestor requestor = new DefaultFDv2Requestor(
                    clientContext.getEvaluationContext(), pollingBase,
                    StandardEndpoints.FDV2_POLLING_REQUEST_GET_BASE_PATH,
                    StandardEndpoints.FDV2_POLLING_REQUEST_REPORT_BASE_PATH,
                    httpProps, clientContext.getHttp().isUseReport(),
                    clientContext.isEvaluationReasons(), null,
                    clientContext.getBaseLogger());
            ScheduledExecutorService exec = Executors.newScheduledThreadPool(1);
            return new FDv2PollingSynchronizer(requestor, selectorSource, exec,
                    0, pollIntervalMillis, clientContext.getBaseLogger());
        }
    }

    static final class StreamingSynchronizerBuilderImpl extends StreamingSynchronizerBuilder {
        @Override
        public Synchronizer build(ClientContext clientContext) {
            ClientContextImpl impl = ClientContextImpl.get(clientContext);
            SelectorSource selectorSource = makeSelectorSource(impl);
            ServiceEndpoints endpoints = serviceEndpointsOverride != null
                    ? serviceEndpointsOverride
                    : clientContext.getServiceEndpoints();
            URI streamBase = StandardEndpoints.selectBaseUri(
                    endpoints.getStreamingBaseUri(),
                    StandardEndpoints.DEFAULT_STREAMING_BASE_URI,
                    "streaming", clientContext.getBaseLogger());
            URI pollingBase = StandardEndpoints.selectBaseUri(
                    endpoints.getPollingBaseUri(),
                    StandardEndpoints.DEFAULT_POLLING_BASE_URI,
                    "polling", clientContext.getBaseLogger());
            HttpProperties httpProps = LDUtil.makeHttpProperties(clientContext);
            FDv2Requestor requestor = new DefaultFDv2Requestor(
                    clientContext.getEvaluationContext(), pollingBase,
                    StandardEndpoints.FDV2_POLLING_REQUEST_GET_BASE_PATH,
                    StandardEndpoints.FDV2_POLLING_REQUEST_REPORT_BASE_PATH,
                    httpProps, clientContext.getHttp().isUseReport(),
                    clientContext.isEvaluationReasons(), null,
                    clientContext.getBaseLogger());
            return new FDv2StreamingSynchronizer(
                    clientContext.getEvaluationContext(), selectorSource, streamBase,
                    StandardEndpoints.FDV2_STREAMING_REQUEST_BASE_PATH,
                    requestor,
                    initialReconnectDelayMillis,
                    clientContext.isEvaluationReasons(), clientContext.getHttp().isUseReport(),
                    httpProps, Executors.newSingleThreadExecutor(),
                    clientContext.getBaseLogger(), null);
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

    private static SelectorSource makeSelectorSource(ClientContextImpl impl) {
        TransactionalDataStore store = impl.getTransactionalDataStore();
        return store != null
                ? new SelectorSourceFacade(store)
                : () -> com.launchdarkly.sdk.fdv2.Selector.EMPTY;
    }
}
