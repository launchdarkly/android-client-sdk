package com.launchdarkly.android;

import android.app.Activity;
import android.app.Application;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.launchdarkly.android.test.TestActivity;
import com.launchdarkly.android.ConnectionInformation.ConnectionMode;

import org.easymock.EasyMockSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ConnectivityManagerTest extends EasyMockSupport {

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    private EventProcessor eventProcessor;
    private UserManager userManager;
    private ConnectivityManager connectivityManager;

    @Before
    public void before() {
        NetworkTestController.setup(activityTestRule.getActivity());
    }

    @After
    public void after() throws InterruptedException {
        NetworkTestController.enableNetwork();
    }

    private void createTestManager(boolean setOffline, boolean streaming, boolean backgroundDisabled) {
        Activity activity = activityTestRule.getActivity();
        Application application = activity.getApplication();

        LDConfig testConfig = new LDConfig.Builder()
                .setMobileKey("test-mobile-key")
                .setOffline(setOffline)
                .setStream(streaming)
                .setDisableBackgroundUpdating(backgroundDisabled)
                .build();

        if (eventProcessor == null) {
            eventProcessor = niceMock(EventProcessor.class);
        }

        if (userManager == null) {
            userManager = niceMock(UserManager.class);
        }

        connectivityManager = new ConnectivityManager(application, testConfig, eventProcessor, userManager, "default");
    }

    @Test
    public void testSetOffline() throws ExecutionException {
        eventProcessor = strictMock(EventProcessor.class);
        userManager = strictMock(UserManager.class);
        createTestManager(true, false, false);
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        awaitableCallback.await();
        assertTrue(connectivityManager.isInitialized());
        assertTrue(connectivityManager.isOffline());
        assertEquals(ConnectionMode.SET_OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastFailure());
        assertNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
    }

    // This test requires either Android < API 21 or mobile data to be off on device/emulator.
    @Test
    public void testDeviceOffline() throws ExecutionException, InterruptedException {
        eventProcessor = strictMock(EventProcessor.class);
        userManager = strictMock(UserManager.class);
        NetworkTestController.setup(activityTestRule.getActivity());
        NetworkTestController.disableNetwork();
        createTestManager(false, true, false);
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        awaitableCallback.await();
        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isOffline());
        assertEquals(ConnectionMode.OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastFailure());
        assertNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
    }

    @Test
    public void testBackgroundDisabled() throws ExecutionException {
        eventProcessor = strictMock(EventProcessor.class);
        userManager = strictMock(UserManager.class);
        ForegroundTestController.setup(false);
        createTestManager(false, true, true);
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        awaitableCallback.await();
        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isOffline());
        assertEquals(ConnectionMode.BACKGROUND_DISABLED, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastFailure());
        assertNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
    }

    @Test
    public void testSetOfflineReloadUser() throws ExecutionException {
        // Setup
        eventProcessor = strictMock(EventProcessor.class);
        userManager = strictMock(UserManager.class);
        createTestManager(true, false, false);
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        awaitableCallback.await();

        // Reload
        awaitableCallback = new AwaitableCallback<>();
        connectivityManager.reloadUser(awaitableCallback);
        awaitableCallback.await();

        // Assert
        assertTrue(connectivityManager.isInitialized());
        assertTrue(connectivityManager.isOffline());
        assertEquals(ConnectionMode.SET_OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastFailure());
        assertNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
    }

    // This test requires either Android < API 21 or mobile data to be off on device/emulator.
    @Test
    public void testDeviceOfflineReloadUser() throws ExecutionException, InterruptedException {
        // Setup
        eventProcessor = strictMock(EventProcessor.class);
        userManager = strictMock(UserManager.class);
        NetworkTestController.setup(activityTestRule.getActivity());
        NetworkTestController.disableNetwork();
        createTestManager(false, true, false);
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        awaitableCallback.await();

        // Reload
        awaitableCallback = new AwaitableCallback<>();
        connectivityManager.reloadUser(awaitableCallback);
        awaitableCallback.await();

        // Assert
        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isOffline());
        assertEquals(ConnectionMode.OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastFailure());
        assertNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
    }

    @Test
    public void testBackgroundDisabledReloadUser() throws ExecutionException {
        // Setup
        eventProcessor = strictMock(EventProcessor.class);
        userManager = strictMock(UserManager.class);
        ForegroundTestController.setup(false);
        createTestManager(false, true, true);
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        awaitableCallback.await();

        // Reload
        awaitableCallback = new AwaitableCallback<>();
        connectivityManager.reloadUser(awaitableCallback);
        awaitableCallback.await();

        // Assert
        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isOffline());
        assertEquals(ConnectionMode.BACKGROUND_DISABLED, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastFailure());
        assertNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
    }

    @Test
    public void testBackgroundPollingReloadUser() throws ExecutionException {
        // Setup
        eventProcessor = strictMock(EventProcessor.class);
        userManager = strictMock(UserManager.class);
        ForegroundTestController.setup(false);
        createTestManager(false, true, false);
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
        awaitableCallback.await();

        // Reload
        awaitableCallback = new AwaitableCallback<>();
        connectivityManager.reloadUser(awaitableCallback);
        awaitableCallback.await();

        // Assert
        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isOffline());
        assertEquals(ConnectionMode.BACKGROUND_POLLING, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastFailure());
        assertNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
    }
}
