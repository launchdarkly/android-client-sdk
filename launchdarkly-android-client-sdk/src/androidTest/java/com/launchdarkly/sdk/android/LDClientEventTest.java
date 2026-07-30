package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;
import com.launchdarkly.sdk.internal.GsonHelpers;
import com.launchdarkly.sdk.json.JsonSerialization;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public class LDClientEventTest {
    // For better test isolation, any tests here that need to inject flag data will use a simple
    // in-memory store rather than the actual SharedPreferences-based storage mechanism. The
    // purpose of these tests is to validate event-related logic, not the store implementation.

    private static final String mobileKey = "test-mobile-key";
    private static final LDContext ldContext = LDContext.create("userKey");
    private Application application;

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void testTrack() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();

            // Don't wait as we are not set offline
            try (LDClient ldClient = LDClient.init(application, ldConfig, ldContext, 0)){
                ldClient.track("test-event");
                ldClient.blockingFlush();

                LDValue[] events = getEventsFromLastRequest(mockEventsServer, 2);
                LDValue identifyEvent = events[0], customEvent = events[1];
                assertIdentifyEvent(identifyEvent, ldContext);
                assertCustomEvent(customEvent, ldContext, "test-event");
                assertEquals(LDValue.ofNull(), customEvent.get("data"));
                assertEquals(LDValue.ofNull(), customEvent.get("metricValue"));
            }
        }
    }

    @Test
    public void testTrackData() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            // Don't wait as we are not set offline
            try (LDClient client = LDClient.init(application, ldConfig, ldContext, 0)) {
                LDValue testData = LDValue.of("abc");

                client.trackData("test-event", testData);
                client.blockingFlush();

                LDValue[] events = getEventsFromLastRequest(mockEventsServer, 2);
                LDValue identifyEvent = events[0], customEvent = events[1];
                assertIdentifyEvent(identifyEvent, ldContext);
                assertCustomEvent(customEvent, ldContext, "test-event");
                assertEquals(testData, customEvent.get("data"));
                assertEquals(LDValue.ofNull(), customEvent.get("metricValue"));
            }
        }
    }

    @Test
    public void testTrackDataValueNull() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldContext, 0)) {
                client.trackData("test-event", null);
                client.blockingFlush();

                LDValue[] events = getEventsFromLastRequest(mockEventsServer, 2);
                LDValue identifyEvent = events[0], customEvent = events[1];
                assertIdentifyEvent(identifyEvent, ldContext);
                assertCustomEvent(customEvent, ldContext, "test-event");
                assertEquals(LDValue.ofNull(), customEvent.get("data"));
                assertEquals(LDValue.ofNull(), customEvent.get("metricValue"));
            }
        }
    }

    @Test
    public void testTrackDataValueOfNull() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldContext, 0)) {
                client.trackData("test-event", LDValue.ofNull());
                client.blockingFlush();

                LDValue[] events = getEventsFromLastRequest(mockEventsServer, 2);
                LDValue identifyEvent = events[0], customEvent = events[1];
                assertIdentifyEvent(identifyEvent, ldContext);
                assertCustomEvent(customEvent, ldContext, "test-event");
                assertEquals(LDValue.ofNull(), customEvent.get("data"));
                assertEquals(LDValue.ofNull(), customEvent.get("metricValue"));
            }
        }
    }

    @Test
    public void testTrackMetric() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldContext, 0)) {
                LDValue testData = LDValue.of("abc");

                client.trackMetric("test-event", testData, 5.5);
                client.blockingFlush();

                LDValue[] events = getEventsFromLastRequest(mockEventsServer, 2);
                LDValue identifyEvent = events[0], customEvent = events[1];
                assertIdentifyEvent(identifyEvent, ldContext);
                assertCustomEvent(customEvent, ldContext, "test-event");
                assertEquals(testData, customEvent.get("data"));
                assertEquals(LDValue.of(5.5), customEvent.get("metricValue"));
            }
        }
    }

    @Test
    public void variationFlagTrackReasonGeneratesEventWithReason() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            // Setup flag store with test flag
            Flag flag = new FlagBuilder("track-reason-flag").version(10)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.off())
                    .trackEvents(true).trackReason(true).build();
            PersistentDataStore store = new InMemoryPersistentDataStore();
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flag);

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer)
                    .persistentDataStore(store).build();

            try (LDClient client = LDClient.init(application, ldConfig, ldContext, 0)) {
                client.boolVariation("track-reason-flag", false);
                client.blockingFlush();

                LDValue[] events = getEventsFromLastRequest(mockEventsServer, 3);
                LDValue identifyEvent = events[0], featureEvent = events[1], summaryEvent = events[2];
                assertIdentifyEvent(identifyEvent, ldContext);
                assertFeatureEvent(featureEvent, ldContext);
                assertEquals(LDValue.of("track-reason-flag"), featureEvent.get("key"));
                assertEquals(LDValue.of(1), featureEvent.get("variation"));
                assertEquals(LDValue.of(true), featureEvent.get("value"));
                assertEquals(LDValue.of(10), featureEvent.get("version"));
                assertEquals(LDValue.parse(JsonSerialization.serialize(flag.getReason())),
                        featureEvent.get("reason"));
                assertSummaryEvent(summaryEvent);
            }
        }
    }

    @Test
    public void flagEvaluationWithPrereqProducesPrereqEvents() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            // Setup flag store with test flag
            Flag flagA = new FlagBuilder("flagA").version(1)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.targetMatch()).build();
            Flag flagAB = new FlagBuilder("flagAB").prerequisites(new String[]{"flagA"}).version(1)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.targetMatch()).build();
            Flag flagAC = new FlagBuilder("flagAC").prerequisites(new String[]{"flagA"}).version(1)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.targetMatch()).build();
            Flag flagABD = new FlagBuilder("flagABD").prerequisites(new String[]{"flagAB"}).version(1)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.targetMatch()).build();
            PersistentDataStore store = new InMemoryPersistentDataStore();
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flagA);
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flagAB);
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flagAC);
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flagABD);
            LDConfig ldConfig = baseConfigBuilder(mockEventsServer)
                    .persistentDataStore(store).build();

            try (LDClient client = LDClient.init(application, ldConfig, ldContext, 0)) {
                assertTrue(client.boolVariation("flagA", false));
                assertTrue(client.boolVariation("flagAB", false));
                assertTrue(client.boolVariation("flagAC", false));
                assertTrue(client.boolVariation("flagABD", false));
                client.blockingFlush();

                LDValue[] events = getEventsFromLastRequest(mockEventsServer, 2);
                LDValue summaryEvent = events[1];
                assertSummaryEvent(summaryEvent);
                assertEquals(LDValue.of(4), summaryEvent.get("features").get("flagA").get("counters").get(0).get("count"));
                assertEquals(LDValue.of(2), summaryEvent.get("features").get("flagAB").get("counters").get(0).get("count"));
                assertEquals(LDValue.of(1), summaryEvent.get("features").get("flagAC").get("counters").get(0).get("count"));
                assertEquals(LDValue.of(1), summaryEvent.get("features").get("flagABD").get("counters").get(0).get("count"));
            }
        }
    }

    // Cycle-detection tests exercise CSPE 1.2.5, 1.2.5.1, and 1.2.5.2. Prior to the cycle guard,
    // any of these configurations would cause a StackOverflowError on the first variation() call.
    // The tests set up a cyclic prerequisite graph via the persistent store, evaluate one flag on
    // the cycle, and assert (a) the SDK returns the flag's cached value unchanged and (b) summary
    // counters reflect each cycle-safe descent exactly once.

    @Test
    public void flagEvaluationWithSelfLoopPrereqReturnsCachedValue() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            mockEventsServer.enqueue(new MockResponse());

            // flagA's only prerequisite is itself. The cycle guard must skip the self-prereq.
            Flag flagA = new FlagBuilder("flagA").prerequisites(new String[]{"flagA"}).version(1)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.targetMatch()).build();
            PersistentDataStore store = new InMemoryPersistentDataStore();
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flagA);
            LDConfig ldConfig = baseConfigBuilder(mockEventsServer)
                    .persistentDataStore(store).build();

            try (LDClient client = LDClient.init(application, ldConfig, ldContext, 0)) {
                // Requirement 1.2.5.1: the requested flag's cached value is returned unchanged.
                assertTrue(client.boolVariation("flagA", false));
                client.blockingFlush();

                LDValue[] events = getEventsFromLastRequest(mockEventsServer, 2);
                LDValue summaryEvent = events[1];
                assertSummaryEvent(summaryEvent);
                // flagA is counted exactly once (top-level); the cyclic self-prereq does not
                // recurse and therefore does not increment the counter.
                assertEquals(LDValue.of(1), summaryEvent.get("features").get("flagA").get("counters").get(0).get("count"));
            }
        }
    }

    @Test
    public void flagEvaluationWithTwoCyclePrereqReturnsCachedValue() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            mockEventsServer.enqueue(new MockResponse());

            // flagA <-> flagB. Evaluating flagA descends into flagB; flagB's prereq flagA is on the
            // ancestor path so the cycle guard skips it.
            Flag flagA = new FlagBuilder("flagA").prerequisites(new String[]{"flagB"}).version(1)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.targetMatch()).build();
            Flag flagB = new FlagBuilder("flagB").prerequisites(new String[]{"flagA"}).version(1)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.targetMatch()).build();
            PersistentDataStore store = new InMemoryPersistentDataStore();
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flagA);
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flagB);
            LDConfig ldConfig = baseConfigBuilder(mockEventsServer)
                    .persistentDataStore(store).build();

            try (LDClient client = LDClient.init(application, ldConfig, ldContext, 0)) {
                assertTrue(client.boolVariation("flagA", false));
                client.blockingFlush();

                LDValue[] events = getEventsFromLastRequest(mockEventsServer, 2);
                LDValue summaryEvent = events[1];
                assertSummaryEvent(summaryEvent);
                // flagA: top-level (1). flagB: reached as prereq of flagA (1). Cyclic descent into
                // flagA from flagB is skipped, so flagA is not double-counted.
                assertEquals(LDValue.of(1), summaryEvent.get("features").get("flagA").get("counters").get(0).get("count"));
                assertEquals(LDValue.of(1), summaryEvent.get("features").get("flagB").get("counters").get(0).get("count"));
            }
        }
    }

    @Test
    public void flagEvaluationWithThreeCyclePrereqReturnsCachedValue() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            mockEventsServer.enqueue(new MockResponse());

            // A -> B -> C -> A. Deepest cycle-safe descent reaches C; C's prereq A is on the
            // ancestor path and is skipped.
            Flag flagA = new FlagBuilder("flagA").prerequisites(new String[]{"flagB"}).version(1)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.targetMatch()).build();
            Flag flagB = new FlagBuilder("flagB").prerequisites(new String[]{"flagC"}).version(1)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.targetMatch()).build();
            Flag flagC = new FlagBuilder("flagC").prerequisites(new String[]{"flagA"}).version(1)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.targetMatch()).build();
            PersistentDataStore store = new InMemoryPersistentDataStore();
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flagA);
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flagB);
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flagC);
            LDConfig ldConfig = baseConfigBuilder(mockEventsServer)
                    .persistentDataStore(store).build();

            try (LDClient client = LDClient.init(application, ldConfig, ldContext, 0)) {
                assertTrue(client.boolVariation("flagA", false));
                client.blockingFlush();

                LDValue[] events = getEventsFromLastRequest(mockEventsServer, 2);
                LDValue summaryEvent = events[1];
                assertSummaryEvent(summaryEvent);
                assertEquals(LDValue.of(1), summaryEvent.get("features").get("flagA").get("counters").get(0).get("count"));
                assertEquals(LDValue.of(1), summaryEvent.get("features").get("flagB").get("counters").get(0).get("count"));
                assertEquals(LDValue.of(1), summaryEvent.get("features").get("flagC").get("counters").get(0).get("count"));
            }
        }
    }

    @Test
    public void flagEvaluationWithDiamondPrereqCountsSharedDescendantTwice() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            mockEventsServer.enqueue(new MockResponse());

            // Diamond: A -> [B, C], B -> [D], C -> [D]. This is NOT a cycle. Per CSPE 1.2.5.2,
            // ancestor-set (current-path) semantics must let D be reached on each of the two
            // independent paths — so D's counter must reach 2. A naive "visited across the whole
            // walk" implementation would incorrectly count D only once; this test guards against
            // that regression.
            Flag flagA = new FlagBuilder("flagA").prerequisites(new String[]{"flagB", "flagC"}).version(1)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.targetMatch()).build();
            Flag flagB = new FlagBuilder("flagB").prerequisites(new String[]{"flagD"}).version(1)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.targetMatch()).build();
            Flag flagC = new FlagBuilder("flagC").prerequisites(new String[]{"flagD"}).version(1)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.targetMatch()).build();
            Flag flagD = new FlagBuilder("flagD").version(1)
                    .variation(1).value(LDValue.of(true)).reason(EvaluationReason.targetMatch()).build();
            PersistentDataStore store = new InMemoryPersistentDataStore();
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flagA);
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flagB);
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flagC);
            TestUtil.writeFlagUpdateToStore(store, mobileKey, ldContext, flagD);
            LDConfig ldConfig = baseConfigBuilder(mockEventsServer)
                    .persistentDataStore(store).build();

            try (LDClient client = LDClient.init(application, ldConfig, ldContext, 0)) {
                assertTrue(client.boolVariation("flagA", false));
                client.blockingFlush();

                LDValue[] events = getEventsFromLastRequest(mockEventsServer, 2);
                LDValue summaryEvent = events[1];
                assertSummaryEvent(summaryEvent);
                assertEquals(LDValue.of(1), summaryEvent.get("features").get("flagA").get("counters").get(0).get("count"));
                assertEquals(LDValue.of(1), summaryEvent.get("features").get("flagB").get("counters").get(0).get("count"));
                assertEquals(LDValue.of(1), summaryEvent.get("features").get("flagC").get("counters").get(0).get("count"));
                assertEquals(LDValue.of(2), summaryEvent.get("features").get("flagD").get("counters").get(0).get("count"));
            }
        }
    }

    @Test
    public void additionalHeadersIncludedInEventsRequest() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer)
                    .http(Components.httpConfiguration().headerTransform(headers -> {
                        headers.put("Proxy-Authorization", "token");
                        headers.put("Authorization", "foo");
                    })).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldContext, 0)) {
                client.blockingFlush();
            }

            RecordedRequest r = mockEventsServer.takeRequest();
            assertEquals("token", r.getHeader("Proxy-Authorization"));
            assertEquals("foo", r.getHeader("Authorization"));
        }
    }

    @Test
    public void testEventBufferFillsUp() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer)
                    .events(Components.sendEvents().capacity(1))
                    .build();

            // Don't wait as we are not set offline
            try (LDClient client = LDClient.init(application, ldConfig, ldContext, 0)) {
                client.identify(ldContext);
                LDValue testData = LDValue.of("xyz");
                client.trackData("test-event", testData);
                Thread.sleep(200); // let it drain the queue so the flush request isn't lost
                client.blockingFlush();

                // Verify that only the first event was sent and other events were dropped
                LDValue[] events = getEventsFromLastRequest(mockEventsServer, 1);
                assertIdentifyEvent(events[0], ldContext);
            }
        }
    }

    @Test
    public void testEventContainsAutoEnvAttributesWhenEnabled() throws Exception {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            HttpUrl baseUrl = mockEventsServer.url("/");
            LDConfig ldConfig = new LDConfig.Builder(AutoEnvAttributes.Enabled)
                    .mobileKey(mobileKey)
                    .diagnosticOptOut(true)
                    .serviceEndpoints(Components.serviceEndpoints()
                            .events(baseUrl.uri())
                    )
                    .build();

            try (LDClient ldClient = LDClient.init(application, ldConfig, ldContext, 0)){
                ldClient.blockingFlush();

                LDValue[] events = getEventsFromLastRequest(mockEventsServer, 1);
                LDValue identifyEvent = events[0];
                assertTrue(identifyEvent.get("context").toString().contains("ld_application"));
                assertTrue(identifyEvent.get("context").toString().contains("ld_device"));
            }
        }
    }

    @Test
    public void testEventDoesNotContainAutoEnvAttributesWhenDisabled() throws Exception {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            HttpUrl baseUrl = mockEventsServer.url("/");
            LDConfig ldConfig = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                    .mobileKey(mobileKey)
                    .diagnosticOptOut(true)
                    .serviceEndpoints(Components.serviceEndpoints()
                            .events(baseUrl.uri())
                    )
                    .build();

            try (LDClient ldClient = LDClient.init(application, ldConfig, ldContext, 0)){
                ldClient.blockingFlush();

                LDValue[] events = getEventsFromLastRequest(mockEventsServer, 1);
                LDValue identifyEvent = events[0];
                assertFalse(identifyEvent.get("context").toString().contains("ld_application"));
                assertFalse(identifyEvent.get("context").toString().contains("ld_device"));
            }
        }
    }

    private LDValue[] getEventsFromLastRequest(MockWebServer server, int expectedCount) throws InterruptedException {
        RecordedRequest r = server.takeRequest();
        assertEquals("POST", r.getMethod());
        assertEquals("/mobile/events/bulk", r.getPath());
        assertEquals(mobileKey, r.getHeader("Authorization"));
        String body = r.getBody().readUtf8();
        System.out.println(body);
        LDValue[] events = GsonHelpers.gsonInstance().fromJson(body, LDValue[].class);
        if (events.length != expectedCount) {
            fail("count should be " + expectedCount + " for: " + body);
        }
        return events;
    }

    private LDConfig.Builder baseConfigBuilder(MockWebServer server) {
        HttpUrl baseUrl = server.url("/");
        return new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey(mobileKey)
                .diagnosticOptOut(true)
                .serviceEndpoints(Components.serviceEndpoints().events(baseUrl.uri()));
    }

    private void assertIdentifyEvent(LDValue event, LDContext context) {
        assertEquals("identify", event.get("kind").stringValue());
    }

    private void assertContext(LDValue event, LDContext context) {
        assertNotNull(event.get("context"));
        assertEquals(context.getKind().toString(), event.get("context").get("kind").stringValue());
        assertEquals(context.getKey(), event.get("context").get("key").stringValue());
    }

    private void assertFeatureEvent(LDValue event, LDContext context) {
        assertEquals("feature", event.get("kind").stringValue());
    }

    private void assertCustomEvent(LDValue event, LDContext context, String eventKey) {
        assertEquals("custom", event.get("kind").stringValue());
        assertContext(event, context);
        assertEquals(eventKey, event.get("key").stringValue());
    }

    private void assertSummaryEvent(LDValue event) {
        assertEquals("summary", event.get("kind").stringValue());
    }
}
