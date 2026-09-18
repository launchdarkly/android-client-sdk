package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EventPersistence;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Behavior of the SDK's own event processor, covering the parts that are not about buffering under
 * load (see {@link EventProcessorBufferingTest} for those).
 */
public class DirectEventProcessorTest extends EventProcessorTestBase {
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
    public void flushPersistsFeatureAndSummaryEventsSynchronouslyWhileOffline() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordEvaluationEvent(CONTEXT, FLAG_KEY, FLAG_VERSION, VARIATION,
                        FLAG_VALUE, EvaluationReason.off(), DEFAULT_VALUE, true, null);
                eventProcessor.setOffline(true);

                eventProcessor.flush();

                EventStore reader = EventStore.create(eventsDirectory.getRoot(), MOBILE_KEY, "test",
                        DEFAULT_CAPACITY, true, logging.logger);
                List<byte[]> persisted = reader.pendingEventPayloads();
                int featureEvents = 0;
                int summaryEvents = 0;
                for (byte[] payload : persisted) {
                    String kind = LDValue.parse(new String(payload, "UTF-8")).get("kind").stringValue();
                    featureEvents += "feature".equals(kind) ? 1 : 0;
                    summaryEvents += "summary".equals(kind) ? 1 : 0;
                }
                assertEquals(1, featureEvents);
                assertEquals(1, summaryEvents);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void immediatePersistencePutsATrackOnDiskBeforeItReturns() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    eventsBuilder(DEFAULT_CAPACITY).eventPersistence(EventPersistence.IMMEDIATE), true);
            try {
                // Offline so a delivery cannot drain the store out from under the assertion. The commit
                // this is about runs either way; only the sending is held back.
                eventProcessor.setOffline(true);
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.of("data"), 2.5);

                // No waiting and no flush: the guarantee is that the call did the work before returning, which
                // is the whole of what this setting buys and the only way a SIGKILL here still reports it.
                assertEquals(1, kindsOnDisk("custom"));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void deferredPersistenceStillPutsATrackOnDisk() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    eventsBuilder(DEFAULT_CAPACITY).eventPersistence(EventPersistence.DEFERRED), true);
            try {
                // Offline for the same reason as the immediate case: delivery would race the read.
                eventProcessor.setOffline(true);
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.of("data"), 2.5);

                // Durable a moment later rather than immediately: the commit was queued, not skipped.
                long deadline = System.currentTimeMillis() + 5_000;
                while (kindsOnDisk("custom") == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(1, kindsOnDisk("custom"));
            } finally {
                eventProcessor.close();
            }
        }
    }

    /** @return how many events of this kind the store holds, read as another process would read it */
    private int kindsOnDisk(String kind) throws Exception {
        EventStore reader = EventStore.create(eventsDirectory.getRoot(), MOBILE_KEY, "test",
                DEFAULT_CAPACITY, true, logging.logger);
        int found = 0;
        for (byte[] payload : reader.pendingEventPayloads()) {
            if (kind.equals(LDValue.parse(new String(payload, "UTF-8")).get("kind").stringValue())) {
                found++;
            }
        }
        return found;
    }

    @Test
    public void persistenceIsOffUnlessTheApplicationAsksForIt() throws Exception {
        try (HttpServer server = startEventsServer()) {
            // Deliberately not eventsBuilder(), which turns persistence on: this is what an application
            // gets without configuring anything.
            EventProcessor eventProcessor = makeEventProcessor(server,
                    Components.sendEvents()
                            .capacity(DEFAULT_CAPACITY)
                            .flushIntervalMillis(NO_PERIODIC_FLUSH_MILLIS),
                    true);
            try {
                eventProcessor.recordIdentifyEvent(CONTEXT);
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.of("data"), 2.5);

                EventStore reader = EventStore.create(eventsDirectory.getRoot(), MOBILE_KEY, "test",
                        DEFAULT_CAPACITY, true, logging.logger);
                assertTrue("an event reached the disk without being asked to",
                        reader.pendingEventPayloads().isEmpty());

                // Held in memory instead, so turning persistence off costs durability and nothing else.
                List<LDValue> events = flushAndCollect(eventProcessor, server);
                requireEventOfKind(events, "identify");
                requireEventOfKind(events, "custom");
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
    public void comingBackOnlineDeliversWithoutWaitingForTheNextInterval() throws Exception {
        // A connectivity blip cancels the periodic flush and then restarts it from zero, so waiting
        // for it would hold these events back by a full interval — and by several of them if the
        // network keeps dropping.
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.setOffline(true);
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);

                eventProcessor.setOffline(false);

                // Periodic flushing is effectively off in this fixture, so a payload arriving
                // without an explicit flush can only have come from the transition.
                List<LDValue> events = collectDelivered(server);
                assertEquals(1, countEventsOfKind(events, "custom"));
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

    @Test
    public void flushWithTimeoutReportsDeliveredEvents() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);

                assertTrue(eventProcessor.blockingFlush(10, TimeUnit.SECONDS));

                assertEquals(1, countEventsOfKind(collectDelivered(server), "custom"));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void flushWithTimeoutReportsSuccessWhenThereIsNothingToSend() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                // Nothing was recorded, so the caller's events are not waiting anywhere.
                assertTrue(eventProcessor.blockingFlush(10, TimeUnit.SECONDS));

                server.getRecorder().requireNoRequests(100, TimeUnit.MILLISECONDS);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void flushWithTimeoutReportsFailureWhileOffline() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.setOffline(true);
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);

                // The events are still buffered rather than delivered, and no amount of waiting
                // changes that, so the caller is told so instead of being told they are safe.
                assertFalse(eventProcessor.blockingFlush(10, TimeUnit.SECONDS));

                server.getRecorder().requireNoRequests(100, TimeUnit.MILLISECONDS);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void flushWithTimeoutReportsFailureWhenTheTimeoutExpiresFirst() throws Exception {
        Semaphore letResponseFinish = new Semaphore(0);
        try (HttpServer server = HttpServer.start(Handlers.all(Handlers.waitFor(letResponseFinish),
                Handlers.status(202)))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);

                assertFalse(eventProcessor.blockingFlush(100, TimeUnit.MILLISECONDS));
            } finally {
                // Released before closing, so that the delivery still in flight can finish rather
                // than hold up the shutdown that close() waits on.
                letResponseFinish.release(Integer.MAX_VALUE);
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
