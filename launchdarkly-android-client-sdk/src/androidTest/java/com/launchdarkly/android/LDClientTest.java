package com.launchdarkly.android;

import android.app.Application;
import android.net.Uri;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.launchdarkly.android.test.TestActivity;

import org.junit.Before;
import org.junit.Rule;
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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;

@RunWith(AndroidJUnit4.class)
public class LDClientTest {

    private static final String mobileKey = "test-mobile-key";

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    private Application application;
    private LDClient ldClient;
    private Future<LDClient> ldClientFuture;
    private LDConfig ldConfig;
    private LDUser ldUser;

    @Before
    public void setUp() {
        application = activityTestRule.getActivity().getApplication();
        ldConfig = new LDConfig.Builder()
                .setOffline(true)
                .build();

        ldUser = new LDUser.Builder("userKey").build();
    }

    @Test
    public void testOfflineClientReturnsFallbacks() {
        ldClient = LDClient.init(application, ldConfig, ldUser, 1);

        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());

        assertTrue(ldClient.boolVariation("boolFlag", true));
        assertEquals(1.0F, ldClient.floatVariation("floatFlag", 1.0F));
        assertEquals(Integer.valueOf(1), ldClient.intVariation("intFlag", 1));
        assertEquals("fallback", ldClient.stringVariation("stringFlag", "fallback"));

        JsonObject expectedJson = new JsonObject();
        expectedJson.addProperty("field", "value");
        assertEquals(expectedJson, ldClient.jsonVariation("jsonFlag", expectedJson));
    }

    @Test
    public void givenFallbacksAreNullAndTestOfflineClientReturnsFallbacks() {
        ldClient = LDClient.init(application, ldConfig, ldUser, 1);

        assertTrue(ldClient.isInitialized());
        assertTrue(ldClient.isOffline());
        assertNull(ldClient.jsonVariation("jsonFlag", null));

        assertNull(ldClient.boolVariation("boolFlag", null));
        assertNull(ldClient.floatVariation("floatFlag", null));
        assertNull(ldClient.intVariation("intFlag", null));
        assertNull(ldClient.stringVariation("stringFlag", null));
    }

    @Test
    public void testInitMissingApplication() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        //noinspection ConstantConditions
        ldClientFuture = LDClient.init(null, ldConfig, ldUser);

        try {
            ldClientFuture.get();
        } catch (InterruptedException e) {
            fail();
        } catch (ExecutionException e) {
            actualFutureException = e;
            actualProvidedException = (LaunchDarklyException) e.getCause();
        }

        assertThat(actualFutureException, instanceOf(ExecutionException.class));
        assertThat(actualProvidedException, instanceOf(LaunchDarklyException.class));
        assertTrue("No future task to run", ldClientFuture.isDone());
    }

    @Test
    public void testInitMissingConfig() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        //noinspection ConstantConditions
        ldClientFuture = LDClient.init(application, null, ldUser);

        try {
            ldClientFuture.get();
        } catch (InterruptedException e) {
            fail();
        } catch (ExecutionException e) {
            actualFutureException = e;
            actualProvidedException = (LaunchDarklyException) e.getCause();
        }

        assertThat(actualFutureException, instanceOf(ExecutionException.class));
        assertThat(actualProvidedException, instanceOf(LaunchDarklyException.class));
        assertTrue("No future task to run", ldClientFuture.isDone());
    }

    @Test
    public void testInitMissingUser() {
        ExecutionException actualFutureException = null;
        LaunchDarklyException actualProvidedException = null;

        //noinspection ConstantConditions
        ldClientFuture = LDClient.init(application, ldConfig, null);

        try {
            ldClientFuture.get();
        } catch (InterruptedException e) {
            fail();
        } catch (ExecutionException e) {
            actualFutureException = e;
            actualProvidedException = (LaunchDarklyException) e.getCause();
        }

        assertThat(actualFutureException, instanceOf(ExecutionException.class));
        assertThat(actualProvidedException, instanceOf(LaunchDarklyException.class));
        assertTrue("No future task to run", ldClientFuture.isDone());
    }

    @Test
    public void testDoubleClose() throws IOException {
        ldClient = LDClient.init(application, ldConfig, ldUser, 1);
        ldClient.close();
        ldClient.close();
    }

    @Test
    public void testInitBackgroundThread() throws ExecutionException, InterruptedException {
        Future<?> backgroundComplete =
                new BackgroundThreadExecutor().newFixedThreadPool(1).submit(new Runnable() {
            @Override
            public void run() {
                try {
                    ldClient = LDClient.init(application, ldConfig, ldUser).get();
                } catch (Exception e) {
                    fail();
                }
                assertTrue(ldClient.isInitialized());
                assertTrue(ldClient.isOffline());

                assertTrue(ldClient.boolVariation("boolFlag", true));
                assertEquals(1.0F, ldClient.floatVariation("floatFlag", 1.0F));
                assertEquals(Integer.valueOf(1), ldClient.intVariation("intFlag", 1));
                assertEquals("fallback", ldClient.stringVariation("stringFlag", "fallback"));

                JsonObject expectedJson = new JsonObject();
                expectedJson.addProperty("field", "value");
                assertEquals(expectedJson, ldClient.jsonVariation("jsonFlag", expectedJson));
            }
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
            assertEquals(2, events.length);
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
                JsonPrimitive testData = new JsonPrimitive("abc");

                client.track("test-event", testData);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertEquals(2, events.length);
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
                client.track("test-event", null);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertEquals(2, events.length);
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
                client.track("test-event", null, 5.5);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertEquals(2, events.length);
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
                client.track("test-event", null, null);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertEquals(2, events.length);
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
                JsonObject testData = new JsonObject();
                testData.add("data", new JsonPrimitive(10));

                client.track("test-event", testData, -10.0);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 2);
                assertEquals(2, events.length);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof CustomEvent);
                CustomEvent event = (CustomEvent) events[1];
                assertEquals("userKey", event.userKey);
                assertEquals("test-event", event.key);
                assertEquals(testData, event.data);
                assertEquals(-10.0, event.metricValue);
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

            LDConfig ldConfig = baseConfigBuilder(mockEventsServer).build();
            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.blockingFlush();
                client.identify(ldUser);
                client.blockingFlush();
            }

            String firstPayloadId = mockEventsServer.takeRequest().getHeader("X-LaunchDarkly-Payload-ID");
            String secondPayloadId = mockEventsServer.takeRequest().getHeader("X-LaunchDarkly-Payload-ID");
            assertFalse(firstPayloadId.equals(secondPayloadId));
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
            assertTrue(initialPayloadId.equals(retryPayloadId));
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
            FlagStore flagStore = new SharedPrefsFlagStoreFactory(application).createFlagStore(mobileKey + ldUser.getSharedPrefsKey());
            flagStore.applyFlagUpdate(new FlagBuilder("track-reason-flag").trackEvents(true).trackReason(true).reason(testReason).build());

            try (LDClient client = LDClient.init(application, ldConfig, ldUser, 0)) {
                client.boolVariation("track-reason-flag", false);
                client.blockingFlush();

                Event[] events = getEventsFromLastRequest(mockEventsServer, 3);
                assertEquals(3, events.length);
                assertTrue(events[0] instanceof IdentifyEvent);
                assertTrue(events[1] instanceof FeatureRequestEvent);
                FeatureRequestEvent event = (FeatureRequestEvent) events[1];
                assertEquals("track-reason-flag", event.key);
                assertEquals("userKey", event.userKey);
                assertNull(event.variation);
                assertNull(event.version);
                assertFalse(event.value.getAsBoolean());
                assertFalse(event.defaultVal.getAsBoolean());
                assertEquals(testReason, event.reason);
                assertTrue(events[2] instanceof SummaryEvent);
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
            assertTrue("count should be " + expectedCount + " for: " + body, false);
        }
        return events;
    }

    private LDConfig.Builder baseConfigBuilder(MockWebServer server) {
        HttpUrl baseUrl = server.url("/mobile");
        return new LDConfig.Builder()
            .setMobileKey(mobileKey)
            .setEventsUri(Uri.parse(baseUrl.toString()));
    }
}
