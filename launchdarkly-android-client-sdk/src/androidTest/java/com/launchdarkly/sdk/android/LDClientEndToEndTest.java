package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

@RunWith(AndroidJUnit4.class)
public class LDClientEndToEndTest {
    private static final String MOBILE_KEY = "test-mobile-key";
    private static final LDContext CONTEXT = LDContext.create("context");

    private Application application;
    private MockWebServer mockPollingServer;
    private URI mockPollingServerUri;
    private final PersistentDataStore store = new InMemoryPersistentDataStore();

    @Rule
    public final ActivityScenarioRule<TestActivity> testScenario =
            new ActivityScenarioRule<>(TestActivity.class); // see setUp()

    @Rule
    public AndroidLoggingRule logging = new AndroidLoggingRule();

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();

        AndroidTestUtil.doSynchronouslyOnMainThreadForTestScenario(testScenario,
                // Not 100% sure we still need to defer this piece of initialization onto another
                // thread, but we had problems in the past - see comments in TestUtil
                act -> {
                    mockPollingServer = new MockWebServer();
                    try {
                        mockPollingServer.start();
                    } catch (IOException err) {
                        throw new RuntimeException(err);
                    }
                    mockPollingServerUri = mockPollingServer.url("/").uri();
                });
    }

    @After
    public void after() throws IOException {
        mockPollingServer.close();
        testScenario.getScenario().close();
    }

    private LDConfig.Builder baseConfig() {
        return new LDConfig.Builder()
                .mobileKey(MOBILE_KEY)
                .persistentDataStore(store)
                .diagnosticOptOut(true)
                .events(Components.noEvents())
                .logAdapter(logging.logAdapter)
                .loggerName(logging.loggerName)
                .logLevel(LDLogLevel.DEBUG);
    }

    @Test
    public void clientStartsInPollingModeWithoutStoredFlags() throws Exception {
        String flagKey = "flag-key", flagValue = "good-value";
        Flag flag = new FlagBuilder(flagKey).version(1).value(LDValue.of(flagValue)).build();
        EnvironmentData data = new DataSetBuilder().add(flag).build();
        mockPollingServer.enqueue(new MockResponse().setResponseCode(200).setBody(data.toJson()));

        LDConfig config = baseConfig()
                .dataSource(Components.pollingDataSource())
                .serviceEndpoints(Components.serviceEndpoints().polling(mockPollingServerUri))
                .build();

        try (LDClient client = LDClient.init(application, config, CONTEXT, 30)) {
            assertTrue("client was not initialized", client.isInitialized());
            assertFalse("client was offline", client.isOffline());

            assertEquals(flagValue, client.stringVariation(flagKey, "default"));
        }
    }

    @Test
    public void clientUsesStoredFlagsIfInitializationFailsInPollingMode() throws Exception {
        String flagKey = "flag-key", flagValue = "stored-value";
        Flag flag = new FlagBuilder(flagKey).version(1).value(LDValue.of(flagValue)).build();
        TestUtil.writeFlagUpdateToStore(store, MOBILE_KEY, CONTEXT, flag);

        mockPollingServer.enqueue(new MockResponse().setResponseCode(401));

        LDConfig config = baseConfig()
                .dataSource(Components.pollingDataSource())
                .serviceEndpoints(Components.serviceEndpoints().polling(mockPollingServerUri))
                .build();

        try (LDClient client = LDClient.init(application, config, CONTEXT, 1)) {
            assertFalse("client should not have been initialized", client.isInitialized());
            assertFalse("client was offline", client.isOffline());

            assertEquals(flagValue, client.stringVariation(flagKey, "default"));
        }
    }

    @Test
    public void clientUsesStoredFlagsIfInitializationTimesOutInPollingMode() throws Exception {
        String flagKey = "flag-key", flagValue = "stored-value";
        Flag flag = new FlagBuilder(flagKey).version(1).value(LDValue.of(flagValue)).build();
        TestUtil.writeFlagUpdateToStore(store, MOBILE_KEY, CONTEXT, flag);

        mockPollingServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}")
                .setBodyDelay(1, TimeUnit.SECONDS));

        LDConfig config = baseConfig()
                .dataSource(Components.pollingDataSource())
                .serviceEndpoints(Components.serviceEndpoints().polling(mockPollingServerUri))
                .build();

        try (LDClient client = LDClient.init(application, config, CONTEXT, 0)) {
            assertFalse("client should not have been initialized", client.isInitialized());
            assertFalse("client was offline", client.isOffline());

            assertEquals(flagValue, client.stringVariation(flagKey, "default"));
        }
    }
}
