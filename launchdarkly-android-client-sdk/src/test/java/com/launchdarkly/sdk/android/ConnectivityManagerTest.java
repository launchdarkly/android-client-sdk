package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.android.TestUtil.requireNoMoreValues;
import static com.launchdarkly.sdk.android.TestUtil.requireValue;
import static org.easymock.EasyMock.anyBoolean;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.android.ConnectionInformation.ConnectionMode;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.integrations.AutomaticModeSwitchingConfig;
import com.launchdarkly.sdk.android.env.EnvironmentReporterBuilder;
import com.launchdarkly.sdk.android.env.IEnvironmentReporter;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;

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

import com.launchdarkly.sdk.android.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ConnectivityManagerTest extends EasyMockSupport {
    // These tests use a mock PlatformState instead of AndroidPlatformState, so that we can test
    // the ConnectivityManager logic for how to behave under various network/foreground/background
    // conditions separately from the implementation details of how we detect those conditions.
    // Also, we do not use a real data source such as StreamingDataSource, because the logic in
    // ConnectivityManager should be independent of what data source implementation is being used.
    // Instead, we use a mock component and verify that ConnectivityManager is passing the right
    // parameters to it.

    private static final long FDV2_TEST_DEBOUNCE_MS = 50;
    private static final LDContext CONTEXT = LDContext.create("test-context");
    private static final String MOBILE_KEY = "test-mobile-key";
    private static final EnvironmentData DATA = new DataSetBuilder()
            .add("flag1", 1, LDValue.of(true), 0)
            .build();

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();
    @Rule
    public EasyMockRule easyMockRule = new EasyMockRule(this);
    @Rule
    public Timeout globalTimeout = Timeout.seconds(20);

    @SuppressWarnings("unused")
    @Mock(MockType.STRICT)
    private EventProcessor eventProcessor;

    private final TaskExecutor taskExecutor = new SimpleTestTaskExecutor();
    private final MockPlatformState mockPlatformState = new MockPlatformState();

    private final IEnvironmentReporter environmentReporter = new EnvironmentReporterBuilder().build();

    private PersistentDataStoreWrapper.PerEnvironmentData environmentStore;
    private final BlockingQueue<ClientContext> receivedClientContexts = new LinkedBlockingQueue<>();
    private final BlockingQueue<DataSource> startedDataSources = new LinkedBlockingQueue<>();
    private final BlockingQueue<DataSource> stoppedDataSources = new LinkedBlockingQueue<>();
    private final BlockingQueue<List<String>> allFlagsReceived = new LinkedBlockingQueue<>();

    private ConnectivityManager connectivityManager;
    private ContextDataManager contextDataManager;

    @Before
    public void before() {
        environmentStore = TestUtil.makeSimplePersistentDataStoreWrapper().perEnvironmentData(MOBILE_KEY);
    }

    @After
    public void after() throws InterruptedException, IOException {
        taskExecutor.close();
    }

    /** LDConfig with {@link #MOBILE_KEY} and the usual offline / disable-background flags. */
    private static LDConfig defaultTestConfig(boolean setOffline, boolean backgroundDisabled) {
        return new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey(MOBILE_KEY)
                .offline(setOffline)
                .disableBackgroundUpdating(backgroundDisabled)
                .connectionModeStateDebounceMs(FDV2_TEST_DEBOUNCE_MS)
                .build();
    }

    private void createTestManager(
            @NonNull LDConfig config,
            ComponentConfigurer<DataSource> dataSourceConfigurer
    ) {
        ClientContext clientContext = ClientContextImpl.fromConfig(
                config,
                MOBILE_KEY,
                "",
                environmentStore, null,
                CONTEXT,
                logging.logger,
                mockPlatformState,
                environmentReporter,
                taskExecutor
        );

        contextDataManager = new ContextDataManager(
                clientContext,
                environmentStore,
                1,
                false
        );
        contextDataManager.registerAllFlagsListener(flagsUpdated -> {
            allFlagsReceived.add(flagsUpdated);
        });

        connectivityManager = new ConnectivityManager(
                clientContext,
                dataSourceConfigurer,
                eventProcessor,
                environmentStore
        );
    }

    private void awaitStartUp() {
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(contextDataManager, awaitableCallback);
        try {
            awaitableCallback.await();
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
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
    }

    @Test
    public void initSetOffline() throws ExecutionException {
        eventProcessor.setOffline(true); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call

        ComponentConfigurer<DataSource> mockDataSourceConfigurerIsNeverCalled = createStrictMock(ComponentConfigurer.class);
        // strict mock will cause an error if build() is ever called, which it shouldn't be

        replayAll();

        createTestManager(defaultTestConfig(true, false), mockDataSourceConfigurerIsNeverCalled);

        awaitStartUp();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertTrue(connectivityManager.isForcedOffline());
        assertEquals(ConnectionMode.SET_OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    @Test
    public void initDeviceOffline() throws ExecutionException, InterruptedException {
        eventProcessor.setOffline(true); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call

        ComponentConfigurer<DataSource> mockDataSourceConfigurerIsNeverCalled = createStrictMock(ComponentConfigurer.class);
        // strict mock will cause an error if build() is ever called, which it shouldn't be

        replayAll();

        mockPlatformState.setNetworkAvailable(false);

        createTestManager(defaultTestConfig(false, true), mockDataSourceConfigurerIsNeverCalled);

        awaitStartUp();
        verifyAll();

        assertFalse(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());
        assertEquals(ConnectionMode.OFFLINE, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    @Test
    public void initBackgroundDisabled() throws ExecutionException {
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(true); // we expect this call

        ComponentConfigurer<DataSource> mockDataSourceConfigurerIsNeverCalled = createStrictMock(ComponentConfigurer.class);
        // strict mock will cause an error if build() is ever called, which it shouldn't be

        replayAll();

        mockPlatformState.setForeground(false);

        createTestManager(defaultTestConfig(false, true), mockDataSourceConfigurerIsNeverCalled);

        awaitStartUp();
        verifyAll();

        assertFalse(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());
        assertEquals(ConnectionMode.BACKGROUND_DISABLED, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNoConnection();
    }

    @Test
    public void initForegroundDataSource() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());

        awaitStartUp();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());

        assertEquals(ConnectionMode.POLLING, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastFailure());
        assertNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNotNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());

        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyNoMoreDataSourcesWereCreated();
    }

    @Test
    public void initBackgroundDataSource() throws Exception {
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(true); // we expect this call
        replayAll();

        mockPlatformState.setForeground(false);

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());

        awaitStartUp();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());

        assertEquals(ConnectionMode.BACKGROUND_POLLING, connectivityManager.getConnectionInformation().getConnectionMode());
        assertNull(connectivityManager.getConnectionInformation().getLastFailure());
        assertNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNotNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());

        verifyBackgroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyNoMoreDataSourcesWereCreated();
    }

    @Test
    public void initDataSourceWithKnownError() throws ExecutionException {
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        final Throwable testError = new Throwable();
        final LDFailure testFailure = new LDFailure("failure", testError, LDFailure.FailureType.NETWORK_FAILURE);
        ComponentConfigurer<DataSource> dataSourceConfigurer = clientContext ->
                MockComponents.failingDataSource(clientContext, ConnectionMode.POLLING, testFailure);

        createTestManager(defaultTestConfig(false, false), dataSourceConfigurer);

        awaitStartUp();
        verifyAll();

        assertFalse(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());
        assertEquals(ConnectionMode.POLLING, connectivityManager.getConnectionInformation().getConnectionMode());
        LDFailure failure = connectivityManager.getConnectionInformation().getLastFailure();
        assertNotNull(failure);
        assertEquals("failure", failure.getMessage());
        assertEquals(LDFailure.FailureType.NETWORK_FAILURE, failure.getFailureType());
        assertEquals(testError, failure.getCause());
        assertNotNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
    }

    @Test
    public void initDataSourceWithUnknownError() throws ExecutionException {
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        final Throwable testError = new Throwable();
        ComponentConfigurer<DataSource> dataSourceConfigurer = clientContext ->
                MockComponents.failingDataSource(clientContext, ConnectionMode.POLLING, testError);

        createTestManager(defaultTestConfig(false, false), dataSourceConfigurer);

        awaitStartUp();
        verifyAll();

        assertFalse(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());
        assertEquals(ConnectionMode.POLLING, connectivityManager.getConnectionInformation().getConnectionMode());
        LDFailure failure = connectivityManager.getConnectionInformation().getLastFailure();
        assertNotNull(failure);
        assertEquals("Unknown failure", failure.getMessage());
        assertEquals(LDFailure.FailureType.UNKNOWN_ERROR, failure.getFailureType());
        assertEquals(testError, failure.getCause());
        assertNotNull(connectivityManager.getConnectionInformation().getLastFailedConnection());
        assertNull(connectivityManager.getConnectionInformation().getLastSuccessfulConnection());
    }

    @Test
    public void setOfflineAfterInit() throws Exception {
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());

        awaitStartUp();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());
        assertEquals(ConnectionMode.POLLING, connectivityManager.getConnectionInformation().getConnectionMode());

        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyNoMoreDataSourcesWereCreated();

        resetAll();
        eventProcessor.setOffline(true); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        connectivityManager.setForceOffline(true);

        // We don't currently have a good way to wait for this state change to take effect, so we'll
        // poll for it.
        ConnectionMode newConnectionMode = awaitConnectionModeChangedFrom(ConnectionMode.POLLING);
        assertEquals(ConnectionMode.SET_OFFLINE, newConnectionMode);
        assertTrue(connectivityManager.isForcedOffline());

        verifyAll(); // verifies eventProcessor calls
        verifyDataSourceWasStopped();
        verifyNoMoreDataSourcesWereCreated();
    }

    @Test
    public void setInBackgroundAfterInitWithBackgroundPollingEnabled() throws Exception {
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());

        awaitStartUp();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());
        assertEquals(ConnectionMode.POLLING, connectivityManager.getConnectionInformation().getConnectionMode());

        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        resetAll();
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(true); // we expect this call
        eventProcessor.flush(); // CONNMODE 3.3.1: flush before background transition
        replayAll();

        mockPlatformState.setAndNotifyForegroundChangeListeners(false);

        // We don't currently have a good way to wait for this state change to take effect, so we'll
        // poll for it.
        ConnectionMode newConnectionMode = awaitConnectionModeChangedFrom(ConnectionMode.POLLING);
        assertEquals(ConnectionMode.BACKGROUND_POLLING, newConnectionMode);

        verifyAll(); // verifies eventProcessor calls
        verifyDataSourceWasStopped();
        verifyBackgroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyNoMoreDataSourcesWereCreated();
    }

    @Test
    public void setInBackgroundDoesNotRefreshDataSourceIfDataSourceSaysNotTo() throws Exception {
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        ComponentConfigurer<DataSource> makeDataSourceThatDoesNotRefresh = clientContext -> {
            DataSource underlyingDataSource = makeSuccessfulDataSource(clientContext);
            return new DataSource() {
                @Override
                public void start(@NonNull Callback<Boolean> resultCallback) {
                    underlyingDataSource.start(resultCallback);
                }

                @Override
                public void stop(@NonNull Callback<Void> completionCallback) {
                    underlyingDataSource.stop(completionCallback);
                }

                @Override
                public boolean needsRefresh(boolean newInBackground, LDContext newEvaluationContext) {
                    return false;
                }
            };
        };
        createTestManager(defaultTestConfig(false, false), makeDataSourceThatDoesNotRefresh);

        awaitStartUp();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());
        assertEquals(ConnectionMode.POLLING, connectivityManager.getConnectionInformation().getConnectionMode());

        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        mockPlatformState.setAndNotifyForegroundChangeListeners(false);

        verifyNoMoreDataSourcesWereCreated();
        verifyNoMoreDataSourcesWereStopped();
    }

    @Test
    public void setInBackgroundAfterInitWithBackgroundPollingDisabled() throws Exception {
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        createTestManager(defaultTestConfig(false, true), makeSuccessfulDataSourceFactory());

        awaitStartUp();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());
        assertEquals(ConnectionMode.POLLING, connectivityManager.getConnectionInformation().getConnectionMode());

        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyNoMoreDataSourcesWereCreated();

        resetAll();
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(true); // we expect this call
        eventProcessor.flush(); // CONNMODE 3.3.1: flush before background transition
        replayAll();

        mockPlatformState.setAndNotifyForegroundChangeListeners(false);

        // We don't currently have a good way to wait for this state change to take effect, so we'll
        // poll for it.
        ConnectionMode newConnectionMode = awaitConnectionModeChangedFrom(ConnectionMode.POLLING);
        assertEquals(ConnectionMode.BACKGROUND_DISABLED, newConnectionMode);

        verifyAll(); // verifies eventProcessor calls
        verifyDataSourceWasStopped();
        verifyNoMoreDataSourcesWereCreated();
    }

    @Test
    public void refreshDataSourceForNewContext() throws Exception {
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());

        awaitStartUp();
        verifyAll();

        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyNoMoreDataSourcesWereCreated();

        resetAll();
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        long connectionTimeBeforeSwitch = connectivityManager.getConnectionInformation().getLastSuccessfulConnection();
        LDContext context2 = LDContext.create("context2");
        AwaitableCallback<Void> done = new AwaitableCallback<>();
        contextDataManager.switchToContext(context2, false, done);
        done.await();
        long connectionTimeAfterSwitch = connectivityManager.getConnectionInformation().getLastSuccessfulConnection();

        verifyAll(); // verifies eventProcessor calls
        verifyDataSourceWasStopped();
        verifyForegroundDataSourceWasCreatedAndStarted(context2);
        verifyNoMoreDataSourcesWereCreated();
        assertNotEquals(connectionTimeBeforeSwitch, connectionTimeAfterSwitch);
    }

    @Test
    public void refreshDataSourceWhileOffline() {
        eventProcessor.setOffline(true); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        createTestManager(defaultTestConfig(true, false), makeSuccessfulDataSourceFactory());

        awaitStartUp();
        verifyAll();

        verifyNoMoreDataSourcesWereCreated();

        resetAll();
        eventProcessor.setOffline(true); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        LDContext context2 = LDContext.create("context2");
        contextDataManager.switchToContext(context2, false, LDUtil.noOpCallback());

        verifyAll(); // verifies eventProcessor calls
        verifyNoMoreDataSourcesWereCreated();
    }

    @Test
    public void refreshDataSourceWhileInBackgroundWithBackgroundPollingDisabled() {
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(true); // we expect this call
        replayAll();

        mockPlatformState.setForeground(false);

        createTestManager(defaultTestConfig(false, true), makeSuccessfulDataSourceFactory());

        awaitStartUp();
        verifyAll();

        verifyNoMoreDataSourcesWereCreated();

        resetAll();
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(true); // we expect this call
        replayAll();

        LDContext context2 = LDContext.create("context2");
        contextDataManager.switchToContext(context2, false, LDUtil.noOpCallback());

        verifyAll(); // verifies eventProcessor calls
        verifyNoMoreDataSourcesWereCreated();
    }

    // ==== FDv1 state-transition round-trip tests ====
    //
    // These tests exercise the FDv1 code path through state transitions that were added or
    // restructured alongside the FDv2 work, ensuring the FDv1 flow is unaffected.

    @Test
    public void fdv1_shutDown_doesNotCallCloseOnFactory() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        connectivityManager.shutDown();
        verifyDataSourceWasStopped();
    }

    @Test
    public void fdv1_setOffline_thenBackOnline_rebuildsDataSource() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        resetAll();
        eventProcessor.setOffline(true);
        eventProcessor.setInBackground(false);
        replayAll();

        connectivityManager.setForceOffline(true);
        ConnectionMode offlineMode = awaitConnectionModeChangedFrom(ConnectionMode.POLLING);
        assertEquals(ConnectionMode.SET_OFFLINE, offlineMode);
        verifyDataSourceWasStopped();
        verifyAll();

        resetAll();
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        connectivityManager.setForceOffline(false);
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyAll();
    }

    @Test
    public void fdv1_networkLost_thenRestored_rebuildsDataSource() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        resetAll();
        eventProcessor.setOffline(true);
        eventProcessor.setInBackground(false);
        replayAll();

        mockPlatformState.setAndNotifyConnectivityChangeListeners(false);
        ConnectionMode offlineMode = awaitConnectionModeChangedFrom(ConnectionMode.POLLING);
        assertEquals(ConnectionMode.OFFLINE, offlineMode);
        verifyDataSourceWasStopped();
        verifyAll();

        resetAll();
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        mockPlatformState.setAndNotifyConnectivityChangeListeners(true);
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyAll();
    }

    @Test
    public void fdv1_foregroundToBackground_thenBackToForeground_rebuildsDataSource() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        resetAll();
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(true);
        eventProcessor.flush(); // CONNMODE 3.3.1: flush before background transition
        replayAll();

        mockPlatformState.setAndNotifyForegroundChangeListeners(false);
        ConnectionMode bgMode = awaitConnectionModeChangedFrom(ConnectionMode.POLLING);
        assertEquals(ConnectionMode.BACKGROUND_POLLING, bgMode);
        verifyDataSourceWasStopped();
        verifyBackgroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyAll();

        resetAll();
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        mockPlatformState.setAndNotifyForegroundChangeListeners(true);
        verifyDataSourceWasStopped();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyAll();
    }

    @Test
    public void fdv1_connectivityChange_whileOnline_doesNotRebuildPollingDataSource() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        resetAll();
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        // Fire a connectivity change while the network remains available — the existing
        // polling data source should NOT be torn down and rebuilt.
        mockPlatformState.setAndNotifyConnectivityChangeListeners(true);

        verifyNoMoreDataSourcesWereCreated();
        verifyNoMoreDataSourcesWereStopped();
        verifyAll();
    }

    @Test
    public void fdv1_foregroundToBackground_rebuildsPollingDataSource() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        resetAll();
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(true);
        eventProcessor.flush(); // CONNMODE 3.3.1: flush before background transition
        replayAll();

        mockPlatformState.setAndNotifyForegroundChangeListeners(false);
        ConnectionMode bgMode = awaitConnectionModeChangedFrom(ConnectionMode.POLLING);
        assertEquals(ConnectionMode.BACKGROUND_POLLING, bgMode);
        verifyDataSourceWasStopped();
        verifyBackgroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyAll();
    }

    @Test
    public void fdv1_forDataSource_selectorSourceIsPassedThrough() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), clientContext -> {
            receivedClientContexts.add(clientContext);
            ClientContextImpl impl = ClientContextImpl.get(clientContext);
            assertNotNull(impl.getSelectorSource());
            return MockComponents.successfulDataSource(clientContext, DATA,
                    ConnectionMode.POLLING, startedDataSources, stoppedDataSources);
        });

        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);
    }

    @Test
    public void notifyListenersWhenStatusChanges() throws Exception {
        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());
        awaitStartUp();

        LDStatusListener mockListener = mock(LDStatusListener.class);
        // expected initial connection mode
        mockListener.onConnectionModeChanged(anyObject(ConnectionInformation.class));
        // expected second connection mode after identify
        mockListener.onConnectionModeChanged(anyObject(ConnectionInformation.class));
        expectLastCall();
        replayAll();

        CountDownLatch latch = new CountDownLatch(2);
        connectivityManager.registerStatusListener(mockListener);
        connectivityManager.registerStatusListener(new LDStatusListener() {
            @Override
            public void onConnectionModeChanged(ConnectionInformation connectionInformation) {
                // since the callback system is on another thread, need to use awaitable callback
                latch.countDown();
            }

            @Override
            public void onInternalFailure(LDFailure ldFailure) {
                Assert.fail(); // unexpected
            }
        });

        LDContext context2 = LDContext.create("context2");
        contextDataManager.switchToContext(context2, false, new AwaitableCallback<>());
        latch.await(500, TimeUnit.MILLISECONDS);

        verifyAll();
    }

    // ==== View-based data source gating tests ====

    @Test
    public void startUpRegistersListenerAndCreatesDataSource() throws ExecutionException {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());
        awaitStartUp();

        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyNoMoreDataSourcesWereCreated();
    }

    @Test
    public void contextSwitchStopsOldDataSourceAndCreatesNew() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        LDContext context2 = LDContext.create("context2");
        AwaitableCallback<Void> done = new AwaitableCallback<>();
        contextDataManager.switchToContext(context2, false, done);
        done.await();

        verifyDataSourceWasStopped();
        verifyForegroundDataSourceWasCreatedAndStarted(context2);
    }

    @Test
    public void dataSourceReceivesViewAsSelectorSource() throws ExecutionException {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), clientContext -> {
            receivedClientContexts.add(clientContext);
            ClientContextImpl impl = ClientContextImpl.get(clientContext);
            SelectorSource selectorSource = impl.getSelectorSource();
            assertNotNull(selectorSource);
            assertTrue("SelectorSource should be a ContextDataManagerView",
                    selectorSource instanceof ContextDataManager.ContextDataManagerView);
            return MockComponents.successfulDataSource(clientContext, DATA,
                    ConnectionMode.POLLING, startedDataSources, stoppedDataSources);
        });
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);
    }

    @Test
    public void startupCallbackIsInvokedOnCompletion() throws ExecutionException {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());

        AwaitableCallback<Void> callback = new AwaitableCallback<>();
        connectivityManager.startUp(contextDataManager, callback);
        callback.await();
    }

    private ComponentConfigurer<DataSource> makeSuccessfulDataSourceFactory() {
        return clientContext -> makeSuccessfulDataSource(clientContext);
    }

    private DataSource makeSuccessfulDataSource(ClientContext clientContext) {
        receivedClientContexts.add(clientContext);
        return MockComponents.successfulDataSource(clientContext, DATA,
                clientContext.isInBackground() ? ConnectionMode.BACKGROUND_POLLING : ConnectionMode.POLLING,
                startedDataSources,
                stoppedDataSources);
    }

    private ConnectionMode awaitConnectionModeChangedFrom(ConnectionMode originalConnectionMode) {
        return AssertHelpers.assertPolledFunctionReturnsValue(1, TimeUnit.SECONDS,
                10, TimeUnit.MILLISECONDS, () -> {
                    return connectivityManager.getConnectionInformation().getConnectionMode() ==
                            originalConnectionMode ? null :
                            connectivityManager.getConnectionInformation().getConnectionMode();
                });
    }

    private ClientContext requireReceivedClientContext() {
        return requireValue(receivedClientContexts, 1, TimeUnit.SECONDS,
                "creation of data source");
    }

    private void verifyForegroundDataSourceWasCreatedAndStarted(
            LDContext evaluationContext
    ) {
        ClientContext clientContext = requireReceivedClientContext();
        assertFalse(clientContext.isInBackground());
        assertEquals(evaluationContext, clientContext.getEvaluationContext());
        requireValue(startedDataSources, 1, TimeUnit.SECONDS, "starting of data source");
    }

    private DataSource verifyBackgroundDataSourceWasCreatedAndStarted(
            LDContext evaluationContext
    ) {
        ClientContext clientContext = requireReceivedClientContext();
        assertTrue(clientContext.isInBackground());
        assertEquals(evaluationContext, clientContext.getEvaluationContext());
        return requireValue(startedDataSources, 1, TimeUnit.SECONDS, "starting of data source");
    }

    private void verifyDataSourceWasStopped() {
        requireValue(stoppedDataSources, 1, TimeUnit.SECONDS, "stopping of data source");
    }

    private void verifyNoMoreDataSourcesWereCreated() {
        requireNoMoreValues(receivedClientContexts, 10, TimeUnit.MILLISECONDS,
                "call to create another data source");
    }

    /**
     * Like {@link #verifyNoMoreDataSourcesWereCreated()}, but waits longer than the FDv2
     * debounce window so we can confirm that even after the debounce timer fires, no data
     * source was created.
     */
    private void verifyNoMoreDataSourcesWereCreatedAfterDebounce() {
        requireNoMoreValues(receivedClientContexts, FDV2_TEST_DEBOUNCE_MS * 4, TimeUnit.MILLISECONDS,
                "call to create another data source");
    }

    private void verifyNoMoreDataSourcesWereStopped() {
        requireNoMoreValues(stoppedDataSources, 1, TimeUnit.SECONDS, "stopping of data source");
    }

    // ==== FDv2 mode resolution tests ====

    /**
     * Creates a test FDv2DataSourceBuilder that returns mock data sources
     * which track start/stop via the shared queues. Each build() call creates
     * a new mock data source.
     */
    private FDv2DataSourceBuilder makeFDv2DataSourceFactory() {
        Map<com.launchdarkly.sdk.android.ConnectionMode, ModeDefinition> table = new LinkedHashMap<>();
        table.put(com.launchdarkly.sdk.android.ConnectionMode.STREAMING, new ModeDefinition(
                Collections.<DataSourceBuilder<Initializer>>emptyList(),
                Collections.<DataSourceBuilder<Synchronizer>>singletonList(inputs -> null),
                null
        ));
        table.put(com.launchdarkly.sdk.android.ConnectionMode.BACKGROUND, new ModeDefinition(
                Collections.<DataSourceBuilder<Initializer>>emptyList(),
                Collections.<DataSourceBuilder<Synchronizer>>singletonList(inputs -> null),
                null
        ));
        table.put(com.launchdarkly.sdk.android.ConnectionMode.OFFLINE, new ModeDefinition(
                Collections.<DataSourceBuilder<Initializer>>emptyList(),
                Collections.<DataSourceBuilder<Synchronizer>>emptyList(),
                null
        ));
        return new FDv2DataSourceBuilder(table, com.launchdarkly.sdk.android.ConnectionMode.STREAMING) {
            @Override
            public DataSource build(ClientContext clientContext) {
                receivedClientContexts.add(clientContext);
                return MockComponents.successfulDataSource(clientContext, DATA,
                        ConnectionMode.STREAMING, startedDataSources, stoppedDataSources);
            }
        };
    }

    @Test
    public void fdv2_foregroundToBackground_rebuildsDataSource() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(true);
        eventProcessor.flush(); // CONNMODE 3.3.1: flush before background transition
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        mockPlatformState.setAndNotifyForegroundChangeListeners(false);

        verifyDataSourceWasStopped();
        requireValue(receivedClientContexts, 1, TimeUnit.SECONDS, "new data source creation");
        requireValue(startedDataSources, 1, TimeUnit.SECONDS, "new data source started");
        verifyAll();
    }

    @Test
    public void fdv2_backgroundToForeground_rebuildsDataSource() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(true);
        eventProcessor.flush(); // CONNMODE 3.3.1: flush before background transition
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        mockPlatformState.setAndNotifyForegroundChangeListeners(false);
        verifyDataSourceWasStopped();
        requireValue(receivedClientContexts, 1, TimeUnit.SECONDS, "bg data source creation");
        requireValue(startedDataSources, 1, TimeUnit.SECONDS, "bg data source started");

        mockPlatformState.setAndNotifyForegroundChangeListeners(true);
        verifyDataSourceWasStopped();
        requireValue(receivedClientContexts, 1, TimeUnit.SECONDS, "fg data source creation");
        requireValue(startedDataSources, 1, TimeUnit.SECONDS, "fg data source started");

        verifyAll();
    }

    @Test
    public void fdv2_networkLost_rebuildsToOffline() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(true);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        mockPlatformState.setAndNotifyConnectivityChangeListeners(false);

        // Data source rebuild is debounced (CONNMODE 3.5.1)
        verifyDataSourceWasStopped();
        requireValue(receivedClientContexts, 2, TimeUnit.SECONDS, "offline data source creation");
        requireValue(startedDataSources, 2, TimeUnit.SECONDS, "offline data source started");
        verifyAll();
    }

    @Test
    public void fdv2_forceOffline_rebuildsToOffline() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(true);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        connectivityManager.setForceOffline(true);

        verifyDataSourceWasStopped();
        requireValue(receivedClientContexts, 1, TimeUnit.SECONDS, "offline data source creation");
        requireValue(startedDataSources, 1, TimeUnit.SECONDS, "offline data source started");
        verifyAll();
    }

    @Test
    public void fdv2_connectivityChange_withSameMode_doesNotRebuildDataSource() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeFDv2DataSourceFactory());

        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        resetAll();
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        // Fire a connectivity change while the network remains available — the resolved
        // mode is still STREAMING, so no rebuild should occur.
        mockPlatformState.setAndNotifyConnectivityChangeListeners(true);

        verifyNoMoreDataSourcesWereCreated();
        verifyNoMoreDataSourcesWereStopped();
        verifyAll();
    }

    @Test
    public void fdv2_sameModeDoesNotRebuild() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        mockPlatformState.setAndNotifyForegroundChangeListeners(true);

        verifyNoMoreDataSourcesWereCreated();
        verifyNoMoreDataSourcesWereStopped();
        verifyAll();
    }

    /**
     * Regression: ConnectivityManager must resolve modes using
     * {@link FDv2DataSourceBuilder#getResolutionTable()}, not {@link ModeResolutionTable#MOBILE}.
     * <p>
     * With the bug, foreground + network always resolved to STREAMING (MOBILE default). A custom
     * table with foreground POLLING would still activate STREAMING. This test would fail in that
     * case because we assert POLLING.
     */
    @Test
    public void fdv2_customResolutionTable_determinesActiveModeOnStartup() throws Exception {
        ModeResolutionTable custom = ModeResolutionTable.createMobile(
                com.launchdarkly.sdk.android.ConnectionMode.POLLING,
                com.launchdarkly.sdk.android.ConnectionMode.BACKGROUND);

        ModeDefinition def = new ModeDefinition(
                Collections.<DataSourceBuilder<Initializer>>emptyList(),
                Collections.<DataSourceBuilder<Synchronizer>>singletonList(inputs -> null),
                null);
        Map<com.launchdarkly.sdk.android.ConnectionMode, ModeDefinition> modeTable = new LinkedHashMap<>();
        modeTable.put(com.launchdarkly.sdk.android.ConnectionMode.POLLING, def);
        modeTable.put(com.launchdarkly.sdk.android.ConnectionMode.STREAMING, def);
        modeTable.put(com.launchdarkly.sdk.android.ConnectionMode.BACKGROUND, def);
        modeTable.put(com.launchdarkly.sdk.android.ConnectionMode.OFFLINE, new ModeDefinition(
                Collections.<DataSourceBuilder<Initializer>>emptyList(),
                Collections.<DataSourceBuilder<Synchronizer>>emptyList(),
                null));

        final com.launchdarkly.sdk.android.ConnectionMode[] activeModeCapture = new com.launchdarkly.sdk.android.ConnectionMode[1];

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(
                modeTable,
                com.launchdarkly.sdk.android.ConnectionMode.STREAMING,
                custom) {
            @Override
            void setActiveMode(com.launchdarkly.sdk.android.ConnectionMode mode, boolean includeInitializers) {
                activeModeCapture[0] = mode;
                super.setActiveMode(mode, includeInitializers);
            }

            @Override
            public DataSource build(ClientContext clientContext) {
                receivedClientContexts.add(clientContext);
                return MockComponents.successfulDataSource(clientContext, DATA,
                        ConnectionMode.STREAMING, startedDataSources, stoppedDataSources);
            }
        };

        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), builder);
        awaitStartUp();

        assertEquals(com.launchdarkly.sdk.android.ConnectionMode.POLLING, activeModeCapture[0]);
        verifyAll();
    }

    @Test
    public void fdv2_equivalentConfigDoesNotRebuild() throws Exception {
        ModeDefinition sharedDef = new ModeDefinition(
                Collections.<DataSourceBuilder<Initializer>>emptyList(),
                Collections.<DataSourceBuilder<Synchronizer>>singletonList(inputs -> null),
                null
        );
        Map<com.launchdarkly.sdk.android.ConnectionMode, ModeDefinition> table = new LinkedHashMap<>();
        table.put(com.launchdarkly.sdk.android.ConnectionMode.STREAMING, sharedDef);
        table.put(com.launchdarkly.sdk.android.ConnectionMode.BACKGROUND, sharedDef);

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(table, com.launchdarkly.sdk.android.ConnectionMode.STREAMING) {
            @Override
            public DataSource build(ClientContext clientContext) {
                receivedClientContexts.add(clientContext);
                return MockComponents.successfulDataSource(clientContext, DATA,
                        ConnectionMode.STREAMING, startedDataSources, stoppedDataSources);
            }
        };

        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(true);
        eventProcessor.flush(); // CONNMODE 3.3.1: flush before background transition
        replayAll();

        createTestManager(defaultTestConfig(false, false), builder);
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        // STREAMING and BACKGROUND share the same ModeDefinition object, so 5.3.8 says no rebuild
        mockPlatformState.setAndNotifyForegroundChangeListeners(false);

        // Wait longer than debounce window to confirm no rebuild occurs
        verifyNoMoreDataSourcesWereCreatedAfterDebounce();
        verifyNoMoreDataSourcesWereStopped();
        verifyAll();
    }

    @Test
    public void fdv2_contextChange_rebuildsDataSource() throws Exception {
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        LDContext context2 = LDContext.create("context2");
        AwaitableCallback<Void> done = new AwaitableCallback<>();
        contextDataManager.switchToContext(context2, false, done);
        done.await();

        verifyDataSourceWasStopped();
        verifyForegroundDataSourceWasCreatedAndStarted(context2);
        verifyNoMoreDataSourcesWereCreated();
        verifyAll();
    }

    @Test
    public void fdv2_modeSwitchIncludesInitializersWhenSelectorEmpty() throws Exception {
        BlockingQueue<Boolean> initializerIncluded = new LinkedBlockingQueue<>();

        Map<com.launchdarkly.sdk.android.ConnectionMode, ModeDefinition> table = new LinkedHashMap<>();
        table.put(com.launchdarkly.sdk.android.ConnectionMode.STREAMING, new ModeDefinition(
                Collections.<DataSourceBuilder<Initializer>>singletonList(inputs -> null),
                Collections.<DataSourceBuilder<Synchronizer>>singletonList(inputs -> null),
                null
        ));
        table.put(com.launchdarkly.sdk.android.ConnectionMode.BACKGROUND, new ModeDefinition(
                Collections.<DataSourceBuilder<Initializer>>singletonList(inputs -> null),
                Collections.<DataSourceBuilder<Synchronizer>>singletonList(inputs -> null),
                null
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(table, com.launchdarkly.sdk.android.ConnectionMode.STREAMING) {
            @Override
            public DataSource build(ClientContext clientContext) {
                initializerIncluded.offer(readIncludeInitializersFlag(this));
                receivedClientContexts.add(clientContext);
                return MockComponents.successfulDataSource(clientContext, DATA,
                        ConnectionMode.STREAMING, startedDataSources, stoppedDataSources);
            }
        };

        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(true);
        eventProcessor.flush(); // CONNMODE 3.3.1: flush before background transition
        replayAll();

        createTestManager(defaultTestConfig(false, false), builder);
        awaitStartUp();
        assertEquals(Boolean.TRUE, initializerIncluded.poll(1, TimeUnit.SECONDS));
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        mockPlatformState.setAndNotifyForegroundChangeListeners(false);

        verifyDataSourceWasStopped();
        // Matches js-core FDv2DataManagerBase: includeInitializers = !selector — empty selector
        // means the pipeline may still need initializer work after a mode change.
        assertEquals(Boolean.TRUE, initializerIncluded.poll(2, TimeUnit.SECONDS));
        requireValue(receivedClientContexts, 2, TimeUnit.SECONDS, "bg data source creation");
        requireValue(startedDataSources, 2, TimeUnit.SECONDS, "bg data source started");
        verifyAll();
    }

    @Test
    public void fdv2_modeSwitchExcludesInitializersWhenSelectorNonEmpty() throws Exception {
        BlockingQueue<Boolean> initializerIncluded = new LinkedBlockingQueue<>();

        Map<com.launchdarkly.sdk.android.ConnectionMode, ModeDefinition> table = new LinkedHashMap<>();
        table.put(com.launchdarkly.sdk.android.ConnectionMode.STREAMING, new ModeDefinition(
                Collections.<DataSourceBuilder<Initializer>>singletonList(inputs -> null),
                Collections.<DataSourceBuilder<Synchronizer>>singletonList(inputs -> null),
                null
        ));
        table.put(com.launchdarkly.sdk.android.ConnectionMode.BACKGROUND, new ModeDefinition(
                Collections.<DataSourceBuilder<Initializer>>singletonList(inputs -> null),
                Collections.<DataSourceBuilder<Synchronizer>>singletonList(inputs -> null),
                null
        ));

        FDv2DataSourceBuilder builder = new FDv2DataSourceBuilder(table, com.launchdarkly.sdk.android.ConnectionMode.STREAMING) {
            @Override
            public DataSource build(ClientContext clientContext) {
                initializerIncluded.offer(readIncludeInitializersFlag(this));
                receivedClientContexts.add(clientContext);
                return MockComponents.successfulDataSource(clientContext, DATA,
                        ConnectionMode.STREAMING, startedDataSources, stoppedDataSources);
            }
        };

        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(true);
        eventProcessor.flush(); // CONNMODE 3.3.1: flush before background transition
        replayAll();

        createTestManager(defaultTestConfig(false, false), builder);
        awaitStartUp();
        assertEquals(Boolean.TRUE, initializerIncluded.poll(1, TimeUnit.SECONDS));

        Flag flag = new FlagBuilder("flag1").version(1).build();
        Map<String, Flag> items = new HashMap<>();
        items.put(flag.getKey(), flag);
        Selector selector = Selector.make(1, "state-1");
        contextDataManager.apply(CONTEXT, new ChangeSet<>(
                ChangeSetType.Full, selector, items, null, false));

        mockPlatformState.setAndNotifyForegroundChangeListeners(false);

        verifyDataSourceWasStopped();
        assertEquals(Boolean.FALSE, initializerIncluded.poll(2, TimeUnit.SECONDS));
        requireValue(receivedClientContexts, 2, TimeUnit.SECONDS, "bg data source creation");
        requireValue(startedDataSources, 2, TimeUnit.SECONDS, "bg data source started");
        verifyAll();
    }

    @Test
    public void fdv2_lifecycleSwitchingDisabled_doesNotRebuildOnForegroundChange() throws Exception {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey(MOBILE_KEY)
                .connectionModeStateDebounceMs(FDV2_TEST_DEBOUNCE_MS)
                .dataSystem(
                        Components.dataSystem()
                                .automaticModeSwitching(
                                        DataSystemComponents.automaticModeSwitching()
                                                .lifecycle(false)
                                                .network(true)
                                                .build()))
                .build();

        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(true);
        eventProcessor.flush(); // CONNMODE 3.3.1: flush before background transition (independent of lifecycle switching)
        replayAll();

        createTestManager(config, makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        mockPlatformState.setAndNotifyForegroundChangeListeners(false);

        verifyNoMoreDataSourcesWereCreated();
        verifyNoMoreDataSourcesWereStopped();
        verifyAll();
    }

    @Test
    public void fdv2_networkSwitchingDisabled_doesNotRebuildOnConnectivityChange() throws Exception {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey(MOBILE_KEY)
                .connectionModeStateDebounceMs(FDV2_TEST_DEBOUNCE_MS)
                .dataSystem(
                        Components.dataSystem()
                                .automaticModeSwitching(
                                        DataSystemComponents.automaticModeSwitching()
                                                .lifecycle(true)
                                                .network(false)
                                                .build()))
                .build();

        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(true);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(config, makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        mockPlatformState.setAndNotifyConnectivityChangeListeners(false);

        verifyNoMoreDataSourcesWereCreated();
        verifyNoMoreDataSourcesWereStopped();
        verifyAll();
    }

    @Test
    public void fdv2_fullyDisabled_lifecycleChangeDoesNotRebuildDataSource() throws Exception {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey(MOBILE_KEY)
                .connectionModeStateDebounceMs(FDV2_TEST_DEBOUNCE_MS)
                .dataSystem(
                        Components.dataSystem()
                                .automaticModeSwitching(AutomaticModeSwitchingConfig.disabled()))
                .build();

        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(true);
        eventProcessor.flush(); // CONNMODE 3.3.1: flush before background transition (independent of mode switching)
        replayAll();

        createTestManager(config, makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        mockPlatformState.setAndNotifyForegroundChangeListeners(false);

        verifyNoMoreDataSourcesWereCreated();
        verifyNoMoreDataSourcesWereStopped();
        verifyAll();
    }

    @Test
    public void fdv2_fullyDisabled_connectivityChangeDoesNotRebuildDataSource() throws Exception {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey(MOBILE_KEY)
                .connectionModeStateDebounceMs(FDV2_TEST_DEBOUNCE_MS)
                .dataSystem(
                        Components.dataSystem()
                                .automaticModeSwitching(AutomaticModeSwitchingConfig.disabled()))
                .build();

        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(true);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(config, makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        mockPlatformState.setAndNotifyConnectivityChangeListeners(false);

        verifyNoMoreDataSourcesWereCreated();
        verifyNoMoreDataSourcesWereStopped();
        verifyAll();
    }

    // ==== FDv2 debouncing tests ====
    //
    // These tests verify that CONNMODE 3.5.x debouncing behavior is correctly wired
    // into ConnectivityManager for FDv2 data sources.

    @Test
    public void fdv2_rapidStateChangesCoalesceIntoOneRebuild() throws Exception {
        // CONNMODE 3.5.1-3.5.3: rapid state changes should coalesce into a single rebuild
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyAll();

        // After startup, the rapid connectivity changes call updateEventProcessor in
        // parallel threads, so ordering is nondeterministic. Use anyTimes().
        resetAll();
        eventProcessor.setOffline(anyBoolean());
        expectLastCall().anyTimes();
        eventProcessor.setInBackground(anyBoolean());
        expectLastCall().anyTimes();
        replayAll();

        // Fire multiple rapid connectivity changes — debounce should coalesce them
        mockPlatformState.setAndNotifyConnectivityChangeListeners(false);
        mockPlatformState.setAndNotifyConnectivityChangeListeners(true);
        mockPlatformState.setAndNotifyConnectivityChangeListeners(false);

        // Should result in exactly one data source rebuild (to OFFLINE)
        verifyDataSourceWasStopped();
        requireValue(receivedClientContexts, 2, TimeUnit.SECONDS, "offline data source creation");
        requireValue(startedDataSources, 2, TimeUnit.SECONDS, "offline data source started");
        verifyNoMoreDataSourcesWereCreatedAfterDebounce();
    }

    @Test
    public void fdv2_identifyBypassesDebounce() throws Exception {
        // CONNMODE 3.5.6: identify does not participate in debounce
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        // The debouncer is reset (not rebuilt) on identify, so the same instance
        // must survive across context switches.
        StateDebounceManager debouncerBefore = readDebounceManager(connectivityManager);
        assertNotNull("debouncer should exist after startup", debouncerBefore);

        // identify should rebuild immediately, not waiting for debounce
        LDContext context2 = LDContext.create("context2");
        AwaitableCallback<Void> done = new AwaitableCallback<>();
        contextDataManager.switchToContext(context2, false, done);
        done.await();

        verifyDataSourceWasStopped();
        verifyForegroundDataSourceWasCreatedAndStarted(context2);
        verifyNoMoreDataSourcesWereCreated();
        verifyAll();

        StateDebounceManager debouncerAfter = readDebounceManager(connectivityManager);
        assertSame("identify should reset the debouncer in place, not replace it",
                debouncerBefore, debouncerAfter);
    }

    @Test
    public void fdv2_identifyAfterShutdownIsNoOp() throws Exception {
        // After shutDown(), an identify must short-circuit instead of touching
        // closed components (debouncer, event processor, data sources).
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        connectivityManager.shutDown();
        verifyDataSourceWasStopped();

        // After shutDown, identify must succeed (callback fires) without producing
        // any further mock interactions or data sources. The strict mock's verifyAll()
        // below would catch any unexpected calls.
        LDContext context2 = LDContext.create("context2");
        AwaitableCallback<Void> done = new AwaitableCallback<>();
        contextDataManager.switchToContext(context2, false, done);
        done.await();

        verifyNoMoreDataSourcesWereCreated();
        verifyAll();
    }

    @Test
    public void fdv2_setForceOfflineAfterShutdownIsNoOp() throws Exception {
        // After shutDown(), setForceOffline must not rebuild any data source.
        // The event processor is still updated (setForceOffline runs before
        // handleStateChange's closed guard), but no further data-source
        // creation or start may occur.
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(true);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        connectivityManager.shutDown();
        verifyDataSourceWasStopped();

        connectivityManager.setForceOffline(true);

        verifyNoMoreDataSourcesWereCreated();
        verifyAll();
    }

    @Test
    public void fdv2_eventsFlushedOnBackgroundTransition() throws Exception {
        // CONNMODE 3.3.1: flush pending events before background transition
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(true);
        eventProcessor.flush(); // CONNMODE 3.3.1: flush happens before debounce timer is set
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        mockPlatformState.setAndNotifyForegroundChangeListeners(false);

        // The flush should happen immediately (before the debounce fires),
        // and the strict mock verifyAll() confirms the expected call sequence
        verifyDataSourceWasStopped();
        requireValue(receivedClientContexts, 2, TimeUnit.SECONDS, "bg data source creation");
        requireValue(startedDataSources, 2, TimeUnit.SECONDS, "bg data source started");
        verifyAll();
    }

    @Test
    public void fdv1_eventsFlushedOnBackgroundTransition() throws Exception {
        // CONNMODE 3.3.1: the background-transition flush is independent of FDv2 mode
        // resolution — FDv1 must also flush queued events before the OS may kill us.
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(true);
        eventProcessor.flush();
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeSuccessfulDataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        mockPlatformState.setAndNotifyForegroundChangeListeners(false);

        ConnectionMode bgMode = awaitConnectionModeChangedFrom(ConnectionMode.POLLING);
        assertEquals(ConnectionMode.BACKGROUND_POLLING, bgMode);
        verifyAll();
    }

    @Test
    public void fdv2_lifecycleSwitchingDisabled_stillFlushesOnBackgroundTransition() throws Exception {
        // CONNMODE 3.3.1: the background-transition flush is independent of whether
        // lifecycle-driven mode switching is enabled. Even when the user has opted out
        // of automatic background mode switching, queued events must still be flushed
        // because the OS may kill the app at any moment after backgrounding.
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey(MOBILE_KEY)
                .connectionModeStateDebounceMs(FDV2_TEST_DEBOUNCE_MS)
                .dataSystem(
                        Components.dataSystem()
                                .automaticModeSwitching(
                                        DataSystemComponents.automaticModeSwitching()
                                                .lifecycle(false)
                                                .network(true)
                                                .build()))
                .build();

        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(true);
        eventProcessor.flush();
        replayAll();

        createTestManager(config, makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        mockPlatformState.setAndNotifyForegroundChangeListeners(false);

        // Lifecycle switching is off, so no rebuild is expected — but the flush must still happen.
        verifyNoMoreDataSourcesWereCreated();
        verifyNoMoreDataSourcesWereStopped();
        verifyAll();
    }

    @Test
    public void fdv2_forceOfflineBypassesDebounce() throws Exception {
        // setForceOffline remains immediate per design — not debounced
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(true);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        connectivityManager.setForceOffline(true);

        verifyDataSourceWasStopped();
        requireValue(receivedClientContexts, 1, TimeUnit.SECONDS, "offline data source creation");
        requireValue(startedDataSources, 1, TimeUnit.SECONDS, "offline data source started");
        verifyAll();
    }

    @Test
    public void fdv2_shutdownClosesDebounceManager() throws Exception {
        // After shutDown(), debounced state changes should not trigger any rebuilds
        eventProcessor.setOffline(false);
        eventProcessor.setInBackground(false);
        eventProcessor.setOffline(true);
        eventProcessor.setInBackground(false);
        replayAll();

        createTestManager(defaultTestConfig(false, false), makeFDv2DataSourceFactory());
        awaitStartUp();
        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        // Actually schedule a pending debounce by notifying listeners, then shut down.
        // Sleep briefly to allow MockPlatformState's background listener thread to fire
        // the callback and schedule the debounce timer, but less than the debounce window
        // so the timer hasn't fired yet when shutDown() cancels it.
        mockPlatformState.setAndNotifyConnectivityChangeListeners(false);
        Thread.sleep(FDV2_TEST_DEBOUNCE_MS / 3);
        connectivityManager.shutDown();

        verifyDataSourceWasStopped();
        // No additional data sources should be created despite the pending state change
        verifyNoMoreDataSourcesWereCreatedAfterDebounce();
        verifyAll();
    }

    private static boolean readIncludeInitializersFlag(FDv2DataSourceBuilder builder) {
        try {
            java.lang.reflect.Field f = FDv2DataSourceBuilder.class.getDeclaredField("includeInitializers");
            f.setAccessible(true);
            return f.getBoolean(builder);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static StateDebounceManager readDebounceManager(ConnectivityManager cm) {
        try {
            java.lang.reflect.Field f = ConnectivityManager.class.getDeclaredField("stateDebounceManager");
            f.setAccessible(true);
            return (StateDebounceManager) f.get(cm);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}