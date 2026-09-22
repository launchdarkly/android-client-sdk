package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EventPersistence;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.events.EventSender;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.After;
import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    // Enough concurrent evaluations that close() reliably lands between the two writes one evaluation
    // makes. Against the unfixed code this failed in the first trial of every run, by two to four
    // events -- roughly the number of recorders that can sit in the gap at once.
    private static final int RACE_TRIALS = 20;
    private static final int RACE_RECORDERS = 4;
    private static final int EVALUATIONS_PER_RACE_RECORDER = 2_000;
    private static final int EVALUATIONS_BEFORE_CLOSE = 200;
    private static final int RACE_CAPACITY = 150;

    // Long enough that the only delivery in a test is the one it asks for.
    private static final int NO_PERIODIC_FLUSH_MILLIS = 600_000;
    // Short enough to keep the close tests quick; the production value is chosen for a real network.
    private static final long CLOSE_BUDGET_MILLIS = 200;

    /** Created by makeEventProcessor, which the tests call instead of building a processor. */
    private final List<ExecutorService> diagnosticExecutors = new ArrayList<>();

    @After
    public void shutDownDiagnosticExecutors() {
        for (ExecutorService executor : diagnosticExecutors) {
            executor.shutdownNow();
        }
    }

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
    public void unserializableMetricDoesNotPoisonLaterDeliveries() throws Exception {
        try (HttpServer server = startEventsServer()) {
            // One slot makes this cover both recovery and release of the poisoned event's capacity.
            EventProcessor eventProcessor = makeEventProcessor(server, 1);
            try {
                // This is the event produced by LDClient.trackMetric(..., Double.NaN). Gson's
                // strict writer rejects the non-finite metric.
                eventProcessor.recordCustomEvent(
                        CONTEXT, "poison", LDValue.ofNull(), Double.NaN);
                eventProcessor.blockingFlush();
                server.getRecorder().requireNoRequests(100, TimeUnit.MILLISECONDS);

                eventProcessor.recordCustomEvent(
                        CONTEXT, "after-poison", LDValue.ofNull(), 1.0);
                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(1, events.size());
                assertEquals(LDValue.of("after-poison"), requireEventOfKind(events, "custom").get("key"));
                logging.assertErrorLogged("Dropping unserializable");
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void unserializableMetricDoesNotDropSiblingEvents() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(
                        CONTEXT, "poison", LDValue.ofNull(), Double.NaN);
                eventProcessor.recordCustomEvent(
                        CONTEXT, "kept", LDValue.ofNull(), 1.0);

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(1, events.size());
                assertEquals(LDValue.of("kept"), requireEventOfKind(events, "custom").get("key"));
                logging.assertErrorLogged("Dropping unserializable");
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void unserializableSummaryDoesNotPoisonLaterDeliveries() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                // Java callers can violate boolVariation's @NonNull contract. A null flag key can
                // enter the summarizer, but Gson cannot use it as a JSON object member name.
                eventProcessor.recordEvaluationEvent(CONTEXT, null, FLAG_VERSION, VARIATION,
                        FLAG_VALUE, null, DEFAULT_VALUE, false, null);
                eventProcessor.blockingFlush();
                server.getRecorder().requireNoRequests(100, TimeUnit.MILLISECONDS);

                recordEvaluation(eventProcessor, false, null);
                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(1, events.size());
                assertEquals(1, summaryCountFor(events, FLAG_KEY));
                logging.assertErrorLogged("Dropping unserializable");
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void unserializableSummaryDoesNotDropSiblingEvents() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordEvaluationEvent(CONTEXT, null, FLAG_VERSION, VARIATION,
                        FLAG_VALUE, null, DEFAULT_VALUE, false, null);
                eventProcessor.recordIdentifyEvent(CONTEXT);

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(1, countEventsOfKind(events, "identify"));
                assertEquals(0, countEventsOfKind(events, "summary"));
                logging.assertErrorLogged("Dropping unserializable");
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
    public void closeNeverDeliversASummaryWithoutTheFeatureEventItCounted() throws Exception {
        // An evaluation of a tracked flag writes a summary counter and a feature event. close() has
        // to take both or neither. If it can take the counter and then refuse the event, it reports
        // an evaluation that no event describes -- for experimentation traffic, a data point that
        // disappears while the summary insists it happened.
        //
        // The window is the gap between those two writes, so this races them rather than asserting
        // on a single ordering. Nothing here is timing-tolerant: the invariant holds for every
        // interleaving, so any trial that breaks it is a real defect.
        int featureEventsSeen = 0;
        for (int trial = 0; trial < RACE_TRIALS; trial++) {
            try (HttpServer server = startEventsServer()) {
                // Deliberately small. The recorders outrun the buffer, so capacity is the only thing
                // bounding the payload; left unbounded the body grows with however long close() takes
                // and runs past what the test server reads back.
                DirectEventProcessor eventProcessor =
                        (DirectEventProcessor) makeEventProcessor(server, RACE_CAPACITY);
                // Guarantees the final delivery has something in it, so collectDelivered always has
                // a request to read even if every recorder loses the race.
                eventProcessor.recordIdentifyEvent(CONTEXT);

                AtomicInteger recorded = new AtomicInteger();
                List<Thread> recorders = new ArrayList<>();
                for (int i = 0; i < RACE_RECORDERS; i++) {
                    Thread recorder = new Thread(() -> {
                        for (int n = 0; n < EVALUATIONS_PER_RACE_RECORDER; n++) {
                            eventProcessor.recordEvaluationEvent(CONTEXT, FLAG_KEY, FLAG_VERSION,
                                    VARIATION, FLAG_VALUE, null, DEFAULT_VALUE, true, null);
                            recorded.incrementAndGet();
                        }
                    });
                    recorders.add(recorder);
                    recorder.start();
                }
                // Close in the middle of the burst rather than at the edge of it. Closing before the
                // recorders are going would resolve the race trivially every time.
                while (recorded.get() < EVALUATIONS_BEFORE_CLOSE) {
                    Thread.yield();
                }
                eventProcessor.close();
                for (Thread recorder : recorders) {
                    recorder.join();
                }

                long dropped = eventProcessor.getAndClearDroppedCount();
                List<LDValue> events = collectDelivered(server);
                int featureEvents = countEventsOfKind(events, "feature");
                // Every evaluation is accounted for one of three ways: delivered in full, dropped
                // for capacity, or refused outright before anything was written. Only the first two
                // may leave a counter behind, so a counter that matches neither is one that was
                // taken from a half-recorded evaluation.
                assertEquals("trial " + trial + ": a summary counted an evaluation whose feature"
                                + " event was neither delivered nor dropped",
                        featureEvents + dropped, summaryCounters(events, FLAG_KEY));
                featureEventsSeen += featureEvents;
            }
        }
        // Guards against the whole thing passing because nothing ever got as far as being delivered.
        assertTrue("no evaluation survived to be delivered, so nothing was actually compared",
                featureEventsSeen > 0);
    }

    /**
     * Like {@code summaryCountFor}, but returns zero rather than failing when no summary was
     * delivered. Here a trial in which close() beat every recorder is a legitimate outcome.
     */
    private int summaryCounters(List<LDValue> events, String flagKey) {
        int total = 0;
        for (LDValue event : events) {
            if (!"summary".equals(event.get("kind").stringValue())) {
                continue;
            }
            for (LDValue counter : event.get("features").get(flagKey).get("counters").values()) {
                total += counter.get("count").intValue();
            }
        }
        return total;
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
    public void periodicTaskSurvivesErrorFromSender() throws Exception {
        // An Error (not Exception) from a scheduled run used to cancel the repeating future with
        // nothing logged, after which enableOrDisableTask kept returning that dead future forever.
        // Driven through diagnostics, because that is the periodic task the injectable EventSender
        // still carries once analytics go out through the store and AnalyticsEventSender.
        CountDownLatch firstPeriodicAttempt = new CountDownLatch(1);
        BlockingQueue<byte[]> delivered = new LinkedBlockingQueue<>();
        EventSender sender = new StubEventSender() {
            private final AtomicInteger periodicAttempts = new AtomicInteger();

            @Override
            public Result sendDiagnosticEvent(byte[] data, URI eventsBaseUri) {
                String kind = LDValue.parse(new String(data, StandardCharsets.UTF_8))
                        .get("kind").stringValue();
                if ("diagnostic-init".equals(kind)) {
                    return new Result(true, false, null); // one-shot, not the series under test
                }
                if (periodicAttempts.getAndIncrement() == 0) {
                    firstPeriodicAttempt.countDown();
                    throw new Error("periodic diagnostics");
                }
                delivered.add(data);
                return new Result(true, false, null);
            }
        };

        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(sender,
                URI.create("https://events.example"), makeDiagnosticStore(),
                NO_PERIODIC_FLUSH_MILLIS, 40, DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS,
                scheduler);
        try {
            // Coming online is the only thing that schedules the series. Nothing after this point
            // touches the processor's state, so it has to stay alive on its own.
            eventProcessor.setOffline(false);
            assertTrue("sender never saw the first periodic diagnostic",
                    firstPeriodicAttempt.await(2, TimeUnit.SECONDS));

            assertNotNull("periodic diagnostics did not run again after Error",
                    delivered.poll(2, TimeUnit.SECONDS));
            logging.assertErrorLogged("Unexpected error in event processor");
        } finally {
            eventProcessor.close();
            scheduler.shutdownNow();
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
    public void closeGivesUpWaitingOnAStalledDelivery() throws Exception {
        // close() runs on the caller's thread, usually the main one, so a send that never comes back
        // used to park the application there for as long as the HTTP timeouts allowed.
        Semaphore letResponseFinish = new Semaphore(0);
        try (HttpServer server = HttpServer.start(Handlers.all(Handlers.waitFor(letResponseFinish),
                Handlers.status(202)))) {
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                    server.getUri(), null, NO_PERIODIC_FLUSH_MILLIS, 60_000, CLOSE_BUDGET_MILLIS,
                    scheduler);
            try {
                eventProcessor.setOffline(false);
                eventProcessor.recordCustomEvent(CONTEXT, "stalled", LDValue.ofNull(), null);

                long startedAtNanos = System.nanoTime();
                eventProcessor.close();
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

                assertNotNull("the sender was never asked to send anything",
                        server.getRecorder().requireRequest(2, TimeUnit.SECONDS));
                assertTrue("close() waited " + elapsedMillis + "ms on a budget of "
                        + CLOSE_BUDGET_MILLIS + "ms", elapsedMillis < CLOSE_BUDGET_MILLIS * 5);
                logging.assertWarnLogged("Gave up waiting for the final event delivery");
            } finally {
                letResponseFinish.release(Integer.MAX_VALUE);
                scheduler.shutdownNow();
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

    @Test
    public void closeReleasesTheStoreAndSenderOnlyAfterTheLastDeliveryFinishes() throws Exception {
        // Giving up on the wait must not turn into pulling the store or the HTTP client out from
        // under the delivery we just decided not to wait for. Neither can be observed being closed
        // from here, but the consequence can: a delivery that lost either would fail once released
        // and leave its batch on disk instead of deleting it.
        Semaphore letResponseFinish = new Semaphore(0);
        try (HttpServer server = HttpServer.start(Handlers.all(Handlers.waitFor(letResponseFinish),
                Handlers.status(202)))) {
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                    server.getUri(), null, NO_PERIODIC_FLUSH_MILLIS, 60_000, CLOSE_BUDGET_MILLIS,
                    scheduler);
            try {
                eventProcessor.setOffline(false);
                eventProcessor.recordCustomEvent(CONTEXT, "stalled", LDValue.ofNull(), null);

                eventProcessor.close(); // returns on its budget with the post still stalled
                letResponseFinish.release(Integer.MAX_VALUE);

                assertTrue("the stalled delivery never completed, so its batch is still on disk",
                        awaitNoPendingEvents(5, TimeUnit.SECONDS));
            } finally {
                // Drained first, because the release above may already have happened and topping a
                // semaphore up twice from Integer.MAX_VALUE overflows its permit count.
                letResponseFinish.drainPermits();
                letResponseFinish.release(Integer.MAX_VALUE);
                scheduler.shutdownNow();
            }
        }
    }

    private boolean awaitNoPendingEvents(long timeout, TimeUnit unit) throws Exception {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        do {
            EventStore reader = EventStore.create(eventsDirectory.getRoot(), MOBILE_KEY, "test",
                    DEFAULT_CAPACITY, true, logging.logger);
            try {
                if (reader.pendingEventPayloads().isEmpty()) {
                    return true;
                }
            } finally {
                reader.close();
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadlineNanos);
        return false;
    }

    @Test
    public void flushAfterCloseDoesNotDeliverThroughReleasedResources() throws Exception {
        // close() hands the store and the analytics sender back on the delivery thread and then stops
        // that thread taking work, so nothing it accepts afterwards can find them already released.
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            eventProcessor.recordCustomEvent(CONTEXT, "before-close", LDValue.ofNull(), null);
            // Leaves the buffer empty, so any request after this is one close should not have made.
            flushAndCollect(eventProcessor, server);

            eventProcessor.close();

            eventProcessor.recordCustomEvent(CONTEXT, "after-close", LDValue.ofNull(), null);
            eventProcessor.flush();
            eventProcessor.blockingFlush();

            server.getRecorder().requireNoRequests(200, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    public void stalledDiagnosticPostDoesNotHoldUpAnalyticsDelivery() throws Exception {
        // Diagnostics used to share the one thread that delivers analytics, so a post against a
        // network that accepts connections and never answers stalled every flush behind it, and a
        // buffer that is not being drained fills up and drops what the application asked to send.
        CountDownLatch diagnosticStarted = new CountDownLatch(1);
        CountDownLatch releaseDiagnostic = new CountDownLatch(1);
        EventSender sender = new StubEventSender() {
            @Override
            public Result sendDiagnosticEvent(byte[] data, URI eventsBaseUri) {
                diagnosticStarted.countDown();
                awaitQuietly(releaseDiagnostic, 5, TimeUnit.SECONDS);
                return new Result(true, false, null);
            }
        };

        // Analytics go out through AnalyticsEventSender rather than the injectable one, so the
        // delivery has to be watched at the server rather than at the stub.
        try (HttpServer server = startEventsServer()) {
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            DirectEventProcessor eventProcessor = makeEventProcessor(sender, server.getUri(),
                    makeDiagnosticStore(), NO_PERIODIC_FLUSH_MILLIS, 60_000,
                    DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS, scheduler);
            try {
                // Coming online posts the diagnostic init event, which then never comes back.
                eventProcessor.setOffline(false);
                assertTrue("the diagnostic event was never posted",
                        diagnosticStarted.await(2, TimeUnit.SECONDS));

                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
                eventProcessor.flush();

                RequestInfo request = null;
                try {
                    request = server.getRecorder().requireRequest(2, TimeUnit.SECONDS);
                } catch (Exception timedOut) {
                    // Reported by the assertion below, which can say what it means.
                }
                assertNotNull("analytics delivery was stuck behind the diagnostic post", request);
                assertTrue(request.getBody().contains("an-event"));
            } finally {
                releaseDiagnostic.countDown();
                eventProcessor.close();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    public void diagnosticEventIsDroppedRatherThanQueuedBehindOneStillPosting() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger posts = new AtomicInteger();
        EventSender sender = new StubEventSender() {
            @Override
            public Result sendDiagnosticEvent(byte[] data, URI eventsBaseUri) {
                posts.incrementAndGet();
                firstStarted.countDown();
                awaitQuietly(releaseFirst, 5, TimeUnit.SECONDS);
                return new Result(true, false, null);
            }
        };

        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        // A diagnostic interval short enough that the periodic task fires repeatedly while the init
        // event is still stuck on the posting thread.
        DirectEventProcessor eventProcessor = makeEventProcessor(sender, makeDiagnosticStore(),
                NO_PERIODIC_FLUSH_MILLIS, 20, scheduler);
        try {
            eventProcessor.setOffline(false);
            assertTrue("the diagnostic event was never posted",
                    firstStarted.await(2, TimeUnit.SECONDS));

            // Long enough for many periodic runs, every one of which has to be turned away rather
            // than left on the posting thread's queue. Counting posts is not enough on its own: a
            // queued one would not have started yet either, so the skips are what distinguishes
            // being dropped from merely waiting.
            Thread.sleep(300);
            assertTrue("no diagnostic event was turned away, so they were queueing up instead",
                    countLogged("Skipped a diagnostic event") > 0);
            assertEquals("more than one diagnostic event reached the sender", 1, posts.get());
        } finally {
            releaseFirst.countDown();
            eventProcessor.close();
            scheduler.shutdownNow();
        }
    }

    private static final URI UNUSED_EVENTS_URI = URI.create("https://events.example");

    private DirectEventProcessor makeEventProcessor(EventSender diagnosticSender,
                                                    long flushIntervalMillis,
                                                    ScheduledExecutorService scheduler) {
        return makeEventProcessor(diagnosticSender, UNUSED_EVENTS_URI, null, flushIntervalMillis,
                60_000, DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS, scheduler);
    }

    private DirectEventProcessor makeEventProcessor(EventSender diagnosticSender,
                                                    DiagnosticStore diagnosticStore,
                                                    long flushIntervalMillis,
                                                    ScheduledExecutorService scheduler) {
        return makeEventProcessor(diagnosticSender, UNUSED_EVENTS_URI, diagnosticStore, flushIntervalMillis,
                60_000, DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS, scheduler);
    }

    private DirectEventProcessor makeEventProcessor(EventSender diagnosticSender,
                                                    DiagnosticStore diagnosticStore,
                                                    long flushIntervalMillis,
                                                    long diagnosticIntervalMillis,
                                                    ScheduledExecutorService scheduler) {
        return makeEventProcessor(diagnosticSender, UNUSED_EVENTS_URI, diagnosticStore, flushIntervalMillis,
                diagnosticIntervalMillis, DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS,
                scheduler);
    }

    private DirectEventProcessor makeEventProcessor(EventSender diagnosticSender,
                                                    URI eventsUri,
                                                    DiagnosticStore diagnosticStore,
                                                    long flushIntervalMillis,
                                                    long diagnosticIntervalMillis,
                                                    long closeBudgetMillis,
                                                    ScheduledExecutorService scheduler) {
        ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
        diagnosticExecutors.add(diagnosticExecutor);
        return new DirectEventProcessor(
                new OutboundEventBuffer(false, Collections.emptyList(), true, DEFAULT_CAPACITY,
                        logging.logger),
                EventStore.create(eventsDirectory.getRoot(), MOBILE_KEY, "test", DEFAULT_CAPACITY,
                        true, logging.logger),
                diagnosticSender,
                new AnalyticsEventSender(LDUtil.makeHttpProperties(
                        new HttpConfiguration(2000, Collections.emptyMap(), null, false)),
                        logging.logger),
                eventsUri,
                diagnosticStore,
                DEFAULT_CAPACITY,
                false, // commitOnCallerThread
                flushIntervalMillis,
                diagnosticIntervalMillis,
                closeBudgetMillis,
                false,
                true, // initiallyOffline, as the SDK builds it
                scheduler,
                diagnosticExecutor,
                logging.logger);
    }

    private long countLogged(String messageSubstring) {
        long count = 0;
        for (String message : logging.logCapture.getMessageStrings()) {
            if (message.contains(messageSubstring)) {
                count++;
            }
        }
        return count;
    }

    private DiagnosticStore makeDiagnosticStore() {
        return new DiagnosticStore(new DiagnosticStore.SdkDiagnosticParams(MOBILE_KEY,
                "android-client-sdk", "0.0.0", "Android", null, Collections.emptyMap(), null));
    }

    private static void awaitQuietly(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            latch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Accepts everything, so that a test overrides only the one method it is about. */
    private static class StubEventSender implements EventSender {
        @Override
        public Result sendAnalyticsEvents(byte[] data, int eventCount, URI eventsBaseUri) {
            return new Result(true, false, null);
        }

        @Override
        public Result sendDiagnosticEvent(byte[] data, URI eventsBaseUri) {
            return new Result(true, false, null);
        }

        @Override
        public void close() {}
    }

    private void recordEvaluation(EventProcessor eventProcessor, boolean requireFullEvent,
                                  Long debugEventsUntilDate) {
        eventProcessor.recordEvaluationEvent(CONTEXT, FLAG_KEY, FLAG_VERSION, VARIATION, FLAG_VALUE,
                null, DEFAULT_VALUE, requireFullEvent, debugEventsUntilDate);
    }
}
