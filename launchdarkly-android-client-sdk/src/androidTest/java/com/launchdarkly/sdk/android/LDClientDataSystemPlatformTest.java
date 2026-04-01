package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.integrations.AutomaticModeSwitchingConfig;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.URI;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * FDv2 data-system tests that depend on platform signals (activity lifecycle,
 * connectivity) and {@link MockWebServer} request patterns.
 * <p>
 * Lifecycle transitions are simulated via a {@link ControllableTestApplication} that
 * implements {@link AndroidPlatformState.TestApplicationForegroundStateOverride} and
 * directly invokes the captured {@link Application.ActivityLifecycleCallbacks}. This
 * avoids the problems of real backgrounding (process freezing on Android 12+) and
 * {@code ActivityScenario.moveToState} (the internal {@code EmptyActivity} prevents the
 * SDK from detecting a background transition).
 * <p>
 * Detailed FDv2 mode resolution with a mocked {@link PlatformState} lives in JVM tests
 * (for example {@code ConnectivityManagerTest} FDv2 sections).
 */
@RunWith(AndroidJUnit4.class)
public class LDClientDataSystemPlatformTest {

    private static final String MOBILE_KEY = "test-mobile-key";
    private static final LDContext CONTEXT = LDContext.create("context");

    /** Matches {@link AndroidPlatformState} debounce (500 ms) plus a small buffer. */
    private static final int AFTER_PAUSE_WAIT_MS = 600;

    /** How long to wait when asserting that no new polls occurred. */
    private static final int NO_ACTIVITY_WAIT_MS = 500;

    private ControllableTestApplication testApp;
    private MockWebServer mockPollingServer;
    private URI mockPollingServerUri;
    private PersistentDataStore store;

    @Rule
    public AndroidLoggingRule logging = new AndroidLoggingRule();

    @Before
    public void setUp() throws IOException {
        Application realApp = ApplicationProvider.getApplicationContext();
        testApp = new ControllableTestApplication(true, realApp);

        mockPollingServer = new MockWebServer();
        mockPollingServer.start();
        mockPollingServerUri = mockPollingServer.url("/").uri();

        store = new InMemoryPersistentDataStore();
    }

    @After
    public void after() throws IOException {
        mockPollingServer.close();
    }

    private LDConfig.Builder baseConfig() {
        return new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey(MOBILE_KEY)
                .persistentDataStore(store)
                .diagnosticOptOut(true)
                .events(Components.noEvents())
                .logAdapter(logging.logAdapter)
                .loggerName(logging.loggerName)
                .logLevel(LDLogLevel.DEBUG);
    }

    private static void enqueueManyFdv2PollResponses(MockWebServer server, Flag flag, int count) {
        String body = FDv2TestResponses.pollResponseBody(flag);
        for (int i = 0; i < count; i++) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody(body));
        }
    }

    // ---- Lifecycle tests (use ControllableTestApplication) ----

    @Test
    public void lifecycleSwitchStopsFdV2PollingInEmptyBackgroundModeAndResumesOnForeground()
            throws Exception {
        Flag flag = new FlagBuilder("flag-key").version(1).value(LDValue.of("v")).build();
        enqueueManyFdv2PollResponses(mockPollingServer, flag, 64);

        LDConfig config = baseConfig()
                .dataSystem(
                        Components.dataSystem()
                                .foregroundConnectionMode(ConnectionMode.POLLING)
                                .customizeConnectionMode(
                                        ConnectionMode.BACKGROUND,
                                        DataSystemComponents.customMode()
                                                .initializers()
                                                .synchronizers()))
                .serviceEndpoints(Components.serviceEndpoints().polling(mockPollingServerUri))
                .build();

        try (LDClient client = LDClient.init(testApp, config, CONTEXT, 5)) {
            assertTrue(client.isInitialized());

            int afterInit = mockPollingServer.getRequestCount();
            assertTrue("expected at least one FDv2 poll", afterInit >= 1);

            testApp.moveToBackground();
            SystemClock.sleep(AFTER_PAUSE_WAIT_MS);

            int afterBackground = mockPollingServer.getRequestCount();
            SystemClock.sleep(NO_ACTIVITY_WAIT_MS);
            assertEquals(
                    "empty background mode should not issue further polls",
                    afterBackground,
                    mockPollingServer.getRequestCount());

            testApp.moveToForeground();

            assertTrue(
                    "foreground polling should resume HTTP after background empty mode",
                    waitForRequestCountAbove(mockPollingServer, afterBackground, 2_000));
        }
    }

    @Test
    public void networkLossStopsFdV2PollingAndReconnectsWhenNetworkReturns() throws Exception {
        Flag flag = new FlagBuilder("flag-key").version(1).value(LDValue.of("v")).build();
        enqueueManyFdv2PollResponses(mockPollingServer, flag, 64);

        LDConfig config = baseConfig()
                .dataSystem(Components.dataSystem().foregroundConnectionMode(ConnectionMode.POLLING))
                .serviceEndpoints(Components.serviceEndpoints().polling(mockPollingServerUri))
                .build();

        try (LDClient client = LDClient.init(testApp, config, CONTEXT, 5)) {
            assertTrue(client.isInitialized());
            int afterInit = mockPollingServer.getRequestCount();
            assertTrue(afterInit >= 1);

            testApp.setNetworkAvailable(false);
            SystemClock.sleep(200);

            int whileOffline = mockPollingServer.getRequestCount();
            SystemClock.sleep(NO_ACTIVITY_WAIT_MS);
            assertEquals(
                    "offline FDv2 mode should not poll",
                    whileOffline,
                    mockPollingServer.getRequestCount());

            testApp.setNetworkAvailable(true);

            assertTrue(
                    "polling should resume after network returns",
                    waitForRequestCountAbove(mockPollingServer, whileOffline, 2_000));
        }
    }

    @Test
    public void automaticModeSwitchingEnabled_offlineInBackgroundReconnectsOnForeground() throws Exception {
        Flag flag = new FlagBuilder("flag-key").version(1).value(LDValue.of("v")).build();
        enqueueManyFdv2PollResponses(mockPollingServer, flag, 64);

        LDConfig config = baseConfig()
                .disableBackgroundUpdating(true)
                .dataSystem(Components.dataSystem().foregroundConnectionMode(ConnectionMode.POLLING))
                .serviceEndpoints(Components.serviceEndpoints().polling(mockPollingServerUri))
                .build();

        try (LDClient client = LDClient.init(testApp, config, CONTEXT, 5)) {
            assertTrue(client.isInitialized());
            int afterInit = mockPollingServer.getRequestCount();
            assertTrue(afterInit >= 1);

            testApp.moveToBackground();
            SystemClock.sleep(AFTER_PAUSE_WAIT_MS);

            int afterBackground = mockPollingServer.getRequestCount();
            SystemClock.sleep(NO_ACTIVITY_WAIT_MS);
            assertEquals(afterBackground, mockPollingServer.getRequestCount());

            testApp.moveToForeground();

            assertTrue(
                    "foreground should issue a new poll after offline background",
                    waitForRequestCountAbove(mockPollingServer, afterBackground, 2_000));
        }
    }

    @Test
    public void automaticModeSwitchingDisabled_doesNotRebuildOnLifecycle() throws Exception {
        Flag flag = new FlagBuilder("flag-key").version(1).value(LDValue.of("v")).build();
        enqueueManyFdv2PollResponses(mockPollingServer, flag, 64);

        LDConfig config = baseConfig()
                .disableBackgroundUpdating(true)
                .dataSystem(
                        Components.dataSystem()
                                .foregroundConnectionMode(ConnectionMode.POLLING)
                                .automaticModeSwitching(AutomaticModeSwitchingConfig.disabled()))
                .serviceEndpoints(Components.serviceEndpoints().polling(mockPollingServerUri))
                .build();

        try (LDClient client = LDClient.init(testApp, config, CONTEXT, 5)) {
            assertTrue(client.isInitialized());
            int afterInit = mockPollingServer.getRequestCount();
            assertTrue(afterInit >= 1);

            testApp.moveToBackground();
            SystemClock.sleep(AFTER_PAUSE_WAIT_MS);
            testApp.moveToForeground();
            SystemClock.sleep(NO_ACTIVITY_WAIT_MS);

            assertEquals(
                    "automatic mode switching disabled: no extra FDv2 polls from lifecycle",
                    afterInit,
                    mockPollingServer.getRequestCount());
        }
    }

    @Test
    public void automaticModeSwitchingLifecycleOff_doesNotRebuildOnBackground() throws Exception {
        Flag flag = new FlagBuilder("flag-key").version(1).value(LDValue.of("v")).build();
        enqueueManyFdv2PollResponses(mockPollingServer, flag, 64);

        LDConfig config = baseConfig()
                .disableBackgroundUpdating(true)
                .dataSystem(
                        Components.dataSystem()
                                .foregroundConnectionMode(ConnectionMode.POLLING)
                                .automaticModeSwitching(
                                        DataSystemComponents.automaticModeSwitching()
                                                .lifecycle(false)
                                                .network(true)
                                                .build()))
                .serviceEndpoints(Components.serviceEndpoints().polling(mockPollingServerUri))
                .build();

        try (LDClient client = LDClient.init(testApp, config, CONTEXT, 5)) {
            assertTrue(client.isInitialized());
            int afterInit = mockPollingServer.getRequestCount();
            assertTrue(afterInit >= 1);

            testApp.moveToBackground();
            SystemClock.sleep(AFTER_PAUSE_WAIT_MS);
            testApp.moveToForeground();
            SystemClock.sleep(NO_ACTIVITY_WAIT_MS);

            assertEquals(
                    "lifecycle automatic switching disabled: no extra polls from background/foreground",
                    afterInit,
                    mockPollingServer.getRequestCount());
        }
    }

    // ---- Helpers ----

    /**
     * Polls until the server's request count exceeds {@code baseline}, or the timeout expires.
     */
    private static boolean waitForRequestCountAbove(MockWebServer server, int baseline, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (server.getRequestCount() <= baseline) {
            if (SystemClock.uptimeMillis() >= deadline) {
                return false;
            }
            SystemClock.sleep(100);
        }
        return true;
    }

    // ---- Test Application for lifecycle and network control ----

    /**
     * An {@link Application} subclass that delegates to a real application context but
     * allows tests to programmatically simulate foreground/background transitions and
     * network availability changes.
     */
    static class ControllableTestApplication extends Application
            implements AndroidPlatformState.TestApplicationForegroundStateOverride,
                       AndroidPlatformState.TestNetworkOverride {

        private volatile boolean inForeground;
        private volatile boolean networkAvailable = true;
        private volatile ActivityLifecycleCallbacks sdkCallbacks;
        private volatile BroadcastReceiver connectivityReceiver;

        ControllableTestApplication(boolean startInForeground, Context realContext) {
            this.inForeground = startInForeground;
            attachBaseContext(realContext);
        }

        @Override
        public boolean isTestFixtureInitiallyInForeground() {
            return inForeground;
        }

        @Override
        public boolean isTestFixtureNetworkAvailable() {
            return networkAvailable;
        }

        @Override
        public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
            sdkCallbacks = callback;
            if (inForeground) {
                callback.onActivityResumed(null);
            } else {
                callback.onActivityPaused(null);
            }
        }

        @Override
        public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
            if (sdkCallbacks == callback) {
                sdkCallbacks = null;
            }
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            if (filter != null && filter.hasAction("android.net.conn.CONNECTIVITY_CHANGE")) {
                connectivityReceiver = receiver;
            }
            return null;
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
            return registerReceiver(receiver, filter);
        }

        @Override
        public void unregisterReceiver(BroadcastReceiver receiver) {
            if (receiver == connectivityReceiver) {
                connectivityReceiver = null;
            }
        }

        void moveToForeground() {
            inForeground = true;
            if (sdkCallbacks != null) {
                sdkCallbacks.onActivityResumed(null);
            }
        }

        void moveToBackground() {
            inForeground = false;
            if (sdkCallbacks != null) {
                sdkCallbacks.onActivityPaused(null);
            }
        }

        void setNetworkAvailable(boolean available) {
            networkAvailable = available;
            if (connectivityReceiver != null) {
                Intent intent = new Intent("android.net.conn.CONNECTIVITY_CHANGE");
                connectivityReceiver.onReceive(this, intent);
            }
        }
    }
}
