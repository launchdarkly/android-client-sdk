package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.sdk.internal.events.EventSender;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
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

    // Long enough that the only delivery in a test is the one it asks for.
    private static final long NO_PERIODIC_FLUSH_MILLIS = 600_000;
    // Short enough to keep the close tests quick; the production value is chosen for a real network.
    private static final long CLOSE_BUDGET_MILLIS = 200;

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

    private DirectEventProcessor makeEventProcessor(EventSender sender, long flushIntervalMillis,
                                                    ScheduledExecutorService scheduler) {
        return makeEventProcessor(sender, flushIntervalMillis,
                DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS, scheduler);
    }

    private DirectEventProcessor makeEventProcessor(EventSender sender, long flushIntervalMillis,
                                                    long closeBudgetMillis,
                                                    ScheduledExecutorService scheduler) {
        return new DirectEventProcessor(
                new OutboundEventBuffer(false, Collections.emptyList(), true, logging.logger),
                sender,
                URI.create("https://events.example"),
                null,
                DEFAULT_CAPACITY,
                flushIntervalMillis,
                60_000,
                closeBudgetMillis,
                false,
                true, // initiallyOffline, as the SDK builds it
                scheduler,
                logging.logger);
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
