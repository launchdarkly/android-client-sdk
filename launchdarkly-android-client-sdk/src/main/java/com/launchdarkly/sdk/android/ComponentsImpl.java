package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.integrations.HttpConfigurationBuilder;
import com.launchdarkly.sdk.android.integrations.PollingDataSourceBuilder;
import com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder;
import com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder;
import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DiagnosticDescription;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;
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
                            0, // use default retry delay
                            clientContext.getBaseLogger()),
                    1, // eventSendingThreadPoolSize
                    clientContext.getServiceEndpoints().getEventsBaseUri(),
                    flushIntervalMillis,
                    clientContext.isInBackground(),
                    true, // initiallyOffline
                    privateAttributes
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
            clientContext.getDataSourceUpdateSink().setStatus(
                    clientContext.isInBackground() ? ConnectionInformation.ConnectionMode.BACKGROUND_POLLING :
                            ConnectionInformation.ConnectionMode.POLLING,
                    null
            );
            int actualPollIntervalMillis = clientContext.isInBackground() ? backgroundPollIntervalMillis :
                    pollIntervalMillis;
            int initialDelayMillis;
            if (clientContext.isInBackground() && Boolean.FALSE.equals(clientContext.getPreviouslyInBackground())) {
                // If we're transitioning from foreground to background, then we don't want to do a
                // poll right away because we already have recent flag data. Start polling *after*
                // the first background poll interval.
                initialDelayMillis = backgroundPollIntervalMillis;
            } else {
                // If we're in the foreground-- or, if we're in the background but we started out
                // that way rather than transitioning-- then we should do the first poll right away.
                initialDelayMillis = 0;
            }
            ClientContextImpl clientContextImpl = ClientContextImpl.get(clientContext);
            return new PollingDataSource(
                    clientContext.getEvaluationContext(),
                    clientContext.getDataSourceUpdateSink(),
                    initialDelayMillis,
                    actualPollIntervalMillis,
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
            if (clientContext.isInBackground()) {
                return Components.pollingDataSource()
                        .backgroundPollIntervalMillis(backgroundPollIntervalMillis)
                        .pollIntervalMillis(backgroundPollIntervalMillis)
                        .build(clientContext);
            }
            clientContext.getDataSourceUpdateSink().setStatus(ConnectionInformation.ConnectionMode.STREAMING, null);
            ClientContextImpl clientContextImpl = ClientContextImpl.get(clientContext);
            return new StreamUpdateProcessor(
                    clientContext,
                    clientContext.getEvaluationContext(),
                    clientContext.getDataSourceUpdateSink(),
                    clientContextImpl.getFetcher(),
                    initialReconnectDelayMillis
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

    // Marker interface for data source implementations that will require a FeatureFetcher
    interface DataSourceRequiresFeatureFetcher {}
}
