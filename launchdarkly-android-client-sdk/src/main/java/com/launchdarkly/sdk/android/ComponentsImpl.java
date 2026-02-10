package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.integrations.HooksConfigurationBuilder;
import com.launchdarkly.sdk.android.integrations.HttpConfigurationBuilder;
import com.launchdarkly.sdk.android.integrations.PluginsConfigurationBuilder;
import com.launchdarkly.sdk.android.integrations.PollingDataSourceBuilder;
import com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder;
import com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder;
import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DiagnosticDescription;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.sdk.android.subsystems.HookConfiguration;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;
import com.launchdarkly.sdk.android.subsystems.PluginsConfiguration;
import com.launchdarkly.sdk.internal.events.DefaultEventProcessor;
import com.launchdarkly.sdk.internal.events.DefaultEventSender;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.sdk.internal.events.EventsConfiguration;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * This class contains the package-private implementations of component factories and builders whose
 * public factory methods are in {@link Components}.
 */
abstract class ComponentsImpl {
    private ComponentsImpl() {}

    static final class ServiceEndpointsBuilderImpl extends ServiceEndpointsBuilder {
        @Override
        public ServiceEndpoints createServiceEndpoints() {
            // If *any* custom URIs have been set, then we do not want to use default values for any that were not set,
            // so we will leave those null. That way, if we decide later on (in other component factories, such as
            // EventProcessorBuilder) that we are actually interested in one of these values, and we
            // see that it is null, we can assume that there was a configuration mistake and log an
            // error.
            if (streamingBaseUri == null && pollingBaseUri == null && eventsBaseUri == null) {
                return new ServiceEndpoints(
                        StandardEndpoints.DEFAULT_STREAMING_BASE_URI,
                        StandardEndpoints.DEFAULT_POLLING_BASE_URI,
                        StandardEndpoints.DEFAULT_EVENTS_BASE_URI
                );
            }
            return new ServiceEndpoints(streamingBaseUri, pollingBaseUri, eventsBaseUri);
        }
    }

    static final ComponentConfigurer<EventProcessor> NULL_EVENT_PROCESSOR_FACTORY = new ComponentConfigurer<EventProcessor>() {
        public EventProcessor build(ClientContext clientContext) {
            return NullEventProcessor.INSTANCE;
        }
    };

    /**
     * Stub implementation of {@link EventProcessor} for when we don't want to send any events.
     */
    static final class NullEventProcessor implements EventProcessor {
        static final NullEventProcessor INSTANCE = new NullEventProcessor();

        private NullEventProcessor() {}

        @Override
        public void flush() {}

        @Override
        public void blockingFlush() {}

        @Override
        public void setInBackground(boolean inBackground) {}

        @Override
        public void setOffline(boolean offline) {}

        @Override
        public void close() {}

        @Override
        public void recordEvaluationEvent(LDContext context, String flagKey, int flagVersion, int variation, LDValue value,
                                          EvaluationReason reason, LDValue defaultValue, boolean requireFullEvent,
                                          Long debugEventsUntilDate) {}

        @Override
        public void recordIdentifyEvent(LDContext context) {}

        @Override
        public void recordCustomEvent(LDContext context, String eventKey, LDValue data, Double metricValue) {}
    }

    static final class EventProcessorBuilderImpl extends EventProcessorBuilder
            implements DiagnosticDescription {
        @Override
        public EventProcessor build(ClientContext clientContext) {
            ClientContextImpl clientContextImpl = ClientContextImpl.get(clientContext);
            EventsConfiguration eventsConfig = new EventsConfiguration(
                    allAttributesPrivate,
                    capacity,
                    null, // contextDeduplicator - not needed for client-side use
                    diagnosticRecordingIntervalMillis,
                    clientContextImpl.getDiagnosticStore(),
                    new DefaultEventSender(
                            LDUtil.makeHttpProperties(clientContext),
                            StandardEndpoints.ANALYTICS_EVENTS_REQUEST_PATH,
                            StandardEndpoints.DIAGNOSTIC_EVENTS_REQUEST_PATH,
                            0L, // use default retry delay
                            false, // disable gzip compression for Android
                            clientContext.getBaseLogger()),
                    1, // eventSendingThreadPoolSize
                    clientContext.getServiceEndpoints().getEventsBaseUri(),
                    flushIntervalMillis,
                    clientContext.isInBackground(),
                    true, // initiallyOffline
                    privateAttributes,
                    true // perContextSummarization - enable for client SDK
            );
            return new DefaultEventProcessorWrapper(new DefaultEventProcessor(
                    eventsConfig,
                    EventUtil.makeEventsTaskExecutor(),
                    Thread.NORM_PRIORITY, // note, we may want to make this configurable as it is in java-server-sdk
                    clientContext.getBaseLogger()
            ));
        }

        @Override
        public LDValue describeConfiguration(ClientContext clientContext) {
            return LDValue.buildObject()
                    .put("allAttributesPrivate", allAttributesPrivate)
                    .put("diagnosticRecordingIntervalMillis", diagnosticRecordingIntervalMillis)
                    .put("eventsCapacity", capacity)
                    .put("diagnosticRecordingIntervalMillis", diagnosticRecordingIntervalMillis)
                    .put("eventsFlushIntervalMillis", flushIntervalMillis)
                    .build();
        }

        /**
         * Adapter from the public component interface of EventProcessor to the internal
         * implementation class from java-sdk-internal.
         */
        private final class DefaultEventProcessorWrapper implements EventProcessor {
            private final DefaultEventProcessor eventProcessor;

            DefaultEventProcessorWrapper(DefaultEventProcessor eventProcessor) {
                this.eventProcessor = eventProcessor;
            }

            @Override
            public void recordEvaluationEvent(
                    LDContext context,
                    String flagKey,
                    int flagVersion,
                    int variation,
                    LDValue value,
                    EvaluationReason reason,
                    LDValue defaultValue,
                    boolean requireFullEvent,
                    Long debugEventsUntilDate
            ) {
                eventProcessor.sendEvent(new Event.FeatureRequest(
                        System.currentTimeMillis(), flagKey, context, flagVersion, variation,
                        value, defaultValue, reason, null, requireFullEvent,
                        debugEventsUntilDate, false));
            }

            @Override
            public void recordIdentifyEvent(LDContext context) {
                eventProcessor.sendEvent(new Event.Identify(System.currentTimeMillis(), context));
            }

            @Override
            public void recordCustomEvent(LDContext context, String eventKey, LDValue data, Double metricValue) {
                eventProcessor.sendEvent(new Event.Custom(System.currentTimeMillis(), eventKey,
                        context, data, metricValue));
            }

            @Override
            public void setInBackground(boolean inBackground) {
                eventProcessor.setInBackground(inBackground);
            }

            @Override
            public void setOffline(boolean offline) {
                eventProcessor.setOffline(offline);
            }

            @Override
            public void flush() {
                eventProcessor.flushAsync();
            }

            @Override
            public void blockingFlush() {
                eventProcessor.flushBlocking();
            }

            @Override
            public void close() throws IOException {
                eventProcessor.close();
            }
        }
    }

    static final class HttpConfigurationBuilderImpl extends HttpConfigurationBuilder
            implements DiagnosticDescription {
        @Override
        public HttpConfiguration build(ClientContext clientContext) {
            LDLogger logger = clientContext.getBaseLogger();
            // Build the default headers
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", LDUtil.AUTH_SCHEME + clientContext.getMobileKey());
            headers.put("User-Agent", LDUtil.USER_AGENT_HEADER_VALUE);
            String tagHeader = LDUtil.applicationTagHeader(
                    clientContext.getEnvironmentReporter().getApplicationInfo(),
                    clientContext.getBaseLogger()
            );
            if (!tagHeader.isEmpty()) {
                headers.put("X-LaunchDarkly-Tags", tagHeader);
            }
            if (wrapperName != null) {
                String wrapperId = wrapperVersion == null ? wrapperName : (wrapperName + "/" + wrapperVersion);
                headers.put("X-LaunchDarkly-Wrapper", wrapperId);
            }

            return new HttpConfiguration(
                    connectTimeoutMillis,
                    headers,
                    headerTransform,
                    useReport
            );
        }

        @Override
        public LDValue describeConfiguration(ClientContext clientContext) {
            return LDValue.buildObject()
                    .put("connectTimeoutMillis", connectTimeoutMillis)
                    .put("useReport", useReport)
                    .build();
        }
    }

    static final class PollingDataSourceBuilderImpl extends PollingDataSourceBuilder
            implements DiagnosticDescription, DataSourceRequiresFeatureFetcher {
        @Override
        public DataSource build(ClientContext clientContext) {
            ClientContextImpl clientContextImpl = ClientContextImpl.get(clientContext);
            clientContextImpl.getDataSourceUpdateSink().setStatus(
                    clientContextImpl.isInBackground() ? ConnectionInformation.ConnectionMode.BACKGROUND_POLLING :
                            ConnectionInformation.ConnectionMode.POLLING,
                    null
            );

            int pollInterval = clientContextImpl.isInBackground() ? backgroundPollIntervalMillis :
                    pollIntervalMillis;

            // get the last updated timestamp for this context
            PersistentDataStoreWrapper.PerEnvironmentData perEnvironmentData = clientContextImpl.getPerEnvironmentData();
            String hashedContextId = LDUtil.urlSafeBase64HashedContextId(clientContextImpl.getEvaluationContext());
            String fingerprint = LDUtil.urlSafeBase64Hash(clientContextImpl.getEvaluationContext());
            Long lastUpdated = perEnvironmentData.getLastUpdated(hashedContextId, fingerprint);
            if (lastUpdated == null) {
                lastUpdated = 0L; // default to beginning of time
            }

            // To avoid unnecessarily frequent polling requests due to process or application lifecycle, we have added
            // this rate limiting logic. Calculate how much time has passed since the last update, if that is less than
            // the polling interval, delay to when the next poll would have occurred, otherwise 0 delay.
            long elapsedSinceUpdate = System.currentTimeMillis() - lastUpdated;
            long initialDelayMillis = Math.max(pollInterval - elapsedSinceUpdate, 0);

            long maxNumPolls = Long.MAX_VALUE; // effectively unlimited number of polls
            if (oneShot) {
                if (initialDelayMillis > 0) {
                    clientContext.getBaseLogger().info("One shot polling attempt will be blocked by rate limiting.");
                    maxNumPolls = 0; // one shot was blocked by rate limiting logic, so never poll
                } else {
                    maxNumPolls = 1; // one shot was not blocked by rate limiting logic
                }
            }

            return new PollingDataSource(
                    clientContextImpl.getEvaluationContext(),
                    clientContextImpl.getDataSourceUpdateSink(),
                    initialDelayMillis,
                    pollInterval,
                    maxNumPolls,
                    clientContextImpl.getFetcher(),
                    clientContextImpl.getPlatformState(),
                    clientContextImpl.getTaskExecutor(),
                    clientContext.getBaseLogger()
            );
        }

        @Override
        public LDValue describeConfiguration(ClientContext clientContext) {
            return LDValue.buildObject()
                    .put("streamingDisabled", true)
                    .put("backgroundPollingIntervalMillis", backgroundPollIntervalMillis)
                    .put("pollingIntervalMillis", pollIntervalMillis)
                    .build();
        }

        // This method is for testing - not exposed in the public PollingDataSourceBuilder
        public PollingDataSourceBuilder backgroundPollIntervalMillisNoMinimum(int backgroundPollIntervalMillis) {
            this.backgroundPollIntervalMillis = backgroundPollIntervalMillis;
            return this;
        }

        // This method is for testing - not exposed in the public PollingDataSourceBuilder
        public PollingDataSourceBuilder pollIntervalMillisNoMinimum(int pollIntervalMillis) {
            this.pollIntervalMillis = pollIntervalMillis;
            return this;
        }
    }

    static final class StreamingDataSourceBuilderImpl extends StreamingDataSourceBuilder
            implements DiagnosticDescription, DataSourceRequiresFeatureFetcher {
        @Override
        public DataSource build(ClientContext clientContext) {
            // Even though this is called StreamingDataSourceBuilder, it doesn't always create a
            // streaming data source. By default, we only do streaming when in the foreground; if
            // we're in the background, this builder delegates to the *polling* builder. But that
            // can be overridden with streamEvenInBackground.
            if (clientContext.isInBackground() && !streamEvenInBackground) {
                return Components.pollingDataSource()
                        .backgroundPollIntervalMillis(backgroundPollIntervalMillis)
                        .pollIntervalMillis(backgroundPollIntervalMillis)
                        .build(clientContext);
            }
            clientContext.getDataSourceUpdateSink().setStatus(ConnectionInformation.ConnectionMode.STREAMING, null);
            ClientContextImpl clientContextImpl = ClientContextImpl.get(clientContext);
            return new StreamingDataSource(
                    clientContext,
                    clientContext.getEvaluationContext(),
                    clientContext.getDataSourceUpdateSink(),
                    clientContextImpl.getFetcher(),
                    initialReconnectDelayMillis,
                    streamEvenInBackground
            );
        }

        @Override
        public LDValue describeConfiguration(ClientContext clientContext) {
            return LDValue.buildObject()
                    .put("streamingDisabled", false)
                    .put("backgroundPollingIntervalMillis", backgroundPollIntervalMillis)
                    .put("reconnectTimeMillis", initialReconnectDelayMillis)
                    .build();
        }
    }

    static final class HooksConfigurationBuilderImpl extends HooksConfigurationBuilder {
        public static HooksConfigurationBuilderImpl fromHooksConfiguration(HookConfiguration hooksConfiguration) {
            HooksConfigurationBuilderImpl builder = new HooksConfigurationBuilderImpl();
            builder.setHooks(hooksConfiguration.getHooks());
            return builder;
        }

        @Override
        public HookConfiguration build() {
            return new HookConfiguration(hooks);
        }
    }

    static final class PluginsConfigurationBuilderImpl extends PluginsConfigurationBuilder {

        @Override
        public PluginsConfiguration build() {
            return new PluginsConfiguration(plugins);
        }
    }

    // Marker interface for data source implementations that will require a FeatureFetcher
    interface DataSourceRequiresFeatureFetcher {}
}
