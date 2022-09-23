package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.Context;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.StrictMode;
import android.os.StrictMode.ThreadPolicy;
import android.os.StrictMode.VmPolicy;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.ConnectionInformation.ConnectionMode;
import com.launchdarkly.sdk.internal.events.EventProcessor;

import org.easymock.Capture;
import org.easymock.EasyMockRule;
import org.easymock.EasyMockSupport;
import org.easymock.Mock;
import org.easymock.MockType;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockWebServer;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.reset;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class ConnectivityManagerTest extends EasyMockSupport {
    // These tests use a mock PlatformState instead of AndroidPlatformState, so that we can test
    // the ConnectivityManager logic for how to behave under various network/foreground/background
    // conditions separately from the implementation details of how we detect those conditions.

    private static final LDContext CONTEXT = LDContext.create("test-context");
    private static final String MOBILE_KEY = "test-mobile-key";

    @Rule
    public final ActivityScenarioRule<TestActivity> testScenario =
            new ActivityScenarioRule<>(TestActivity.class);

    @Rule
    public AndroidLoggingRule logging = new AndroidLoggingRule();
    @Rule
    public EasyMockRule easyMockRule = new EasyMockRule(this);
    @Rule
    public Timeout globalTimeout = Timeout.seconds(20);

    @SuppressWarnings("unused")
    @Mock(MockType.STRICT)
    private EventProcessor eventProcessor;

    private AndroidTaskExecutor taskExecutor;
    private final MockPlatformState mockPlatformState = new MockPlatformState();
    private final Application application = ApplicationProvider.getApplicationContext();

    @SuppressWarnings("unused")
    @Mock(MockType.STRICT)
    private FeatureFetcher fetcher;

    private PersistentDataStoreWrapper.PerEnvironmentData environmentStore;
    private ContextDataManager contextDataManager;
    private ClientStateImpl clientState;
    private BlockingQueue<List<String>> allFlagsReceived;

    private ConnectivityManager connectivityManager;
    private MockWebServer mockStreamServer;

    static {
        StrictMode.setThreadPolicy(ThreadPolicy.LAX);
        StrictMode.setVmPolicy(VmPolicy.LAX);
    }

    @Before
    public void before() {
        environmentStore = TestUtil.makeSimplePersistentDataStoreWrapper().perEnvironmentData(MOBILE_KEY);
        taskExecutor = new AndroidTaskExecutor(application, logging.logger);

        clientState = new ClientStateImpl(
                MOBILE_KEY,
                "default",
                logging.logger,
                false
        );

        contextDataManager = new ContextDataManager(
                environmentStore,
                CONTEXT,
                1,
                taskExecutor,
                logging.logger
        );
        allFlagsReceived = new LinkedBlockingQueue<>();
        contextDataManager.registerAllFlagsListener(flagsUpdated -> {
            allFlagsReceived.add(flagsUpdated);
        });

        final Capture<LDUtil.ResultCallback<String>> callbackCapture = Capture.newInstance();
        fetcher.fetch(eq(CONTEXT), capture(callbackCapture));
        expectLastCall().andAnswer(() -> {
            callbackCapture.getValue().onSuccess("{}");
            return null;
        }).anyTimes();

        AndroidTestUtil.doSynchronouslyOnMainThreadForTestScenario(testScenario,
                // Not 100% sure we still need to defer this piece of initialization onto another
                // thread, but we had problems in the past - see comments in TestUtil
                act -> {
                    NetworkTestController.setup(act.getApplication());
                    mockStreamServer = new MockWebServer();
                    try {
                        mockStreamServer.start();
                    } catch (IOException err) {
                        throw new RuntimeException(err);
                    }
                });
    }

    @After
    public void after() throws InterruptedException, IOException {
        mockStreamServer.close();
        taskExecutor.close();
        testScenario.getScenario().close();
    }

    private void createTestManager(boolean setOffline, boolean streaming, boolean backgroundDisabled) {
        createTestManager(setOffline, streaming, backgroundDisabled, null);
    }

    private void createTestManager(boolean setOffline, boolean streaming, boolean backgroundDisabled, String streamUri) {
        LDConfig config = new LDConfig.Builder()
                .mobileKey(MOBILE_KEY)
                .offline(setOffline)
                .stream(streaming)
                .disableBackgroundUpdating(backgroundDisabled)
                .streamUri(streamUri != null ? Uri.parse(streamUri) : Uri.parse(mockStreamServer.url("/").toString()))
                .build();
        clientState.setForceOffline(setOffline);

        connectivityManager = new ConnectivityManager(
                mockPlatformState,
                clientState,
                config,
                eventProcessor,
                contextDataManager,
                fetcher,
                environmentStore,
                taskExecutor,
                null
        );
    }

    private void awaitStartUp() throws ExecutionException {
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        awaitableCallback.await();
    }

    private void awaitDataReceived() {
        try {
            List<String> flags = allFlagsReceived.poll(5, TimeUnit.SECONDS);
            if (flags == null) {
                fail("timed out waiting for flags to be received");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void assertNoConnection() {
        assertNull(connectivityManager.getConnectionInformation().getLastFailure());
        assertNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }

    @Test
    public void initSetOffline() throws ExecutionException {
        replayAll();
        createTestManager(true, false, false);

        awaitStartUp();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertTrue(clientState.isForcedOffline());
        assertEquals(ConnectionMode.SET_OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    // This test requires either Android < API 21 or mobile data to be off on device/emulator.
    @Test
    public void initDeviceOffline() throws ExecutionException, InterruptedException {
        replayAll();
        NetworkTestController.disableNetwork();
        createTestManager(false, true, false);

        awaitStartUp();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(clientState.isForcedOffline());
        assertEquals(ConnectionMode.OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    @Test
    public void initBackgroundDisabled() throws ExecutionException {
        eventProcessor.setOffline(false);
        replayAll();

        mockPlatformState.setForeground(false);

        createTestManager(false, true, true);

        awaitStartUp();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(clientState.isForcedOffline());
        assertEquals(ConnectionMode.BACKGROUND_DISABLED, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    @Test
    public void initBackgroundPolling() throws ExecutionException {
        eventProcessor.setOffline(false);
        replayAll();

        mockPlatformState.setForeground(false);

        createTestManager(false, true, false);

        awaitStartUp();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(clientState.isForcedOffline());
        assertEquals(ConnectionMode.BACKGROUND_POLLING, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    @Test
    public void initPolling() throws ExecutionException {
        eventProcessor.setOffline(false);
        replayAll();

        createTestManager(false, false, false);

        awaitStartUp();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(clientState.isForcedOffline());
        assertEquals(ConnectionMode.POLLING, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastFailure());
        assertNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNotNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }

    @Test
    public void initPollingKnownError() throws ExecutionException {
        final Throwable testError = new Throwable();
        reset(fetcher);
        final Capture<LDUtil.ResultCallback<String>> callbackCapture = Capture.newInstance();
        fetcher.fetch(eq(CONTEXT), capture(callbackCapture));
        expectLastCall().andAnswer(() -> {
            callbackCapture.getValue().onError(new LDFailure("failure", testError, LDFailure.FailureType.NETWORK_FAILURE));
            return null;
        });
        eventProcessor.setOffline(false);
        replayAll();

        createTestManager(false, false, false);

        awaitStartUp();
        verifyAll();

        assertFalse(connectivityManager.isInitialized());
        assertFalse(clientState.isForcedOffline());
        assertEquals(ConnectionMode.POLLING, connectivityManager.getConnectionInformation().getConnectionMode());
        LDFailure failure = connectivityManager.getConnectionInformation().getLastFailure();
        assertNotNull(failure);
        assertEquals("failure", failure.getMessage());
        assertEquals(LDFailure.FailureType.NETWORK_FAILURE, failure.getFailureType());
        assertEquals(testError, failure.getCause());
        assertNotNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }

    @Test
    public void initPollingUnknownError() throws ExecutionException {
        final Throwable testError = new Throwable();
        reset(fetcher);
        final Capture<LDUtil.ResultCallback<String>> callbackCapture = Capture.newInstance();
        fetcher.fetch(eq(CONTEXT), capture(callbackCapture));
        expectLastCall().andAnswer(() -> {
            callbackCapture.getValue().onError(testError);
            return null;
        });
        eventProcessor.setOffline(false);
        replayAll();

        createTestManager(false, false, false);

        awaitStartUp();
        verifyAll();

        assertFalse(connectivityManager.isInitialized());
        assertFalse(clientState.isForcedOffline());
        assertEquals(ConnectionMode.POLLING, connectivityManager.getConnectionInformation().getConnectionMode());
        LDFailure failure = connectivityManager.getConnectionInformation().getLastFailure();
        assertNotNull(failure);
        assertEquals("Unknown failure", failure.getMessage());
        assertEquals(LDFailure.FailureType.UNKNOWN_ERROR, failure.getFailureType());
        assertEquals(testError, failure.getCause());
        assertNotNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }

    @Test
    public void reloadSetOffline() throws ExecutionException {
        createTestManager(true, false, false);

        awaitStartUp();

        assertTrue(connectivityManager.isInitialized());
        assertTrue(clientState.isForcedOffline());
        assertEquals(ConnectionMode.SET_OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    static boolean isRoamingConnected(Context context) {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network net = cm.getActiveNetwork();
        if (net == null)
            return false;

        NetworkCapabilities nwc = cm.getNetworkCapabilities(net);

        return nwc != null && nwc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    // This test requires either Android < API 21 or mobile data to be off on device/emulator.
    @Test
    public void reloadDeviceOffline() throws ExecutionException, InterruptedException {
        if (Build.VERSION.SDK_INT < 21 || !isRoamingConnected(ApplicationProvider.getApplicationContext())) {
            NetworkTestController.disableNetwork();
            createTestManager(false, true, false);

            awaitStartUp();

            assertTrue(connectivityManager.isInitialized());
            assertFalse(clientState.isForcedOffline());
            assertEquals(ConnectionMode.OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
            assertNoConnection();
        }
    }

    @Test
    public void reloadBackgroundDisabled() throws ExecutionException {
        mockPlatformState.setForeground(false);

        createTestManager(false, true, true);

        awaitStartUp();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(clientState.isForcedOffline());
        assertEquals(ConnectionMode.BACKGROUND_DISABLED, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    @Test
    public void reloadBackgroundPolling() throws ExecutionException {
        mockPlatformState.setForeground(false);

        createTestManager(false, true, false);

        awaitStartUp();
        // We don't call awaitDataReceived() here because, if it's in background mode from the start,
        // it's not going to do a poll until the background polling interval has elapsed. So we'd
        // be waiting a very long time. This is not a realistic use case because normally the SDK
        // is started when the application is started, in the foreground.

        assertTrue(connectivityManager.isInitialized());
        assertFalse(clientState.isForcedOffline());
        assertEquals(ConnectionMode.BACKGROUND_POLLING, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    @Test
    public void setOfflineDuringInitStreaming() throws ExecutionException {
        eventProcessor.setOffline(false);
        eventProcessor.setOffline(true);
        replayAll();

        // 192.0.2.1 is assigned as TEST-NET-1 reserved usage.
        createTestManager(false, true, false, "http://192.0.2.1");

        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        connectivityManager.setOffline();
        awaitableCallback.await();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertTrue(clientState.isForcedOffline());
        assertEquals(ConnectionMode.SET_OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
        //assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }

    @Test
    public void shutdownDuringInitStreaming() throws ExecutionException {
        eventProcessor.setOffline(false); // goes online at startup
        eventProcessor.setOffline(true); // goes offline at shutdown
        replayAll();

        // 192.0.2.1 is assigned as TEST-NET-1 reserved usage.
        createTestManager(false, true, false, "http://192.0.2.1");

        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        connectivityManager.shutdown();
        awaitableCallback.await();
        verifyAll();

        assertFalse(connectivityManager.isInitialized());
        assertTrue(clientState.isForcedOffline());
        assertEquals(ConnectionMode.SHUTDOWN, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }

    @Test
    public void backgroundedDuringInitStreaming() throws ExecutionException {
        mockPlatformState.setForeground(true);

        eventProcessor.setOffline(false); // goes online at startup
        eventProcessor.setInBackground(true); // expect it to be put into the background
        replayAll();

        // 192.0.2.1 is assigned as TEST-NET-1 reserved usage.
        createTestManager(false, true, true, "http://192.0.2.1");

        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);

        mockPlatformState.setForeground(false);
        mockPlatformState.notifyForegroundChangeListeners(false);

        awaitableCallback.await();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(clientState.isForcedOffline());
        assertEquals(ConnectionMode.BACKGROUND_DISABLED, connectivityManager.getConnectionInformation().getConnectionMode());
//        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }

    @Test
    public void deviceOfflinedDuringInitStreaming() throws ExecutionException, InterruptedException {
        eventProcessor.setOffline(false);
        eventProcessor.setOffline(true);
        replayAll();

        // 192.0.2.1 is assigned as TEST-NET-1 reserved usage.
        createTestManager(false, true, false, "http://192.0.2.1");

        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        NetworkTestController.disableNetwork();
        // Connectivity manager is normally notified of change by LDClient
        connectivityManager.onNetworkConnectivityChange(false);
        awaitableCallback.await();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(clientState.isForcedOffline());
        assertEquals(ConnectionMode.OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
//        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }

    @Test
    public void reloadCompletesPending() throws ExecutionException {
        eventProcessor.setOffline(false);
        expectLastCall().anyTimes();
        replayAll();

        // 192.0.2.1 is assigned as TEST-NET-1 reserved usage.
        createTestManager(false, true, false, "http://192.0.2.1");

        AwaitableCallback<Void> awaitableCallbackInit = new AwaitableCallback<>();
        AwaitableCallback<Void> awaitableCallbackReload = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallbackInit);
        connectivityManager.reloadData(awaitableCallbackReload);
        awaitableCallbackInit.await();
        verifyAll();

        assertFalse(connectivityManager.isInitialized());
        assertFalse(clientState.isForcedOffline());
        assertEquals(ConnectionMode.STREAMING, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }
}