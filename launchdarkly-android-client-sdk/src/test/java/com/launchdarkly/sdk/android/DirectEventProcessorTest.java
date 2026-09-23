package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.events.Event;
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
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
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
    private static final int RACE_CAPACITY = 30;
    // Past anything the recorders can produce, so that a payload short of a feature event is short
    // because the evaluation was split rather than because the buffer was full.
    private static final int NO_DROP_CAPACITY = RACE_RECORDERS * EVALUATIONS_PER_RACE_RECORDER * 2;
    private static final int SPLIT_TRIALS = 8;

    // Long enough that the only delivery in a test is the one it asks for.
    private static final long NO_PERIODIC_FLUSH_MILLIS = 600_000;
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
    public void aNonPositiveCapacityStillReportsRatherThanGoingSilent() throws Exception {
        // Nought and negatives both mean one, and the processor and the buffer have to agree on
        // that: disagreeing leaves the SDK holding an event it will never summarize, or summarizing
        // for a buffer that will never send. Disabling events entirely is what noEvents() is for.
        for (int capacity : new int[] { 0, -5 }) {
            try (HttpServer server = startEventsServer()) {
                EventProcessor eventProcessor = makeEventProcessor(server, capacity);
                try {
                    recordEvaluation(eventProcessor, false, null);

                    List<LDValue> events = flushAndCollect(eventProcessor, server);

                    assertEquals("capacity " + capacity + " summarized nothing",
                            1, summaryCountFor(events, FLAG_KEY));
                } finally {
                    eventProcessor.close();
                }
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
                // bounding the payload, and the test server records the body with a single unlooped
                // read -- so anything past one socket read's worth comes back truncated, at a point
                // that moves from run to run. A few kilobytes stays well clear of that.
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
    public void closingWhileOfflineStaysOffTheNetwork() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            eventProcessor.setOffline(true);
            eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
            recordEvaluation(eventProcessor, false, null);

            eventProcessor.close();

            server.getRecorder().requireNoRequests(500, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    public void goingOnlineForTheFirstTimeDoesNotDeliverOnItsOwn() throws Exception {
        // What LDClient does at startup: the initial identify is recorded while the processor is
        // still offline, and initialization then turns it on. The identify waits to be batched with
        // what follows rather than going out alone.
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = buildOfflineEventProcessor(server,
                    eventsBuilder(DEFAULT_CAPACITY), true);
            try {
                eventProcessor.recordIdentifyEvent(CONTEXT);

                eventProcessor.setOffline(false);

                server.getRecorder().requireNoRequests(500, TimeUnit.MILLISECONDS);
                List<LDValue> events = flushAndCollect(eventProcessor, server);
                assertEquals(1, countEventsOfKind(events, "identify"));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void closeCancelsThePeriodicFlushAndNothingAfterItSchedulesAnother() throws Exception {
        List<ScheduledFuture<?>> scheduled = Collections.synchronizedList(new ArrayList<>());
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                             long delay, TimeUnit unit) {
                ScheduledFuture<?> future = super.scheduleWithFixedDelay(command, initialDelay, delay, unit);
                scheduled.add(future);
                return future;
            }
        };
        // Otherwise shutting the executor down would cancel the task for close(), and this could not
        // tell whether close() did.
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(true);
        DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                NO_PERIODIC_FLUSH_MILLIS, scheduler);
        try {
            eventProcessor.setOffline(false);
            eventProcessor.close();
            eventProcessor.setOffline(true);
            eventProcessor.setOffline(false);
            eventProcessor.setInBackground(true);
            eventProcessor.setInBackground(false);

            assertEquals(1, scheduled.size());
            assertTrue("close() left the periodic flush running", scheduled.get(0).isCancelled());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    public void anOutageDoesNotRestartTheFlushInterval() throws Exception {
        // Restarting it on every reconnect would let a run of brief outages hold events back for
        // far longer than one interval.
        AtomicInteger scheduled = new AtomicInteger();
        ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                             long delay, TimeUnit unit) {
                scheduled.incrementAndGet();
                return super.scheduleWithFixedDelay(command, initialDelay, delay, unit);
            }
        };
        DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                NO_PERIODIC_FLUSH_MILLIS, scheduler);
        try {
            eventProcessor.setOffline(false);
            for (int i = 0; i < 5; i++) {
                eventProcessor.setOffline(true);
                eventProcessor.setOffline(false);
            }

            assertEquals(1, scheduled.get());
        } finally {
            eventProcessor.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    public void eventsHeldDuringAnOutageGoOutWithTheNextPeriodicFlush() throws Exception {
        BlockingQueue<byte[]> delivered = new LinkedBlockingQueue<>();
        EventSender sender = new StubEventSender() {
            @Override
            public Result sendAnalyticsEvents(byte[] data, int eventCount, URI eventsBaseUri) {
                delivered.add(data);
                return new Result(true, false, null);
            }
        };
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(sender, 50, scheduler);
        try {
            eventProcessor.setOffline(true);
            eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
            assertNull("a periodic flush sent events while offline",
                    delivered.poll(300, TimeUnit.MILLISECONDS));

            eventProcessor.setOffline(false);

            assertNotNull("the periodic flush did not deliver after the outage",
                    delivered.poll(2, TimeUnit.SECONDS));
        } finally {
            eventProcessor.close();
            scheduler.shutdownNow();
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
                returnToTheForegroundWithTheDiagnosticsThreadFree(eventProcessor);
                server.getRecorder().requireNoRequests(500, TimeUnit.MILLISECONDS);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void diagnosticInitEventIsNotSentWhileOffline() throws Exception {
        // Built offline and left that way. Waiting for the init first, as this used to, means the
        // only thing stopping a second one is that the first already went -- so the test passes just
        // as happily against a processor that sends init events while offline.
        BlockingQueue<LDValue> posted = new LinkedBlockingQueue<>();
        EventSender sender = new StubEventSender() {
            @Override
            public Result sendDiagnosticEvent(byte[] data, URI eventsBaseUri) {
                posted.add(LDValue.parse(new String(data, StandardCharsets.UTF_8)));
                return new Result(true, false, null);
            }
        };
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(sender, makeDiagnosticStore(),
                NO_PERIODIC_FLUSH_MILLIS, scheduler);
        try {
            assertNull("a diagnostic event was sent while offline",
                    posted.poll(500, TimeUnit.MILLISECONDS));

            // Proof that the silence above was the offline state and not a fixture that could never
            // have sent anything.
            eventProcessor.setOffline(false);
            assertEquals(LDValue.of("diagnostic-init"), requirePosted(posted).get("kind"));
        } finally {
            eventProcessor.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    public void beingToldToShutDownStopsRecordingAndDelivery() throws Exception {
        // A 401 means the mobile key will not start working again, so the SDK is supposed to stop
        // for the life of the process rather than keep posting events nobody will accept.
        try (HttpServer server = HttpServer.start(Handlers.status(401))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "before", LDValue.ofNull(), null);
                eventProcessor.blockingFlush();
                server.getRecorder().requireRequest(10, TimeUnit.SECONDS);

                eventProcessor.recordCustomEvent(CONTEXT, "after", LDValue.ofNull(), null);
                eventProcessor.blockingFlush();

                server.getRecorder().requireNoRequests(500, TimeUnit.MILLISECONDS);
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
    public void aFlushNeverSplitsAnEvaluationAcrossTwoPayloads() throws Exception {
        // The other half of the atomicity invariant. close() only ever delivers once, so it can show
        // an evaluation being stranded but not one being split: a counter going out in payload N with
        // its feature event following in N+1. That leaves the totals correct and each payload wrong,
        // so this checks payloads one at a time, against a flush running while recording continues.
        //
        // Every evaluation is tracked and the capacity is far beyond what the run produces, so within
        // a payload the counter for the flag and the number of feature events are the same number.
        //
        // Repeated because the window is narrow -- the two writes are adjacent, and a flush has to
        // land between them. A single run catches a split lock about two times in three.
        int payloadsWithCounters = 0;
        for (int trial = 0; trial < SPLIT_TRIALS; trial++) {
            Queue<LDValue> payloads = new ConcurrentLinkedQueue<>();
            EventSender sender = new StubEventSender() {
                @Override
                public Result sendAnalyticsEvents(byte[] data, int eventCount, URI eventsBaseUri) {
                    payloads.add(LDValue.parse(new String(data, StandardCharsets.UTF_8)));
                    return new Result(true, false, null);
                }
            };
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            DirectEventProcessor eventProcessor =
                    makeEventProcessorWithCapacity(sender, NO_DROP_CAPACITY, scheduler);
            try {
                eventProcessor.setOffline(false);
                AtomicInteger recorded = new AtomicInteger();
                List<Thread> recorders = new ArrayList<>();
                for (int i = 0; i < RACE_RECORDERS; i++) {
                    Thread recorder = new Thread(() -> {
                        for (int n = 0; n < EVALUATIONS_PER_RACE_RECORDER; n++) {
                            recordEvaluation(eventProcessor, true, null);
                            recorded.incrementAndGet();
                        }
                    });
                    recorders.add(recorder);
                    recorder.start();
                }
                int target = RACE_RECORDERS * EVALUATIONS_PER_RACE_RECORDER;
                while (recorded.get() < target) {
                    eventProcessor.blockingFlush();
                }
                for (Thread recorder : recorders) {
                    recorder.join();
                }
                eventProcessor.blockingFlush();

                assertEquals("trial " + trial + ": events were dropped, so a payload may be short"
                                + " for that reason instead",
                        0, eventProcessor.getAndClearDroppedCount());
                int counted = 0;
                for (LDValue payload : payloads) {
                    List<LDValue> events = new ArrayList<>();
                    for (LDValue event : payload.values()) {
                        events.add(event);
                    }
                    int counters = summaryCounters(events, FLAG_KEY);
                    assertEquals("trial " + trial + ": a payload counted evaluations whose feature"
                                    + " events went out separately",
                            countEventsOfKind(events, "feature"), counters);
                    counted += counters;
                    if (counters > 0) {
                        payloadsWithCounters++;
                    }
                }
                assertEquals("trial " + trial + ": some evaluations never reached a payload",
                        target, counted);
            } finally {
                eventProcessor.close();
                scheduler.shutdownNow();
            }
        }
        // Otherwise a single delivery per trial would satisfy everything above without a flush ever
        // having overlapped a recording.
        assertTrue("every evaluation went out in one payload, so nothing was interleaved",
                payloadsWithCounters > SPLIT_TRIALS);
    }

    @Test
    public void periodicFlushSurvivesErrorFromSender() throws Exception {
        // An Error (not Exception) from a scheduled run used to cancel the repeating future with
        // nothing logged, after which enableOrDisableTask kept returning that dead future forever.
        CountDownLatch firstAttempt = new CountDownLatch(1);
        BlockingQueue<byte[]> delivered = new LinkedBlockingQueue<>();
        EventSender sender = new StubEventSender() {
            private final AtomicInteger attempts = new AtomicInteger();

            @Override
            public Result sendAnalyticsEvents(byte[] data, int eventCount, URI eventsBaseUri) {
                if (attempts.getAndIncrement() == 0) {
                    firstAttempt.countDown();
                    throw new Error("periodic flush");
                }
                delivered.add(data);
                return new Result(true, false, null);
            }
        };

        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(sender, 40, scheduler);
        try {
            eventProcessor.setOffline(false);
            eventProcessor.blockingFlush();

            eventProcessor.recordCustomEvent(CONTEXT, "before-error", LDValue.ofNull(), null);
            assertTrue("sender never saw the first periodic flush",
                    firstAttempt.await(2, TimeUnit.SECONDS));

            // A background toggle stays online, so nothing here reschedules on the processor's
            // behalf. The periodic series has to still be alive on its own.
            eventProcessor.setInBackground(true);
            eventProcessor.setInBackground(false);

            eventProcessor.recordCustomEvent(CONTEXT, "after-error", LDValue.ofNull(), null);
            byte[] payload = delivered.poll(2, TimeUnit.SECONDS);
            assertNotNull("periodic flush did not run again after Error", payload);
            assertTrue(new String(payload, StandardCharsets.UTF_8).contains("after-error"));
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
    public void unexpectedRecordingErrorDoesNotBubbleToCallerAndLogs() throws Exception {
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                NO_PERIODIC_FLUSH_MILLIS, scheduler);
        try {
            // Must not throw if record throws:
            eventProcessor.record(new Event(System.currentTimeMillis(), CONTEXT) {
                @Override
                public long getSamplingRatio() {
                    throw new RuntimeException("simulated record crash");
                }
            });
            logging.assertErrorLogged("Unexpected error in event processor: java.lang.RuntimeException: simulated record crash");
        } finally {
            eventProcessor.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    public void anErrorWhileRecordingStillReachesTheCaller() throws Exception {
        // Only exceptions are the SDK's to absorb. An Error such as OutOfMemoryError belongs to the
        // application's crash reporting, and swallowing it on the caller's thread would hide it.
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                NO_PERIODIC_FLUSH_MILLIS, scheduler);
        try {
            eventProcessor.record(new Event(System.currentTimeMillis(), CONTEXT) {
                @Override
                public long getSamplingRatio() {
                    throw new StackOverflowError("simulated");
                }
            });
            fail("the Error was swallowed");
        } catch (StackOverflowError expected) {
            // what the application's handler would see
        } finally {
            eventProcessor.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    public void closeGivesUpWaitingOnAStalledDelivery() throws Exception {
        // close() runs on the caller's thread, usually the main one, so a send that never comes back
        // used to park the application there for as long as the HTTP timeouts allowed.
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        EventSender sender = new StubEventSender() {
            @Override
            public Result sendAnalyticsEvents(byte[] data, int eventCount, URI eventsBaseUri) {
                sendStarted.countDown();
                // Bounded so that a regression fails on the elapsed time rather than hanging until
                // the suite's global timeout.
                awaitQuietly(releaseSend, 5, TimeUnit.SECONDS);
                return new Result(true, false, null);
            }
        };

        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(sender, NO_PERIODIC_FLUSH_MILLIS,
                CLOSE_BUDGET_MILLIS, scheduler);
        try {
            eventProcessor.setOffline(false);
            eventProcessor.recordCustomEvent(CONTEXT, "stalled", LDValue.ofNull(), null);

            long startedAtNanos = System.nanoTime();
            eventProcessor.close();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

            assertTrue("the sender was never asked to send anything",
                    sendStarted.await(2, TimeUnit.SECONDS));
            assertTrue("close() waited " + elapsedMillis + "ms on a budget of " + CLOSE_BUDGET_MILLIS
                    + "ms", elapsedMillis < CLOSE_BUDGET_MILLIS * 5);
            logging.assertWarnLogged("Gave up waiting for the final event delivery");
        } finally {
            releaseSend.countDown();
            scheduler.shutdownNow();
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
    public void closeReleasesTheSenderOnlyAfterTheLastDeliveryFinishes() throws Exception {
        // Giving up on the wait must not turn into pulling the HTTP client out from under the
        // delivery we just decided not to wait for.
        CountDownLatch releaseSend = new CountDownLatch(1);
        CountDownLatch senderClosed = new CountDownLatch(1);
        AtomicBoolean posting = new AtomicBoolean(false);
        AtomicBoolean closedMidPost = new AtomicBoolean(false);
        EventSender sender = new StubEventSender() {
            @Override
            public Result sendAnalyticsEvents(byte[] data, int eventCount, URI eventsBaseUri) {
                posting.set(true);
                awaitQuietly(releaseSend, 5, TimeUnit.SECONDS);
                posting.set(false);
                return new Result(true, false, null);
            }

            @Override
            public void close() {
                if (posting.get()) {
                    closedMidPost.set(true);
                }
                senderClosed.countDown();
            }
        };

        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(sender, NO_PERIODIC_FLUSH_MILLIS,
                CLOSE_BUDGET_MILLIS, scheduler);
        try {
            eventProcessor.setOffline(false);
            eventProcessor.recordCustomEvent(CONTEXT, "stalled", LDValue.ofNull(), null);

            eventProcessor.close();
            assertEquals("the sender was closed while a delivery was still in flight",
                    1, senderClosed.getCount());

            releaseSend.countDown();
            assertTrue("the sender was never closed once the delivery finished",
                    senderClosed.await(2, TimeUnit.SECONDS));
            assertFalse("the sender was closed while a delivery was posting through it",
                    closedMidPost.get());
        } finally {
            releaseSend.countDown();
            scheduler.shutdownNow();
        }
    }

    @Test
    public void flushAfterCloseDoesNotPostThroughTheReleasedSender() throws Exception {
        AtomicBoolean postedAfterRelease = new AtomicBoolean(false);
        EventSender sender = releaseTrackingSender(postedAfterRelease);

        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(sender, NO_PERIODIC_FLUSH_MILLIS,
                CLOSE_BUDGET_MILLIS, scheduler);
        try {
            eventProcessor.setOffline(false);
            eventProcessor.close();

            eventProcessor.recordCustomEvent(CONTEXT, "after-close", LDValue.ofNull(), null);
            eventProcessor.flush();
            eventProcessor.blockingFlush();

            assertFalse("a flush after close posted through a sender that had been released",
                    postedAfterRelease.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    /** Reports through {@code postedAfterRelease} if it is asked to send once it has been closed. */
    private static EventSender releaseTrackingSender(AtomicBoolean postedAfterRelease) {
        AtomicBoolean released = new AtomicBoolean(false);
        return new StubEventSender() {
            @Override
            public Result sendAnalyticsEvents(byte[] data, int eventCount, URI eventsBaseUri) {
                if (released.get()) {
                    postedAfterRelease.set(true);
                }
                return new Result(true, false, null);
            }

            @Override
            public void close() {
                released.set(true);
            }
        };
    }

    @Test
    public void stalledDiagnosticPostDoesNotHoldUpAnalyticsDelivery() throws Exception {
        // Diagnostics used to share the one thread that delivers analytics, so a post against a
        // network that accepts connections and never answers stalled every flush behind it, and a
        // buffer that is not being drained fills up and drops what the application asked to send.
        CountDownLatch diagnosticStarted = new CountDownLatch(1);
        CountDownLatch releaseDiagnostic = new CountDownLatch(1);
        BlockingQueue<byte[]> analyticsDelivered = new LinkedBlockingQueue<>();
        EventSender sender = new StubEventSender() {
            @Override
            public Result sendDiagnosticEvent(byte[] data, URI eventsBaseUri) {
                diagnosticStarted.countDown();
                awaitQuietly(releaseDiagnostic, 5, TimeUnit.SECONDS);
                return new Result(true, false, null);
            }

            @Override
            public Result sendAnalyticsEvents(byte[] data, int eventCount, URI eventsBaseUri) {
                analyticsDelivered.add(data);
                return new Result(true, false, null);
            }
        };

        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(sender, makeDiagnosticStore(),
                NO_PERIODIC_FLUSH_MILLIS, scheduler);
        try {
            // Coming online posts the diagnostic init event, which then never comes back.
            eventProcessor.setOffline(false);
            assertTrue("the diagnostic event was never posted",
                    diagnosticStarted.await(2, TimeUnit.SECONDS));

            eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
            eventProcessor.flush();

            byte[] payload = analyticsDelivered.poll(2, TimeUnit.SECONDS);
            assertNotNull("analytics delivery was stuck behind the diagnostic post", payload);
            assertTrue(new String(payload, StandardCharsets.UTF_8).contains("an-event"));
        } finally {
            releaseDiagnostic.countDown();
            eventProcessor.close();
            scheduler.shutdownNow();
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

    @Test
    public void goingToTheBackgroundDefersADiagnosticPeriodRatherThanDestroyingIt() throws Exception {
        // createEventAndReset hands the period's statistics back and clears them in the same call, so
        // whoever consumes it owns it. The post reaches the diagnostics thread through a queue it may
        // have to wait in, and the SDK can go offline or to the background in the meantime. If that is
        // noticed after the event was built, the period is gone: nothing retries it, and the next
        // event reports a window starting after the reset, so the statistics are not merely late but
        // absent.
        BlockingQueue<LDValue> posted = new LinkedBlockingQueue<>();
        EventSender sender = new StubEventSender() {
            @Override
            public Result sendDiagnosticEvent(byte[] data, URI eventsBaseUri) {
                posted.add(LDValue.parse(new String(data, StandardCharsets.UTF_8)));
                return new Result(true, false, null);
            }
        };

        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
        diagnosticExecutors.add(diagnosticExecutor);
        // Short enough that the periodic task fires while the diagnostics thread is held.
        DirectEventProcessor eventProcessor = makeEventProcessor(sender, makeDiagnosticStore(),
                NO_PERIODIC_FLUSH_MILLIS, 20, DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS,
                scheduler, diagnosticExecutor);
        try {
            eventProcessor.setOffline(false);
            assertEquals(LDValue.of("diagnostic-init"),
                    requirePosted(posted).get("kind"));

            // Something for the period to have in it. Capacity is what makes these countable, and the
            // dropped count is carried by the periodic event and nothing else. Far past capacity so
            // that drops happen whatever else is draining the buffer.
            for (int i = 0; i < DEFAULT_CAPACITY * 10; i++) {
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
            }
            logging.assertWarnLogged("Exceeded event queue capacity");

            // Holds the diagnostics thread so the next periodic post has to queue behind it, which is
            // the window the state can change in.
            CountDownLatch releaseThread = new CountDownLatch(1);
            diagnosticExecutor.submit(() -> awaitQuietly(releaseThread, 5, TimeUnit.SECONDS));
            // A later run being turned away is proof that an earlier one took the claim and is sitting
            // on the queue, which is what we need before changing the state underneath it.
            awaitLogged("Skipped a diagnostic event");

            eventProcessor.setInBackground(true);
            releaseThread.countDown();
            // The queued post now runs suspended. Nothing should reach the sender.
            assertNull("a diagnostic event was posted from the background",
                    posted.poll(300, TimeUnit.MILLISECONDS));

            eventProcessor.setInBackground(false);
            LDValue statistics = requirePosted(posted);

            assertEquals(LDValue.of("diagnostic"), statistics.get("kind"));
            // Nothing is recorded after the suspension, so every drop this could report happened
            // before it. A destroyed period therefore reports exactly zero, which is what separates
            // the two outcomes. The exact figure is not asserted because it is not the same on every
            // tier: where events are staged to a store, reaching it frees capacity as we go.
            assertTrue("the suspended period was destroyed rather than carried forward",
                    statistics.get("droppedEvents").longValue() > 0);
        } finally {
            eventProcessor.close();
            scheduler.shutdownNow();
        }
    }

    private LDValue requirePosted(BlockingQueue<LDValue> posted) throws InterruptedException {
        LDValue event = posted.poll(5, TimeUnit.SECONDS);
        assertNotNull("no diagnostic event was posted", event);
        return event;
    }

    /**
     * Goes to the background and back, leaving the processor at the point where it decides whether
     * to send a second init event.
     * <p>
     * Two things have to be true for that decision to be reached, and only one of them is under the
     * test's control. Going to the foreground has to be a real transition, because setInBackground
     * returns immediately when the value is unchanged. And the diagnostics thread has to be free:
     * the first init event holds a claim on it until its post returns, which is after the request
     * reaches the server, so a transition right after the request arrives is turned away before it
     * gets anywhere near the decision. Being turned away is logged, which is what this waits out.
     */
    private void returnToTheForegroundWithTheDiagnosticsThreadFree(EventProcessor eventProcessor)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            long skipsBefore = countLogged("Skipped a diagnostic event");
            eventProcessor.setInBackground(true);
            eventProcessor.setInBackground(false);
            // Logged by the claim itself, on this thread, so it is already there if it happened.
            if (countLogged("Skipped a diagnostic event") == skipsBefore) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the diagnostics thread never came free");
    }

    private void awaitLogged(String messageSubstring) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (countLogged(messageSubstring) > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("never logged: " + messageSubstring);
    }

    private DirectEventProcessor makeEventProcessor(EventSender sender, long flushIntervalMillis,
                                                    ScheduledExecutorService scheduler) {
        return makeEventProcessor(sender, flushIntervalMillis,
                DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS, scheduler);
    }

    private DirectEventProcessor makeEventProcessor(EventSender sender, long flushIntervalMillis,
                                                    long closeBudgetMillis,
                                                    ScheduledExecutorService scheduler) {
        return makeEventProcessor(sender, null, flushIntervalMillis, 60_000, closeBudgetMillis,
                scheduler);
    }

    /**
     * For the one test whose subject is what a payload contains rather than how much fits. Named
     * rather than overloaded: an int alongside the flush-interval long would quietly take over the
     * calls that pass an interval as a literal.
     */
    private DirectEventProcessor makeEventProcessorWithCapacity(EventSender sender, int capacity,
                                                    ScheduledExecutorService scheduler) {
        ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
        diagnosticExecutors.add(diagnosticExecutor);
        return makeEventProcessor(sender, null, NO_PERIODIC_FLUSH_MILLIS, 60_000,
                DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS, scheduler, diagnosticExecutor,
                capacity);
    }

    private DirectEventProcessor makeEventProcessor(EventSender sender,
                                                    DiagnosticStore diagnosticStore,
                                                    long flushIntervalMillis,
                                                    ScheduledExecutorService scheduler) {
        return makeEventProcessor(sender, diagnosticStore, flushIntervalMillis, 60_000,
                DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS, scheduler);
    }

    private DirectEventProcessor makeEventProcessor(EventSender sender,
                                                    DiagnosticStore diagnosticStore,
                                                    long flushIntervalMillis,
                                                    long diagnosticIntervalMillis,
                                                    ScheduledExecutorService scheduler) {
        return makeEventProcessor(sender, diagnosticStore, flushIntervalMillis,
                diagnosticIntervalMillis, DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS,
                scheduler);
    }

    private DirectEventProcessor makeEventProcessor(EventSender sender,
                                                    DiagnosticStore diagnosticStore,
                                                    long flushIntervalMillis,
                                                    long diagnosticIntervalMillis,
                                                    long closeBudgetMillis,
                                                    ScheduledExecutorService scheduler) {
        ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
        diagnosticExecutors.add(diagnosticExecutor);
        return makeEventProcessor(sender, diagnosticStore, flushIntervalMillis,
                diagnosticIntervalMillis, closeBudgetMillis, scheduler, diagnosticExecutor);
    }

    private DirectEventProcessor makeEventProcessor(EventSender sender,
                                                    DiagnosticStore diagnosticStore,
                                                    long flushIntervalMillis,
                                                    long diagnosticIntervalMillis,
                                                    long closeBudgetMillis,
                                                    ScheduledExecutorService scheduler,
                                                    ExecutorService diagnosticExecutor) {
        return makeEventProcessor(sender, diagnosticStore, flushIntervalMillis,
                diagnosticIntervalMillis, closeBudgetMillis, scheduler, diagnosticExecutor,
                DEFAULT_CAPACITY);
    }

    private DirectEventProcessor makeEventProcessor(EventSender sender,
                                                    DiagnosticStore diagnosticStore,
                                                    long flushIntervalMillis,
                                                    long diagnosticIntervalMillis,
                                                    long closeBudgetMillis,
                                                    ScheduledExecutorService scheduler,
                                                    ExecutorService diagnosticExecutor,
                                                    int capacity) {
        return new DirectEventProcessor(
                new OutboundEventBuffer(false, Collections.emptyList(), true, capacity,
                        logging.logger),
                sender,
                URI.create("https://events.example"),
                diagnosticStore,
                capacity,
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
