package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
//                assertIdentifyEvent(identifyEvent, ldContext);
                assertFeatureEvent(featureEvent, ldContext);
//                assertEquals(LDValue.of("track-reason-flag"), featureEvent.get("key"));
//                assertEquals(LDValue.of(1), featureEvent.get("variation"));
//                assertEquals(LDValue.of(true), featureEvent.get("value"));
//                assertEquals(LDValue.of(10), featureEvent.get("version"));
//                assertEquals(LDValue.parse(JsonSerialization.serialize(flag.getReason())),
//                        featureEvent.get("reason"));
//                assertSummaryEvent(summaryEvent);
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
        assertEquals(LDUtil.AUTH_SCHEME + mobileKey, r.getHeader("Authorization"));
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

    private void assertContextKeys(LDValue event, LDContext context) {
        ObjectBuilder o = LDValue.buildObject();
        for (int i = 0; i < context.getIndividualContextCount(); i++) {
            o.put(context.getIndividualContext(i).getKind().toString(),
                    context.getIndividualContext(i).getKey());
        }
        assertEquals(o.build(), event.get("contextKeys"));
    }

    private void assertFeatureEvent(LDValue event, LDContext context) {
        assertEquals("feature", event.get("kind").stringValue());
        assertEquals(context, event.get("context"));
    }

    private void assertCustomEvent(LDValue event, LDContext context, String eventKey) {
        assertEquals("custom", event.get("kind").stringValue());
        assertContextKeys(event, context);
        assertEquals(eventKey, event.get("key").stringValue());
    }

    private void assertSummaryEvent(LDValue event) {
        assertEquals("summary", event.get("kind").stringValue());
    }
}
