package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.integrations.HttpConfigurationBuilder;
import com.launchdarkly.sdk.android.integrations.PollingDataSourceBuilder;
import com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder;
import com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DiagnosticDescription;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;
import com.launchdarkly.sdk.android.subsystems.ServiceEndpoints;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * This class contains the package-private implementations of component factories and builders whose
 * public factory methods are in {@link Components}.
 */
abstract class ComponentsImpl {
    private ComponentsImpl() {
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
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public void close() {}

        @Override
        public void recordEvaluationEvent(LDUser user, String flagKey, int flagVersion, int variation, LDValue value,
                                          EvaluationReason reason, LDValue defaultValue, boolean requireFullEvent,
                                          Long debugEventsUntilDate) {}

        @Override
        public void recordIdentifyEvent(LDUser user) {}

        @Override
        public void recordCustomEvent(LDUser user, String eventKey, LDValue data, Double metricValue) {}

        @Override
        public void recordAliasEvent(LDUser user, LDUser previousUser) {}

        @Override
        public void setOffline(boolean offline) {}
    }

    static final class EventProcessorBuilderImpl extends EventProcessorBuilder
            implements DiagnosticDescription {
        // see comments in LDConfig constructor regarding the purpose of these package-private getters
        boolean isAllAttributesPrivate() {
            return allAttributesPrivate;
        }

        int getDiagnosticRecordingIntervalMillis() { return diagnosticRecordingIntervalMillis; }

        Set<String> getPrivateAttributes() {
            return privateAttributes;
        }

        @Override
        public EventProcessor build(ClientContext clientContext) {
            ClientContextImpl clientContextImpl = ClientContextImpl.get(clientContext);
            URI eventsUri = StandardEndpoints.selectBaseUri(clientContext.getServiceEndpoints().getEventsBaseUri(),
                    StandardEndpoints.DEFAULT_EVENTS_BASE_URI, "events", clientContext.getBaseLogger());
            return new DefaultEventProcessor(
                    clientContext.getApplication(),
                    clientContext.getConfig(),
                    clientContext.getHttp(),
                    eventsUri,
                    clientContextImpl.getSummaryEventStore(),
                    clientContext.getEnvironmentName(),
                    clientContext.isInitiallySetOffline(),
                    capacity,
                    flushIntervalMillis,
                    inlineUsers,
                    clientContextImpl.getDiagnosticStore(),
                    clientContextImpl.getSharedEventClient(),
                    clientContext.getBaseLogger()
            );
        }

        @Override
        public LDValue describeConfiguration(ClientContext clientContext) {
            return LDValue.buildObject()
                    .put("allAttributesPrivate", allAttributesPrivate)
                    .put("diagnosticRecordingIntervalMillis", diagnosticRecordingIntervalMillis)
                    .put("eventsCapacity", capacity)
                    .put("diagnosticRecordingIntervalMillis", diagnosticRecordingIntervalMillis)
                    .put("eventsFlushIntervalMillis", flushIntervalMillis)
                    .put("inlineUsersInEvents", inlineUsers)
                    .build();
        }
    }

    static final class HttpConfigurationBuilderImpl extends HttpConfigurationBuilder
            implements DiagnosticDescription {
        @Override
        public HttpConfiguration build(ClientContext clientContext) {
            // Build the default headers
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", LDUtil.AUTH_SCHEME + clientContext.getMobileKey());
            headers.put("User-Agent", LDUtil.USER_AGENT_HEADER_VALUE);
            if (clientContext.getApplicationInfo() != null) {
                String tagHeader = LDUtil.applicationTagHeader(clientContext.getApplicationInfo(),
                        clientContext.getBaseLogger());
                if (!tagHeader.isEmpty()) {
                    headers.put("X-LaunchDarkly-Tags", tagHeader);
                }
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
            implements DiagnosticDescription {
        @Override
        public DataSource build(ClientContext clientContext) {
            return new DataSourceImpl(true, backgroundPollIntervalMillis, 0,
                    pollIntervalMillis, false);
        }

        @Override
        public LDValue describeConfiguration(ClientContext clientContext) {
            return LDValue.buildObject()
                    .put("streamingDisabled", true)
                    .put("backgroundPollingIntervalMillis", backgroundPollIntervalMillis)
                    .put("pollingIntervalMillis", pollIntervalMillis)
                    .build();
        }
    }

    static final class ServiceEndpointsBuilderImpl extends ServiceEndpointsBuilder {
        @Override
        public ServiceEndpoints build() {
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

    static final class StreamingDataSourceBuilderImpl extends StreamingDataSourceBuilder
            implements DiagnosticDescription {
        @Override
        public DataSource build(ClientContext clientContext) {
            return new DataSourceImpl(false, backgroundPollIntervalMillis,
                    initialReconnectDelayMillis, 0, streamEvenInBackground);
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

    private static final class DataSourceImpl implements DataSource {
        private final boolean streamingDisabled;
        private final int backgroundPollIntervalMillis;
        private final int initialReconnectDelayMillis;
        private final int pollIntervalMillis;
        private final boolean streamEvenInBackground;

        DataSourceImpl(
                boolean streamingDisabled,
                int backgroundPollIntervalMillis,
                int initialReconnectDelayMillis,
                int pollIntervalMillis,
                boolean streamEvenInBackground
        ) {
            this.streamingDisabled = streamingDisabled;
            this.backgroundPollIntervalMillis = backgroundPollIntervalMillis;
            this.initialReconnectDelayMillis = initialReconnectDelayMillis;
            this.pollIntervalMillis = pollIntervalMillis;
            this.streamEvenInBackground = streamEvenInBackground;
        }

        public boolean isStreamingDisabled() {
            return streamingDisabled;
        }

        public int getBackgroundPollIntervalMillis() {
            return backgroundPollIntervalMillis;
        }

        public int getInitialReconnectDelayMillis() {
            return initialReconnectDelayMillis;
        }

        public int getPollIntervalMillis() {
            return pollIntervalMillis;
        }

        public boolean isStreamEvenInBackground() {
            return streamEvenInBackground;
        }
    }
}
