package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.integrations.ApplicationInfoBuilder;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.integrations.HooksConfigurationBuilder;
import com.launchdarkly.sdk.android.integrations.HttpConfigurationBuilder;
import com.launchdarkly.sdk.android.integrations.PluginsConfigurationBuilder;
import com.launchdarkly.sdk.android.integrations.PollingDataSourceBuilder;
import com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder;
import com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;

import static com.launchdarkly.sdk.android.ComponentsImpl.NULL_EVENT_PROCESSOR_FACTORY;

/**
 * Provides configurable factories for the standard implementations of LaunchDarkly component interfaces.
 * <p>
 * Some of the configuration options in {@link LDConfig.Builder} affect the entire SDK, but others are
 * specific to one area of functionality, such as how the SDK receives feature flag updates or processes
 * analytics events. For the latter, the standard way to specify a configuration is to call one of the
 * static methods in {@link Components}, apply any desired configuration change to the object that that
 * method returns, and then use the corresponding method in {@link LDConfig.Builder} to use that
 * configured component in the SDK.
 *
 * @since 3.3.0
 */
public abstract class Components {
    private Components() {}

    /**
     * Returns a configuration builder for the SDK's application metadata.
     * <p>
     * Passing this to {@link LDConfig.Builder#applicationInfo(com.launchdarkly.sdk.android.integrations.ApplicationInfoBuilder)},
     * after setting any desired properties on the builder, applies this configuration to the SDK.
     * <pre><code>
     *     LDConfig config = new LDConfig.Builder()
     *         .applicationInfo(
     *             Components.applicationInfo()
     *                 .applicationId("authentication-service")
     *                 .applicationVersion("1.0.0")
     *         )
     *         .build();
     * </code></pre>
     *
     * @return a builder object
     * @see LDConfig.Builder#applicationInfo(com.launchdarkly.sdk.android.integrations.ApplicationInfoBuilder)
     * @since 4.1.0
     */
    public static ApplicationInfoBuilder applicationInfo() {
        return new ApplicationInfoBuilder();
    }

    /**
     * Returns a configuration builder for the SDK's networking configuration.
     * <p>
     * Passing this to {@link LDConfig.Builder#http(ComponentConfigurer)} applies this configuration
     * to all HTTP/HTTPS requests made by the SDK.
     * <pre><code>
     *     LDConfig config = new LDConfig.Builder()
     *         .http(
     *              Components.httpConfiguration()
     *                  .connectTimeoutMillis(3000)
     *                  .proxyHostAndPort("my-proxy", 8080)
     *         )
     *         .build();
     * </code></pre>
     *
     * @return a factory object
     * @see LDConfig.Builder#http(ComponentConfigurer)
     */
    public static HttpConfigurationBuilder httpConfiguration() {
        return new ComponentsImpl.HttpConfigurationBuilderImpl();
    }

    /**
     * Returns a configuration object that disables analytics events.
     * <p>
     * Passing this to {@link LDConfig.Builder#events(ComponentConfigurer)} causes the SDK
     * to discard all analytics events and not send them to LaunchDarkly, regardless of any other configuration.
     * <pre><code>
     *     LDConfig config = new LDConfig.Builder()
     *         .events(Components.noEvents())
     *         .build();
     * </code></pre>
     *
     * @return a configuration object
     * @see #sendEvents()
     * @see LDConfig.Builder#events(ComponentConfigurer)
     */
    public static ComponentConfigurer<EventProcessor> noEvents() {
        return NULL_EVENT_PROCESSOR_FACTORY;
    }

    /**
     * Returns a configuration builder for using polling mode to get feature flag data.
     * <p>
     * By default, the SDK uses a streaming connection to receive feature flag data from LaunchDarkly. To use the
     * default behavior, you do not need to call this method. However, if you want to customize the behavior of
     * the connection, call this method to obtain a builder, change its properties with the
     * {@link PollingDataSourceBuilder} methods, and pass it to {@link LDConfig.Builder#dataSource(ComponentConfigurer)}:
     * <pre><code>
     *     LDConfig config = new LDConfig.Builder()
     *         .dataSource(Components.pollingDataSource().initialReconnectDelayMillis(500))
     *         .build();
     * </code></pre>
     * <p>
     * Setting {@link LDConfig.Builder#offline(boolean)} to {@code true} will supersede this setting
     * and completely disable network requests.
     *
     * @return a builder for setting streaming connection properties
     * @see LDConfig.Builder#dataSource(ComponentConfigurer)
     */
    public static PollingDataSourceBuilder pollingDataSource() {
        return new ComponentsImpl.PollingDataSourceBuilderImpl();
    }

    /**
     * Returns a configuration builder for analytics event delivery.
     * <p>
     * The default configuration has events enabled with default settings. If you want to
     * customize this behavior, call this method to obtain a builder, change its properties
     * with the {@link EventProcessorBuilder} properties, and pass it to {@link LDConfig.Builder#events(ComponentConfigurer)}:
     * <pre><code>
     *     LDConfig config = new LDConfig.Builder()
     *         .events(Components.sendEvents().capacity(500).flushIntervalMillis(2000))
     *         .build();
     * </code></pre>
     * To completely disable sending analytics events, use {@link #noEvents()} instead.
     * <p>
     * Setting {@link LDConfig.Builder#offline(boolean)} to {@code true} will supersede this setting
     * and completely disable network requests.
     *
     * @return a builder for setting event-related options
     * @see #noEvents()
     * @see LDConfig.Builder#events(ComponentConfigurer)
     */
    public static EventProcessorBuilder sendEvents() {
        return new ComponentsImpl.EventProcessorBuilderImpl();
    }

    /**
     * Returns a builder for configuring custom service URIs.
     * <p>
     * Passing this to {@link LDConfig.Builder#serviceEndpoints(com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder)},
     * after setting any desired properties on the builder, applies this configuration to the SDK.
     * <pre><code>
     *     LDConfig config = new LDConfig.Builder()
     *         .serviceEndpoints(
     *             Components.serviceEndpoints()
     *                 .relayProxy("http://my-relay-hostname:80")
     *         )
     *         .build();
     * </code></pre>
     *
     * @return a builder object
     * @see LDConfig.Builder#serviceEndpoints(com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder)
     */
    public static ServiceEndpointsBuilder serviceEndpoints() {
        return new ComponentsImpl.ServiceEndpointsBuilderImpl();
    }

    /**
     * Returns a configuration builder for using streaming mode to get feature flag data.
     * <p>
     * By default, the SDK uses a streaming connection to receive feature flag data from LaunchDarkly. To use the
     * default behavior, you do not need to call this method. However, if you want to customize the behavior of
     * the connection, call this method to obtain a builder, change its properties with the
     * {@link StreamingDataSourceBuilder} methods, and pass it to {@link LDConfig.Builder#dataSource(ComponentConfigurer)}:
     * <pre><code>
     *     LDConfig config = new LDConfig.Builder()
     *         .dataSource(Components.streamingDataSource().initialReconnectDelayMillis(500))
     *         .build();
     * </code></pre>
     * <p>
     * Setting {@link LDConfig.Builder#offline(boolean)} to {@code true} will supersede this setting
     * and completely disable network requests.
     *
     * @return a builder for setting streaming connection properties
     * @see LDConfig.Builder#dataSource(ComponentConfigurer)
     */
    public static StreamingDataSourceBuilder streamingDataSource() {
        return new ComponentsImpl.StreamingDataSourceBuilderImpl();
    }

    /**
     * Returns a builder for configuring hooks.
     * Passing this to {@link LDConfig.Builder#hooks(com.launchdarkly.sdk.android.integrations.HooksConfigurationBuilder)},
     * after setting any desired hooks on the builder, applies this configuration to the SDK.
     * <pre><code>
     *     List hooks = myCreateHooksFunc();
     *     LDConfig config = new LDConfig.Builder()
     *         .hooks(
     *             Components.hooks()
     *                 .setHooks(hooks)
     *         )
     *         .build();
     * </code></pre>
     * @return a {@link HooksConfigurationBuilder} that can be used for customization
     */
    public static HooksConfigurationBuilder hooks() {
        return new ComponentsImpl.HooksConfigurationBuilderImpl();
    }

    /**
     * Returns a builder for configuring plugins.
     * Passing this to {@link LDConfig.Builder#plugins(com.launchdarkly.sdk.android.integrations.PluginsConfigurationBuilder)},
     * after setting any desired plugins on the builder, applies this configuration to the SDK.
     * <pre><code>
     *     List plugins = getPluginsFunc();
     *     LDConfig config = new LDConfig.Builder()
     *         .plugins(
     *             Components.plugins()
     *                 .setPlugins(plugins)
     *         )
     *         .build();
     * </code></pre>
     *
     * @return a {@link PluginsConfigurationBuilder} for plugins configuration
     */
    public static PluginsConfigurationBuilder plugins() {
        return new ComponentsImpl.PluginsConfigurationBuilderImpl();
    }
}
