package com.launchdarkly.android;

import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ApplicationProvider;
import com.launchdarkly.android.test.TestActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.HashMap;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static junit.framework.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class DiagnosticEventProcessorTest {

    private MockWebServer mockEventsServer;

    @Before
    public void before() throws IOException {
        NetworkTestController.setup(ApplicationProvider.getApplicationContext());
        mockEventsServer = new MockWebServer();
        mockEventsServer.start();
    }

    @After
    public void after() throws InterruptedException, IOException {
        NetworkTestController.enableNetwork();
        mockEventsServer.close();
    }
  
    @Test
    public void defaultDiagnosticRequest() throws InterruptedException {
        // Setup in background to prevent initial diagnostic event
        ForegroundTestController.setup(false);
        OkHttpClient okHttpClient = new OkHttpClient.Builder().build();
        LDConfig ldConfig = new LDConfig.Builder()
                .mobileKey("test-mobile-key")
                .eventsUri(Uri.parse(mockEventsServer.url("").toString()))
                .build();
        DiagnosticStore diagnosticStore = new DiagnosticStore(ApplicationProvider.getApplicationContext(), "test-mobile-key");
        DiagnosticEventProcessor diagnosticEventProcessor = new DiagnosticEventProcessor(ldConfig, "default", diagnosticStore, ApplicationProvider.getApplicationContext(), okHttpClient);

        DiagnosticEvent testEvent = new DiagnosticEvent("test-kind", System.currentTimeMillis(), diagnosticStore.getDiagnosticId());

        mockEventsServer.enqueue(new MockResponse());
        diagnosticEventProcessor.sendDiagnosticEventSync(testEvent);
        RecordedRequest r = mockEventsServer.takeRequest();
        assertEquals("POST", r.getMethod());
        assertEquals("/mobile/events/diagnostic", r.getPath());
        assertEquals("api_key test-mobile-key", r.getHeader("Authorization"));
        assertEquals("AndroidClient/" + BuildConfig.VERSION_NAME, r.getHeader("User-Agent"));
        assertEquals("application/json; charset=utf-8", r.getHeader("Content-Type"));
        assertEquals(GsonCache.getGson().toJson(testEvent), r.getBody().readUtf8());
    }

    @Test
    public void defaultDiagnosticRequestIncludingWrapper() throws InterruptedException {
        // Setup in background to prevent initial diagnostic event
        ForegroundTestController.setup(false);
        OkHttpClient okHttpClient = new OkHttpClient.Builder().build();
        LDConfig ldConfig = new LDConfig.Builder()
                .mobileKey("test-mobile-key")
                .eventsUri(Uri.parse(mockEventsServer.url("").toString()))
                .wrapperName("ReactNative")
                .wrapperVersion("1.0.0")
                .build();
        DiagnosticStore diagnosticStore = new DiagnosticStore(ApplicationProvider.getApplicationContext(), "test-mobile-key");
        DiagnosticEventProcessor diagnosticEventProcessor = new DiagnosticEventProcessor(ldConfig, "default", diagnosticStore, ApplicationProvider.getApplicationContext(), okHttpClient);

        DiagnosticEvent testEvent = new DiagnosticEvent("test-kind", System.currentTimeMillis(), diagnosticStore.getDiagnosticId());

        mockEventsServer.enqueue(new MockResponse());
        diagnosticEventProcessor.sendDiagnosticEventSync(testEvent);
        RecordedRequest r = mockEventsServer.takeRequest();
        assertEquals("POST", r.getMethod());
        assertEquals("/mobile/events/diagnostic", r.getPath());
        assertEquals("ReactNative/1.0.0", r.getHeader("X-LaunchDarkly-Wrapper"));
        assertEquals(GsonCache.getGson().toJson(testEvent), r.getBody().readUtf8());
    }

    @Test
    public void defaultDiagnosticRequestIncludingAdditionalHeaders() throws InterruptedException {
        // Setup in background to prevent initial diagnostic event
        ForegroundTestController.setup(false);
        OkHttpClient okHttpClient = new OkHttpClient.Builder().build();

        HashMap<String, String> additionalHeaders = new HashMap<>();
        additionalHeaders.put("Proxy-Authorization", "token");
        additionalHeaders.put("Authorization", "foo");
        LDConfig ldConfig = new LDConfig.Builder()
                .mobileKey("test-mobile-key")
                .eventsUri(Uri.parse(mockEventsServer.url("").toString()))
                .additionalHeaders(additionalHeaders)
                .build();
        DiagnosticStore diagnosticStore = new DiagnosticStore(ApplicationProvider.getApplicationContext(), "test-mobile-key");
        DiagnosticEventProcessor diagnosticEventProcessor = new DiagnosticEventProcessor(ldConfig, "default", diagnosticStore, ApplicationProvider.getApplicationContext(), okHttpClient);

        DiagnosticEvent testEvent = new DiagnosticEvent("test-kind", System.currentTimeMillis(), diagnosticStore.getDiagnosticId());

        mockEventsServer.enqueue(new MockResponse());
        diagnosticEventProcessor.sendDiagnosticEventSync(testEvent);
        RecordedRequest r = mockEventsServer.takeRequest();
        assertEquals("POST", r.getMethod());
        assertEquals("/mobile/events/diagnostic", r.getPath());
        assertEquals("token", r.getHeader("Proxy-Authorization"));
        assertEquals("foo", r.getHeader("Authorization"));
        assertEquals(GsonCache.getGson().toJson(testEvent), r.getBody().readUtf8());
    }
}
