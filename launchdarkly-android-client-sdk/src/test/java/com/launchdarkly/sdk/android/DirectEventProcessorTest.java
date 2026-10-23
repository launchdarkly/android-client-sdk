package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EventPersistence;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.internal.events.Event;
import com.launchdarkly.sdk.internal.events.EventSender;
import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.HandlerSwitcher;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.After;
import org.junit.Test;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
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
    private static final String PAYLOAD_ID_HEADER = "X-LaunchDarkly-Payload-ID";

    // Enough concurrent evaluations that close() reliably lands between the two writes one evaluation
    // makes. Against the unfixed code this failed in the first trial of every run, by two to four
    // events -- roughly the number of recorders that can sit in the gap at once.
    private static final int RACE_TRIALS = 20;
    private static final int RACE_RECORDERS = 4;
    private static final int EVALUATIONS_PER_RACE_RECORDER = 2_000;
    private static final int EVALUATIONS_BEFORE_CLOSE = 200;
    private static final int RACE_CAPACITY = 30;
    // Past anything the recorders can produce, so that a commit short of a feature event is short
    // because the evaluation was split rather than because the buffer was full.
    private static final int NO_DROP_CAPACITY = RACE_RECORDERS * EVALUATIONS_PER_RACE_RECORDER * 2;
    private static final int SPLIT_TRIALS = 8;

    // Long enough that the only delivery in a test is the one it asks for.
    private static final int NO_PERIODIC_FLUSH_MILLIS = 600_000;
    // Short enough to keep the close tests quick; the production value is chosen for a real network.
    private static final long CLOSE_BUDGET_MILLIS = 200;

    /** Created by makeEventProcessor, which the tests call instead of building a processor. */
    private final List<ExecutorService> diagnosticExecutors = new ArrayList<>();
    /** The commit executor the last processor made here commits on, for a test to wait behind. */
    private ExecutorService lastCommitExecutor;

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
        assumePersisting();
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordEvaluationEvent(CONTEXT, FLAG_KEY, FLAG_VERSION, VARIATION,
                        FLAG_VALUE, EvaluationReason.off(), DEFAULT_VALUE, true, null);
                eventProcessor.setOffline(true);

                eventProcessor.flush();

                List<byte[]> persisted = payloadsOnDisk();
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

    @Test
    public void aDeferredTrackReachesTheDiskWhileADeliveryHoldsTheEventsThread() throws Exception {
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        EventStore store = EventStore.create(eventsDirectory.getRoot(), MOBILE_KEY,
                DEFAULT_CAPACITY, true, logging.logger);
        DirectEventProcessor eventProcessor = makeEventProcessor(store, DEFAULT_CAPACITY, scheduler);
        CountDownLatch deliveryAnswers = new CountDownLatch(1);
        try {
            // Stands in for a post to a network that does not answer, which holds the events thread for
            // as long as its timeouts allow.
            scheduler.submit(() -> awaitQuietly(deliveryAnswers, 10, TimeUnit.SECONDS));

            eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.of("data"), 2.5);

            long deadline = System.currentTimeMillis() + 5_000;
            while (kindsOnDisk("custom") == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, kindsOnDisk("custom"));
        } finally {
            deliveryAnswers.countDown();
            eventProcessor.close();
        }
    }

    @Test
    public void aFlushThatWaitsWritesWhileADeliveryHoldsTheEventsThread() throws Exception {
        // An evaluation is not a commit point, so a flush is what makes one durable. flushAsync hands
        // the delivery to the events thread, which a post or a retry can hold for longer than the
        // caller's timeout -- and flushAndWait is what an application calls when it is about to be
        // killed, so the write cannot be left to that thread. It goes to the commit executor instead,
        // which the network never holds.
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
        diagnosticExecutors.add(diagnosticExecutor);
        EventStore store = EventStore.create(eventsDirectory.getRoot(), MOBILE_KEY,
                DEFAULT_CAPACITY, true, logging.logger);
        DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                UNUSED_EVENTS_URI, null, NO_PERIODIC_FLUSH_MILLIS, 60_000, CLOSE_BUDGET_MILLIS,
                scheduler, diagnosticExecutor, store, DEFAULT_CAPACITY, true);
        CountDownLatch deliveryAnswers = new CountDownLatch(1);
        try {
            eventProcessor.setOffline(false);
            // Stands in for a post to a network that does not answer, which holds the events thread for
            // as long as its timeouts allow.
            scheduler.submit(() -> awaitQuietly(deliveryAnswers, 10, TimeUnit.SECONDS));
            eventProcessor.recordEvaluationEvent(CONTEXT, FLAG_KEY, FLAG_VERSION, VARIATION,
                    FLAG_VALUE, EvaluationReason.off(), DEFAULT_VALUE, true, null);

            assertFalse("the delivery answered even though the events thread was held",
                    awaitFlush(eventProcessor, 0, TimeUnit.MILLISECONDS));

            // While the events thread is still held, so the delivery's own commit cannot be the one.
            assertEquals(1, kindsOnDiskSoon("feature"));
            assertEquals(1, kindsOnDiskSoon("summary"));
        } finally {
            deliveryAnswers.countDown();
            eventProcessor.close();
        }
    }

    @Test
    public void aDeferredFlushThatWaitsReachesTheDiskWhileADeliveryHoldsTheEventsThread() throws Exception {
        // As above, where the application asked for writes to be queued rather than made on its own
        // thread.
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        EventStore store = EventStore.create(eventsDirectory.getRoot(), MOBILE_KEY,
                DEFAULT_CAPACITY, true, logging.logger);
        DirectEventProcessor eventProcessor = makeEventProcessor(store, DEFAULT_CAPACITY, scheduler);
        CountDownLatch deliveryAnswers = new CountDownLatch(1);
        try {
            eventProcessor.setOffline(false);
            scheduler.submit(() -> awaitQuietly(deliveryAnswers, 10, TimeUnit.SECONDS));
            eventProcessor.recordEvaluationEvent(CONTEXT, FLAG_KEY, FLAG_VERSION, VARIATION,
                    FLAG_VALUE, EvaluationReason.off(), DEFAULT_VALUE, true, null);

            assertFalse("the delivery answered even though the events thread was held",
                    awaitFlush(eventProcessor, 0, TimeUnit.MILLISECONDS));

            assertEquals(1, kindsOnDiskSoon("feature"));
            assertEquals(1, kindsOnDiskSoon("summary"));
        } finally {
            deliveryAnswers.countDown();
            eventProcessor.close();
        }
    }

    @Test
    public void aFlushThatWaitsGetsItsFutureWhileAWriteIsStalledOnTheDisk() throws Exception {
        // flushAndWait can only enforce its timeout once it holds the future. A write stalled on the disk
        // holds the commit lock, and a flush that committed before handing back its future waited behind
        // it with no bound at all, on the crashing thread when the crash handler was the caller.
        CountDownLatch writeStalled = new CountDownLatch(1);
        CountDownLatch diskAnswers = new CountDownLatch(1);
        EventStore store = new EventStore(eventsDirectory.newFolder(), DEFAULT_CAPACITY, true,
                logging.logger) {
            @Override
            void commit() {
                writeStalled.countDown();
                awaitQuietly(diskAnswers, 10, TimeUnit.SECONDS);
                super.commit();
            }
        };
        DirectEventProcessor eventProcessor = makeEventProcessor(store, DEFAULT_CAPACITY,
                EventUtil.makeEventsTaskExecutor());
        try {
            eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
            assertTrue("the track's commit never reached the disk", writeStalled.await(5, TimeUnit.SECONDS));

            eventProcessor.recordCustomEvent(CONTEXT, "before-exit", LDValue.ofNull(), null);
            long start = System.nanoTime();
            eventProcessor.flushAsync();
            long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue("flushAsync waited " + tookMillis + "ms for the stalled write", tookMillis < 1_000);
        } finally {
            diskAnswers.countDown();
            eventProcessor.close();
        }
    }

    @Test
    public void aDeferredFlushWritesWithoutWaitingForTheQueueATrackedEventsWriteGoesTo() throws Exception {
        // A flush is what an application calls before ending the process on purpose, so at DEFERRED it
        // writes on its own thread rather than queueing the write the way track does. Holding the commit
        // executor keeps a queued write off the disk, so only a write on this thread can pass.
        EventStore store = EventStore.create(eventsDirectory.getRoot(), MOBILE_KEY,
                DEFAULT_CAPACITY, true, logging.logger);
        DirectEventProcessor eventProcessor = makeEventProcessor(store, DEFAULT_CAPACITY,
                EventUtil.makeEventsTaskExecutor());
        CountDownLatch commitsResume = new CountDownLatch(1);
        try {
            lastCommitExecutor.submit(() -> awaitQuietly(commitsResume, 10, TimeUnit.SECONDS));
            eventProcessor.recordCustomEvent(CONTEXT, "before-exit", null, null);
            assertEquals(0, kindsOnDisk("custom"));

            eventProcessor.flush();

            assertEquals(1, kindsOnDisk("custom"));
        } finally {
            commitsResume.countDown();
            eventProcessor.close();
        }
    }

    @Test
    public void ofTwoFlushesWaitingOnOneRetryOnlyTheOneWhoseDeliveryLostAnEventIsTold() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.sequential(Handlers.status(503),
                Handlers.status(202), Handlers.status(202)))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "poison", LDValue.ofNull(), Double.NaN);
                eventProcessor.recordCustomEvent(CONTEXT, "kept", LDValue.ofNull(), 1.0);

                // The first flush loses the poison event, is refused, and waits for the retry a second away.
                Future<Boolean> first = eventProcessor.flushAsync();
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                // The second finds the backoff waiting and joins the same retry, with nothing of its own.
                Future<Boolean> second = eventProcessor.flushAsync();

                // The retry succeeds. Whichever answers first, the loss is the first flush's.
                assertEquals(false, first.get(10, TimeUnit.SECONDS));
                assertEquals(true, second.get(10, TimeUnit.SECONDS));
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);

                // A flush after both is not told again.
                eventProcessor.recordCustomEvent(CONTEXT, "after", LDValue.ofNull(), 1.0);
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aLossIsToldToTheFlushWhoseDeliveryPassedItNotWhicheverAnswersFirst() throws Exception {
        Semaphore letFirstResponseFinish = new Semaphore(0);
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(Handlers.waitFor(letFirstResponseFinish), Handlers.status(202)),
                Handlers.status(202)))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                // The first closes a batch of its own and waits on it. Nothing has been lost at that point.
                eventProcessor.recordCustomEvent(CONTEXT, "first", LDValue.ofNull(), null);
                Future<Boolean> first = eventProcessor.flushAsync();
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);

                // Lost after the first closed its batch, so the loss is the second's.
                eventProcessor.recordCustomEvent(CONTEXT, "poison", LDValue.ofNull(), Double.NaN);
                eventProcessor.recordCustomEvent(CONTEXT, "second", LDValue.ofNull(), null);
                Future<Boolean> second = eventProcessor.flushAsync();
                letFirstResponseFinish.release();

                assertTrue(first.get(5, TimeUnit.SECONDS));
                assertFalse(second.get(5, TimeUnit.SECONDS));
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
            } finally {
                letFirstResponseFinish.release(Integer.MAX_VALUE);
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aFlushWithNothingOfItsOwnIsNotToldOfALossTheDeliveryBeforeItHad() throws Exception {
        Semaphore letFirstResponseFinish = new Semaphore(0);
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(Handlers.waitFor(letFirstResponseFinish), Handlers.status(202)),
                Handlers.status(202)))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "poison", LDValue.ofNull(), Double.NaN);
                eventProcessor.recordCustomEvent(CONTEXT, "kept", LDValue.ofNull(), null);
                Future<Boolean> first = eventProcessor.flushAsync();
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);

                // Told too, it would report a loss that was not its own.
                Future<Boolean> second = eventProcessor.flushAsync();
                letFirstResponseFinish.release();

                assertFalse(first.get(5, TimeUnit.SECONDS));
                assertTrue(second.get(5, TimeUnit.SECONDS));
            } finally {
                letFirstResponseFinish.release(Integer.MAX_VALUE);
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aLossADeliveryNobodyWaitedOnPassedIsToldToTheNextFlush() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "poison", LDValue.ofNull(), Double.NaN);
                eventProcessor.recordCustomEvent(CONTEXT, "kept", LDValue.ofNull(), null);
                // As the periodic flush or going online would, with nobody to tell.
                eventProcessor.flush();
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);

                assertFalse(awaitFlush(eventProcessor, 5, TimeUnit.SECONDS));
                assertTrue(awaitFlush(eventProcessor, 5, TimeUnit.SECONDS));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aFailedDeliveryIsRetriedOnceUnderTheSamePayloadId() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.sequential(Handlers.status(503),
                Handlers.status(202)))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);

                // A flush that waits, waits for the retry too, so it reports the delivery that worked.
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));

                RequestInfo failed = server.getRecorder().requireRequest(1, TimeUnit.SECONDS);
                RequestInfo retried = server.getRecorder().requireRequest(1, TimeUnit.SECONDS);
                assertEquals(failed.getHeader("X-LaunchDarkly-Payload-ID"),
                        retried.getHeader("X-LaunchDarkly-Payload-ID"));
                assertEquals(failed.getBody(), retried.getBody());
                server.getRecorder().requireNoRequests(200, TimeUnit.MILLISECONDS);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void waitingToRetryDoesNotHoldTheEventsThread() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(503))) {
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
            diagnosticExecutors.add(diagnosticExecutor);
            EventStore store = EventStore.create(eventsDirectory.getRoot(), MOBILE_KEY,
                    DEFAULT_CAPACITY, true, logging.logger);
            DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                    server.getUri(), null, NO_PERIODIC_FLUSH_MILLIS, 60_000, CLOSE_BUDGET_MILLIS,
                    scheduler, diagnosticExecutor, store, DEFAULT_CAPACITY);
            try {
                eventProcessor.setOffline(false);
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
                eventProcessor.flush();
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);

                // Well inside the retry delay: a thread sleeping through it would not get to this.
                scheduler.submit(() -> { }).get(DirectEventProcessor.RETRY_DELAY_MILLIS / 2,
                        TimeUnit.MILLISECONDS);

                // One retry and no more; the batch then waits for the next flush.
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                server.getRecorder().requireNoRequests(DirectEventProcessor.RETRY_DELAY_MILLIS * 2,
                        TimeUnit.MILLISECONDS);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aStoredBatchThisVersionCannotReadIsDroppedAndReportedWhileTheRestAreDelivered() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(202))) {
            EventStore store = EventStore.create(eventsDirectory.getRoot(), MOBILE_KEY,
                    DEFAULT_CAPACITY, true, logging.logger);
            ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
            diagnosticExecutors.add(diagnosticExecutor);
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                    server.getUri(), null, NO_PERIODIC_FLUSH_MILLIS, 60_000, CLOSE_BUDGET_MILLIS,
                    scheduler, diagnosticExecutor, store, DEFAULT_CAPACITY);
            try {
                // Online, and past the startup listing, before the batch exists, so the flush below is the
                // delivery that finds it rather than one that recovery or coming online starts, which
                // nobody waits on.
                eventProcessor.setOffline(false);
                scheduler.submit(() -> { }).get(2, TimeUnit.SECONDS);
                store.stage("{\"kind\":\"custom\",\"key\":\"damaged\"}".getBytes(StandardCharsets.UTF_8));
                EventStore.Batch damaged = closeOne(store);
                assertNotNull(damaged);
                // Its name still counts it, so it is listed without being read; the damage is only found on
                // sending.
                File damagedFile = findFile(eventsDirectory.getRoot(), "ready-" + damaged.payloadId + ".1");
                assertNotNull(damagedFile);
                java.nio.file.Files.write(damagedFile.toPath(),
                        "not an event log".getBytes(StandardCharsets.UTF_8));
                eventProcessor.recordCustomEvent(CONTEXT, "intact", LDValue.ofNull(), null);

                assertFalse("a flush that lost a batch reported success",
                        awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));

                RequestInfo sent = server.getRecorder().requireRequest(1, TimeUnit.SECONDS);
                assertTrue(sent.getBody().contains("intact"));
                server.getRecorder().requireNoRequests(200, TimeUnit.MILLISECONDS);
                assertFalse(damagedFile.exists());
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aStoredBatchDamagedAfterItsFirstFrameIsSentShortAndReportedAsLosingTheRest() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(202))) {
            EventStore store = EventStore.create(eventsDirectory.getRoot(), MOBILE_KEY,
                    DEFAULT_CAPACITY, true, logging.logger);
            ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
            diagnosticExecutors.add(diagnosticExecutor);
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                    server.getUri(), null, NO_PERIODIC_FLUSH_MILLIS, 60_000, CLOSE_BUDGET_MILLIS,
                    scheduler, diagnosticExecutor, store, DEFAULT_CAPACITY);
            try {
                // As above, past the startup listing, so the flush is the delivery that finds the batch.
                eventProcessor.setOffline(false);
                scheduler.submit(() -> { }).get(2, TimeUnit.SECONDS);
                for (String key : new String[] { "kept-0", "kept-1", "torn" }) {
                    store.stage(("{\"kind\":\"custom\",\"key\":\"" + key + "\"}").getBytes(StandardCharsets.UTF_8));
                }
                EventStore.Batch damaged = closeOne(store);
                File damagedFile = findFile(eventsDirectory.getRoot(), "ready-" + damaged.payloadId + ".3");
                assertNotNull(damagedFile);
                byte[] log = java.nio.file.Files.readAllBytes(damagedFile.toPath());
                log[log.length - 1] ^= 0x01;
                java.nio.file.Files.write(damagedFile.toPath(), log);

                assertFalse("a flush that sent a batch short reported success",
                        awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));

                RequestInfo sent = server.getRecorder().requireRequest(1, TimeUnit.SECONDS);
                assertTrue(sent.getBody().contains("kept-0"));
                assertTrue(sent.getBody().contains("kept-1"));
                assertFalse(sent.getBody().contains("torn"));
                assertFalse(damagedFile.exists());

                // Reported once: the next flush has nothing of it to answer for.
                eventProcessor.recordCustomEvent(CONTEXT, "after", LDValue.ofNull(), null);
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
            } finally {
                eventProcessor.close();
            }
        }
    }

    /** Closes what a test expects to be exactly one batch, as anything short of a failed write leaves. */
    private static EventStore.Batch closeOne(EventStore store) {
        List<EventStore.Batch> closed = store.closeBatch();
        assertEquals(1, closed.size());
        return closed.get(0);
    }

    private static File findFile(File directory, String name) {
        File[] files = directory.listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (file.getName().equals(name)) {
                return file;
            }
            File found = file.isDirectory() ? findFile(file, name) : null;
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** @return how many events of this kind the store holds, read as another process would read it */
    /**
     * Every event in the environment's directory, read straight off the disk the way a process starting after a
     * kill finds them: every log, whoever has it open, and every batch.
     */
    private List<byte[]> payloadsOnDisk() throws IOException {
        List<byte[]> payloads = new ArrayList<>();
        File[] files = EventDirectory.defaultDirectory(eventsDirectory.getRoot(), MOBILE_KEY).listFiles();
        if (files == null) {
            return payloads;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(DiskEventLog.PREFIX) || name.startsWith("ready-")) {
                try {
                    payloads.addAll(EventLogFormat.eventPayloads(Files.readAllBytes(file.toPath())));
                } catch (NoSuchFileException e) {
                    // Closed off or delivered since the listing.
                }
            }
        }
        return payloads;
    }

    private int kindsOnDisk(String kind) throws Exception {
        int found = 0;
        for (byte[] payload : payloadsOnDisk()) {
            if (kind.equals(LDValue.parse(new String(payload, "UTF-8")).get("kind").stringValue())) {
                found++;
            }
        }
        return found;
    }

    /** {@link #kindsOnDisk}, once at least one is there or five seconds have passed. */
    private int kindsOnDiskSoon(String kind) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (kindsOnDisk(kind) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return kindsOnDisk(kind);
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

                assertTrue("an event reached the disk without being asked to", payloadsOnDisk().isEmpty());

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
                // The recorders outrun the buffer, so capacity is the only thing bounding the payload.
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
    public void eventsOnTheirWayIntoTheStoreStillCountAgainstCapacity() throws Exception {
        // A commit takes the pending events and encodes them before staging, and for that long they
        // are in neither place. Counting only the two would let an event recorded then past capacity.
        CountDownLatch staging = new CountDownLatch(1);
        CountDownLatch releaseStaging = new CountDownLatch(1);
        // Persisting, since without it a commit point queues no commit at all.
        EventStore store = new EventStore(eventsDirectory.newFolder(), 1, true,
                logging.logger) {
            @Override
            boolean stageReserved(byte[] serializedEvent) {
                staging.countDown();
                awaitQuietly(releaseStaging, 5, TimeUnit.SECONDS);
                return super.stageReserved(serializedEvent);
            }
        };
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(store, 1, scheduler);
        try {
            eventProcessor.recordIdentifyEvent(CONTEXT);
            assertTrue("the commit never started staging", staging.await(2, TimeUnit.SECONDS));

            eventProcessor.recordIdentifyEvent(CONTEXT);
            releaseStaging.countDown();

            assertEquals(1, eventProcessor.getAndClearDroppedCount());
        } finally {
            releaseStaging.countDown();
            eventProcessor.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    public void anEventAlreadyStagedIsNotCountedTwiceAgainstCapacity() throws Exception {
        // Paused just after the first event reached the store, with the commit that staged it still
        // running. Counted once, it leaves room for a second event under a capacity of two; counted both
        // as staged and as on its way, it would not.
        CountDownLatch staged = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        // Persisting, for the reason the test above is.
        EventStore store = new EventStore(eventsDirectory.newFolder(), 2, true,
                logging.logger) {
            @Override
            boolean stageReserved(byte[] serializedEvent) {
                boolean result = super.stageReserved(serializedEvent);
                staged.countDown();
                awaitQuietly(releaseCommit, 5, TimeUnit.SECONDS);
                return result;
            }
        };
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(store, 2, scheduler);
        try {
            eventProcessor.recordIdentifyEvent(CONTEXT);
            assertTrue("the commit never staged the event", staged.await(2, TimeUnit.SECONDS));

            eventProcessor.recordIdentifyEvent(CONTEXT);
            releaseCommit.countDown();

            assertEquals(0, eventProcessor.getAndClearDroppedCount());
        } finally {
            releaseCommit.countDown();
            eventProcessor.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    public void storedEventsPastTheMaximumAgeAreDiscardedAsDroppedWithoutFailingAFlush() throws Exception {
        assumePersisting();
        EventStore previousRun = EventStore.create(eventsDirectory.getRoot(), MOBILE_KEY,
                DEFAULT_CAPACITY, true, logging.logger);
        previousRun.stage(LDValue.buildObject().put("kind", "custom").put("key", "old-1").build()
                .toJsonString().getBytes(StandardCharsets.UTF_8));
        previousRun.stage(LDValue.buildObject().put("kind", "custom").put("key", "old-2").build()
                .toJsonString().getBytes(StandardCharsets.UTF_8));
        previousRun.closeBatch();
        previousRun.close();
        File[] batches = previousRun.getDirectory().listFiles((dir, name) -> name.startsWith("ready-"));
        assertEquals(1, batches.length);
        assertTrue(batches[0].setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)));

        try (HttpServer server = startEventsServer()) {
            DirectEventProcessor eventProcessor = (DirectEventProcessor) makeEventProcessor(server,
                    DEFAULT_CAPACITY);
            try {
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));

                assertEquals(0, server.getRecorder().count());
                assertEquals(2, eventProcessor.getAndClearDroppedCount());
                assertFalse(batches[0].exists());
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void anEventWithNoBytesLeftForItIsDroppedBeforeItIsEncoded() throws Exception {
        EventStore store = new EventStore(eventsDirectory.newFolder(), DEFAULT_CAPACITY, 1000,
                EventProcessorBuilder.DEFAULT_MAX_AGE_MILLIS, true, logging.logger);
        byte[] filler = new byte[800 - EventLogFormat.FRAME_OVERHEAD];
        Arrays.fill(filler, (byte) 'x');
        assertTrue(store.stage(filler));
        assertFalse(store.hasRoomForEventBytes());
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(store, DEFAULT_CAPACITY, scheduler);
        try {
            eventProcessor.recordCustomEvent(CONTEXT, "refused", LDValue.ofNull(), null);
            eventProcessor.flush();

            assertEquals(1, eventProcessor.getAndClearDroppedCount());
            assertEquals(1, store.getPendingEventCount());
        } finally {
            eventProcessor.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    public void aFlushFailsWhenAnAcceptedEventIsTooLargeForTheBytesLeft() throws Exception {
        assumePersisting();
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            data.append('x');
        }
        try (HttpServer server = startEventsServer()) {
            DirectEventProcessor eventProcessor = (DirectEventProcessor) makeEventProcessor(server,
                    eventsBuilder(DEFAULT_CAPACITY).capacityBytes(400), true);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "too-large", LDValue.of(data.toString()), null);

                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                logging.assertErrorLogged("no room");
                assertEquals(0, eventProcessor.getAndClearDroppedCount());
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void theStoreAsksThePlatformWhereItLivesOffTheCallersThread() throws Exception {
        // LDClient.init builds the processor, usually on the main thread, and answering touches the disk:
        // the platform creates the no-backup directory when asked for it.
        Queue<Thread> askedOn = new ConcurrentLinkedQueue<>();
        CountDownLatch asked = new CountDownLatch(1);
        MockPlatformState platformState = new MockPlatformState() {
            @Override
            public File getNoBackupFilesDir() {
                askedOn.add(Thread.currentThread());
                asked.countDown();
                return eventsDirectory.getRoot();
            }
        };
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = buildOfflineEventProcessor(server,
                    Components.sendEvents().eventPersistence(EventPersistence.DEFERRED)
                            .flushIntervalMillis(NO_PERIODIC_FLUSH_MILLIS),
                    true, platformState);
            eventProcessor.setOffline(false);
            eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
            // Waits for the store to ask rather than flushing: a flush commits on the caller's thread where
            // events persist, so whichever commit reaches the disk first would be the one asking.
            awaitQuietly(asked, 5, TimeUnit.SECONDS);
            eventProcessor.close();

            assertFalse("the platform was never asked, so this proves nothing", askedOn.isEmpty());
            assertFalse("the platform was asked on the caller's thread",
                    askedOn.contains(Thread.currentThread()));
        }
    }

    @Test
    public void withoutPersistenceTheStoreNeverAsksThePlatformWhereItLives() throws Exception {
        // Asking is itself a filesystem operation, so a store that never asks never touches the disk:
        // not for a previous run's events at startup, and not on every delivery after.
        AtomicInteger asked = new AtomicInteger();
        MockPlatformState platformState = new MockPlatformState() {
            @Override
            public File getNoBackupFilesDir() {
                asked.incrementAndGet();
                return eventsDirectory.getRoot();
            }
        };
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = buildOfflineEventProcessor(server,
                    Components.sendEvents().eventPersistence(EventPersistence.DISABLED)
                            .flushIntervalMillis(NO_PERIODIC_FLUSH_MILLIS),
                    true, platformState);
            eventProcessor.setOffline(false);
            eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
            eventProcessor.flush();
            eventProcessor.blockingFlush();
            eventProcessor.close();

            assertEquals("the event was never delivered, so this proves nothing",
                    1, countEventsOfKind(collectDelivered(server), "custom"));
            assertEquals("a store without persistence looked for its directory", 0, asked.get());
        }
    }

    @Test
    public void concurrentImmediateTracksShareCommitsAndEachIsDurableOnReturn() throws Exception {
        final int trackers = 8;
        Queue<String> staged = new ConcurrentLinkedQueue<>();
        Queue<String> durable = new ConcurrentLinkedQueue<>();
        AtomicInteger commits = new AtomicInteger();
        EventStore store = new EventStore(eventsDirectory.newFolder(), DEFAULT_CAPACITY, true,
                logging.logger) {
            @Override
            boolean stageReserved(byte[] serializedEvent) {
                staged.add(new String(serializedEvent, StandardCharsets.UTF_8));
                return super.stageReserved(serializedEvent);
            }

            @Override
            void commit() {
                commits.incrementAndGet();
                // A slow disk, so that the other tracks arrive while this write is under way.
                sleepQuietly(50);
                // Everything staged so far was staged under the commit lock this commit holds.
                List<String> writing = new ArrayList<>(staged);
                super.commit();
                durable.addAll(writing);
            }
        };
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
        diagnosticExecutors.add(diagnosticExecutor);
        DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(), UNUSED_EVENTS_URI,
                null, NO_PERIODIC_FLUSH_MILLIS, 60_000, CLOSE_BUDGET_MILLIS, scheduler, diagnosticExecutor,
                store, DEFAULT_CAPACITY, true);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger notDurableOnReturn = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < trackers; i++) {
                String key = "event-" + i;
                Thread thread = new Thread(() -> {
                    awaitQuietly(start, 5, TimeUnit.SECONDS);
                    eventProcessor.recordCustomEvent(CONTEXT, key, LDValue.ofNull(), null);
                    boolean found = false;
                    for (String event : durable) {
                        found |= event.contains("\"key\":\"" + key + "\"");
                    }
                    if (!found) {
                        notDurableOnReturn.incrementAndGet();
                    }
                });
                thread.start();
                threads.add(thread);
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join(10_000);
            }

            assertEquals("a track returned before its event was on disk", 0, notDurableOnReturn.get());
            assertTrue("each track committed on its own: " + commits.get() + " commits for "
                    + trackers + " tracks", commits.get() < trackers);
        } finally {
            eventProcessor.close();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void deferredPersistenceWritesOnTheCallersThreadOnlyForAFlush() throws Exception {
        Queue<Thread> committedOn = new ConcurrentLinkedQueue<>();
        EventStore store = new EventStore(eventsDirectory.newFolder(), DEFAULT_CAPACITY, true,
                logging.logger) {
            @Override
            void commit() {
                committedOn.add(Thread.currentThread());
                super.commit();
            }
        };
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(store, DEFAULT_CAPACITY, scheduler);
        CountDownLatch commitsResume = new CountDownLatch(1);
        try {
            eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
            eventProcessor.recordCustomEvent(CONTEXT, "another", LDValue.ofNull(), null);
            lastCommitExecutor.submit(() -> { }).get(2, TimeUnit.SECONDS);

            assertFalse("nothing was ever committed, so this proves nothing", committedOn.isEmpty());
            assertFalse("a track committed on the caller's thread",
                    committedOn.contains(Thread.currentThread()));

            // Held, because the track below queues a commit of its own. Were it to run first it would cover
            // everything the flush needs, leaving the flush nothing to write on this thread.
            lastCommitExecutor.submit(() -> awaitQuietly(commitsResume, 10, TimeUnit.SECONDS));
            eventProcessor.recordCustomEvent(CONTEXT, "before-exit", LDValue.ofNull(), null);
            eventProcessor.flush();

            assertTrue("a flush left its commit to another thread",
                    committedOn.contains(Thread.currentThread()));
        } finally {
            commitsResume.countDown();
            eventProcessor.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    public void aFullPendingRunIsCommittedWherePersistenceIsOn() throws Exception {
        EventStore store = new EventStore(eventsDirectory.newFolder(), DEFAULT_CAPACITY, true,
                logging.logger);

        assertTrue("the run was never committed", pendingEventsInStoreAfterAFullRun(store) > 0);
    }

    @Test
    public void aFullPendingRunWaitsForTheFlushWherePersistenceIsOff() throws Exception {
        // Committing it would make nothing durable, and would put the encode in among the evaluations.
        EventStore store = new EventStore(eventsDirectory.newFolder(), DEFAULT_CAPACITY, false,
                logging.logger);

        assertEquals(0, pendingEventsInStoreAfterAFullRun(store));
    }

    @Test
    public void anOfflineFlushEncodesNothingWherePersistenceIsOff() throws Exception {
        AtomicInteger encoded = new AtomicInteger();
        EventStore store = encodingCountingStore(encoded);
        try (HttpServer server = startEventsServer()) {
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            DirectEventProcessor eventProcessor = makeEventProcessor(server, store, scheduler);
            try {
                eventProcessor.setOffline(true);
                // Past the pending threshold, which schedules a commit only where one would write.
                for (int i = 0; i < 40; i++) {
                    recordEvaluation(eventProcessor, true, null);
                }
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);

                assertFalse(awaitFlush(eventProcessor, 5, TimeUnit.SECONDS));
                eventProcessor.blockingFlush();
                eventProcessor.flush();
                scheduler.submit(() -> { }).get(5, TimeUnit.SECONDS);
                lastCommitExecutor.submit(() -> { }).get(5, TimeUnit.SECONDS);

                assertEquals("an offline flush encoded events it could not send", 0, encoded.get());
                server.getRecorder().requireNoRequests(100, TimeUnit.MILLISECONDS);

                eventProcessor.setOffline(false);
                eventProcessor.blockingFlush();

                // One delivery, carrying everything recorded while offline.
                RequestInfo sent = server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                server.getRecorder().requireNoRequests(200, TimeUnit.MILLISECONDS);
                List<LDValue> events = new ArrayList<>();
                for (LDValue event : LDValue.parse(sent.getBody()).values()) {
                    events.add(event);
                }
                assertEquals(40, countEventsOfKind(events, "feature"));
                assertEquals(1, countEventsOfKind(events, "custom"));
                assertEquals(40, summaryCountFor(events, FLAG_KEY));
            } finally {
                eventProcessor.close();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    public void closingOfflineEncodesNothingWherePersistenceIsOff() throws Exception {
        AtomicInteger encoded = new AtomicInteger();
        EventStore store = encodingCountingStore(encoded);
        try (HttpServer server = startEventsServer()) {
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            DirectEventProcessor eventProcessor = makeEventProcessor(server, store, scheduler);
            try {
                eventProcessor.setOffline(true);
                recordEvaluation(eventProcessor, true, null);
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);

                eventProcessor.close();
                assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(lastCommitExecutor.awaitTermination(5, TimeUnit.SECONDS));

                assertEquals("closing offline encoded events it could not send", 0, encoded.get());
                server.getRecorder().requireNoRequests(100, TimeUnit.MILLISECONDS);
            } finally {
                eventProcessor.close();
                scheduler.shutdownNow();
            }
        }
    }

    /** A store that does not persist, counting every encoded event or summary staged into it. */
    private EventStore encodingCountingStore(AtomicInteger encoded) throws Exception {
        return new EventStore(eventsDirectory.newFolder(), DEFAULT_CAPACITY, false, logging.logger) {
            @Override
            boolean stage(byte[] serializedEvent, boolean bypassingCapacity) {
                encoded.incrementAndGet();
                return super.stage(serializedEvent, bypassingCapacity);
            }

            @Override
            boolean stageReserved(byte[] serializedEvent) {
                encoded.incrementAndGet();
                return super.stageReserved(serializedEvent);
            }
        };
    }

    private DirectEventProcessor makeEventProcessor(HttpServer server, EventStore store,
                                                    ScheduledExecutorService scheduler) {
        return makeEventProcessor(server, store, DEFAULT_CAPACITY, scheduler);
    }

    private DirectEventProcessor makeEventProcessor(HttpServer server, EventStore store, int capacity,
                                                    ScheduledExecutorService scheduler) {
        ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
        diagnosticExecutors.add(diagnosticExecutor);
        return makeEventProcessor(new StubEventSender(), server.getUri(), null, NO_PERIODIC_FLUSH_MILLIS,
                60_000, DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS, scheduler, diagnosticExecutor,
                store, capacity);
    }

    @Test
    public void aBatchDoesNotCountWhileItsRequestIsOutAndCountsAgainIfTheSendIsToBeRetried()
            throws Exception {
        for (boolean persistEvents : new boolean[] { true, false }) {
            EventStore store = new EventStore(eventsDirectory.newFolder(), 1, persistEvents,
                    logging.logger);
            assertTrue(store.stage(serialized("first")));
            EventStore.Batch batch = closeOne(store);

            store.startedSending(batch);
            assertEquals(0, store.getPendingEventCount());
            assertTrue(store.stage(serialized("while-sending")));

            store.stoppedSending(batch);
            assertEquals(2, store.getPendingEventCount());
            assertFalse(store.stage(serialized("after-retryable")));
        }
    }

    @Test
    public void aSentBatchLeavesTheCountOnlyOnceWhenItIsDelivered() throws Exception {
        for (boolean persistEvents : new boolean[] { true, false }) {
            EventStore store = new EventStore(eventsDirectory.newFolder(), 2, persistEvents,
                    logging.logger);
            assertTrue(store.stage(serialized("first")));
            EventStore.Batch batch = closeOne(store);
            assertTrue(store.stage(serialized("second")));

            store.startedSending(batch);
            store.remove(batch);
            assertEquals(1, store.getPendingEventCount());
            store.stoppedSending(batch);
            assertEquals(1, store.getPendingEventCount());
            store.pendingBatches();
            assertEquals(1, store.getPendingEventCount());
        }
    }

    @Test
    public void aBatchAlreadyRemovedIsNotLeftOutOfTheCountWhenItsSendStarts() throws Exception {
        // As when another process delivered it and a listing dropped it before this one got to it: leaving
        // it out would take its events from the batches still counted, and admit that many more.
        for (boolean persistEvents : new boolean[] { true, false }) {
            EventStore store = new EventStore(eventsDirectory.newFolder(), 2, persistEvents,
                    logging.logger);
            assertTrue(store.stage(serialized("first")));
            EventStore.Batch removed = closeOne(store);
            assertTrue(store.stage(serialized("second")));
            closeOne(store);
            store.remove(removed);

            store.startedSending(removed);
            assertEquals(1, store.getPendingEventCount());
            assertTrue(store.stage(serialized("third")));
            assertFalse(store.stage(serialized("fourth")));

            store.stoppedSending(removed);
            assertEquals(2, store.getPendingEventCount());
        }
    }

    @Test
    public void aBatchAnotherProcessDeliveredIsNotLeftOutOfTheCountWhenItsSendStarts() throws Exception {
        File directory = eventsDirectory.newFolder();
        EventStore store = new EventStore(directory, 2, true, logging.logger);
        assertTrue(store.stage(serialized("first")));
        EventStore.Batch delivered = closeOne(store);
        assertTrue(store.stage(serialized("second")));
        closeOne(store);
        EventStore other = new EventStore(directory, 2, true, logging.logger);
        other.pendingBatches();
        other.remove(delivered);
        store.pendingBatches();

        store.startedSending(delivered);
        assertEquals(1, store.getPendingEventCount());
        assertTrue(store.stage(serialized("third")));
        assertFalse(store.stage(serialized("fourth")));
    }

    /**
     * What the contract tests' "buffer is reset after flush" does: they have the payload as soon as it is
     * sent, and record the next event before the SDK has heard back.
     */
    @Test
    public void aFullStoreMakesRoomAsSoonAsItsBatchIsSent() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch respond = new CountDownLatch(1);
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                context -> {
                    received.countDown();
                    awaitQuietly(respond, 5, TimeUnit.SECONDS);
                    context.setStatus(202);
                },
                Handlers.status(202)))) {
            EventProcessor eventProcessor = makeEventProcessor(server, 2);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "first", LDValue.ofNull(), null);
                eventProcessor.recordCustomEvent(CONTEXT, "second", LDValue.ofNull(), null);
                eventProcessor.flush();
                assertTrue("the first batch was never sent", received.await(5, TimeUnit.SECONDS));

                eventProcessor.recordCustomEvent(CONTEXT, "one-more", LDValue.ofNull(), null);
                respond.countDown();
                eventProcessor.flush();

                List<String> keys = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    RequestInfo request = server.getRecorder().requireRequest(10, TimeUnit.SECONDS);
                    for (LDValue event : LDValue.parse(request.getBody()).values()) {
                        keys.add(event.get("key").stringValue());
                    }
                }
                assertEquals(Arrays.asList("first", "second", "one-more"), keys);
            } finally {
                eventProcessor.close();
            }
        }
    }

    private static byte[] serialized(String key) {
        return ("{\"kind\":\"custom\",\"key\":\"" + key + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Records more tracked evaluations than a pending run holds, lets the commit thread run whatever that
     * queued, and reports how many events reached the store.
     */
    private int pendingEventsInStoreAfterAFullRun(EventStore store) throws Exception {
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        DirectEventProcessor eventProcessor = makeEventProcessor(store, DEFAULT_CAPACITY, scheduler);
        try {
            for (int i = 0; i < 40; i++) {
                eventProcessor.recordEvaluationEvent(CONTEXT, FLAG_KEY, FLAG_VERSION, VARIATION,
                        FLAG_VALUE, EvaluationReason.off(), DEFAULT_VALUE, true, null);
            }
            lastCommitExecutor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            return store.getPendingEventCount();
        } finally {
            eventProcessor.close();
            scheduler.shutdownNow();
        }
    }

    @Test
    public void closeStillWritesWhenTheEventsThreadNeverGetsToIt() throws Exception {
        // The events thread is single-threaded, so a delivery that hangs would hold a final commit queued
        // there for longer than close() waits. The write is close()'s promise, so it does not run there.
        AtomicBoolean closing = new AtomicBoolean(false);
        Queue<Thread> committedDuringClose = new ConcurrentLinkedQueue<>();
        EventStore store = new EventStore(eventsDirectory.newFolder(), DEFAULT_CAPACITY, true,
                logging.logger) {
            @Override
            void commit() {
                if (closing.get()) {
                    committedDuringClose.add(Thread.currentThread());
                }
                super.commit();
            }
        };
        CountDownLatch releaseEventsThread = new CountDownLatch(1);
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
        diagnosticExecutors.add(diagnosticExecutor);
        DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                UNUSED_EVENTS_URI, null, NO_PERIODIC_FLUSH_MILLIS, 60_000, CLOSE_BUDGET_MILLIS,
                scheduler, diagnosticExecutor, store, DEFAULT_CAPACITY);
        try {
            Thread[] eventsThread = new Thread[1];
            CountDownLatch eventsThreadHeld = new CountDownLatch(1);
            scheduler.submit(() -> {
                eventsThread[0] = Thread.currentThread();
                eventsThreadHeld.countDown();
                awaitQuietly(releaseEventsThread, 5, TimeUnit.SECONDS);
            });
            assertTrue(eventsThreadHeld.await(5, TimeUnit.SECONDS));
            eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);

            closing.set(true);
            eventProcessor.close();

            assertFalse("close() returned without writing the event down",
                    committedDuringClose.isEmpty());
            assertFalse("the final commit waited on the events thread",
                    committedDuringClose.contains(eventsThread[0]));
            assertEquals(1, store.getPendingEventCount());
        } finally {
            releaseEventsThread.countDown();
            scheduler.shutdownNow();
        }
    }

    @Test
    public void eventsHeldDuringAnOutageGoOutWithTheNextPeriodicFlush() throws Exception {
        // Analytics go out through AnalyticsEventSender rather than the injectable one, so the
        // delivery has to be watched at the server rather than at the stub.
        try (HttpServer server = startEventsServer()) {
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                    server.getUri(), null, 50, 60_000, DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS,
                    scheduler);
            try {
                eventProcessor.setOffline(true);
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
                server.getRecorder().requireNoRequests(300, TimeUnit.MILLISECONDS);

                eventProcessor.setOffline(false);

                List<LDValue> events = collectDelivered(server);
                assertEquals(1, countEventsOfKind(events, "custom"));
            } finally {
                eventProcessor.close();
                scheduler.shutdownNow();
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
    public void debugEventIsNotSentOnceTheServiceClockIsPastItsDate() throws Exception {
        // The device clock can be behind, so a date the device still has ahead of it may be over already.
        String serviceDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(10));
        try (HttpServer server = HttpServer.start(Handlers.all(Handlers.header("Date", serviceDate),
                Handlers.status(202)))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "learns-the-service-clock", LDValue.ofNull(), null);
                flushAndCollect(eventProcessor, server);

                recordEvaluation(eventProcessor, false, System.currentTimeMillis() + 3_000);
                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(0, countEventsOfKind(events, "debug"));
                assertEquals(1, summaryCountFor(events, FLAG_KEY));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aTrackedEvaluationWhileDebuggingIsActiveSendsBothAFeatureAndADebugEvent() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                recordEvaluation(eventProcessor, true, System.currentTimeMillis() + 3_600_000);

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertEquals(1, countEventsOfKind(events, "feature"));
                assertEquals(1, countEventsOfKind(events, "debug"));
                assertEquals(1, summaryCountFor(events, FLAG_KEY));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void anEvaluationTheSummarizerTurnsAwayCountsAsDroppedAndStillKeepsItsFullEvent() throws Exception {
        try (HttpServer server = startEventsServer()) {
            DirectEventProcessor eventProcessor = (DirectEventProcessor) makeEventProcessor(server, 2);
            try {
                // Capacity bounds the number of contexts counted as well as the number of events held.
                for (String key : new String[] {"a", "b"}) {
                    eventProcessor.recordEvaluationEvent(LDContext.create(key), FLAG_KEY, FLAG_VERSION,
                            VARIATION, FLAG_VALUE, null, DEFAULT_VALUE, false, null);
                }
                assertEquals(0, eventProcessor.getAndClearDroppedCount());

                eventProcessor.recordEvaluationEvent(LDContext.create("c"), FLAG_KEY, FLAG_VERSION,
                        VARIATION, FLAG_VALUE, null, DEFAULT_VALUE, true, null);
                assertEquals(1, eventProcessor.getAndClearDroppedCount());

                List<LDValue> events = flushAndCollect(eventProcessor, server);
                assertEquals(LDValue.of("c"), requireEventOfKind(events, "feature").get("context").get("key"));
                assertEquals(2, summaryCountFor(events, FLAG_KEY));
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
    public void flushWithTimeoutReportsDeliveredEvents() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);

                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));

                assertEquals(1, countEventsOfKind(collectDelivered(server), "custom"));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aCommitNeverSplitsAnEvaluationAcrossTwoCommits() throws Exception {
        // The other half of the atomicity invariant. close() only ever commits once, so it can show
        // an evaluation being stranded but not one being split: a counter staged by commit N with its
        // feature event following in N+1. Payloads cannot show it here, because a delivery reads the
        // store and may cut or join commits for its own reasons, so this looks at what each commit
        // stages instead, while recording continues.
        //
        // Every evaluation is tracked and the capacity is far beyond what the run produces, so within
        // a commit the counter for the flag and the number of feature events are the same number.
        //
        // Persistence is off so that the store never commits on its own; every commit() is one of the
        // processor's. Where nothing persists only a delivery commits, so the processor is online and
        // each flush commits and delivers.
        //
        // Repeated because the window is narrow -- the two writes are adjacent, and a commit has to
        // land between them.
        int commitsWithCounters = 0;
        for (int trial = 0; trial < SPLIT_TRIALS; trial++) {
            List<List<LDValue>> commits = new ArrayList<>();
            EventStore store = new EventStore(eventsDirectory.newFolder(), NO_DROP_CAPACITY,
                    false, logging.logger) {
                private List<LDValue> staged = new ArrayList<>();

                @Override
                synchronized boolean stage(byte[] serializedEvent, boolean bypassingCapacity) {
                    staged.add(LDValue.parse(new String(serializedEvent, StandardCharsets.UTF_8)));
                    return super.stage(serializedEvent, bypassingCapacity);
                }

                @Override
                synchronized boolean stageReserved(byte[] serializedEvent) {
                    staged.add(LDValue.parse(new String(serializedEvent, StandardCharsets.UTF_8)));
                    return super.stageReserved(serializedEvent);
                }

                @Override
                synchronized void commit() {
                    commits.add(staged);
                    staged = new ArrayList<>();
                    super.commit();
                }
            };
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            HttpServer server = startEventsServer();
            DirectEventProcessor eventProcessor = makeEventProcessor(server, store, NO_DROP_CAPACITY,
                    scheduler);
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

                assertEquals("trial " + trial + ": events were dropped, so a commit may be short"
                                + " for that reason instead",
                        0, eventProcessor.getAndClearDroppedCount());
                int counted = 0;
                synchronized (store) {
                    for (List<LDValue> commit : commits) {
                        int counters = summaryCounters(commit, FLAG_KEY);
                        assertEquals("trial " + trial + ": a commit counted evaluations whose feature"
                                        + " events were staged separately",
                                countEventsOfKind(commit, "feature"), counters);
                        counted += counters;
                        if (counters > 0) {
                            commitsWithCounters++;
                        }
                    }
                }
                assertEquals("trial " + trial + ": some evaluations were never committed",
                        target, counted);
            } finally {
                eventProcessor.close();
                scheduler.shutdownNow();
                server.close();
            }
        }
        // Otherwise a single commit per trial would satisfy everything above without a commit ever
        // having overlapped a recording.
        assertTrue("every evaluation went out in one commit, so nothing was interleaved",
                commitsWithCounters > SPLIT_TRIALS);
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
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));

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
    public void aFailedCommitOnTheCallersThreadDoesNotReachTheCaller() throws Exception {
        // With IMMEDIATE the commit runs inside track, identify and flush, after record() has
        // returned, so it needs its own guard.
        EventStore store = new EventStore(eventsDirectory.newFolder(), DEFAULT_CAPACITY, true,
                logging.logger) {
            @Override
            void commit() {
                throw new IllegalStateException("simulated commit failure");
            }
        };
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
        diagnosticExecutors.add(diagnosticExecutor);
        DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                UNUSED_EVENTS_URI, null, NO_PERIODIC_FLUSH_MILLIS, 60_000, CLOSE_BUDGET_MILLIS,
                scheduler, diagnosticExecutor, store, DEFAULT_CAPACITY, true);
        try {
            eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
            eventProcessor.recordIdentifyEvent(CONTEXT);
            eventProcessor.flush();
            // Its own last-chance commit runs on the caller's thread when the events thread's fails.
            eventProcessor.close();

            logging.assertErrorLogged(
                    "Unexpected error in event processor: java.lang.IllegalStateException: simulated commit failure");
        } finally {
            scheduler.shutdownNow();
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
    public void closeGivesUpWaitingOnAStalledDiskWrite() throws Exception {
        // A commit stalled on the disk holds commitLock, and the store's I/O lock, for as long as the
        // write takes. close() used to fall back to committing on the caller's thread once its budget
        // ran out, which then waited on those locks with no bound at all.
        CountDownLatch writeStarted = new CountDownLatch(1);
        Semaphore letWriteFinish = new Semaphore(0);
        EventStore store = new EventStore(eventsDirectory.newFolder(), DEFAULT_CAPACITY, true,
                logging.logger) {
            @Override
            FileOutputStream openForAppending(File file) throws IOException {
                writeStarted.countDown();
                awaitQuietly(letWriteFinish);
                return super.openForAppending(file);
            }
        };
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
        diagnosticExecutors.add(diagnosticExecutor);
        DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                UNUSED_EVENTS_URI, null, NO_PERIODIC_FLUSH_MILLIS, 60_000, CLOSE_BUDGET_MILLIS,
                scheduler, diagnosticExecutor, store, DEFAULT_CAPACITY, false);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            // A commit point, so a commit is queued and stalls on the disk, holding the locks.
            eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
            assertTrue("the commit never reached the disk", writeStarted.await(5, TimeUnit.SECONDS));

            long startedAtNanos = System.nanoTime();
            // On a thread of its own, so that a close() that does hang fails this test rather than
            // hanging the suite.
            Future<?> closing = caller.submit(() -> {
                eventProcessor.close();
                return null;
            });
            closing.get(CLOSE_BUDGET_MILLIS * 5, TimeUnit.MILLISECONDS);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

            assertTrue("close() waited " + elapsedMillis + "ms on a budget of "
                    + CLOSE_BUDGET_MILLIS + "ms", elapsedMillis < CLOSE_BUDGET_MILLIS * 3);
            logging.assertWarnLogged("Gave up waiting for the final event commit");
        } finally {
            letWriteFinish.release(Integer.MAX_VALUE);
            caller.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    private static void awaitQuietly(Semaphore semaphore) {
        try {
            if (semaphore.tryAcquire(30, TimeUnit.SECONDS)) {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));

                server.getRecorder().requireNoRequests(100, TimeUnit.MILLISECONDS);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void flushesArrivingWhileADeliveryRunsShareOneFollowUpDelivery() throws Exception {
        // Otherwise a flush called faster than a post completes queues a post per call, and the
        // flush that matters -- the one at shutdown, with a deadline -- waits behind all of them.
        Semaphore letFirstResponseFinish = new Semaphore(0);
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(Handlers.waitFor(letFirstResponseFinish), Handlers.status(202)),
                Handlers.status(202)))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "first", LDValue.ofNull(), null);
                Future<Boolean> first = eventProcessor.flushAsync();
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);

                // The delivery thread is inside that post, so none of these can start, and each of
                // them has to be answered by the one delivery that is queued behind it.
                eventProcessor.recordCustomEvent(CONTEXT, "second", LDValue.ofNull(), null);
                Future<Boolean> queued = eventProcessor.flushAsync();
                for (int i = 0; i < 50; i++) {
                    assertSame(queued, eventProcessor.flushAsync());
                }

                letFirstResponseFinish.release(1);
                assertTrue(first.get(5, TimeUnit.SECONDS));
                assertTrue(queued.get(5, TimeUnit.SECONDS));

                // One post for the running delivery and one for the 51 that joined, and no more.
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                server.getRecorder().requireNoRequests(100, TimeUnit.MILLISECONDS);
            } finally {
                letFirstResponseFinish.release(Integer.MAX_VALUE);
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aFlushJoiningADeliveryStillCoversWhatTheCallerRecorded() throws Exception {
        // Joining is only sound while the delivery it joins has not taken the buffer yet, so what
        // the joining caller recorded has to come back in that delivery's payload.
        Semaphore letFirstResponseFinish = new Semaphore(0);
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(Handlers.waitFor(letFirstResponseFinish), Handlers.status(202)),
                Handlers.status(202)))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "first", LDValue.ofNull(), null);
                Future<Boolean> first = eventProcessor.flushAsync();
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);

                eventProcessor.recordCustomEvent(CONTEXT, "joined", LDValue.ofNull(), null);
                Future<Boolean> queued = eventProcessor.flushAsync();

                letFirstResponseFinish.release(1);
                assertTrue(first.get(5, TimeUnit.SECONDS));
                assertTrue(queued.get(5, TimeUnit.SECONDS));

                RequestInfo second = server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                assertTrue("the joining caller's event was left behind",
                        second.getBody().contains("\"key\":\"joined\""));
            } finally {
                letFirstResponseFinish.release(Integer.MAX_VALUE);
                eventProcessor.close();
            }
        }
    }

    @Test
    public void theStoreIsHeldToItsCapacityThroughFlushesThatEachFindADeliveryInFlight() throws Exception {
        // A batch whose request is out stops counting against capacity. With a request per flush, a run of
        // flushes each held up by a slow response would take the store a batch past capacity per flush.
        Semaphore letFirstResponseFinish = new Semaphore(0);
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(Handlers.waitFor(letFirstResponseFinish), Handlers.status(202)),
                Handlers.status(202), Handlers.status(202)))) {
            EventProcessor eventProcessor = makeEventProcessor(server, 2);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "a-0", LDValue.ofNull(), null);
                eventProcessor.recordCustomEvent(CONTEXT, "a-1", LDValue.ofNull(), null);
                eventProcessor.flush();
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);

                for (int round = 0; round < 3; round++) {
                    eventProcessor.recordCustomEvent(CONTEXT, "b" + round + "-0", LDValue.ofNull(), null);
                    eventProcessor.recordCustomEvent(CONTEXT, "b" + round + "-1", LDValue.ofNull(), null);
                    eventProcessor.flush();
                }
                server.getRecorder().requireNoRequests(300, TimeUnit.MILLISECONDS);

                letFirstResponseFinish.release();
                eventProcessor.blockingFlush();

                // The batch out made room for one store's worth, and nothing past it was taken.
                List<String> keys = new ArrayList<>();
                for (LDValue event : collectDelivered(server)) {
                    keys.add(event.get("key").stringValue());
                }
                assertEquals(Arrays.asList("b0-0", "b0-1"), keys);
            } finally {
                letFirstResponseFinish.release(Integer.MAX_VALUE);
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aFlushIsAnsweredByTheFailureOfADeliveryHoldingTheBatchItWouldHaveSent() throws Exception {
        // Everything stored belongs to the delivery already running, and that may include this caller's
        // events, so this caller is answered by that delivery rather than told its events arrived.
        Semaphore letFirstResponseFinish = new Semaphore(0);
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(Handlers.waitFor(letFirstResponseFinish), Handlers.status(503)),
                Handlers.status(503), Handlers.status(503), Handlers.status(503)))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "refused", LDValue.ofNull(), null);
                Future<Boolean> first = eventProcessor.flushAsync();
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);

                Future<Boolean> second = eventProcessor.flushAsync();
                Thread.sleep(300);
                assertFalse(second.isDone());

                letFirstResponseFinish.release();
                // Allows for the one-second retry, after which the batch is still stored and undelivered.
                assertFalse(first.get(10, TimeUnit.SECONDS));
                assertFalse(second.get(10, TimeUnit.SECONDS));
            } finally {
                letFirstResponseFinish.release(Integer.MAX_VALUE);
                eventProcessor.close();
            }
        }
    }

    @Test
    public void anEvaluatingThreadIsNotHeldUpWhileADeliveryListsTheStore() throws Exception {
        // Standing in for the listing a delivery makes on a device whose disk is busy, which on the first one
        // after launch reads every batch a previous run left behind.
        AtomicBoolean slow = new AtomicBoolean();
        CountDownLatch listing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EventStore store = new EventStore(new File(eventsDirectory.getRoot(), "events"), DEFAULT_CAPACITY,
                persistence() != EventPersistence.DISABLED, logging.logger) {
            @Override
            Listing listPendingBatches() {
                if (slow.get()) {
                    listing.countDown();
                    awaitQuietly(release, 10, TimeUnit.SECONDS);
                }
                return super.listPendingBatches();
            }
        };
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
        diagnosticExecutors.add(diagnosticExecutor);
        try (HttpServer server = startEventsServer()) {
            DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(), server.getUri(),
                    null, NO_PERIODIC_FLUSH_MILLIS, 60_000, DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS,
                    scheduler, diagnosticExecutor, store, DEFAULT_CAPACITY);
            try {
                eventProcessor.setOffline(false);
                slow.set(true);
                eventProcessor.recordCustomEvent(CONTEXT, "tracked", LDValue.ofNull(), null);
                eventProcessor.flush();
                assertTrue("the delivery never listed the store", listing.await(5, TimeUnit.SECONDS));

                CountDownLatch answered = new CountDownLatch(1);
                new Thread(() -> {
                    eventProcessor.recordEvaluationEvent(CONTEXT, FLAG_KEY, FLAG_VERSION, VARIATION, FLAG_VALUE,
                            null, DEFAULT_VALUE, true, System.currentTimeMillis() + 3_600_000);
                    eventProcessor.recordCustomEvent(CONTEXT, "meanwhile", LDValue.ofNull(), null);
                    answered.countDown();
                }).start();
                assertTrue("the evaluation waited on the listing", answered.await(2, TimeUnit.SECONDS));

                release.countDown();
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                eventProcessor.close();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    public void aDeliveredBatchIsOutOfTheStoreBeforeTheFlushIsAnsweredEvenWhileACommitIsStuckWriting()
            throws Exception {
        assumePersisting();
        // A slow disk, holding the store's I/O, which removing a delivered batch also needs.
        AtomicBoolean blockNextWrite = new AtomicBoolean();
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch finishWrite = new CountDownLatch(1);
        EventStore store = new EventStore(new File(eventsDirectory.getRoot(), "events"), DEFAULT_CAPACITY,
                true, logging.logger) {
            @Override
            FileOutputStream openForAppending(File file) throws IOException {
                if (blockNextWrite.getAndSet(false)) {
                    writeStarted.countDown();
                    awaitQuietly(finishWrite, 10, TimeUnit.SECONDS);
                }
                return super.openForAppending(file);
            }
        };
        Semaphore letFirstResponseFinish = new Semaphore(0);
        ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
        ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
        diagnosticExecutors.add(diagnosticExecutor);
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(Handlers.waitFor(letFirstResponseFinish), Handlers.status(202)),
                Handlers.status(202)))) {
            DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(), server.getUri(),
                    null, NO_PERIODIC_FLUSH_MILLIS, 60_000, DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS,
                    scheduler, diagnosticExecutor, store, DEFAULT_CAPACITY);
            try {
                eventProcessor.setOffline(false);
                eventProcessor.recordCustomEvent(CONTEXT, "delivered", LDValue.ofNull(), null);
                Future<Boolean> flush = eventProcessor.flushAsync();
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);

                blockNextWrite.set(true);
                new Thread(() -> eventProcessor.recordCustomEvent(CONTEXT, "written", LDValue.ofNull(), null))
                        .start();
                assertTrue(writeStarted.await(2, TimeUnit.SECONDS));
                letFirstResponseFinish.release();

                try {
                    Boolean delivered = flush.get(500, TimeUnit.MILLISECONDS);
                    // Only the event still being written. The delivered one was gone before anyone heard.
                    int pendingWhenTold = store.getPendingEventCount();
                    assertTrue(delivered);
                    assertEquals(1, pendingWhenTold);
                } catch (TimeoutException waitingOnTheWrite) {
                    // Answered only once the removal gets the disk, which is as good.
                }
                finishWrite.countDown();
                assertTrue(flush.get(5, TimeUnit.SECONDS));
                assertEquals(1, store.getPendingEventCount());
            } finally {
                letFirstResponseFinish.release(Integer.MAX_VALUE);
                finishWrite.countDown();
                eventProcessor.close();
                scheduler.shutdownNow();
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
                Future<Boolean> delivery = eventProcessor.flushAsync();

                try {
                    delivery.get(100, TimeUnit.MILLISECONDS);
                    fail("the delivery finished while its response was still being held");
                } catch (TimeoutException expected) {
                    // The caller gives up here, as flushAndWait does when its budget runs out.
                }

                // Left running rather than canceled, the delivery still gets its events through
                // once the service answers, and a canceled one could not report that.
                letResponseFinish.release(1);
                assertTrue(delivery.get(5, TimeUnit.SECONDS));
                server.getRecorder().requireRequest(1, TimeUnit.SECONDS);
            } finally {
                // Released before closing, so that the delivery still in flight can finish rather
                // than hold up the shutdown that close() waits on.
                letResponseFinish.drainPermits();
                letResponseFinish.release(Integer.MAX_VALUE);
                eventProcessor.close();
            }
        }
    }

    @Test
    public void flushReportsFailureWhenTheServiceRefusesTheEventsForNow() throws Exception {
        // 503 is a failure that may pass: the processor retries it a second later, and a caller waiting on
        // the flush is answered by that retry. The batch is kept after it; see the tests below.
        try (HttpServer server = HttpServer.start(Handlers.status(503))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);

                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));

                server.getRecorder().requireRequest(1, TimeUnit.SECONDS);
                server.getRecorder().requireRequest(1, TimeUnit.SECONDS);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void flushAndWaitDoesNotReportDeliveryForEventsAnEarlierFailedFlushDrained() throws Exception {
        // Every request fails recoverably (503): each delivery attempts, retries once, and keeps the batch.
        try (HttpServer server = HttpServer.start(Handlers.status(503))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "kept-event", LDValue.ofNull(), null);

                // Stands in for the periodic flush or the SDK's flush-on-background: it closes the
                // buffer into a batch and its delivery fails. The batch stays in the store.
                eventProcessor.blockingFlush();
                server.getRecorder().requireRequest(10, TimeUnit.SECONDS);

                // The buffer is empty, but the event is still waiting in its batch, not delivered.
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aBatchAnUnheardDeliveryCouldNotSendGoesOutOnceTheServiceRecovers() throws Exception {
        // Answers set up front: nothing waits for the retry's answer, so switching once the retry is seen
        // could still answer it with the recovery.
        Handler service = Handlers.sequential(Handlers.status(503), Handlers.status(503),
                Handlers.status(202), Handlers.status(202));
        try (HttpServer server = HttpServer.start(service)) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    eventsBuilderWithoutBackoff(DEFAULT_CAPACITY), true);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "kept-event", LDValue.ofNull(), null);
                eventProcessor.blockingFlush();
                // The attempt and its one retry: the service only recovers once both have failed.
                String payloadId = server.getRecorder().requireRequest(10, TimeUnit.SECONDS)
                        .getHeader(PAYLOAD_ID_HEADER);
                server.getRecorder().requireRequest(10, TimeUnit.SECONDS);

                // Nobody heard the failure, and the next flush still sends what it refused.
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                RequestInfo resent = server.getRecorder().requireRequest(10, TimeUnit.SECONDS);
                assertTrue(resent.getBody().contains("\"key\":\"kept-event\""));
                assertEquals("the same delivery, so the service can discard a repeat",
                        payloadId, resent.getHeader(PAYLOAD_ID_HEADER));

                // Delivered once: the next flush has only its own events to send.
                eventProcessor.recordCustomEvent(CONTEXT, "later-event", LDValue.ofNull(), null);
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                RequestInfo later = server.getRecorder().requireRequest(10, TimeUnit.SECONDS);
                assertTrue(later.getBody().contains("\"key\":\"later-event\""));
                assertFalse(later.getBody().contains("kept-event"));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aBatchIsKeptThroughRepeatedRefusalsAndDeliveredOnceTheServiceRecovers() throws Exception {
        assertKeptThroughRefusalsAndDeliveredOnRecovery(503, eventsBuilderWithoutBackoff(DEFAULT_CAPACITY), 3);
    }

    @Test
    public void aBatchHeldInMemoryIsKeptThroughRepeatedRefusalsWherePersistenceIsOff() throws Exception {
        // Without a disk the store holds batches in memory, and a refusal must not cost them either.
        assertKeptThroughRefusalsAndDeliveredOnRecovery(503,
                eventsBuilderWithoutBackoff(DEFAULT_CAPACITY).eventPersistence(EventPersistence.DISABLED), 3);
    }

    @Test
    public void flushesDuringAnOutageCloseNoBatchesBehindTheOneBlocked() throws Exception {
        // A batch per flush would grow without bound for as long as an outage lasts: files on the disk,
        // or batches held in memory, each with a summary of its own.
        HandlerSwitcher service = new HandlerSwitcher(Handlers.status(503));
        try (HttpServer server = HttpServer.start(service)) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    eventsBuilderWithoutBackoff(DEFAULT_CAPACITY), true);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "blocked", LDValue.ofNull(), null);
                for (int flush = 0; flush < 3; flush++) {
                    assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                    // The attempt and its retry, both of the batch at the head.
                    for (int attempt = 0; attempt < 2; attempt++) {
                        RequestInfo refused = server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                        assertTrue(refused.getBody().contains("\"key\":\"blocked\""));
                    }
                    eventProcessor.recordCustomEvent(CONTEXT, "during-" + flush, LDValue.ofNull(), null);
                }
                service.setTarget(Handlers.status(202));

                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                RequestInfo head = server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                assertTrue(head.getBody().contains("\"key\":\"blocked\""));
                RequestInfo since = server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                for (int flush = 0; flush < 3; flush++) {
                    assertTrue("during-" + flush, since.getBody().contains("\"key\":\"during-" + flush + "\""));
                }
                server.getRecorder().requireNoRequests(200, TimeUnit.MILLISECONDS);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aFlushFailsWhileTheStoredEventsCannotBeListed() throws Exception {
        // A directory that cannot be listed is not an empty one: the batch the flush closes is on the disk.
        assumePersisting();
        try (HttpServer server = HttpServer.start(Handlers.status(202))) {
            EventProcessor eventProcessor = makeEventProcessor(server, eventsBuilder(DEFAULT_CAPACITY), true);
            File directory = EventDirectory.defaultDirectory(eventsDirectory.getRoot(), MOBILE_KEY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "first", LDValue.ofNull(), null);
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                assertTrue(directory.isDirectory());

                eventProcessor.recordCustomEvent(CONTEXT, "unlisted", LDValue.ofNull(), null);
                assertTrue(directory.setReadable(false));
                try {
                    assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                    server.getRecorder().requireNoRequests(200, TimeUnit.MILLISECONDS);
                } finally {
                    assertTrue(directory.setReadable(true));
                }

                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                RequestInfo request = server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                assertTrue(request.getBody().contains("\"key\":\"unlisted\""));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aFlushFailsWhileTheEventLogWillNotClose() throws Exception {
        // The events are still in the log, for the next close to move, and this flush did not send them.
        File directory = eventsDirectory.newFolder();
        AtomicBoolean closesFail = new AtomicBoolean(true);
        EventStore store = new EventStore(directory, DEFAULT_CAPACITY, true, logging.logger) {
            @Override
            List<Batch> closeBatch() {
                if (!closesFail.get()) {
                    return super.closeBatch();
                }
                // Read-only only for the close, so the log is written and then cannot be renamed.
                assertTrue(directory.setWritable(false));
                try {
                    return super.closeBatch();
                } finally {
                    assertTrue(directory.setWritable(true));
                }
            }
        };
        try (HttpServer server = HttpServer.start(Handlers.status(202))) {
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
            diagnosticExecutors.add(diagnosticExecutor);
            DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                    server.getUri(), null, NO_PERIODIC_FLUSH_MILLIS, 60_000, CLOSE_BUDGET_MILLIS,
                    scheduler, diagnosticExecutor, store, DEFAULT_CAPACITY);
            try {
                eventProcessor.setOffline(false);
                eventProcessor.recordCustomEvent(CONTEXT, "unclosed", LDValue.ofNull(), null);

                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                server.getRecorder().requireNoRequests(200, TimeUnit.MILLISECONDS);
                assertTrue(store.holdsUnclosedEvents());

                closesFail.set(false);
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                RequestInfo request = server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                assertTrue(request.getBody().contains("\"key\":\"unclosed\""));
            } finally {
                eventProcessor.close();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    public void theSummariesAnOutageWritesLeaveRoomForEvents() throws Exception {
        // Every flush of an outage is a commit point, and each writes a summary of the evaluations since
        // the last. Counted as events, they would leave a long outage refusing every track.
        HandlerSwitcher service = new HandlerSwitcher(Handlers.status(503));
        try (HttpServer server = HttpServer.start(service)) {
            EventProcessor eventProcessor = makeEventProcessor(server, eventsBuilderWithoutBackoff(2), true);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "blocked", LDValue.ofNull(), null);
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                for (int flush = 0; flush < 3; flush++) {
                    eventProcessor.recordEvaluationEvent(CONTEXT, FLAG_KEY, FLAG_VERSION, VARIATION,
                            FLAG_VALUE, EvaluationReason.off(), DEFAULT_VALUE, false, null);
                    assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                }

                eventProcessor.recordCustomEvent(CONTEXT, "after-the-summaries", LDValue.ofNull(), null);
                service.setTarget(Handlers.status(202));
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                boolean sent = false;
                // Past the requests the outage refused, two for each flush.
                for (int request = 0; request < 10 && !sent; request++) {
                    sent = server.getRecorder().requireRequest(5, TimeUnit.SECONDS).getBody()
                            .contains("\"key\":\"after-the-summaries\"");
                }
                assertTrue("refused for room the summaries took", sent);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void everyFailingStatusKeepsTheBatchPastTheRetry() throws Exception {
        // Each has to keep the batch, not just 503: a refusal such as a 401 only waits longer. Not a 400,
        // which drops the batch when it comes twice in a row; see the tests below.
        for (int status : new int[] { 401, 403, 404, 408, 413, 429, 500, 502, 504 }) {
            assertKeptThroughRefusalsAndDeliveredOnRecovery(status, eventsBuilderWithoutBackoff(DEFAULT_CAPACITY),
                    2);
        }
    }

    @Test
    public void eventsRecordedDuringAnOutageAreDeliveredWithTheBatchesRefusedBeforeThem() throws Exception {
        HandlerSwitcher service = new HandlerSwitcher(Handlers.status(503));
        try (HttpServer server = HttpServer.start(service)) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    eventsBuilderWithoutBackoff(DEFAULT_CAPACITY), true);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "first-event", LDValue.ofNull(), null);
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                eventProcessor.recordCustomEvent(CONTEXT, "second-event", LDValue.ofNull(), null);
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                // Two flushes, each an attempt and a retry, all at the first batch: delivery stops at
                // the oldest batch that fails in a way that may pass.
                for (int i = 0; i < 4; i++) {
                    assertTrue(server.getRecorder().requireRequest(5, TimeUnit.SECONDS).getBody()
                            .contains("\"key\":\"first-event\""));
                }
                service.setTarget(Handlers.status(202));

                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));

                // Oldest first, each exactly once.
                RequestInfo first = server.getRecorder().requireRequest(10, TimeUnit.SECONDS);
                RequestInfo second = server.getRecorder().requireRequest(10, TimeUnit.SECONDS);
                server.getRecorder().requireNoRequests(200, TimeUnit.MILLISECONDS);
                assertTrue(first.getBody().contains("\"key\":\"first-event\""));
                assertFalse(first.getBody().contains("second-event"));
                assertTrue(second.getBody().contains("\"key\":\"second-event\""));
                assertFalse(second.getBody().contains("first-event"));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aBatchStillRefusedAtCloseIsDeliveredByTheNextRun() throws Exception {
        assumePersisting();
        String payloadId;
        try (HttpServer refusing = HttpServer.start(Handlers.status(503))) {
            EventProcessor eventProcessor = makeEventProcessor(refusing, DEFAULT_CAPACITY);
            eventProcessor.recordCustomEvent(CONTEXT, "kept-event", LDValue.ofNull(), null);
            assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
            payloadId = refusing.getRecorder().requireRequest(1, TimeUnit.SECONDS)
                    .getHeader(PAYLOAD_ID_HEADER);
            eventProcessor.close();
        }

        // The same directory, as the application's next launch would find it.
        try (HttpServer server = startEventsServer()) {
            EventProcessor nextRun = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                assertTrue(awaitFlush(nextRun, 10, TimeUnit.SECONDS));
                RequestInfo resent = server.getRecorder().requireRequest(10, TimeUnit.SECONDS);
                assertTrue(resent.getBody().contains("\"key\":\"kept-event\""));
                assertEquals(payloadId, resent.getHeader(PAYLOAD_ID_HEADER));
            } finally {
                nextRun.close();
            }
        }
    }

    @Test
    public void aBatchRefusedWith400TwiceInARowIsDroppedAndNoLongerHoldsTheOnesBehindIt() throws Exception {
        HandlerSwitcher service = new HandlerSwitcher(Handlers.status(400));
        try (HttpServer server = HttpServer.start(service)) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    eventsBuilderWithoutBackoff(DEFAULT_CAPACITY), true);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "malformed", LDValue.ofNull(), null);
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                String payloadId = server.getRecorder().requireRequest(1, TimeUnit.SECONDS)
                        .getHeader(PAYLOAD_ID_HEADER);
                assertEquals(payloadId, server.getRecorder().requireRequest(1, TimeUnit.SECONDS)
                        .getHeader(PAYLOAD_ID_HEADER));
                server.getRecorder().requireNoRequests(300, TimeUnit.MILLISECONDS);
                service.setTarget(Handlers.status(202));

                // What was recorded since goes out alone, and that flush is the first a caller waits on
                // since the loss, so it hears about it.
                eventProcessor.recordCustomEvent(CONTEXT, "next", LDValue.ofNull(), null);
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                RequestInfo next = server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                assertTrue(next.getBody().contains("\"key\":\"next\""));
                assertFalse(next.getBody().contains("malformed"));
                server.getRecorder().requireNoRequests(300, TimeUnit.MILLISECONDS);
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aBatchIsDroppedOnlyForTwo400sInARow() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.sequential(Handlers.status(400),
                Handlers.status(503), Handlers.status(400), Handlers.status(202)))) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    eventsBuilderWithoutBackoff(DEFAULT_CAPACITY), true);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "kept", LDValue.ofNull(), null);
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                String payloadId = null;
                for (int post = 0; post < 4; post++) {
                    RequestInfo request = server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                    assertTrue(request.getBody().contains("\"key\":\"kept\""));
                    if (payloadId == null) {
                        payloadId = request.getHeader(PAYLOAD_ID_HEADER);
                    }
                    assertEquals(payloadId, request.getHeader(PAYLOAD_ID_HEADER));
                }
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aBatchRefusedWith503IsPostedOnlyAsTheBackoffAllows() throws Exception {
        assertPostedOnlyAsTheBackoffAllows(503);
    }

    @Test
    public void aBatchRefusedWith429IsPostedOnlyAsTheBackoffAllows() throws Exception {
        assertPostedOnlyAsTheBackoffAllows(429);
    }

    @Test
    public void aDeliveryAskedForWhileARefusedPostWaitsToRetryStartsOnceThatRetryIsAnswered() throws Exception {
        try (HttpServer server = HttpServer.start(Handlers.status(503))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "kept", LDValue.ofNull(), null);
                Future<Boolean> first = eventProcessor.flushAsync();
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);

                // Well inside the one-second retry delay. The retry belongs to the delivery still running, so
                // this one waits for it rather than posting beside it.
                Future<Boolean> second = eventProcessor.flushAsync();
                Thread.sleep(300);
                assertFalse("answered before the retry it waits on", second.isDone());

                // One retry and no more: the delivery behind it finds the backoff still waiting.
                server.getRecorder().requireRequest(3, TimeUnit.SECONDS);
                assertFalse(first.get(5, TimeUnit.SECONDS));
                assertFalse(second.get(5, TimeUnit.SECONDS));
                server.getRecorder().requireNoRequests(1_500, TimeUnit.MILLISECONDS);
            } finally {
                eventProcessor.close();
            }
        }
    }

    /**
     * Kept is not the same as resent on its own: a batch the service always refuses is posted a second
     * after the first failure and then twice as long after each one, and a flush in between posts nothing,
     * or it would be posted in a loop.
     */
    private void assertPostedOnlyAsTheBackoffAllows(int status) throws Exception {
        long second = DirectEventProcessor.RETRY_DELAY_MILLIS;
        try (HttpServer server = HttpServer.start(Handlers.status(status))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "refused", LDValue.ofNull(), null);
                // The attempt and the retry a second later, which the flush waits for.
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                server.getRecorder().requireRequest(1, TimeUnit.SECONDS);
                server.getRecorder().requireRequest(1, TimeUnit.SECONDS);

                // Two seconds now, which a flush neither posts in nor waits out.
                long start = System.nanoTime();
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                assertTrue("the flush waited out the backoff",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < second);
                server.getRecorder().requireNoRequests(second, TimeUnit.MILLISECONDS);

                // The retry that flush queued, once the two seconds are up, and then four seconds of nothing.
                server.getRecorder().requireRequest(3 * second, TimeUnit.MILLISECONDS);
                server.getRecorder().requireNoRequests(3 * second, TimeUnit.MILLISECONDS);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aRefusedBatchIsKeptAndPostedAgainOnceTheLongerWaitIsOver() throws Exception {
        // A 401 for a valid key happens during an incident and passes in minutes, so the batch is kept and
        // the SDK waits longer before trying again, rather than deleting it and stopping. Cut to a second
        // and a half here from the eight and a half minutes the SDK waits.
        long refusalWaitMillis = 1_500;
        long second = DirectEventProcessor.RETRY_DELAY_MILLIS;
        // One answer per post, set up front: the server records a request before it answers it, so
        // switching once the retry's posts are seen could change the answers they get.
        Handler service = Handlers.sequential(
                Handlers.status(401),
                Handlers.status(202), Handlers.status(202),
                Handlers.status(503), Handlers.status(202));
        try (HttpServer server = HttpServer.start(service)) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    ((ComponentsImpl.EventProcessorBuilderImpl) eventsBuilder(DEFAULT_CAPACITY))
                            .retryDelaysForTesting(second, second, refusalWaitMillis), true);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "refused", LDValue.ofNull(), null);
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                String payloadId = server.getRecorder().requireRequest(1, TimeUnit.SECONDS)
                        .getHeader(PAYLOAD_ID_HEADER);

                // Recording goes on, and a flush during the wait says it failed without posting.
                eventProcessor.recordCustomEvent(CONTEXT, "recorded-meanwhile", LDValue.ofNull(), null);
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                server.getRecorder().requireNoRequests(refusalWaitMillis / 2, TimeUnit.MILLISECONDS);

                // The retry the refusal queued: the refused batch under the ID it was refused under, then what
                // was recorded since.
                RequestInfo resent = server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                assertTrue(resent.getBody().contains("\"key\":\"refused\""));
                assertEquals(payloadId, resent.getHeader(PAYLOAD_ID_HEADER));
                RequestInfo since = server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                assertTrue(since.getBody().contains("\"key\":\"recorded-meanwhile\""));

                // One accepted delivery puts the backoff back: the next failure waits only the second a flush
                // sits out, so the flush hears that its retry worked.
                eventProcessor.recordCustomEvent(CONTEXT, "after", LDValue.ofNull(), null);
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aRetryAfterSetsTheWaitBeforeTheNextPost() throws Exception {
        // Four seconds, jittered down to no less than two, which is past the second a flush waits out.
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(Handlers.header("Retry-After", "4"), Handlers.status(503)),
                Handlers.status(202)))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                server.getRecorder().requireRequest(1, TimeUnit.SECONDS);
                server.getRecorder().requireNoRequests(1_800, TimeUnit.MILLISECONDS);
                assertTrue(server.getRecorder().requireRequest(5, TimeUnit.SECONDS).getBody()
                        .contains("\"key\":\"an-event\""));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void flushesDuringARefusedDeliveryShareOneDeliveryAndOneRetry() throws Exception {
        Semaphore letFirstResponseFinish = new Semaphore(0);
        try (HttpServer server = HttpServer.start(Handlers.all(Handlers.waitFor(letFirstResponseFinish),
                Handlers.status(503)))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "refused", LDValue.ofNull(), null);
                List<Future<Boolean>> flushes = new ArrayList<>();
                flushes.add(eventProcessor.flushAsync());
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);

                // Held on the first post, every one of these joins the single delivery queued behind it.
                for (int i = 0; i < 20; i++) {
                    flushes.add(eventProcessor.flushAsync());
                }
                letFirstResponseFinish.release(100);

                for (Future<Boolean> flush : flushes) {
                    assertFalse(flush.get(10, TimeUnit.SECONDS));
                }
                // The second delivery runs inside the wait the first failure started and posts nothing; the
                // one retry both share does.
                server.getRecorder().requireRequest(2, TimeUnit.SECONDS);
                server.getRecorder().requireNoRequests(DirectEventProcessor.RETRY_DELAY_MILLIS * 3,
                        TimeUnit.MILLISECONDS);
            } finally {
                letFirstResponseFinish.release(100);
                eventProcessor.close();
            }
        }
    }

    @Test
    public void thePeriodicFlushPostsARefusedBatchNoMoreOftenThanTheBackoffAllows() throws Exception {
        int intervalMillis = 200;
        long windowMillis = 2_000;
        try (HttpServer server = HttpServer.start(Handlers.status(503))) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    eventsBuilder(DEFAULT_CAPACITY).flushIntervalMillis(intervalMillis), true);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "refused", LDValue.ofNull(), null);
                Thread.sleep(windowMillis);

                // At most one post a second, the backoff's ceiling where the interval is shorter; a loop
                // would be in the hundreds by now.
                int posts = server.getRecorder().count();
                long most = windowMillis / DirectEventProcessor.RETRY_DELAY_MILLIS + 1;
                assertTrue(posts + " posts in " + windowMillis + "ms", posts >= 2 && posts <= most);

                // Offline stops it entirely, kept batch or not.
                eventProcessor.setOffline(true);
                Thread.sleep(DirectEventProcessor.RETRY_DELAY_MILLIS + intervalMillis);
                int whenOffline = server.getRecorder().count();
                Thread.sleep(intervalMillis * 3);
                assertEquals(whenOffline, server.getRecorder().count());
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aBatchAPreviousRunLeftIsTriedOnceAtStartAndThenWaitsWhileStillRefused() throws Exception {
        assumePersisting();
        try (HttpServer refusing = HttpServer.start(Handlers.status(503))) {
            EventProcessor eventProcessor = makeEventProcessor(refusing, DEFAULT_CAPACITY);
            eventProcessor.recordCustomEvent(CONTEXT, "kept-event", LDValue.ofNull(), null);
            eventProcessor.close();
        }

        try (HttpServer server = HttpServer.start(Handlers.status(503))) {
            EventProcessor nextRun = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                // The delivery at start and its retry, and nothing after them until a flush asks.
                assertTrue(server.getRecorder().requireRequest(5, TimeUnit.SECONDS).getBody()
                        .contains("\"key\":\"kept-event\""));
                server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                server.getRecorder().requireNoRequests(DirectEventProcessor.RETRY_DELAY_MILLIS * 3,
                        TimeUnit.MILLISECONDS);
            } finally {
                nextRun.close();
            }
        }
    }

    /**
     * Refuses a batch with {@code status} for several flushes in a row, each of which attempts it and
     * retries it once, then lets the service recover and checks that the batch arrives exactly once,
     * under the payload ID it was first sent with.
     */
    private void assertKeptThroughRefusalsAndDeliveredOnRecovery(int status, EventProcessorBuilder events,
                                                                 int refusedFlushes) throws Exception {
        HandlerSwitcher service = new HandlerSwitcher(Handlers.status(status));
        try (HttpServer server = HttpServer.start(service)) {
            EventProcessor eventProcessor = makeEventProcessor(server, events, true);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "kept-event", LDValue.ofNull(), null);
                String payloadId = null;
                for (int flush = 0; flush < refusedFlushes; flush++) {
                    assertFalse("HTTP " + status + ", flush " + flush,
                            awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                    for (int attempt = 0; attempt < 2; attempt++) {
                        RequestInfo refused = server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                        assertTrue("HTTP " + status + ": the batch was dropped before flush " + flush,
                                refused.getBody().contains("\"key\":\"kept-event\""));
                        if (payloadId == null) {
                            payloadId = refused.getHeader(PAYLOAD_ID_HEADER);
                        }
                        assertEquals(payloadId, refused.getHeader(PAYLOAD_ID_HEADER));
                    }
                }
                service.setTarget(Handlers.status(202));

                assertTrue("HTTP " + status + ": not delivered once the service recovered",
                        awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                RequestInfo delivered = server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
                assertTrue(delivered.getBody().contains("\"key\":\"kept-event\""));
                assertEquals(payloadId, delivered.getHeader(PAYLOAD_ID_HEADER));

                // Gone once accepted: nothing is left to resend.
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                server.getRecorder().requireNoRequests(200, TimeUnit.MILLISECONDS);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aFlushIsNotToldEventsArrivedThatCouldNotBeSerialized() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "poison", LDValue.ofNull(), Double.NaN);

                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));

                server.getRecorder().requireNoRequests(100, TimeUnit.MILLISECONDS);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aFlushIsNotToldEventsArrivedWhenSomeOfThemCouldNotBeSerialized() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "poison", LDValue.ofNull(), Double.NaN);
                eventProcessor.recordCustomEvent(CONTEXT, "fine", LDValue.ofNull(), 1.0);

                // The post succeeds, and still not everything the caller recorded is in it.
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));

                List<LDValue> events = collectDelivered(server);
                assertEquals(LDValue.of("fine"), requireEventOfKind(events, "custom").get("key"));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aFlushIsNotToldEvaluationsArrivedWhoseSummaryCouldNotBeSerialized() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                // A null flag key, as a Java caller can pass, which Gson cannot write as a member name.
                eventProcessor.recordEvaluationEvent(CONTEXT, null, FLAG_VERSION, VARIATION,
                        FLAG_VALUE, null, DEFAULT_VALUE, false, null);
                recordEvaluation(eventProcessor, false, null);
                eventProcessor.recordIdentifyEvent(CONTEXT);

                // The summary is dropped whole, the valid flag's counter with it, and the flush says so.
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));

                List<LDValue> events = collectDelivered(server);
                assertEquals(1, countEventsOfKind(events, "identify"));
                assertEquals(0, countEventsOfKind(events, "summary"));
                logging.assertErrorLogged("Dropping unserializable summary event");
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aFlushIsNotToldEventsArrivedWhenOneWasTooLargeToStore() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                char[] data = new char[EventLogFormat.MAX_FRAME_SIZE];
                Arrays.fill(data, 'x');
                eventProcessor.recordCustomEvent(CONTEXT, "too-large", LDValue.of(new String(data)), null);
                eventProcessor.recordCustomEvent(CONTEXT, "fine", LDValue.ofNull(), null);

                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));

                List<LDValue> events = collectDelivered(server);
                assertEquals(1, countEventsOfKind(events, "custom"));
                assertEquals(LDValue.of("fine"), requireEventOfKind(events, "custom").get("key"));
                logging.assertErrorLogged("too large to store");
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aFlushOfMoreThanABatchSendsEveryEventOnceInRequestsNoLargerThanABatch() throws Exception {
        // Small enough that a batch closed at the limit is within one event of it, so a body that is
        // short of the limit by more than that shows the split happened somewhere else.
        int dataChars = EventLogFormat.MAX_BATCH_SIZE / 32;
        int eventCount = 100;
        char[] data = new char[dataChars];
        Arrays.fill(data, 'x');
        LDValue padding = LDValue.of(new String(data));
        // MockWebServer rather than test-helpers' HttpServer, which keeps only what one read of the
        // socket returns and so cuts a body this large short.
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setResponseCode(202);
                }
            });
            EventProcessor eventProcessor = makeEventProcessor(server.url("/").uri(),
                    eventsBuilder(eventCount), true);
            try {
                for (int i = 1; i <= eventCount; i++) {
                    eventProcessor.recordCustomEvent(CONTEXT, "event-" + i, padding, null);
                }

                assertTrue(awaitFlush(eventProcessor, 30, TimeUnit.SECONDS));

                List<Integer> bodySizes = new ArrayList<>();
                Set<String> payloadIds = new HashSet<>();
                List<String> keys = new ArrayList<>();
                for (int i = server.getRequestCount(); i > 0; i--) {
                    RecordedRequest request = server.takeRequest(10, TimeUnit.SECONDS);
                    assertNotNull(request);
                    String body = request.getBody().readUtf8();
                    bodySizes.add((int) request.getBodySize());
                    payloadIds.add(request.getHeader(PAYLOAD_ID_HEADER));
                    for (LDValue event : LDValue.parse(body).values()) {
                        keys.add(event.get("key").stringValue());
                    }
                }

                assertTrue("one request carried everything: " + bodySizes, bodySizes.size() > 1);
                Collections.sort(bodySizes);
                assertTrue("a request was larger than a batch: " + bodySizes,
                        bodySizes.get(bodySizes.size() - 1) <= EventLogFormat.MAX_BATCH_SIZE);
                // Only the batch the events ran out in may be short of full.
                for (int size : bodySizes.subList(1, bodySizes.size())) {
                    assertTrue("a batch closed short of the limit: " + bodySizes,
                            size > EventLogFormat.MAX_BATCH_SIZE - dataChars - 4096);
                }
                assertEquals(bodySizes.size(), payloadIds.size());
                List<String> expected = new ArrayList<>();
                for (int i = 1; i <= eventCount; i++) {
                    expected.add("event-" + i);
                }
                Collections.sort(keys);
                Collections.sort(expected);
                assertEquals(expected, keys);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aLossAfterAFlushClosedItsBatchIsReportedToTheNextFlushInstead() throws Exception {
        assumePersisting();
        // A flush answers for the batch it closed. One that met a retryable failure answers only when
        // its retry does, which is long enough for a loss to happen in between, and that loss belongs
        // to the flush that closes the batch after it rather than to this one.
        char[] data = new char[EventLogFormat.MAX_FRAME_SIZE];
        Arrays.fill(data, 'x');
        LDValue tooLarge = LDValue.of(new String(data));
        Semaphore letFirstResponseFinish = new Semaphore(0);
        try (HttpServer server = HttpServer.start(Handlers.sequential(
                Handlers.all(Handlers.waitFor(letFirstResponseFinish), Handlers.status(503)),
                Handlers.status(202)))) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordCustomEvent(CONTEXT, "an-event", LDValue.ofNull(), null);
                Future<Boolean> firstFlush = eventProcessor.flushAsync();
                // The batch is closed and on its way by the time the request lands, so the loss below
                // happens after this flush took everything it can speak for.
                assertNotNull(server.getRecorder().requireRequest(5, TimeUnit.SECONDS));

                eventProcessor.recordCustomEvent(CONTEXT, "too-large", tooLarge, null);
                logging.assertErrorLogged("too large to store");
                // One permit, not every permit: only this first response is held, and the finally
                // below has to be able to release it again without overflowing the count.
                letFirstResponseFinish.release();

                // The retry sends the batch this flush closed, and everything in it arrives.
                assertTrue("told about a loss that happened after it closed its batch",
                        Boolean.TRUE.equals(firstFlush.get(10, TimeUnit.SECONDS)));
                // And the loss is still owed to somebody, so the next caller is the one to hear it.
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
            } finally {
                letFirstResponseFinish.release();
                eventProcessor.close();
            }
        }
    }

    @Test
    public void eventsHeldInMemoryWhenAWriteFailsAreDeliveredWithThoseWrittenBeforeIt() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        EventStore store = new EventStore(eventsDirectory.newFolder(), DEFAULT_CAPACITY, true,
                logging.logger) {
            @Override
            FileOutputStream openForAppending(File file) throws IOException {
                return new FileOutputStream(file, true) {
                    @Override
                    public void write(byte[] bytes) throws IOException {
                        // The file header and the first event land; the disk is full from then on.
                        if (writes.incrementAndGet() > 2) {
                            throw new IOException("No space left on device");
                        }
                        super.write(bytes);
                    }
                };
            }
        };
        try (HttpServer server = startEventsServer()) {
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
            diagnosticExecutors.add(diagnosticExecutor);
            DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                    server.getUri(), null, NO_PERIODIC_FLUSH_MILLIS, 60_000, CLOSE_BUDGET_MILLIS,
                    scheduler, diagnosticExecutor, store, DEFAULT_CAPACITY, true);
            try {
                eventProcessor.setOffline(false);
                eventProcessor.recordCustomEvent(CONTEXT, "written", LDValue.ofNull(), null);
                // Its write fails, so persistence is given up with "written" in the log and this in memory.
                eventProcessor.recordCustomEvent(CONTEXT, "held", LDValue.ofNull(), null);
                logging.assertWarnLogged("Giving up on persisting events");

                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));

                List<String> keys = new ArrayList<>();
                for (LDValue event : collectDelivered(server)) {
                    keys.add(event.get("key").stringValue());
                }
                assertEquals("oldest first, and neither left behind",
                        Arrays.asList("written", "held"), keys);
            } finally {
                eventProcessor.close();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    public void aFlushIsToldOfABatchADeliveryNobodyWaitedOnDroppedAsUnreadable() throws Exception {
        File directory = eventsDirectory.newFolder();
        EventStore previousRun = new EventStore(directory, DEFAULT_CAPACITY, true, logging.logger);
        assertTrue(previousRun.stage(serialized("damaged")));
        EventStore.Batch damaged = closeOne(previousRun);
        previousRun.close();
        // Its name still counts it, so it is listed without being read; the damage is only found on sending.
        File damagedFile = new File(directory, "ready-" + damaged.payloadId + "." + damaged.eventCount);
        try (FileOutputStream out = new FileOutputStream(damagedFile)) {
            out.write("not an event log".getBytes(StandardCharsets.UTF_8));
        }

        EventStore store = new EventStore(directory, DEFAULT_CAPACITY, true, logging.logger);
        try (HttpServer server = startEventsServer()) {
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
            diagnosticExecutors.add(diagnosticExecutor);
            DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                    server.getUri(), null, NO_PERIODIC_FLUSH_MILLIS, 60_000, CLOSE_BUDGET_MILLIS,
                    scheduler, diagnosticExecutor, store, DEFAULT_CAPACITY, true);
            try {
                // Going online delivers what the previous run left, with no caller waiting on it.
                eventProcessor.setOffline(false);
                long deadline = System.currentTimeMillis() + 5_000;
                while (damagedFile.exists() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertFalse("the damaged batch was never delivered", damagedFile.exists());
                logging.assertErrorLogged("this version cannot read");

                eventProcessor.recordCustomEvent(CONTEXT, "after", LDValue.ofNull(), null);
                assertFalse(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                eventProcessor.recordCustomEvent(CONTEXT, "later", LDValue.ofNull(), null);
                assertTrue("the loss was reported twice", awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
            } finally {
                eventProcessor.close();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    public void aBatchThatCannotBeReadIsKeptAndDeliveredOnceItCanBe() throws Exception {
        AtomicBoolean failing = new AtomicBoolean(false);
        EventStore store = new EventStore(eventsDirectory.newFolder(), DEFAULT_CAPACITY, true,
                logging.logger) {
            @Override
            InputStream openForReading(File file) throws IOException {
                if (failing.get()) {
                    // What a process out of file descriptors gets for a file that is there.
                    throw new FileNotFoundException(file + " (Too many open files)");
                }
                return super.openForReading(file);
            }
        };
        try (HttpServer server = startEventsServer()) {
            ScheduledExecutorService scheduler = EventUtil.makeEventsTaskExecutor();
            ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
            diagnosticExecutors.add(diagnosticExecutor);
            DirectEventProcessor eventProcessor = makeEventProcessor(new StubEventSender(),
                    server.getUri(), null, NO_PERIODIC_FLUSH_MILLIS, 60_000, CLOSE_BUDGET_MILLIS,
                    scheduler, diagnosticExecutor, store, DEFAULT_CAPACITY, true);
            try {
                eventProcessor.setOffline(false);
                eventProcessor.recordCustomEvent(CONTEXT, "kept", LDValue.ofNull(), null);

                failing.set(true);
                assertFalse("a batch that could not be read was reported as delivered",
                        awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                server.getRecorder().requireNoRequests(100, TimeUnit.MILLISECONDS);
                logging.assertWarnLogged("Could not read stored events");

                failing.set(false);
                assertTrue(awaitFlush(eventProcessor, 10, TimeUnit.SECONDS));
                List<LDValue> events = collectDelivered(server);
                assertEquals(1, countEventsOfKind(events, "custom"));
                assertEquals(LDValue.of("kept"), requireEventOfKind(events, "custom").get("key"));
            } finally {
                eventProcessor.close();
                scheduler.shutdownNow();
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
            if (payloadsOnDisk().isEmpty()) {
                return true;
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
        DirectEventProcessor eventProcessor = makeEventProcessor(sender, UNUSED_EVENTS_URI,
                makeDiagnosticStore(), NO_PERIODIC_FLUSH_MILLIS, 20,
                DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS, scheduler, diagnosticExecutor);
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
        return makeEventProcessor(diagnosticSender, eventsUri, diagnosticStore, flushIntervalMillis,
                diagnosticIntervalMillis, closeBudgetMillis, scheduler, diagnosticExecutor);
    }

    private DirectEventProcessor makeEventProcessor(EventSender diagnosticSender,
                                                    URI eventsUri,
                                                    DiagnosticStore diagnosticStore,
                                                    long flushIntervalMillis,
                                                    long diagnosticIntervalMillis,
                                                    long closeBudgetMillis,
                                                    ScheduledExecutorService scheduler,
                                                    ExecutorService diagnosticExecutor) {
        return makeEventProcessor(diagnosticSender, eventsUri, diagnosticStore, flushIntervalMillis,
                diagnosticIntervalMillis, closeBudgetMillis, scheduler, diagnosticExecutor,
                EventStore.create(eventsDirectory.getRoot(), MOBILE_KEY,
                        DEFAULT_CAPACITY, true, logging.logger),
                DEFAULT_CAPACITY);
    }

    private DirectEventProcessor makeEventProcessor(EventStore store, int capacity,
                                                    ScheduledExecutorService scheduler) {
        ExecutorService diagnosticExecutor = EventUtil.makeDiagnosticsTaskExecutor();
        diagnosticExecutors.add(diagnosticExecutor);
        return makeEventProcessor(new StubEventSender(), UNUSED_EVENTS_URI, null,
                NO_PERIODIC_FLUSH_MILLIS, 60_000, DirectEventProcessor.DEFAULT_CLOSE_BUDGET_MILLIS,
                scheduler, diagnosticExecutor, store, capacity);
    }

    private DirectEventProcessor makeEventProcessor(EventSender diagnosticSender,
                                                    URI eventsUri,
                                                    DiagnosticStore diagnosticStore,
                                                    long flushIntervalMillis,
                                                    long diagnosticIntervalMillis,
                                                    long closeBudgetMillis,
                                                    ScheduledExecutorService scheduler,
                                                    ExecutorService diagnosticExecutor,
                                                    EventStore store,
                                                    int capacity) {
        return makeEventProcessor(diagnosticSender, eventsUri, diagnosticStore, flushIntervalMillis,
                diagnosticIntervalMillis, closeBudgetMillis, scheduler, diagnosticExecutor, store,
                capacity, false);
    }

    private DirectEventProcessor makeEventProcessor(EventSender diagnosticSender,
                                                    URI eventsUri,
                                                    DiagnosticStore diagnosticStore,
                                                    long flushIntervalMillis,
                                                    long diagnosticIntervalMillis,
                                                    long closeBudgetMillis,
                                                    ScheduledExecutorService scheduler,
                                                    ExecutorService diagnosticExecutor,
                                                    EventStore store,
                                                    int capacity,
                                                    boolean commitOnCallerThread) {
        ExecutorService commitExecutor = EventUtil.makeEventsCommitExecutor();
        diagnosticExecutors.add(commitExecutor);
        lastCommitExecutor = commitExecutor;
        return new DirectEventProcessor(
                new OutboundEventBuffer(false, Collections.emptyList(), true, capacity,
                        logging.logger),
                store,
                diagnosticSender,
                new AnalyticsEventSender(LDUtil.makeHttpProperties(
                        new HttpConfiguration(2000, Collections.emptyMap(), null, false)),
                        logging.logger),
                new DeliveryRetryState(flushIntervalMillis),
                eventsUri,
                diagnosticStore,
                capacity,
                commitOnCallerThread,
                flushIntervalMillis,
                diagnosticIntervalMillis,
                closeBudgetMillis,
                false,
                true, // initiallyOffline, as the SDK builds it
                scheduler,
                commitExecutor,
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

    /**
     * Flushes and waits for the outcome the way {@code LDClient.flushAndWait} does, which is the
     * only place a timeout belongs.
     */
    private static boolean awaitFlush(EventProcessor eventProcessor, long timeout, TimeUnit unit)
            throws Exception {
        try {
            return Boolean.TRUE.equals(eventProcessor.flushAsync().get(timeout, unit));
        } catch (TimeoutException e) {
            return false;
        }
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
