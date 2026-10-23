package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EventPersistence;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestContext;

import org.junit.Test;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

/**
 * What an application gets by default: events held in memory and delivered from there, with nothing
 * on the disk. These are the situations where holding events is hardest, which the suites shared with
 * persistence (run here through their WithoutPersistence subclasses) do not set out to reach.
 */
public class EventProcessorWithoutPersistenceTest extends EventProcessorTestBase {
    private static final String PAYLOAD_ID_HEADER = "X-LaunchDarkly-Payload-ID";

    @Override
    protected EventPersistence persistence() {
        return EventPersistence.DISABLED;
    }

    /**
     * An events service whose answer to each post can change during a test, and which keeps what it took.
     * It serves either test-helpers' {@link HttpServer} or OkHttp's {@link MockWebServer}.
     */
    private static final class EventsService extends Dispatcher implements Handler {
        final AtomicInteger posts = new AtomicInteger();
        /** The status for the nth post, counting from 1. */
        volatile IntUnaryOperator statusForPost = post -> 202;
        /** Custom event keys of each post the service accepted, by payload ID, in the order accepted. */
        final List<Map.Entry<String, List<String>>> accepted =
                Collections.synchronizedList(new ArrayList<Map.Entry<String, List<String>>>());

        @Override
        public void apply(RequestContext context) {
            context.setStatus(respond(context.getRequest().getBody(),
                    context.getRequest().getHeader(PAYLOAD_ID_HEADER)));
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            return new MockResponse().setResponseCode(
                    respond(request.getBody().readUtf8(), request.getHeader(PAYLOAD_ID_HEADER)));
        }

        private int respond(String body, String payloadId) {
            int status = statusForPost.applyAsInt(posts.incrementAndGet());
            if (status == 202) {
                List<String> keys = new ArrayList<>();
                for (LDValue event : LDValue.parse(body).values()) {
                    if ("custom".equals(event.get("kind").stringValue())) {
                        keys.add(event.get("key").stringValue());
                    }
                }
                accepted.add(new HashMap.SimpleEntry<>(payloadId, keys));
            }
            return status;
        }

        /** Every accepted key once per payload ID, as the service counts them, in the order accepted. */
        List<String> acceptedKeys() {
            Set<String> payloads = new HashSet<>();
            List<String> keys = new ArrayList<>();
            synchronized (accepted) {
                for (Map.Entry<String, List<String>> post : accepted) {
                    if (payloads.add(post.getKey())) {
                        keys.addAll(post.getValue());
                    }
                }
            }
            return keys;
        }
    }

    private static boolean awaitFlush(EventProcessor eventProcessor) throws Exception {
        try {
            return Boolean.TRUE.equals(eventProcessor.flushAsync().get(10, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            return false;
        }
    }

    private static List<String> keys(String prefix, int from, int to) {
        List<String> keys = new ArrayList<>();
        for (int i = from; i < to; i++) {
            keys.add(prefix + i);
        }
        return keys;
    }

    private static void track(EventProcessor eventProcessor, String key) {
        eventProcessor.recordCustomEvent(CONTEXT, key, LDValue.ofNull(), null);
    }

    @Test
    public void aSessionOfRefusalsRetriesAndAShutdownNeverTouchesTheFilesystem() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        MockPlatformState platformState = new MockPlatformState() {
            @Override
            public File getNoBackupFilesDir() {
                asked.incrementAndGet();
                return eventsDirectory.getRoot();
            }
        };
        EventsService service = new EventsService();
        // Refused and refused again on the retry; then the batch held from that and the next one are
        // accepted; then the service says the key is no good, after which nothing is posted for a while.
        // The waits are cut to milliseconds, which makes that while about four seconds.
        service.statusForPost = post -> post <= 2 ? 503 : post <= 4 ? 202 : 401;
        try (HttpServer server = HttpServer.start(service)) {
            EventProcessor eventProcessor = buildOfflineEventProcessor(server,
                    ((ComponentsImpl.EventProcessorBuilderImpl) eventsBuilder(100))
                            .retryDelaysForTesting(8, 8, TimeUnit.HOURS.toMillis(1)),
                    true, platformState);
            eventProcessor.setOffline(false);
            try {
                track(eventProcessor, "refused");
                assertFalse(awaitFlush(eventProcessor));
                track(eventProcessor, "accepted");
                assertTrue(awaitFlush(eventProcessor));
                track(eventProcessor, "shut-down");
                awaitFlush(eventProcessor);
                track(eventProcessor, "after-shut-down");
            } finally {
                eventProcessor.close();
            }

            assertEquals(Arrays.asList("refused", "accepted"), service.acceptedKeys());
            assertEquals("posted after the service said the key is no good", 5, service.posts.get());
        }
        assertEquals("a store without persistence looked for its directory", 0, asked.get());
        String[] written = eventsDirectory.getRoot().list();
        assertEquals("wrote to the disk", 0, written == null ? 0 : written.length);
    }

    @Test
    public void anOutageKeepsTheOldestEventsUpToCapacityAndDeliversEachOnceInOrder() throws Exception {
        // Before persistence, a batch refused twice was dropped. Now it waits in memory, still counted
        // against capacity, so an outage costs the newest events rather than the oldest and memory stays
        // bounded however long it lasts.
        int capacity = 20;
        EventsService service = new EventsService();
        AtomicBoolean down = new AtomicBoolean(true);
        service.statusForPost = post -> down.get() ? 503 : 202;
        try (HttpServer server = HttpServer.start(service)) {
            EventProcessor eventProcessor = makeEventProcessor(server, eventsBuilderWithoutBackoff(capacity),
                    true);
            try {
                for (String key : keys("e-", 0, 10)) {
                    track(eventProcessor, key);
                }
                assertFalse("delivered while the service was down", awaitFlush(eventProcessor));
                for (String key : keys("e-", 10, 100)) {
                    track(eventProcessor, key);
                }
                assertFalse("delivered while the service was down", awaitFlush(eventProcessor));

                down.set(false);
                assertTrue(awaitFlush(eventProcessor));

                assertEquals(keys("e-", 0, capacity), service.acceptedKeys());
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void eventsRecordedAndFlushedFromManyThreadsThroughRefusalsAreEachDeliveredOnce() throws Exception {
        int recorders = 4;
        int eventsEach = 150;
        EventsService service = new EventsService();
        // Every third post refused, so retries, in-memory batches waiting for the next delivery and
        // flushes overlapping one another all happen while the recorders are still going.
        service.statusForPost = post -> post % 3 == 0 ? 503 : 202;
        // Not test-helpers' HttpServer, which reads a request body with a single read(): under load a
        // large post arrives in pieces, the body it hands over is cut short, and the rest of it is taken
        // as the next request on the connection, which the SDK is then refused for.
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(service);
            server.start();
            EventProcessor eventProcessor = makeEventProcessor(server.url("/").uri(), eventsBuilder(100_000),
                    true);
            try {
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch recorded = new CountDownLatch(recorders);
                for (int r = 0; r < recorders; r++) {
                    String prefix = "r" + r + "-";
                    new Thread(() -> {
                        try {
                            start.await();
                            for (String key : keys(prefix, 0, eventsEach)) {
                                track(eventProcessor, key);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            recorded.countDown();
                        }
                    }).start();
                }
                Thread flusher = new Thread(() -> {
                    while (recorded.getCount() > 0) {
                        eventProcessor.flush();
                        try {
                            Thread.sleep(2);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                });
                flusher.start();
                start.countDown();
                assertTrue(recorded.await(20, TimeUnit.SECONDS));
                flusher.join(5_000);

                service.statusForPost = post -> 202;
                assertTrue("what was held back was never delivered", awaitFlush(eventProcessor));

                List<String> delivered = service.acceptedKeys();
                Set<String> distinct = new HashSet<>(delivered);
                assertEquals("an event arrived under two payload IDs, so the service counts it twice",
                        delivered.size(), distinct.size());
                Set<String> expected = new HashSet<>();
                for (int r = 0; r < recorders; r++) {
                    expected.addAll(keys("r" + r + "-", 0, eventsEach));
                }
                assertEquals(expected, distinct);
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void eventsRecordedBeforeTheProcessorIsTurnedOnAreDeliveredOnceItIs() throws Exception {
        EventsService service = new EventsService();
        try (HttpServer server = HttpServer.start(service)) {
            EventProcessor eventProcessor = buildOfflineEventProcessor(server, eventsBuilder(100), true);
            try {
                for (String key : keys("offline-", 0, 3)) {
                    track(eventProcessor, key);
                }
                eventProcessor.flush();

                eventProcessor.setOffline(false);
                assertTrue(awaitFlush(eventProcessor));

                assertEquals(keys("offline-", 0, 3), service.acceptedKeys());
                assertEquals("posted while offline, or posted twice", 1, service.posts.get());
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void atMostCapacityContextsAreSummarizedBetweenDeliveries() throws Exception {
        // Without a disk, a commit point does not drain the summarizer, so between two deliveries it
        // counts evaluations for at most capacity contexts and turns the rest away. This pins that
        // trade-off: before the bound, offline evaluation across many contexts grew memory without
        // limit; with persistence on, each commit point makes room again.
        int capacity = 10;
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, eventsBuilder(capacity), true);
            try {
                for (int round = 0; round < 2; round++) {
                    for (int i = 0; i < capacity * 3; i++) {
                        LDContext context = LDContext.create("round-" + round + "-context-" + i);
                        eventProcessor.recordEvaluationEvent(context, "flag", 1, 0, LDValue.of(true), null,
                                LDValue.of(false), false, null);
                    }
                    List<LDValue> events = flushAndCollect(eventProcessor, server);
                    assertEquals("round " + round, capacity, countEventsOfKind(events, "summary"));
                }
            } finally {
                eventProcessor.close();
            }
        }
    }
}
