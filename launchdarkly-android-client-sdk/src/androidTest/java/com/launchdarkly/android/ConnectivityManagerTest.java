package com.launchdarkly.android;

import android.app.Application;
import android.content.Context;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.StrictMode;
import android.os.StrictMode.ThreadPolicy;
import android.os.StrictMode.VmPolicy;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.android.ConnectionInformation.ConnectionMode;
import com.launchdarkly.android.test.TestActivity;
import com.launchdarkly.sdk.LDUser;

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
import java.util.concurrent.ExecutionException;

import okhttp3.mockwebserver.MockWebServer;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * TODO: all these tests crash the emulator
 */
@RunWith(AndroidJUnit4.class)
public class ConnectivityManagerTest extends EasyMockSupport {
    
    // this is a workaround to android being [redacted]
    // you cant run network tasks on the main thread
    // even in tests so we need to spin up a thread each time
    // there is *supposed* to be a way around this with 
    // `StrictMode#setThreadPolicy` but its bugged and doesnt work
    // on android emulators. or at least not the ones we run in CI
    static void doTask(Runnable func) {
        try {
            Thread t = new Thread(func);
            t.start();
            t.join();
        } catch (InterruptedException err) {
            assert false : "failed to run thread";
        }
    }

    @Rule
    public final ActivityScenarioRule<TestActivity> testScenario =
            new ActivityScenarioRule<>(TestActivity.class);

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();
    @Rule
    public EasyMockRule easyMockRule = new EasyMockRule(this);
    @Rule
    public Timeout globalTimeout = Timeout.seconds(20);

    @SuppressWarnings("unused")
    @Mock(MockType.STRICT)
    private EventProcessor eventProcessor;

    @SuppressWarnings("unused")
    @Mock(MockType.STRICT)
    private UserManager userManager;

    private ConnectivityManager connectivityManager;
    private MockWebServer mockStreamServer;
    private ActivityScenario<TestActivity> scenario;

    static {
        StrictMode.setThreadPolicy(ThreadPolicy.LAX);
        StrictMode.setVmPolicy(VmPolicy.LAX);
    }

    @Before
    public void before() {
        scenario = testScenario.getScenario();
        scenario.onActivity(act -> {
            ConnectivityManagerTest.doTask(() -> {
                NetworkTestController.setup(act);
                mockStreamServer = new MockWebServer();
                try {
                    mockStreamServer.start();
                } catch (IOException err) { 
                    // if this throws then we cant do any tests
                    assert false : "failed to start mock stream server";
                }
            });
        });
    }

    @After
    public void after() throws InterruptedException, IOException {
        NetworkTestController.enableNetwork();
        mockStreamServer.close();
        scenario.close();
    }

    private void createTestManager(boolean setOffline, boolean streaming, boolean backgroundDisabled) {
        createTestManager(setOffline, streaming, backgroundDisabled, null);
    }

    private void createTestManager(boolean setOffline, boolean streaming, boolean backgroundDisabled, String streamUri) {
        Application app = ApplicationProvider.getApplicationContext();

        LDConfig config = new LDConfig.Builder()
                .mobileKey("test-mobile-key")
                .offline(setOffline)
                .stream(streaming)
                .disableBackgroundUpdating(backgroundDisabled)
                .streamUri(streamUri != null ? Uri.parse(streamUri) : Uri.parse(mockStreamServer.url("/").toString()))
                .build();

        connectivityManager = new ConnectivityManager(app, config, eventProcessor, userManager, "default", null);
    }

    private void awaitStartUp() throws ExecutionException {
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        awaitableCallback.await();
    }

    private void awaitReloadUser() throws ExecutionException {
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.reloadUser(awaitableCallback);
        awaitableCallback.await();
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
        assertTrue(connectivityManager.isOffline());
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
        assertFalse(connectivityManager.isOffline());
        assertEquals(ConnectionMode.OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    @Test
    public void initBackgroundDisabled() throws ExecutionException {
        eventProcessor.start();
        replayAll();

        ForegroundTestController.setup(false);
        createTestManager(false, true, true);

        awaitStartUp();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isOffline());
        assertEquals(ConnectionMode.BACKGROUND_DISABLED, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    @Test
    public void initBackgroundPolling() throws ExecutionException {
        eventProcessor.start();
        replayAll();

        ForegroundTestController.setup(false);
        createTestManager(false, true, false);

        awaitStartUp();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isOffline());
        assertEquals(ConnectionMode.BACKGROUND_POLLING, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    @Test
    public void initPolling() throws ExecutionException {
        final Capture<LDUtil.ResultCallback<Void>> callbackCapture = Capture.newInstance();
        userManager.updateCurrentUser(capture(callbackCapture));
        expectLastCall().andAnswer(() -> {
            callbackCapture.getValue().onSuccess(null);
            return null;
        });
        eventProcessor.start();
        replayAll();

        createTestManager(false, false, false);

        awaitStartUp();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isOffline());
        assertEquals(ConnectionMode.POLLING, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastFailure());
        assertNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNotNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }

    @Test
    public void initPollingKnownError() throws ExecutionException {
        final Throwable testError = new Throwable();
        final Capture<LDUtil.ResultCallback<Void>> callbackCapture = Capture.newInstance();
        userManager.updateCurrentUser(capture(callbackCapture));
        expectLastCall().andAnswer(() -> {
            callbackCapture.getValue().onError(new LDFailure("failure", testError, LDFailure.FailureType.NETWORK_FAILURE));
            return null;
        });
        eventProcessor.start();
        replayAll();

        createTestManager(false, false, false);

        awaitStartUp();
        verifyAll();

        assertFalse(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isOffline());
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
        final Capture<LDUtil.ResultCallback<Void>> callbackCapture = Capture.newInstance();
        userManager.updateCurrentUser(capture(callbackCapture));
        expectLastCall().andAnswer(() -> {
            callbackCapture.getValue().onError(testError);
            return null;
        });
        eventProcessor.start();
        replayAll();

        createTestManager(false, false, false);

        awaitStartUp();
        verifyAll();

        assertFalse(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isOffline());
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
        awaitReloadUser();

        assertTrue(connectivityManager.isInitialized());
        assertTrue(connectivityManager.isOffline());
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
            awaitReloadUser();

            assertTrue(connectivityManager.isInitialized());
            assertFalse(connectivityManager.isOffline());
            assertEquals(ConnectionMode.OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
            assertNoConnection();
        }
    }

    @Test
    public void reloadBackgroundDisabled() throws ExecutionException {
        ForegroundTestController.setup(false);
        createTestManager(false, true, true);

        awaitStartUp();
        awaitReloadUser();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isOffline());
        assertEquals(ConnectionMode.BACKGROUND_DISABLED, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    @Test
    public void reloadBackgroundPolling() throws ExecutionException {
        ForegroundTestController.setup(false);
        createTestManager(false, true, false);

        awaitStartUp();
        awaitReloadUser();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isOffline());
        assertEquals(ConnectionMode.BACKGROUND_POLLING, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    @Test
    public void setOfflineDuringInitStreaming() throws ExecutionException {
        expect(userManager.getCurrentUser()).andReturn(new LDUser.Builder("test-key").build());
        eventProcessor.start();
        eventProcessor.stop();
        replayAll();

        // 192.0.2.1 is assigned as TEST-NET-1 reserved usage.
        createTestManager(false, true, false, "http://192.0.2.1");

        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        connectivityManager.setOffline();
        awaitableCallback.await();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertTrue(connectivityManager.isOffline());
        assertEquals(ConnectionMode.SET_OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
//        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }

    @Test
    public void shutdownDuringInitStreaming() throws ExecutionException {
        expect(userManager.getCurrentUser()).andReturn(new LDUser.Builder("test-key").build());
        eventProcessor.start();
        replayAll();

        // 192.0.2.1 is assigned as TEST-NET-1 reserved usage.
        createTestManager(false, true, false, "http://192.0.2.1");

        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        connectivityManager.shutdown();
        awaitableCallback.await();
        verifyAll();

        assertFalse(connectivityManager.isInitialized());
        assertTrue(connectivityManager.isOffline());
        assertEquals(ConnectionMode.SHUTDOWN, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }

    @Test
    public void backgroundedDuringInitStreaming() throws ExecutionException {
        ForegroundTestController.setup(true);
        expect(userManager.getCurrentUser()).andReturn(new LDUser.Builder("test-key").build()).anyTimes();
        eventProcessor.start();
        replayAll();

        // 192.0.2.1 is assigned as TEST-NET-1 reserved usage.
        createTestManager(false, true, true, "http://192.0.2.1");

        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        ForegroundTestController.moveToBackground();
        awaitableCallback.await();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isOffline());
        assertEquals(ConnectionMode.BACKGROUND_DISABLED, connectivityManager.getConnectionInformation().getConnectionMode());
//        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }

    @Test
    public void deviceOfflinedDuringInitStreaming() throws ExecutionException, InterruptedException {
        expect(userManager.getCurrentUser()).andReturn(new LDUser.Builder("test-key").build()).anyTimes();
        eventProcessor.start();
        eventProcessor.stop();
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
        assertFalse(connectivityManager.isOffline());
        assertEquals(ConnectionMode.OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
//        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }

    @Test
    public void reloadCompletesPending() throws ExecutionException {
        expect(userManager.getCurrentUser()).andReturn(new LDUser.Builder("test-key").build()).anyTimes();
        eventProcessor.start();
        expectLastCall().anyTimes();
        replayAll();

        // 192.0.2.1 is assigned as TEST-NET-1 reserved usage.
        createTestManager(false, true, false, "http://192.0.2.1");

        AwaitableCallback<Void> awaitableCallbackInit = new AwaitableCallback<>();
        AwaitableCallback<Void> awaitableCallbackReload = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallbackInit);
        connectivityManager.reloadUser(awaitableCallbackReload);
        awaitableCallbackInit.await();
        verifyAll();

        assertFalse(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isOffline());
        assertEquals(ConnectionMode.STREAMING, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
        Assert.assertEquals(0, mockStreamServer.getRequestCount());
    }
}