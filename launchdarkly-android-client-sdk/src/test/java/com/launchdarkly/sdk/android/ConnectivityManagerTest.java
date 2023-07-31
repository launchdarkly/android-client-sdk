package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.android.TestUtil.requireNoMoreValues;
import static com.launchdarkly.sdk.android.TestUtil.requireValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.ConnectionInformation.ConnectionMode;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
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

    private static final LDContext CONTEXT = LDContext.create("test-context");
    private static final String MOBILE_KEY = "test-mobile-key";
    private static final EnvironmentData DATA = new DataSetBuilder()
            .add("flag1", 1, LDValue.of(true), 0)
            .build();
    private static final ConnectionMode EXPECTED_FOREGROUND_MODE = ConnectionMode.POLLING;
    private static final ConnectionMode EXPECTED_BACKGROUND_MODE = ConnectionMode.BACKGROUND_POLLING;

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

    private void createTestManager(
            boolean setOffline,
            boolean backgroundDisabled,
            ComponentConfigurer<DataSource> dataSourceConfigurer
    ) {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey(MOBILE_KEY)
                .offline(setOffline)
                .disableBackgroundUpdating(backgroundDisabled)
                .build();

        ClientContext clientContext = ClientContextImpl.fromConfig(
                config,
                MOBILE_KEY,
                "",
                null,
                CONTEXT,
                logging.logger,
                mockPlatformState,
                environmentReporter,
                taskExecutor
        );

        contextDataManager = new ContextDataManager(
                clientContext,
                environmentStore,
                1
        );
        contextDataManager.registerAllFlagsListener(flagsUpdated -> {
            allFlagsReceived.add(flagsUpdated);
        });

        connectivityManager = new ConnectivityManager(
                clientContext,
                dataSourceConfigurer,
                eventProcessor,
                contextDataManager,
                environmentStore
        );
    }

    private void awaitStartUp() {
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        connectivityManager.startUp(awaitableCallback);
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

        createTestManager(true, false, mockDataSourceConfigurerIsNeverCalled);

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

        createTestManager(false, true, mockDataSourceConfigurerIsNeverCalled);

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

        createTestManager(false, true, mockDataSourceConfigurerIsNeverCalled);

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

        createTestManager(false, false, makeSuccessfulDataSourceFactory());

        awaitStartUp();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());

        assertEquals(EXPECTED_FOREGROUND_MODE, connectivityManager.getConnectionInformation().getConnectionMode());
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

        createTestManager(false, false, makeSuccessfulDataSourceFactory());

        awaitStartUp();
        verifyAll();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());

        assertEquals(EXPECTED_BACKGROUND_MODE, connectivityManager.getConnectionInformation().getConnectionMode());
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
                MockComponents.failingDataSource(clientContext, EXPECTED_FOREGROUND_MODE, testFailure);

        createTestManager(false, false, dataSourceConfigurer);

        awaitStartUp();
        verifyAll();

        assertFalse(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());
        assertEquals(EXPECTED_FOREGROUND_MODE, connectivityManager.getConnectionInformation().getConnectionMode());
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
                MockComponents.failingDataSource(clientContext, EXPECTED_FOREGROUND_MODE, testError);

        createTestManager(false, false, dataSourceConfigurer);

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

        createTestManager(false, false, makeSuccessfulDataSourceFactory());

        awaitStartUp();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());
        assertEquals(EXPECTED_FOREGROUND_MODE, connectivityManager.getConnectionInformation().getConnectionMode());

        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyNoMoreDataSourcesWereCreated();

        resetAll();
        eventProcessor.setOffline(true); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        connectivityManager.setForceOffline(true);

        // We don't currently have a good way to wait for this state change to take effect, so we'll
        // poll for it.
        ConnectionMode newConnectionMode = awaitConnectionModeChangedFrom(EXPECTED_FOREGROUND_MODE);
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

        createTestManager(false, false, makeSuccessfulDataSourceFactory());

        awaitStartUp();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());
        assertEquals(ConnectionMode.POLLING, connectivityManager.getConnectionInformation().getConnectionMode());

        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);

        resetAll();
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(true); // we expect this call
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
        createTestManager(false, false, makeDataSourceThatDoesNotRefresh);

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

        createTestManager(false, true, makeSuccessfulDataSourceFactory());

        awaitStartUp();

        assertTrue(connectivityManager.isInitialized());
        assertFalse(connectivityManager.isForcedOffline());
        assertEquals(ConnectionMode.POLLING, connectivityManager.getConnectionInformation().getConnectionMode());

        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyNoMoreDataSourcesWereCreated();

        resetAll();
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(true); // we expect this call
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

        createTestManager(false, false, makeSuccessfulDataSourceFactory());

        awaitStartUp();
        verifyAll();

        verifyForegroundDataSourceWasCreatedAndStarted(CONTEXT);
        verifyNoMoreDataSourcesWereCreated();

        resetAll();
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        LDContext context2 = LDContext.create("context2");
        contextDataManager.setCurrentContext(context2);
        AwaitableCallback<Void> done = new AwaitableCallback<>();
        connectivityManager.setEvaluationContext(context2, done);
        done.await();

        verifyAll(); // verifies eventProcessor calls
        verifyDataSourceWasStopped();
        verifyForegroundDataSourceWasCreatedAndStarted(context2);
        verifyNoMoreDataSourcesWereCreated();
    }

    @Test
    public void refreshDataSourceWhileOffline() {
        eventProcessor.setOffline(true); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        createTestManager(true, false, makeSuccessfulDataSourceFactory());

        awaitStartUp();
        verifyAll();

        verifyNoMoreDataSourcesWereCreated();

        resetAll();
        eventProcessor.setOffline(true); // we expect this call
        eventProcessor.setInBackground(false); // we expect this call
        replayAll();

        LDContext context2 = LDContext.create("context2");
        contextDataManager.setCurrentContext(context2);
        connectivityManager.setEvaluationContext(context2, LDUtil.noOpCallback());

        verifyAll(); // verifies eventProcessor calls
        verifyNoMoreDataSourcesWereCreated();
    }

    @Test
    public void refreshDataSourceWhileInBackgroundWithBackgroundPollingDisabled() {
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(true); // we expect this call
        replayAll();

        mockPlatformState.setForeground(false);

        createTestManager(false, true, makeSuccessfulDataSourceFactory());

        awaitStartUp();
        verifyAll();

        verifyNoMoreDataSourcesWereCreated();

        resetAll();
        eventProcessor.setOffline(false); // we expect this call
        eventProcessor.setInBackground(true); // we expect this call
        replayAll();

        LDContext context2 = LDContext.create("context2");
        contextDataManager.setCurrentContext(context2);
        connectivityManager.setEvaluationContext(context2, LDUtil.noOpCallback());

        verifyAll(); // verifies eventProcessor calls
        verifyNoMoreDataSourcesWereCreated();
    }

    private ComponentConfigurer<DataSource> makeSuccessfulDataSourceFactory() {
        return clientContext -> makeSuccessfulDataSource(clientContext);
    }

    private DataSource makeSuccessfulDataSource(ClientContext clientContext) {
        receivedClientContexts.add(clientContext);
        return MockComponents.successfulDataSource(clientContext, DATA,
                clientContext.isInBackground() ? EXPECTED_BACKGROUND_MODE : EXPECTED_FOREGROUND_MODE,
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

    private void verifyNoMoreDataSourcesWereStopped() {
        requireNoMoreValues(stoppedDataSources, 1, TimeUnit.SECONDS, "stopping of data source");
    }
}