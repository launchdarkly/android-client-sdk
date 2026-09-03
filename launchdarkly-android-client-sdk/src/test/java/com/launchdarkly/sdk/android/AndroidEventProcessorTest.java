package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Behavior of the SDK's own event processor, covering the parts that are not about buffering under
 * load (see {@link EventProcessorBufferingTest} for those).
 */
public class AndroidEventProcessorTest extends EventProcessorTestBase {
    private static final String FLAG_KEY = "flag-key";
    private static final int FLAG_VERSION = 10;
    private static final int VARIATION = 1;
    private static final LDValue FLAG_VALUE = LDValue.of(true);
    private static final LDValue DEFAULT_VALUE = LDValue.of(false);

    private static final int DEFAULT_CAPACITY = 100;

    @Test
    public void untrackedEvaluationProducesOnlyASummary() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                recordEvaluation(eventProcessor, false, null);

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(1, events.size());
                assertEquals(1, summaryCountFor(events, FLAG_KEY));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void trackedEvaluationProducesAFeatureEventAndASummary() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordEvaluationEvent(CONTEXT, FLAG_KEY, FLAG_VERSION, VARIATION,
                        FLAG_VALUE, EvaluationReason.off(), DEFAULT_VALUE, true, null);

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                LDValue featureEvent = requireEventOfKind(events, "feature");
                assertEquals(LDValue.of(FLAG_KEY), featureEvent.get("key"));
                assertEquals(LDValue.of(VARIATION), featureEvent.get("variation"));
                assertEquals(FLAG_VALUE, featureEvent.get("value"));
                assertEquals(LDValue.of(FLAG_VERSION), featureEvent.get("version"));
                assertEquals(1, summaryCountFor(events, FLAG_KEY));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void identifyAndCustomEventsAreDelivered() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordIdentifyEvent(CONTEXT);
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.of("data"), 2.5);

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(2, events.size());
                requireEventOfKind(events, "identify");
                LDValue customEvent = requireEventOfKind(events, "custom");
                assertEquals(LDValue.of("an-event"), customEvent.get("key"));
                assertEquals(LDValue.of("data"), customEvent.get("data"));
                assertEquals(LDValue.of(2.5), customEvent.get("metricValue"));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void capacityLimitsFullEventsButNotSummaries() throws Exception {
        try (HttpServer server = startEventsServer()) {
            int capacity = 3;
            EventProcessor eventProcessor = makeEventProcessor(server, capacity);
            try {
                for (int i = 0; i < capacity + 2; i++) {
                    eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
                }
                // Evaluations well past capacity still have to be counted in full, because a
                // counter does not occupy a buffer slot.
                int evaluations = capacity * 100;
                for (int i = 0; i < evaluations; i++) {
                    recordEvaluation(eventProcessor, false, null);
                }

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(capacity, countEventsOfKind(events, "custom"));
                assertEquals(evaluations, summaryCountFor(events, FLAG_KEY));
                logging.assertWarnLogged("Exceeded event queue capacity");
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void closeDeliversBufferedEventsWithoutAnExplicitFlush() throws Exception {
        // This is the case the SDK previously lost: a short session that records something and
        // then shuts down before the periodic flush comes around.
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            recordEvaluation(eventProcessor, false, null);
            eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);

            eventProcessor.close();

            List<LDValue> events = collectDelivered(server);
            assertEquals(1, countEventsOfKind(events, "custom"));
            assertEquals(1, summaryCountFor(events, FLAG_KEY));
        }
    }

    @Test
    public void eventsRecordedWhileOfflineAreRetainedAndSentOnceOnline() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.setOffline(true);
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
                recordEvaluation(eventProcessor, false, null);

                eventProcessor.blockingFlush();
                server.getRecorder().requireNoRequests(100, TimeUnit.MILLISECONDS);

                eventProcessor.setOffline(false);
                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(1, countEventsOfKind(events, "custom"));
                assertEquals(1, summaryCountFor(events, FLAG_KEY));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void debugEventIsSentWhileDebuggingIsActive() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                long debugUntil = System.currentTimeMillis() + 3_600_000;
                recordEvaluation(eventProcessor, false, debugUntil);

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                LDValue debugEvent = requireEventOfKind(events, "debug");
                assertEquals(LDValue.of(FLAG_KEY), debugEvent.get("key"));
                // A debug event carries the full context rather than just its keys.
                assertEquals(LDValue.of(CONTEXT.getKey()), debugEvent.get("context").get("key"));
                assertEquals(1, summaryCountFor(events, FLAG_KEY));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void debugEventIsNotSentOnceDebuggingHasExpired() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                long debugUntil = System.currentTimeMillis() - 3_600_000;
                recordEvaluation(eventProcessor, false, debugUntil);

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(0, countEventsOfKind(events, "debug"));
                assertEquals(1, summaryCountFor(events, FLAG_KEY));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void recordingAfterCloseIsIgnored() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            eventProcessor.recordCustomEvent(CONTEXT, "before-close", LDValue.ofNull(), null);
            eventProcessor.close();
            collectDelivered(server);

            eventProcessor.recordCustomEvent(CONTEXT, "after-close", LDValue.ofNull(), null);
            eventProcessor.flush();

            server.getRecorder().requireNoRequests(100, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    public void diagnosticInitEventIsSentWhenComingOnline() throws Exception {
        try (HttpServer server = startEventsServer()) {
            // makeEventProcessor takes the processor online, which is what triggers the init event.
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY, false);
            try {
                RequestInfo request = server.getRecorder().requireRequest(10, TimeUnit.SECONDS);

                assertEquals("/mobile/events/diagnostic", request.getPath());
                LDValue body = LDValue.parse(request.getBody());
                assertEquals(LDValue.of("diagnostic-init"), body.get("kind"));
                // Only one, even though going online and coming to the foreground both ask for it.
                eventProcessor.setInBackground(false);
                server.getRecorder().requireNoRequests(100, TimeUnit.MILLISECONDS);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void diagnosticInitEventIsNotSentWhileOffline() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY, false);
            try {
                server.getRecorder().requireRequest(10, TimeUnit.SECONDS); // the init event
                eventProcessor.setOffline(true);
                eventProcessor.setInBackground(true);

                server.getRecorder().requireNoRequests(100, TimeUnit.MILLISECONDS);
            } finally {
                eventProcessor.close();
            }
        }
    }

    private void recordEvaluation(EventProcessor eventProcessor, boolean requireFullEvent,
                                  Long debugEventsUntilDate) {
        eventProcessor.recordEvaluationEvent(CONTEXT, FLAG_KEY, FLAG_VERSION, VARIATION, FLAG_VALUE,
                null, DEFAULT_VALUE, requireFullEvent, debugEventsUntilDate);
    }
}
