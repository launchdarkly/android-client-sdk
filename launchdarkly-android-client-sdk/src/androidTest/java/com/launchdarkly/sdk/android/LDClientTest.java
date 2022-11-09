package com.launchdarkly.sdk.android;

import android.app.Application;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class LDClientTest {

    private static final String mobileKey = "test-mobile-key";
    private Application application;
    private LDClient ldClient;
    private Future<LDClient> ldClientFuture;
    private LDConfig ldConfig;
    private LDUser ldUser;

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
        ldConfig = new LDConfig.Builder()
                .offline(true)
                .build();

        ldUser = LDClient.customizeUser(new LDUser.Builder("userKey").build());
    }

    @Test
    public void testOfflineClientReturnsDefaults() {
        ldClient = LDClient.init(application, ldConfig, ldUser, 1);

        assertTrue("client was not initialized", ldClient.isInitialized());
        assertTrue("client was offline", ldClient.isOffline());

        assertTrue(ldClient.boolVariation("boolFlag", true));
        assertEquals(1.5, ldClient.doubleVariation("doubleFlag", 1.5), 0.0);
        assertEquals(1, ldClient.intVariation("intFlag", 1));
        assertEquals("default", ldClient.stringVariation("stringFlag", "default"));

        LDValue expectedJson = LDValue.of("value");
        assertEquals(expectedJson, ldClient.jsonValueVariation("jsonFlag", expectedJson));
    }

    @Test
    public void givenDefaultsAreNullAndTestOfflineClientReturnsDefaults() {
        ldClient = LDClient.init(application, ldConfig, ldUser, 1);

        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());
        assertNull(ldClient.stringVariation("stringFlag", null));
        assertEquals(ldClient.jsonValueVariation("jsonFlag", null), LDValue.ofNull());
    }

    @Test
    public void testInitMissingApplication() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        ldClientFuture = LDClient.init(null, ldConfig, ldUser);

        try {
            ldClientFuture.get();
        } catch (InterruptedException e) {
            fail();
        } catch (ExecutionException e) {
            actualFutureException = e;
            actualProvidedException = (LaunchDarklyException) e.getCause();
        }

        assertEquals(ExecutionException.class, actualFutureException.getClass());
        assertEquals(LaunchDarklyException.class, actualProvidedException.getClass());
        assertTrue("No future task to run", ldClientFuture.isDone());
    }

    @Test
    public void testInitMissingConfig() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        ldClientFuture = LDClient.init(application, null, ldUser);

        try {
            ldClientFuture.get();
        } catch (InterruptedException e) {
            fail();
        } catch (ExecutionException e) {
            actualFutureException = e;
            actualProvidedException = (LaunchDarklyException) e.getCause();
        }

        assertEquals(ExecutionException.class, actualFutureException.getClass());
        assertEquals(LaunchDarklyException.class, actualProvidedException.getClass());
        assertTrue("No future task to run", ldClientFuture.isDone());
    }

    @Test
    public void testInitMissingUser() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        ldClientFuture = LDClient.init(application, ldConfig, null);

        try {
            ldClientFuture.get();
        } catch (InterruptedException e) {
            fail();
        } catch (ExecutionException e) {
            actualFutureException = e;
            actualProvidedException = (LaunchDarklyException) e.getCause();
        }

        assertEquals(ExecutionException.class, actualFutureException.getClass());
        assertEquals(LaunchDarklyException.class, actualProvidedException.getClass());
        assertTrue("No future task to run", ldClientFuture.isDone());
    }

    // the second ldClient.close() should throw a npe
    public void testDoubleClose() throws IOException {
        ldClient = LDClient.init(application, ldConfig, ldUser, 1);
        ldClient.close();
        ldClient.close();
    }

    @Test
    public void testInitBackgroundThread() throws ExecutionException, InterruptedException {
        Future<?> backgroundComplete = new BackgroundThreadExecutor().newFixedThreadPool(1).submit(() -> {
            try {
                ldClient = LDClient.init(application, ldConfig, ldUser).get();
            } catch (Exception e) {
                fail();
            }
            assertTrue(ldClient.isInitialized());
            assertTrue(ldClient.isOffline());

            assertTrue(ldClient.boolVariation("boolFlag", true));
            assertEquals(1.0, ldClient.doubleVariation("floatFlag", 1.0), 0.0);
            assertEquals(1, ldClient.intVariation("intFlag", 1));
            assertEquals("default", ldClient.stringVariation("stringFlag", "default"));

            LDValue expectedJson = LDValue.of("value");
            assertEquals(expectedJson, ldClient.jsonValueVariation("jsonFlag", expectedJson));
        });
        backgroundComplete.get();
    }

    @Test
    public void testTrack() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();

            // Don't wait as we are not set offline
            ldClient = LDClient.init(application, ldConfig, ldUser, 0);

            ldClient.track("test-event");
            ldClient.blockingFlush();

            Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
            assertTrue(events[0] instanceof IdentifyEvent);
            assertTrue(events[1] instanceof CustomEvent);
            CustomEvent event = (CustomEvent) events[1];
            assertEquals("userKey", event.userKey);
            assertEquals("test-event", event.key);
            assertNull(event.data);
            assertNull(event.metricValue);
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
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                LDValue testData = LDValue.of("abc");

                client.trackData("test-event", testData);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof CustomEvent);
                CustomEvent event = (CustomEvent) events[1];
                assertEquals("userKey", event.userKey);
                assertEquals("test-event", event.key);
                assertNull(event.metricValue);
            }
        }
    }

    @Test
    public void testTrackDataValue() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            // Don't wait as we are not set offline
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                LDValue testData = LDValue.of("abc");
                client.trackData("test-event", testData);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof CustomEvent);
                CustomEvent event = (CustomEvent) events[1];
                assertEquals("userKey", event.userKey);
                assertEquals("test-event", event.key);
                assertEquals(testData, event.data);
                assertNull(event.metricValue);
            }
        }
    }

    @Test
    public void testTrackDataNull() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.track("test-event");
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof CustomEvent);
                CustomEvent event = (CustomEvent) events[1];
                assertEquals("userKey", event.userKey);
                assertEquals("test-event", event.key);
                assertNull(event.data);
                assertNull(event.metricValue);
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
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.track("test-event");
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof CustomEvent);
                CustomEvent event = (CustomEvent) events[1];
                assertEquals("userKey", event.userKey);
                assertEquals("test-event", event.key);
                assertNull(event.data);
                assertNull(event.metricValue);
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
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.trackData("test-event", LDValue.ofNull());
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof CustomEvent);
                CustomEvent event = (CustomEvent) events[1];
                assertEquals("userKey", event.userKey);
                assertEquals("test-event", event.key);
                assertNull(event.data);
                assertNull(event.metricValue);
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
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.trackMetric("test-event", null, 5.5);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof CustomEvent);
                CustomEvent event = (CustomEvent) events[1];
                assertEquals("userKey", event.userKey);
                assertEquals("test-event", event.key);
                assertNull(event.data);
                assertEquals(5.5, event.metricValue, 0);
            }
        }
    }

    @Test
    public void testTrackMetricNullData() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.trackMetric("test-event", null, 5.5);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof CustomEvent);
                CustomEvent event = (CustomEvent) events[1];
                assertEquals("userKey", event.userKey);
                assertEquals("test-event", event.key);
                assertNull(event.data);
                assertEquals(5.5, event.metricValue, 0);
            }
        }
    }

    @Test
    public void testTrackMetricOfNullData() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.trackMetric("test-event", LDValue.ofNull(), 5.5);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof CustomEvent);
                CustomEvent event = (CustomEvent) events[1];
                assertEquals("userKey", event.userKey);
                assertEquals("test-event", event.key);
                assertNull(event.data);
                assertEquals(5.5, event.metricValue, 0);
            }
        }
    }

    @Test
    public void testTrackMetricNull() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.track("test-event");
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof CustomEvent);
                CustomEvent event = (CustomEvent) events[1];
                assertEquals("userKey", event.userKey);
                assertEquals("test-event", event.key);
                assertNull(event.data);
                assertNull(event.metricValue);
            }
        }
    }

    @Test
    public void testTrackDataAndMetric() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                LDValue testData = LDValue.of(10);

                client.trackMetric("test-event", testData, -10.0);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof CustomEvent);
                CustomEvent event = (CustomEvent) events[1];
                assertEquals("userKey", event.userKey);
                assertEquals("test-event", event.key);
                assertEquals(-10.0, event.metricValue, 0F);
            }
        }
    }

    @Test
    public void testTrackDataAndMetricValue() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                LDValue testVal = new ObjectBuilder()
                        .put("data", LDValue.of(10))
                        .build();

                client.trackMetric("test-event", testVal, -10.0);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof CustomEvent);
                CustomEvent event = (CustomEvent) events[1];
                assertEquals("userKey", event.userKey);
                assertEquals("test-event", event.key);
                assertEquals(testVal, event.data);
                assertEquals(-10.0, event.metricValue, 0F);
            }
        }
    }

    @Test
    public void testExplicitAlias() throws IOException, InterruptedException {
        LDUser identifyUser = new LDUser.Builder("ident").anonymous(true).build();
        LDUser aliasUser = new LDUser.Builder("alias").anonymous(true).build();

        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).autoAliasingOptOut(true).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.identify(identifyUser);
                client.alias(aliasUser, identifyUser);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 3);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof IdentifyEvent);
                assertTrue(events[2] instanceof AliasEvent);
                IdentifyEvent identifyEvent = (IdentifyEvent) events[1];
                AliasEvent aliasEvent = (AliasEvent) events[2];
                assertEquals(identifyEvent.key, "ident");
                assertEquals(aliasEvent.key, "alias");
                assertEquals(aliasEvent.contextKind, "anonymousUser");
                assertEquals(aliasEvent.previousKey, "ident");
                assertEquals(aliasEvent.previousContextKind, "anonymousUser");
            }
        }
    }

    @Test
    public void testAutoAlias() throws IOException, InterruptedException {
        LDUser initialUser = new LDUser.Builder("init").anonymous(true).build();

        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            try (LDClient client = LDClient.init(application, ldConfig, initialUser, 0)) {
                client.identify(ldUser);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 3);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof AliasEvent);
                assertTrue(events[2] instanceof IdentifyEvent);
                AliasEvent aliasEvent = (AliasEvent) events[1];
                assertEquals(aliasEvent.key, "userKey");
                assertEquals(aliasEvent.contextKind, "user");
                assertEquals(aliasEvent.previousKey, "init");
                assertEquals(aliasEvent.previousContextKind, "anonymousUser");
            }
        }
    }

    @Test
    public void eventIncludesPayloadId() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.blockingFlush();
            }

            RecordedRequest r = mockEventsServer.takeRequest();
            String headerVal = r.getHeader("X-LaunchDarkly-Payload-ID");
            assertNotNull(headerVal);
            // Throws if invalid UUID
            assertNotNull(UUID.fromString(headerVal));
        }
    }

    @Test
    public void eventPayloadIdDiffersBetweenRequests() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.blockingFlush();
                client.identify(ldUser);
                client.blockingFlush();
            }

            String firstPayloadId = mockEventsServer.takeRequest().getHeader("X-LaunchDarkly-Payload-ID");
            String secondPayloadId = mockEventsServer.takeRequest().getHeader("X-LaunchDarkly-Payload-ID");
            assertNotEquals(firstPayloadId, secondPayloadId);
        }
    }

    @Test
    public void eventPayloadIdSameOnRetry() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a failure followed by successful response
            mockEventsServer.enqueue(new MockResponse().setResponseCode(429));
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.blockingFlush();
            }

            String initialPayloadId = mockEventsServer.takeRequest(0, TimeUnit.SECONDS).getHeader("X-LaunchDarkly-Payload-ID");
            String retryPayloadId = mockEventsServer.takeRequest(0, TimeUnit.SECONDS).getHeader("X-LaunchDarkly-Payload-ID");
            assertEquals(initialPayloadId, retryPayloadId);
        }
    }

    @Test
    public void variationFlagTrackReasonGeneratesEventWithReason() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();

            // Setup flag store with test flag
            TestUtil.markMigrationComplete(application);
            EvaluationReason testReason = EvaluationReason.off();
            FlagStore flagStore = new SharedPrefsFlagStoreFactory(application, LDLogger.none()).createFlagStore(mobileKey + DefaultUserManager.sharedPrefs(ldUser));
            flagStore.applyFlagUpdate(new FlagBuilder("track-reason-flag").version(10).trackEvents(true).trackReason(true).reason(testReason).build());

            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.boolVariation("track-reason-flag", false);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 3);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof FeatureRequestEvent);
                FeatureRequestEvent event = (FeatureRequestEvent) events[1];
                assertEquals("track-reason-flag", event.key);
                assertEquals("userKey", event.userKey);
                assertNull(event.variation);
                assertEquals(Integer.valueOf(10), event.version);
                assertFalse(event.value.booleanValue());
                assertFalse(event.defaultVal.booleanValue());
                assertEquals(testReason, event.reason);
                assertTrue(events[2] instanceof SummaryEvent);
            }
        }
    }

    @Test
    public void additionalHeadersIncludedInEventsRequest() throws IOException, InterruptedException {
        try (MockWebServer mockEventsServer = new MockWebServer()) {
            mockEventsServer.start();
            // Enqueue a successful empty response
            mockEventsServer.enqueue(new MockResponse());

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).headerTransform(headers -> {
                headers.put("Proxy-Authorization", "token");
                headers.put("Authorization", "foo");
            }).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
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
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.identify(ldUser);
                LDValue testData = LDValue.of("xyz");
                client.trackData("test-event", testData);
                client.blockingFlush();

                // Verify that only the first event was sent and other events were dropped
                Event[] events = getEventsFromLastRequest(mockEventsServer, 1);
                assertTrue(events[0] instanceof IdentifyEvent);
            }
        }
    }

    private Event[] getEventsFromLastRequest(MockWebServer server, int expectedCount) throws InterruptedException {
        RecordedRequest r = server.takeRequest();
        assertEquals("POST", r.getMethod());
        assertEquals("/mobile", r.getPath());
        assertEquals(LDConfig.AUTH_SCHEME + mobileKey, r.getHeader("Authorization"));
        String body = r.getBody().readUtf8();
        System.out.println(body);
        Event[] events = TestUtil.getEventDeserializerGson().fromJson(body, Event[].class);
        if (events.length != expectedCount) {
            fail("count should be " + expectedCount + " for: " + body);
        }
        return events;
    }

    private LDConfig.Builder baseConfigBuilder(MockWebServer server) {
        HttpUrl baseUrl = server.url("/");
        return new LDConfig.Builder()
                .mobileKey(mobileKey)
                .diagnosticOptOut(true)
                .eventsUri(Uri.parse(baseUrl.toString()));
    }
}
