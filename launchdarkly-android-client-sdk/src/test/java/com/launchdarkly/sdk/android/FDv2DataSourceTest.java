package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.DataModel;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.env.EnvironmentReporterBuilder;
import com.launchdarkly.sdk.android.env.IEnvironmentReporter;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.DataSourceState;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.Synchronizer;
import com.launchdarkly.sdk.fdv2.Selector;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class FDv2DataSourceTest {

    private static final LDContext CONTEXT = LDContext.create("test-context");
    private static final IEnvironmentReporter ENV_REPORTER = new EnvironmentReporterBuilder().build();

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();
    @Rule
    public Timeout globalTimeout = Timeout.seconds(15);

    private static final long AWAIT_TIMEOUT_SECONDS = 10;

    private ScheduledExecutorService executor;

    @Before
    public void setUp() {
        executor = Executors.newScheduledThreadPool(2);
    }

    @After
    public void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    private FDv2DataSource buildDataSource(
            MockComponents.MockDataSourceUpdateSink sink,
            List<FDv2DataSource.DataSourceFactory<Initializer>> initializers,
            List<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers) {
        return new FDv2DataSource(
                CONTEXT,
                initializers,
                synchronizers,
                null,
                sink,
                executor,
                logging.logger);
    }

    private FDv2DataSource buildDataSource(
            MockComponents.MockDataSourceUpdateSink sink,
            List<FDv2DataSource.DataSourceFactory<Initializer>> initializers,
            List<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers,
            long fallbackTimeoutSeconds,
            long recoveryTimeoutSeconds) {
        return new FDv2DataSource(
                CONTEXT,
                initializers,
                synchronizers,
                null,
                sink,
                executor,
                logging.logger,
                fallbackTimeoutSeconds,
                recoveryTimeoutSeconds);
    }

    /** Starts the data source and returns a callback that will receive the start result. */
    private AwaitableCallback<Boolean> startDataSource(FDv2DataSource dataSource) {
        AwaitableCallback<Boolean> cb = new AwaitableCallback<>();
        dataSource.start(cb);
        return cb;
    }

    /** Awaits the callback, expecting it to report an error (onError was called). */
    private void awaitExpectingError(AwaitableCallback<?> cb) throws Exception {
        try {
            cb.await(AWAIT_TIMEOUT_SECONDS * 1000);
            fail("Expected callback to report an error");
        } catch (ExecutionException expected) {
            // The callback reported an error, as expected.
        }
    }

    /** Stops the data source synchronously. */
    private void stopDataSource(FDv2DataSource dataSource) throws Exception {
        AwaitableCallback<Void> cb = new AwaitableCallback<>();
        dataSource.stop(cb);
        cb.await(2000);
    }

    /** A ChangeSet with a non-empty selector (signals completion of initialization). */
    private static ChangeSet<Map<String, DataModel.Flag>> makeChangeSet(boolean withSelector) {
        Selector selector = withSelector ? Selector.make(1, "test-state") : Selector.EMPTY;
        return new ChangeSet<>(ChangeSetType.Full, selector, new HashMap<>(), null, true);
    }

    /** A ChangeSet carrying actual flag data. */
    private static ChangeSet<Map<String, DataModel.Flag>> makeFullChangeSet(Map<String, DataModel.Flag> items) {
        return new ChangeSet<>(
                ChangeSetType.Full,
                Selector.EMPTY,
                items != null ? items : new HashMap<>(),
                null,
                true);
    }

    private static FDv2SourceResult interrupted() {
        return FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(new RuntimeException("interrupted")), false);
    }

    private static FDv2SourceResult terminalError() {
        return FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(new RuntimeException("terminal error")), false);
    }

    /**
     * A pre-resolved or pre-rejected initializer. If constructed with a pending future,
     * close() will complete it with SHUTDOWN to unblock any in-progress run().get().
     */
    private static class MockInitializer implements Initializer {
        private final LDAwaitFuture<FDv2SourceResult> future;

        MockInitializer(FDv2SourceResult result) {
            this.future = new LDAwaitFuture<>();
            this.future.set(result);
        }

        MockInitializer(Throwable error) {
            this.future = new LDAwaitFuture<>();
            this.future.setException(error);
        }

        /** For slow or externally controlled futures. close() will complete it with SHUTDOWN. */
        MockInitializer(LDAwaitFuture<FDv2SourceResult> controlledFuture) {
            this.future = controlledFuture;
        }

        @Override
        public LDAwaitFuture<FDv2SourceResult> run() {
            return future;
        }

        @Override
        public void close() {
            future.set(FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false));
        }
    }

    /**
     * A synchronizer that returns one pre-set result on the first next(), then returns a
     * never-completing future (simulating an idle-but-open connection). close() makes subsequent
     * next() calls return SHUTDOWN immediately.
     */
    private static class MockSynchronizer implements Synchronizer {
        private final LDAwaitFuture<FDv2SourceResult> firstResult;
        private volatile boolean closed = false;
        private volatile boolean resultReturned = false;

        MockSynchronizer(FDv2SourceResult result) {
            this.firstResult = new LDAwaitFuture<>();
            this.firstResult.set(result);
        }

        MockSynchronizer(Throwable error) {
            this.firstResult = new LDAwaitFuture<>();
            this.firstResult.setException(error);
        }

        @Override
        public LDAwaitFuture<FDv2SourceResult> next() {
            if (closed) {
                LDAwaitFuture<FDv2SourceResult> f = new LDAwaitFuture<>();
                f.set(FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false));
                return f;
            }
            if (!resultReturned) {
                resultReturned = true;
                return firstResult;
            }
            return new LDAwaitFuture<>(); // never completes; simulates waiting for next event
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * A queue-backed synchronizer. Results are dequeued one at a time via next().
     * When the queue is empty, next() blocks until addResult() or close() is called.
     * close() delivers SHUTDOWN to any pending next() call.
     */
    private static class MockQueuedSynchronizer implements Synchronizer {
        private final BlockingQueue<FDv2SourceResult> queue = new LinkedBlockingQueue<>();
        private final Object lock = new Object();
        private volatile boolean closed = false;
        private LDAwaitFuture<FDv2SourceResult> pendingFuture = null;

        MockQueuedSynchronizer(FDv2SourceResult... results) {
            queue.addAll(Arrays.asList(results));
        }

        void addResult(FDv2SourceResult result) {
            synchronized (lock) {
                if (closed) return;
                if (pendingFuture != null) {
                    LDAwaitFuture<FDv2SourceResult> f = pendingFuture;
                    pendingFuture = null;
                    f.set(result);
                } else {
                    queue.add(result);
                }
            }
        }

        @Override
        public LDAwaitFuture<FDv2SourceResult> next() {
            synchronized (lock) {
                if (!queue.isEmpty()) {
                    LDAwaitFuture<FDv2SourceResult> f = new LDAwaitFuture<>();
                    f.set(queue.poll());
                    return f;
                }
                pendingFuture = new LDAwaitFuture<>();
                return pendingFuture;
            }
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (closed) return;
                closed = true;
                if (pendingFuture != null) {
                    LDAwaitFuture<FDv2SourceResult> f = pendingFuture;
                    pendingFuture = null;
                    f.set(FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false));
                }
            }
        }
    }

    @Test
    public void firstInitializerProvidesData_startSucceedsAndSinkReceivesApply() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        DataModel.Flag flag = new FlagBuilder("flag1").version(1).value(LDValue.of(true)).build();
        Map<String, DataModel.Flag> items = new HashMap<>();
        items.put(flag.getKey(), flag);

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(() -> new MockInitializer(FDv2SourceResult.changeSet(makeFullChangeSet(items), false))),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        ChangeSet<Map<String, DataModel.Flag>> applied = sink.expectApply();
        assertNotNull(applied);
        assertEquals(1, applied.getData().size());
        assertTrue(applied.getData().containsKey("flag1"));
    }

    @Test
    public void firstInitializerFailsSecondInitializerSucceeds() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicBoolean secondCalled = new AtomicBoolean(false);

        FDv2DataSource dataSource = buildDataSource(sink,
                Arrays.asList(
                        () -> new MockInitializer(new RuntimeException("first fails")),
                        () -> {
                            secondCalled.set(true);
                            return new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false));
                        }),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertTrue(secondCalled.get());
        assertEquals(1, sink.getApplyCount());
        assertEquals(DataSourceState.VALID, sink.getLastState());
    }

    @Test
    public void firstInitializerSucceedsWithSelectorSecondInitializerNotInvoked() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicBoolean secondCalled = new AtomicBoolean(false);

        FDv2DataSource dataSource = buildDataSource(sink,
                Arrays.asList(
                        () -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false)),
                        () -> {
                            secondCalled.set(true);
                            return new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false));
                        }),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertFalse(secondCalled.get());
        assertEquals(1, sink.getApplyCount());
        assertEquals(DataSourceState.VALID, sink.getLastState());
    }

    @Test
    public void allInitializersFailSwitchesToSynchronizers() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicBoolean syncCalled = new AtomicBoolean(false);

        FDv2DataSource dataSource = buildDataSource(sink,
                Arrays.asList(
                        () -> new MockInitializer(new RuntimeException("first fails")),
                        () -> new MockInitializer(new RuntimeException("second fails"))),
                Collections.singletonList(() -> {
                    syncCalled.set(true);
                    return new MockSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(false), false));
                }));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertTrue(syncCalled.get());
        sink.awaitApplyCount(1, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, sink.getApplyCount());
    }

    @Test
    public void allInitializersFailWithNoSynchronizers_startReportsNotInitialized() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(
                        () -> new MockInitializer(FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(new RuntimeException("fail")), false))),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        awaitExpectingError(startCallback);
    }

    @Test
    public void secondInitializerSucceeds_afterFirstReturnsTerminalError() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        DataModel.Flag flag = new FlagBuilder("flag1").version(1).value(LDValue.of(true)).build();
        Map<String, DataModel.Flag> items = new HashMap<>();
        items.put(flag.getKey(), flag);

        FDv2DataSource dataSource = buildDataSource(sink,
                Arrays.asList(
                        () -> new MockInitializer(FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(new RuntimeException("first fails")), false)),
                        () -> new MockInitializer(FDv2SourceResult.changeSet(makeFullChangeSet(items), false))),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        ChangeSet<Map<String, DataModel.Flag>> applied = sink.expectApply();
        assertNotNull(applied);
        assertEquals(1, applied.getData().size());
    }

    @Test
    public void oneInitializerNoSynchronizerIsWellBehaved() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(() -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false))),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertEquals(1, sink.getApplyCount());
        assertEquals(DataSourceState.VALID, sink.getLastState());
    }

    @Test
    public void noInitializers_synchronizerProvidesData_startSucceedsAndSinkReceivesApply() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        DataModel.Flag flag = new FlagBuilder("flag1").version(1).value(LDValue.of(true)).build();
        Map<String, DataModel.Flag> items = new HashMap<>();
        items.put(flag.getKey(), flag);

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeFullChangeSet(items), false),
                        FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        ChangeSet<Map<String, DataModel.Flag>> applied = sink.expectApply();
        assertNotNull(applied);
        assertEquals(1, applied.getData().size());
    }

    @Test
    public void noInitializersNoSynchronizers_startSucceedsWithNoData() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertTrue(sink.appliedChangeSets.isEmpty());
        assertEquals(DataSourceState.VALID, sink.getLastState());
    }

    @Test
    public void oneInitializerOneSynchronizerIsWellBehaved() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(() -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(false), false))),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, sink.getApplyCount());
    }

    @Test
    public void emptyInitializerListSkipsToSynchronizers() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicBoolean syncCalled = new AtomicBoolean(false);

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> {
                    syncCalled.set(true);
                    return new MockSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(false), false));
                }));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertTrue(syncCalled.get());
    }

    @Test
    public void fallbackAndRecoveryTasksWellBehaved() throws Exception {
        // First sync: changeset then INTERRUPTED; second sync: changeset; recovery brings back first
        MockQueuedSynchronizer firstSync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(false), false),
                interrupted());
        MockQueuedSynchronizer secondSync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(false), false));

        AtomicInteger firstCallCount = new AtomicInteger(0);
        AtomicInteger secondCallCount = new AtomicInteger(0);
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> { firstCallCount.incrementAndGet(); return firstSync; },
                        () -> { secondCallCount.incrementAndGet(); return secondSync; }),
                1, 2);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        // Wait for fallback + recovery: ~1s fallback + ~2s recovery.
        // Use generous timeouts for Android where thread scheduling can delay timer delivery.
        sink.awaitApplyCount(3, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        List<DataSourceState> statuses = sink.awaitStatuses(3, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(statuses.size() >= 3);
        assertEquals(DataSourceState.VALID, statuses.get(0));
        assertEquals(DataSourceState.INTERRUPTED, statuses.get(1));
        assertEquals(DataSourceState.VALID, statuses.get(2));

        assertTrue(firstCallCount.get() >= 2);
        assertTrue(secondCallCount.get() >= 1);
        stopDataSource(dataSource);
    }

    @Test
    public void canDisposeWhenSynchronizersFallingBack() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                        interrupted())),
                1, 2);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        stopDataSource(dataSource); // should not hang while fallback condition is waiting
    }

    @Test
    public void terminalErrorBlocksSynchronizer() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        BlockingQueue<Integer> callOrder = new LinkedBlockingQueue<>();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> { callOrder.offer(1); return new MockQueuedSynchronizer(
                                FDv2SourceResult.changeSet(makeChangeSet(false), false),
                                terminalError()); },
                        () -> { callOrder.offer(2); return new MockQueuedSynchronizer(
                                FDv2SourceResult.changeSet(makeChangeSet(false), false)); }));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertEquals(Integer.valueOf(1), callOrder.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(2), callOrder.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // Wait for: VALID (first changeset), INTERRUPTED (terminal error), VALID (second changeset)
        List<DataSourceState> statuses = sink.awaitStatuses(3, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(3, statuses.size());
        assertEquals(DataSourceState.VALID, statuses.get(0));
        assertEquals(DataSourceState.INTERRUPTED, statuses.get(1));
        assertEquals(DataSourceState.VALID, statuses.get(2));
        stopDataSource(dataSource);
    }

    @Test
    public void allThreeSynchronizersFailReportsExhaustion() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> new MockQueuedSynchronizer(terminalError()),
                        () -> new MockQueuedSynchronizer(terminalError()),
                        () -> new MockQueuedSynchronizer(terminalError())));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        awaitExpectingError(startCallback);

        List<DataSourceState> statuses = sink.awaitStatuses(4, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(4, statuses.size());
        assertEquals(DataSourceState.INTERRUPTED, statuses.get(0));
        assertEquals(DataSourceState.INTERRUPTED, statuses.get(1));
        assertEquals(DataSourceState.INTERRUPTED, statuses.get(2));
        assertEquals(DataSourceState.OFF, statuses.get(3));
        assertNotNull(sink.getLastError());
    }

    @Test
    public void blockedSynchronizerSkippedInRotation() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger firstCallCount = new AtomicInteger(0);
        AtomicInteger secondCallCount = new AtomicInteger(0);

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> { firstCallCount.incrementAndGet(); return new MockQueuedSynchronizer(terminalError()); },
                        () -> { secondCallCount.incrementAndGet(); return new MockQueuedSynchronizer(
                                FDv2SourceResult.changeSet(makeChangeSet(false), false)); }));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertEquals(1, firstCallCount.get()); // called once then blocked
        assertTrue(secondCallCount.get() >= 1);
        stopDataSource(dataSource);
    }

    @Test
    public void allSynchronizersBlockedReturnsNullAndExits() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> new MockQueuedSynchronizer(terminalError()),
                        () -> new MockQueuedSynchronizer(terminalError())));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        awaitExpectingError(startCallback);
    }

    @Test
    public void recoveryResetsToFirstAvailableSynchronizer() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger firstCallCount = new AtomicInteger(0);
        AtomicInteger secondCallCount = new AtomicInteger(0);

        MockQueuedSynchronizer firstSync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(false), false),
                interrupted());
        MockQueuedSynchronizer secondSync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(false), false));

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> { firstCallCount.incrementAndGet(); return firstSync; },
                        () -> { secondCallCount.incrementAndGet(); return secondSync; }),
                1, 2);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        // Wait for at least 3 applies (first sync apply, second sync apply, first sync recovery).
        // Use generous timeout for Android where ScheduledExecutor can be delayed.
        sink.awaitApplyCount(3, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(firstCallCount.get() >= 2 || secondCallCount.get() >= 1);
        stopDataSource(dataSource);
    }

    @Test
    public void fallbackMovesToNextSynchronizer() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        BlockingQueue<Boolean> secondCalledQueue = new LinkedBlockingQueue<>();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> new MockQueuedSynchronizer(
                                FDv2SourceResult.changeSet(makeChangeSet(false), false),
                                interrupted()),
                        () -> { secondCalledQueue.offer(true); return new MockQueuedSynchronizer(
                                FDv2SourceResult.changeSet(makeChangeSet(false), false)); }),
                1, 300);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        // Generous timeout for Android where fallback timer (1s) and thread scheduling can be delayed
        Boolean secondCalled = secondCalledQueue.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("second synchronizer should be called after fallback", secondCalled);
        stopDataSource(dataSource);
    }

    // ============================================================================
    // Stop / Close Lifecycle
    // ============================================================================

    @Test
    public void stop_closesSynchronizerAndCallsShutDown() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        stopDataSource(dataSource);

        assertTrue(sink.getApplyCount() >= 1);
    }

    @Test
    public void stopWithoutStartDoesNotHang() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        FDv2DataSource dataSource = buildDataSource(sink, Collections.emptyList(), Collections.emptyList());
        stopDataSource(dataSource); // should complete immediately without hanging
    }

    @Test
    public void stopAfterInitializersCompletesImmediately() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(() -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false))),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        stopDataSource(dataSource); // should return quickly
    }

    @Test
    public void closeWhileSynchronizerRunningShutdownsSource() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicBoolean syncClosed = new AtomicBoolean(false);

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(false), false)) {
                    @Override
                    public void close() {
                        syncClosed.set(true);
                        super.close();
                    }
                }));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        stopDataSource(dataSource);

        assertTrue(syncClosed.get());
    }

    @Test
    public void multipleStopCallsAreIdempotent() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(() -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false))),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        stopDataSource(dataSource);
        stopDataSource(dataSource);
        stopDataSource(dataSource); // should not throw
    }

    @Test
    public void closingDataSourceDuringInitializationCompletesStartCallback() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        LDAwaitFuture<FDv2SourceResult> slowFuture = new LDAwaitFuture<>();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(() -> new MockInitializer(slowFuture)),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        stopDataSource(dataSource); // close() completes slowFuture with SHUTDOWN via MockInitializer.close()

        // Start callback must eventually complete (not hang)
        awaitExpectingError(startCallback);

        DataSourceState offStatus = sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(offStatus);
        assertEquals(DataSourceState.OFF, offStatus);
    }

    @Test
    public void dataSourceClosedDuringSynchronizationReportsOffWithoutError() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        stopDataSource(dataSource);

        List<DataSourceState> statuses = sink.awaitStatuses(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, statuses.size());
        assertEquals(DataSourceState.VALID, statuses.get(0));
        assertEquals(DataSourceState.OFF, statuses.get(1));
        assertNull(sink.getLastError());
    }

    @Test
    public void stopInterruptsConditionWaiting() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        // One sync: sends changeset then INTERRUPTED (triggers fallback timer), then hangs
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                        interrupted())),
                120, 300);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        stopDataSource(dataSource); // should not hang while condition is waiting for fallback timeout
    }

    // ============================================================================
    // Multiple Start Calls / Concurrency
    // ============================================================================

    @Test
    public void startedFlagPreventsMultipleRuns() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger runCount = new AtomicInteger(0);

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(() -> {
                    runCount.incrementAndGet();
                    return new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false));
                }),
                Collections.emptyList());

        AwaitableCallback<Boolean> cb1 = new AwaitableCallback<>();
        AwaitableCallback<Boolean> cb2 = new AwaitableCallback<>();
        AwaitableCallback<Boolean> cb3 = new AwaitableCallback<>();
        dataSource.start(cb1);
        dataSource.start(cb2);
        dataSource.start(cb3);

        assertTrue(cb1.await(AWAIT_TIMEOUT_SECONDS * 1000));
        assertTrue(cb2.await(AWAIT_TIMEOUT_SECONDS * 1000));
        assertTrue(cb3.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertEquals(1, runCount.get()); // initializer factory only called once
    }

    @Test
    public void startBeforeRunCompletesAllComplete() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))));

        AwaitableCallback<Boolean> cb1 = new AwaitableCallback<>();
        AwaitableCallback<Boolean> cb2 = new AwaitableCallback<>();
        dataSource.start(cb1);
        dataSource.start(cb2);

        assertTrue(cb1.await(AWAIT_TIMEOUT_SECONDS * 1000));
        assertTrue(cb2.await(AWAIT_TIMEOUT_SECONDS * 1000));
        stopDataSource(dataSource);
    }

    @Test
    public void multipleStartCallsEventuallyComplete() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))));

        List<AwaitableCallback<Boolean>> callbacks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AwaitableCallback<Boolean> cb = new AwaitableCallback<>();
            dataSource.start(cb);
            callbacks.add(cb);
        }

        for (AwaitableCallback<Boolean> cb : callbacks) {
            assertTrue(cb.await(AWAIT_TIMEOUT_SECONDS * 1000));
        }
        stopDataSource(dataSource);
    }

    @Test
    public void concurrentStopAndStartHandledSafely() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        stopDataSource(dataSource); // stop immediately after starting

        // Should not hang regardless of whether the callback reports success or error.
        try {
            startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000);
        } catch (ExecutionException ignored) {
        }
    }

    @Test
    public void dataSourceUpdatesApplyThreadSafe() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        List<FDv2SourceResult> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(FDv2SourceResult.changeSet(makeChangeSet(false), false));
        }
        MockQueuedSynchronizer sync = new MockQueuedSynchronizer();
        for (FDv2SourceResult r : results) {
            sync.addResult(r);
        }

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> sync));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        sink.awaitApplyCount(10, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(sink.getApplyCount() >= 10);
        stopDataSource(dataSource);
    }

    // ============================================================================
    // Exception Handling
    // ============================================================================

    @Test
    public void initializerThrowsExecutionException_secondInitializerSucceeds() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicBoolean firstCalled = new AtomicBoolean(false);

        FDv2DataSource dataSource = buildDataSource(sink,
                Arrays.asList(
                        () -> { firstCalled.set(true); return new MockInitializer(new RuntimeException("execution exception")); },
                        () -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false))),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertTrue(firstCalled.get());
        assertEquals(1, sink.getApplyCount());
    }

    @Test
    public void initializerThrowsInterruptedException_secondInitializerSucceeds() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicBoolean firstCalled = new AtomicBoolean(false);

        FDv2DataSource dataSource = buildDataSource(sink,
                Arrays.asList(
                        () -> { firstCalled.set(true); return new MockInitializer(new InterruptedException("interrupted")); },
                        () -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false))),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertTrue(firstCalled.get());
        assertEquals(1, sink.getApplyCount());
    }

    @Test
    public void synchronizerThrowsExecutionException_nextSynchronizerSucceeds() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> new MockSynchronizer(new RuntimeException("execution exception")),
                        () -> new MockSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(false), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertEquals(1, sink.getApplyCount());
        stopDataSource(dataSource);
    }

    @Test
    public void synchronizerThrowsInterruptedException_nextSynchronizerSucceeds() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicBoolean firstCalled = new AtomicBoolean(false);

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> { firstCalled.set(true); return new MockSynchronizer(new InterruptedException("interrupted")); },
                        () -> new MockSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(false), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertTrue(firstCalled.get());
        assertEquals(1, sink.getApplyCount());
        stopDataSource(dataSource);
    }

    // ============================================================================
    // Active Source Management
    // ============================================================================

    @Test
    public void activeSourceClosedWhenSwitchingSynchronizers() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicBoolean firstClosed = new AtomicBoolean(false);

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> new MockQueuedSynchronizer(
                                FDv2SourceResult.changeSet(makeChangeSet(false), false),
                                terminalError()) {
                            @Override
                            public void close() {
                                firstClosed.set(true);
                                super.close();
                            }
                        },
                        () -> new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(false), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(firstClosed.get());
        stopDataSource(dataSource);
    }

    @Test
    public void activeSourceClosedOnShutdown() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicBoolean syncClosed = new AtomicBoolean(false);

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false)) {
                    @Override
                    public void close() {
                        syncClosed.set(true);
                        super.close();
                    }
                }));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        stopDataSource(dataSource);
        assertTrue(syncClosed.get());
    }

    @Test
    public void stopWhileInitializerRunningHandlesGracefully() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        CountDownLatch initializerStarted = new CountDownLatch(1);
        LDAwaitFuture<FDv2SourceResult> slowFuture = new LDAwaitFuture<>();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(() -> new MockInitializer(slowFuture) {
                    @Override
                    public LDAwaitFuture<FDv2SourceResult> run() {
                        initializerStarted.countDown();
                        return slowFuture;
                    }
                }),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(initializerStarted.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        stopDataSource(dataSource);
        // slowFuture is now completed with SHUTDOWN by MockInitializer.close()
        // Must not hang; error is acceptable since stop triggered exhaustion.
        try {
            startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000);
        } catch (ExecutionException ignored) {
        }
    }

    // ============================================================================
    // Condition Lifecycle
    // ============================================================================

    @Test
    public void conditionsClosedAfterSynchronizerLoop() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                        terminalError())),
                1, 2);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000)); // changeset arrives first, so start succeeds

        stopDataSource(dataSource); // should complete cleanly if conditions were closed
    }

    @Test
    public void conditionsInformedOfAllResults() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                        interrupted(),
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))),
                10, 20);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(sink.getApplyCount() >= 2);
        stopDataSource(dataSource);
    }

    @Test
    public void conditionsClosedOnException() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> new MockSynchronizer(new RuntimeException("error")),
                        () -> new MockSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(false), false))),
                1, 2);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        // If conditions were not closed on exception, resources would leak
        stopDataSource(dataSource);
    }

    @Test
    public void primeSynchronizerHasNoRecoveryCondition() throws Exception {
        // The prime synchronizer (first available) gets only a FallbackCondition, not a RecoveryCondition.
        // With two synchronizers and a very long recovery timeout, the prime sync should stay
        // connected indefinitely without being interrupted by a recovery timer.
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        MockQueuedSynchronizer firstSync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(false), false));

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> firstSync,
                        () -> new MockQueuedSynchronizer()),
                1, 2);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        // Give the recovery timer time to fire if it incorrectly exists (it shouldn't)
        Thread.sleep(100);

        // The first (prime) synchronizer should still be the active one; no switch should have happened
        assertEquals(1, sink.getApplyCount());
        stopDataSource(dataSource);
    }

    @Test
    public void singleSynchronizerHasNoConditions() throws Exception {
        // With only one synchronizer, there should be no fallback or recovery timers.
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))),
                1, 2);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, sink.getApplyCount()); // both changesets processed without condition interruption
        stopDataSource(dataSource);
    }

    @Test
    public void conditionFutureNeverCompletesWhenNoConditions() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))),
                1, 2);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, sink.getApplyCount()); // both processed; conditions future never fired
        stopDataSource(dataSource);
    }

    // ============================================================================
    // Data Flow Verification
    // ============================================================================

    @Test
    public void selectorNonEmptyCompletesInitialization() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        BlockingQueue<Boolean> secondCalledQueue = new LinkedBlockingQueue<>();

        FDv2DataSource dataSource = buildDataSource(sink,
                Arrays.asList(
                        () -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false)),
                        () -> {
                            secondCalledQueue.offer(true);
                            return new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(false), false));
                        }),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertEquals(1, sink.getApplyCount());
        assertNull("second initializer should not be called when first has non-empty selector",
                secondCalledQueue.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    public void initializerChangeSetWithoutSelectorCompletesIfLastInitializer() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(() -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(false), false))),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertEquals(1, sink.getApplyCount());
        assertEquals(DataSourceState.VALID, sink.getLastState());
    }

    @Test
    public void synchronizerChangeSetAlwaysCompletesStartFuture() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(false), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        stopDataSource(dataSource);
    }

    @Test
    public void multipleChangeSetsAppliedInOrder() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        sink.awaitApplyCount(3, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(3, sink.getApplyCount());
        stopDataSource(dataSource);
    }

    @Test
    public void goodbyeStatusHandledGracefully() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                        FDv2SourceResult.status(FDv2SourceResult.Status.goodbye("server-requested"), false),
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(sink.getApplyCount() >= 2);
        stopDataSource(dataSource);
    }

    @Test
    public void shutdownStatusFallsBackToNextSynchronizer() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicBoolean secondSyncCalled = new AtomicBoolean(false);

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> new MockQueuedSynchronizer(
                                FDv2SourceResult.changeSet(makeChangeSet(false), false),
                                FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false)),
                        () -> { secondSyncCalled.set(true); return new MockQueuedSynchronizer(); }));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        sink.awaitApplyCount(1, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // Wait for FDv2DataSource thread to process SHUTDOWN and build second sync (can lag on Android)
        for (int i = 0; i < 100 && !secondSyncCalled.get(); i++) {
            Thread.sleep(50);
        }
        assertTrue(secondSyncCalled.get()); // SHUTDOWN from first sync causes fallback to second
        stopDataSource(dataSource);
    }

    @Test
    public void statusTransitionsToValidAfterInitialization() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(() -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(false), false))),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        DataSourceState status = sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(status);
        assertEquals(DataSourceState.VALID, status);
        assertEquals(DataSourceState.VALID, sink.getLastState());
        assertNull(sink.getLastError());
    }

    @Test
    public void statusIncludesErrorInfoOnFailure() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        RuntimeException terminalErr = new RuntimeException("terminal failure");

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(terminalErr), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        awaitExpectingError(startCallback);

        DataSourceState first = sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(DataSourceState.INTERRUPTED, first);

        DataSourceState second = sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(DataSourceState.OFF, second);

        assertEquals(DataSourceState.OFF, sink.getLastState());
        assertNotNull(sink.getLastError());
    }

    @Test
    public void statusRemainsValidDuringSynchronizerOperation() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        sink.awaitApplyCount(3, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(DataSourceState.VALID, sink.getLastState());
        assertEquals(3, sink.getApplyCount());
        stopDataSource(dataSource);
    }

    @Test
    public void statusTransitionsFromValidToOffWhenAllSynchronizersFail() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        RuntimeException err = new RuntimeException("server error");

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                        FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(err), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000)); // changeset arrives first, so start succeeds

        assertEquals(DataSourceState.VALID, sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(DataSourceState.INTERRUPTED, sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(DataSourceState.OFF, sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertEquals(DataSourceState.OFF, sink.getLastState());
        assertNotNull(sink.getLastError());
    }

    @Test
    public void stopReportsOffStatus() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        DataSourceState validStatus = sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(DataSourceState.VALID, validStatus);

        stopDataSource(dataSource);

        DataSourceState offStatus = sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(DataSourceState.OFF, offStatus);
    }

    // ---- FDv1 fallback ----

    @Test
    public void fdv1FallbackDuringInitializationSkipsRemainingInitializers() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicBoolean secondInitCalled = new AtomicBoolean(false);

        MockQueuedSynchronizer fdv1Sync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(true), false));

        FDv2DataSource dataSource = new FDv2DataSource(
                CONTEXT,
                Arrays.<FDv2DataSource.DataSourceFactory<Initializer>>asList(
                        () -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(false), true)),
                        () -> {
                            secondInitCalled.set(true);
                            return new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false));
                        }),
                Collections.<FDv2DataSource.DataSourceFactory<Synchronizer>>singletonList(
                        () -> new MockSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(false), false))),
                () -> fdv1Sync,
                sink,
                executor,
                logging.logger);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertFalse(secondInitCalled.get());

        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        stopDataSource(dataSource);
    }

    @Test
    public void fdv1FallbackOnTerminalErrorDuringInitializationSwitchesToFdv1() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicBoolean secondInitCalled = new AtomicBoolean(false);

        MockQueuedSynchronizer fdv1Sync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(true), false));

        // First initializer returns TERMINAL_ERROR with fdv1Fallback=true.
        // The fallback should be honored and remaining initializers skipped.
        FDv2DataSource dataSource = new FDv2DataSource(
                CONTEXT,
                Arrays.<FDv2DataSource.DataSourceFactory<Initializer>>asList(
                        () -> new MockInitializer(FDv2SourceResult.status(
                                FDv2SourceResult.Status.terminalError(new RuntimeException("fail")), true)),
                        () -> {
                            secondInitCalled.set(true);
                            return new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false));
                        }),
                Collections.<FDv2DataSource.DataSourceFactory<Synchronizer>>singletonList(
                        () -> new MockSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(false), false))),
                () -> fdv1Sync,
                sink,
                executor,
                logging.logger);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertFalse(secondInitCalled.get());

        // The FDv1 synchronizer should receive data, proving it was activated.
        sink.awaitApplyCount(1, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        stopDataSource(dataSource);
    }

    @Test
    public void fdv1FallbackDuringInitializationWithNonEmptySelectorSwitchesToFdv1() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        MockQueuedSynchronizer fdv1Sync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(true), false));

        // Initializer returns a fully current payload (non-empty selector) AND fdv1Fallback=true.
        // The fallback should still be detected even though the non-empty selector would normally
        // complete initialization immediately.
        FDv2DataSource dataSource = new FDv2DataSource(
                CONTEXT,
                Collections.<FDv2DataSource.DataSourceFactory<Initializer>>singletonList(
                        () -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), true))),
                Collections.<FDv2DataSource.DataSourceFactory<Synchronizer>>singletonList(
                        () -> new MockSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), false))),
                () -> fdv1Sync,
                sink,
                executor,
                logging.logger);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        // The FDv1 synchronizer should receive data, proving it was activated.
        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        stopDataSource(dataSource);
    }

    @Test
    public void fdv1FallbackSwitchesToFdv1Synchronizer() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        MockQueuedSynchronizer fdv2Sync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(true), true));

        MockQueuedSynchronizer fdv1Sync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(true), false));

        FDv2DataSource dataSource = new FDv2DataSource(
                CONTEXT,
                Collections.<FDv2DataSource.DataSourceFactory<Initializer>>emptyList(),
                Collections.<FDv2DataSource.DataSourceFactory<Synchronizer>>singletonList(() -> fdv2Sync),
                () -> fdv1Sync,
                sink,
                executor,
                logging.logger);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertEquals(DataSourceState.VALID, sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        DataSourceState secondValid = sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(DataSourceState.VALID, secondValid);

        stopDataSource(dataSource);
    }

    @Test
    public void fdv1FallbackOnTerminalErrorSwitchesToFdv1Synchronizer() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        // FDv2 synchronizer returns a terminal error (e.g., HTTP 401) with fdv1Fallback=true.
        // The fallback should still be honored even though the error is non-recoverable.
        MockQueuedSynchronizer fdv2Sync = new MockQueuedSynchronizer(
                FDv2SourceResult.status(
                        FDv2SourceResult.Status.terminalError(new RuntimeException("401")),
                        true));

        MockQueuedSynchronizer fdv1Sync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(true), false));

        FDv2DataSource dataSource = new FDv2DataSource(
                CONTEXT,
                Collections.<FDv2DataSource.DataSourceFactory<Initializer>>emptyList(),
                Collections.<FDv2DataSource.DataSourceFactory<Synchronizer>>singletonList(() -> fdv2Sync),
                () -> fdv1Sync,
                sink,
                executor,
                logging.logger);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);

        // The terminal error from the FDv2 synchronizer produces INTERRUPTED first.
        assertEquals(DataSourceState.INTERRUPTED, sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // Then the FDv1 synchronizer takes over and produces VALID.
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        assertEquals(DataSourceState.VALID, sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        stopDataSource(dataSource);
    }

    @Test
    public void fdv1FallbackNotTriggeredWhenAlreadyOnFdv1() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        MockQueuedSynchronizer fdv2Sync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(true), true));

        AtomicInteger fdv1BuildCount = new AtomicInteger(0);
        MockQueuedSynchronizer fdv1Sync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(true), false));

        FDv2DataSource dataSource = new FDv2DataSource(
                CONTEXT,
                Collections.<FDv2DataSource.DataSourceFactory<Initializer>>emptyList(),
                Collections.<FDv2DataSource.DataSourceFactory<Synchronizer>>singletonList(() -> fdv2Sync),
                () -> {
                    fdv1BuildCount.incrementAndGet();
                    return fdv1Sync;
                },
                sink,
                executor,
                logging.logger);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertEquals(DataSourceState.VALID, sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(DataSourceState.VALID, sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertEquals(1, fdv1BuildCount.get());

        stopDataSource(dataSource);
    }

    @Test
    public void fdv1FallbackNotTriggeredWhenNoFdv1SlotExists() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        MockQueuedSynchronizer fdv2Sync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(true), true));

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.<FDv2DataSource.DataSourceFactory<Initializer>>emptyList(),
                Collections.<FDv2DataSource.DataSourceFactory<Synchronizer>>singletonList(() -> fdv2Sync));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertEquals(DataSourceState.VALID, sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        stopDataSource(dataSource);
    }

    @Test
    public void needsRefresh_sameContext_returnsFalse() {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.emptyList());
        assertFalse(dataSource.needsRefresh(true, CONTEXT));
        assertFalse(dataSource.needsRefresh(false, CONTEXT));
    }

    @Test
    public void needsRefresh_differentContext_returnsTrue() {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.emptyList());
        assertTrue(dataSource.needsRefresh(false, LDContext.create("other-context")));
    }
}
