package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.android.ComponentsImpl.NULL_EVENT_PROCESSOR_FACTORY;

import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;

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
}
