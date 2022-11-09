package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DiagnosticDescription;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;

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

        private NullEventProcessor() {
        }

        @Override
        public void flush() {
        }

        @Override
        public void blockingFlush() {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
        }

        @Override
        public void recordEvaluationEvent(LDUser user, String flagKey, int flagVersion, int variation, LDValue value,
                                          EvaluationReason reason, LDValue defaultValue, boolean requireFullEvent,
                                          Long debugEventsUntilDate) {
        }

        @Override
        public void recordIdentifyEvent(LDUser user) {
        }

        @Override
        public void recordCustomEvent(LDUser user, String eventKey, LDValue data, Double metricValue) {
        }

        @Override
        public void recordAliasEvent(LDUser user, LDUser previousUser) {
        }

        @Override
        public void setOffline(boolean offline) {
        }
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
            return new DefaultEventProcessor(
                    clientContext.getApplication(),
                    clientContext.getConfig(),
                    clientContextImpl.getSummaryEventStore(),
                    clientContext.getEnvironmentName(),
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
                    .put("eventsCapacity", capacity)
                    .put("eventsFlushIntervalMillis", flushIntervalMillis)
                    .put("inlineUsersInEvents", inlineUsers)
                    .build();
        }
    }
}
