package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.DataModel;
import com.launchdarkly.sdk.android.env.EnvironmentReporterBuilder;
import com.launchdarkly.sdk.android.env.IEnvironmentReporter;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.android.subsystems.DataSourceState;
import com.launchdarkly.sdk.android.subsystems.FDv2SourceResult;
import com.launchdarkly.sdk.android.subsystems.Initializer;
import com.launchdarkly.sdk.android.subsystems.InitializerFromCache;
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
import java.util.concurrent.TimeoutException;
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

    private static final long ORCHESTRATION_LOG_AWAIT_TIMEOUT_MS = AWAIT_TIMEOUT_SECONDS * 1000L;

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

    /**
     * Builds a data source with production timeouts, park delays, and fallback TTL, and a 10ms failover delay.
     */
    private FDv2DataSource buildDataSource(
            MockComponents.MockDataSourceUpdateSink sink,
            List<FDv2DataSource.DataSourceFactory<Initializer>> initializers,
            List<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers) {
        return buildDataSource(sink, initializers, synchronizers, 120_000, 300_000);
    }

    /**
     * Builds a data source with production park delays and fallback TTL, and a 10ms failover delay.
     */
    private FDv2DataSource buildDataSource(
            MockComponents.MockDataSourceUpdateSink sink,
            List<FDv2DataSource.DataSourceFactory<Initializer>> initializers,
            List<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers,
            long fallbackTimeoutMillis,
            long recoveryTimeoutMillis) {
        return buildDataSource(sink, initializers, synchronizers, null,
                fallbackTimeoutMillis,
                recoveryTimeoutMillis,
                /* parkInitialDelayMillis= */ TimeUnit.MINUTES.toMillis(5),
                /* parkMaxDelayMillis= */ TimeUnit.HOURS.toMillis(1),
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));
    }

    private FDv2DataSource buildDataSource(
            MockComponents.MockDataSourceUpdateSink sink,
            List<FDv2DataSource.DataSourceFactory<Initializer>> initializers,
            List<FDv2DataSource.DataSourceFactory<Synchronizer>> synchronizers,
            FDv2DataSource.DataSourceFactory<Synchronizer> fdv1FallbackSynchronizer,
            long fallbackTimeoutMillis,
            long recoveryTimeoutMillis,
            long parkInitialDelayMillis,
            long parkMaxDelayMillis,
            long failoverDelayMillis,
            long fdv1FallbackTtlMillis) {
        return new FDv2DataSource(
                CONTEXT,
                initializers,
                synchronizers,
                fdv1FallbackSynchronizer,
                sink,
                executor,
                logging.logger,
                fallbackTimeoutMillis,
                recoveryTimeoutMillis,
                parkInitialDelayMillis,
                parkMaxDelayMillis,
                failoverDelayMillis,
                fdv1FallbackTtlMillis);
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
     * Wraps a factory closure with the {@link InitializerFromCache} marker so the data source
     * treats initializers it produces as cache initializers (run synchronously before executor dispatch).
     */
    private static class CacheInitializerFactory
            implements FDv2DataSource.DataSourceFactory<Initializer>, InitializerFromCache {
        private final FDv2DataSource.DataSourceFactory<Initializer> delegate;

        CacheInitializerFactory(FDv2DataSource.DataSourceFactory<Initializer> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Initializer build() {
            return delegate.build();
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
        private volatile LDAwaitFuture<FDv2SourceResult> pendingFuture;

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
            pendingFuture = new LDAwaitFuture<>();
            return pendingFuture;
        }

        @Override
        public void close() {
            closed = true;
            LDAwaitFuture<FDv2SourceResult> f = pendingFuture;
            if (f != null) {
                f.set(FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false));
            }
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
                if (closed) {
                    LDAwaitFuture<FDv2SourceResult> f = new LDAwaitFuture<>();
                    f.set(FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false));
                    return f;
                }
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
        // First sync: changeset then INTERRUPTED; second sync: changeset; recovery brings back first.
        // Each factory call builds a new synchronizer, as a real factory does. Recovery closes the
        // active one and builds the primary again, and a closed mock answers SHUTDOWN at once, so
        // handing the same instance back would spin the orchestrator between two closed mocks.
        AtomicInteger firstCallCount = new AtomicInteger(0);
        AtomicInteger secondCallCount = new AtomicInteger(0);
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> {
                            firstCallCount.incrementAndGet();
                            return new MockQueuedSynchronizer(
                                    FDv2SourceResult.changeSet(makeChangeSet(false), false),
                                    interrupted());
                        },
                        () -> {
                            secondCallCount.incrementAndGet();
                            return new MockQueuedSynchronizer(
                                    FDv2SourceResult.changeSet(makeChangeSet(false), false));
                        }),
                1000, 2000);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        // Wait for fallback + recovery: ~1s fallback + ~2s recovery.
        // Use generous timeouts for Android where thread scheduling can delay timer delivery.
        sink.awaitApplyCount(3, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(sink.getApplyCount() >= 3);

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
                1000, 2000);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        stopDataSource(dataSource); // should not hang while fallback condition is waiting
    }

    // ============================================================================
    // Source Parking
    // ============================================================================

    @Test
    public void terminalErrorFailsOverToNextSynchronizer() throws Exception {
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
    public void terminalErrorParksSynchronizerAndRestartsItAfterUnparkTime() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger buildCount = new AtomicInteger();

        // The first instance fails with a terminal error, and the second instance delivers data.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> buildCount.incrementAndGet() == 1
                        ? new MockQueuedSynchronizer(terminalError())
                        : new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), false))),
                null,
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ 100,
                /* parkMaxDelayMillis= */ 1000,
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source with its only synchronizer.
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        // The parked synchronizer is built again after its unpark time, and its data initializes the data source.
        assertEquals(2, buildCount.get());
        assertEquals(DataSourceState.VALID, sink.getLastState());
        stopDataSource(dataSource);
    }

    @Test
    public void failedNextParksSynchronizer() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger buildCount = new AtomicInteger();

        // The first instance fails from next(), and the second instance delivers data.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> buildCount.incrementAndGet() == 1
                        ? new MockSynchronizer(new RuntimeException("next-boom"))
                        : new MockSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), false))),
                null,
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ 100,
                /* parkMaxDelayMillis= */ 1000,
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source with its only synchronizer.
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        // The failing synchronizer is parked, and is built again after its unpark time.
        assertEquals(2, buildCount.get());
        awaitLogContains(logging, "Synchronizer 'MockSynchronizer' is backing off for ");
        stopDataSource(dataSource);
    }

    @Test
    public void exceptionThrownFromNextParksSynchronizer() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger buildCount = new AtomicInteger();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> {
                    if (buildCount.incrementAndGet() == 1) {
                        // The first instance throws from next() instead of returning a future.
                        return new Synchronizer() {
                            @Override
                            public LDAwaitFuture<FDv2SourceResult> next() {
                                throw new RuntimeException("next-boom");
                            }

                            @Override
                            public void close() {
                            }
                        };
                    }
                    // The second instance delivers data.
                    return new MockSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), false));
                }),
                null,
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ 100,
                /* parkMaxDelayMillis= */ 1000,
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source with its only synchronizer.
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        // The throwing synchronizer is parked, and is built again after its unpark time.
        assertEquals(2, buildCount.get());
        stopDataSource(dataSource);
    }

    @Test
    public void constructionFailureParksSynchronizer() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger buildCount = new AtomicInteger();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> {
                    if (buildCount.incrementAndGet() == 1) {
                        // The first build fails.
                        throw new RuntimeException("build-boom");
                    }
                    // The second build delivers data.
                    return new MockSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), false));
                }),
                null,
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ 100,
                /* parkMaxDelayMillis= */ 1000,
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source with its only synchronizer.
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        // The synchronizer that failed to build is parked, and is built again after its unpark time.
        assertEquals(2, buildCount.get());
        awaitLogContains(logging, "A synchronizer that failed to build is backing off for ");
        stopDataSource(dataSource);
    }

    // A synchronizer that shuts down without being stopped is parked, like one with any other unexpected failure.
    @Test
    public void unpromptedShutdownParksSynchronizer() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger buildCount = new AtomicInteger();

        // The first instance shuts down, and the second instance delivers data.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> buildCount.incrementAndGet() == 1
                        ? new MockQueuedSynchronizer(FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false))
                        : new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), false))),
                null,
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ 100,
                /* parkMaxDelayMillis= */ 1000,
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source with its only synchronizer.
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        // The synchronizer is built again after its unpark time, and its data initializes the data source.
        assertEquals(2, buildCount.get());
        stopDataSource(dataSource);
    }

    // While every synchronizer is parked, the synchronizer that was parked last is stopped.
    @Test
    public void parkedSynchronizerIsClosedWhileAllSynchronizersAreParked() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        CountDownLatch closed = new CountDownLatch(1);

        // The only synchronizer fails with a terminal error, and records when it is closed. It is parked for much
        // longer than the test.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(terminalError()) {
                    @Override
                    public void close() {
                        closed.countDown();
                        super.close();
                    }
                }));

        // Start the data source.
        startDataSource(dataSource);

        // The parked synchronizer is closed while it waits for its unpark time.
        assertTrue(closed.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        stopDataSource(dataSource);
    }

    @Test
    public void allSynchronizersParkedRestartsAfterEarliestUnparkTime() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        BlockingQueue<Integer> buildOrder = new LinkedBlockingQueue<>();

        // Every instance of every synchronizer fails with a terminal error.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> { buildOrder.offer(1); return new MockQueuedSynchronizer(terminalError()); },
                        () -> { buildOrder.offer(2); return new MockQueuedSynchronizer(terminalError()); },
                        () -> { buildOrder.offer(3); return new MockQueuedSynchronizer(terminalError()); }),
                null,
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ 100,
                /* parkMaxDelayMillis= */ 1000,
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source, and wait for each synchronizer to be built and then for one more build.
        startDataSource(dataSource);
        Integer firstBuild = buildOrder.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Integer secondBuild = buildOrder.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Integer thirdBuild = buildOrder.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Integer buildAfterAllParked = buildOrder.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // The synchronizers fail over in order, and a parked synchronizer starts again after all of them are parked.
        assertEquals(Integer.valueOf(1), firstBuild);
        assertEquals(Integer.valueOf(2), secondBuild);
        assertEquals(Integer.valueOf(3), thirdBuild);
        assertNotNull(buildAfterAllParked);

        // Each park reports INTERRUPTED.
        List<DataSourceState> statuses = sink.awaitStatuses(3, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(Arrays.asList(
                DataSourceState.INTERRUPTED,
                DataSourceState.INTERRUPTED,
                DataSourceState.INTERRUPTED), statuses);
        stopDataSource(dataSource);
    }

    @Test
    public void parkedSynchronizerSkippedInRotation() throws Exception {
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

        assertEquals(1, firstCallCount.get()); // called once, then parked
        assertTrue(secondCallCount.get() >= 1);
        stopDataSource(dataSource);
    }

    @Test
    public void resetThresholdMetReturnsParkDelayToBase() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger buildCount = new AtomicInteger();
        BlockingQueue<Long> buildTimes = new LinkedBlockingQueue<>();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> {
                    buildTimes.offer(System.nanoTime());
                    if (buildCount.incrementAndGet() == 3) {
                        // The third instance reports that it met its reset threshold before it fails.
                        return new MockQueuedSynchronizer(
                                FDv2SourceResult.changeSet(makeChangeSet(true), false).withResetThresholdMet(),
                                terminalError());
                    }
                    // Every instance fails with a terminal error.
                    return new MockQueuedSynchronizer(terminalError());
                }),
                null,
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ 100,
                /* parkMaxDelayMillis= */ 10_000,
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source, and record when the synchronizer is built for the third and fourth times.
        startDataSource(dataSource);
        buildTimes.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        buildTimes.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Long thirdBuild = buildTimes.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Long fourthBuild = buildTimes.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // After the reset, the park is at most the base delay. Without the reset, it would be at least twice the base.
        assertNotNull(thirdBuild);
        assertNotNull(fourthBuild);
        long parkMillis = TimeUnit.NANOSECONDS.toMillis(fourthBuild - thirdBuild);
        assertTrue("park lasted " + parkMillis + "ms", parkMillis < 200);
        stopDataSource(dataSource);
    }

    @Test
    public void recoveryResetsToFirstAvailableSynchronizer() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger firstCallCount = new AtomicInteger(0);
        AtomicInteger secondCallCount = new AtomicInteger(0);

        // Each factory must build a fresh synchronizer, as the real ones do. SourceManager closes
        // the synchronizer it is switching away from, and a closed one reports SHUTDOWN
        // immediately, so handing the same instance back on every recovery would spin this loop.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> {
                            firstCallCount.incrementAndGet();
                            return new MockQueuedSynchronizer(
                                    FDv2SourceResult.changeSet(makeChangeSet(false), false),
                                    interrupted());
                        },
                        () -> {
                            secondCallCount.incrementAndGet();
                            return new MockQueuedSynchronizer(
                                    FDv2SourceResult.changeSet(makeChangeSet(false), false));
                        }),
                1000, 2000);

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
                1000, 300_000);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        // Generous timeout for Android where fallback timer (1s) and thread scheduling can be delayed
        Boolean secondCalled = secondCalledQueue.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull("second synchronizer should be called after fallback", secondCalled);
        stopDataSource(dataSource);
    }

    @Test
    public void changeSetDuringInterruptionRestartsFallbackTimeout() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        MockQueuedSynchronizer primary = new MockQueuedSynchronizer(interrupted());
        BlockingQueue<Long> secondaryBuildTimes = new LinkedBlockingQueue<>();

        // The primary synchronizer reports that it is interrupted. The secondary records when it is built.
        // The fallback timeout is 100ms.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> primary,
                        () -> {
                            secondaryBuildTimes.offer(System.nanoTime());
                            return new MockQueuedSynchronizer();
                        }),
                100, 300_000);

        // Start the data source. Before the fallback timeout, the primary delivers a change set and is
        // interrupted again.
        long start = System.nanoTime();
        startDataSource(dataSource);
        executor.schedule(
                () -> primary.addResult(FDv2SourceResult.changeSet(makeChangeSet(true), false)),
                50,
                TimeUnit.MILLISECONDS);
        executor.schedule(() -> primary.addResult(interrupted()), 90, TimeUnit.MILLISECONDS);

        // The data source falls back a full timeout after the second interruption, not at the original deadline.
        Long secondaryBuild = secondaryBuildTimes.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(secondaryBuild);
        long fallbackMillis = TimeUnit.NANOSECONDS.toMillis(secondaryBuild - start);
        assertTrue("fell back after " + fallbackMillis + "ms", fallbackMillis >= 150);
        stopDataSource(dataSource);
    }

    @Test
    public void fallbackWithNoOtherAvailableSynchronizerKeepsCurrentSynchronizer() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger secondaryBuildCount = new AtomicInteger();
        MockQueuedSynchronizer firstSecondary = new MockQueuedSynchronizer(interrupted());

        // The primary synchronizer fails, so it is parked for the rest of the test.
        // The first instance of the secondary synchronizer reports that it is interrupted.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> new MockQueuedSynchronizer(terminalError()),
                        () -> secondaryBuildCount.incrementAndGet() == 1
                                ? firstSecondary
                                : new MockQueuedSynchronizer()),
                null,
                /* fallbackTimeoutMillis= */ 10,
                300_000,
                /* parkInitialDelayMillis= */ TimeUnit.MINUTES.toMillis(5),
                /* parkMaxDelayMillis= */ TimeUnit.HOURS.toMillis(1),
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source. After the fallback condition of the secondary synchronizer is met, send a change
        // set to the instance of the secondary synchronizer that was already running.
        startDataSource(dataSource);
        executor.schedule(
                () -> firstSecondary.addResult(FDv2SourceResult.changeSet(makeChangeSet(true), false)),
                60,
                TimeUnit.MILLISECONDS);

        // The data source applies the change set, so it kept that instance.
        sink.awaitApplyCount(1, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, sink.getApplyCount());
        assertEquals(1, secondaryBuildCount.get());
        stopDataSource(dataSource);
    }

    @Test
    public void unparkWhileCurrentSynchronizerIsHealthyKeepsIt() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger primaryBuildCount = new AtomicInteger();
        MockQueuedSynchronizer secondary = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(true), false));

        // The primary synchronizer fails, so it is parked for 25-50ms. The secondary synchronizer delivers data and
        // stays healthy. The fallback and recovery timeouts are too long to matter.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> {
                            primaryBuildCount.incrementAndGet();
                            return new MockQueuedSynchronizer(terminalError());
                        },
                        () -> secondary),
                null,
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ 50,
                /* parkMaxDelayMillis= */ 1000,
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source, and send another change set after the primary synchronizer unparks.
        startDataSource(dataSource);
        executor.schedule(
                () -> secondary.addResult(FDv2SourceResult.changeSet(makeChangeSet(true), false)),
                100,
                TimeUnit.MILLISECONDS);

        // The data source applies both change sets, and does not start the primary synchronizer again.
        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, sink.getApplyCount());
        assertEquals(1, primaryBuildCount.get());
        stopDataSource(dataSource);
    }

    @Test
    public void fallbackSwitchesWhenParkedSynchronizerUnparks() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger primaryBuildCount = new AtomicInteger();
        AtomicInteger secondaryBuildCount = new AtomicInteger();

        // The first instance of the primary synchronizer fails, and the second instance delivers data.
        // The secondary synchronizer reports that it is interrupted.
        // The fallback condition is met after 10ms, and the primary synchronizer unparks after 50-100ms.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> primaryBuildCount.incrementAndGet() == 1
                                ? new MockQueuedSynchronizer(terminalError())
                                : new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), false)),
                        () -> {
                            secondaryBuildCount.incrementAndGet();
                            return new MockQueuedSynchronizer(interrupted());
                        }),
                null,
                /* fallbackTimeoutMillis= */ 10,
                300_000,
                /* parkInitialDelayMillis= */ 100,
                /* parkMaxDelayMillis= */ 1000,
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source.
        startDataSource(dataSource);

        // When the primary synchronizer unparks, the data source falls back to it and applies its data.
        sink.awaitApplyCount(1, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, primaryBuildCount.get());
        assertEquals(1, secondaryBuildCount.get());
        stopDataSource(dataSource);
    }

    @Test
    public void changeSetAfterFallbackIsMetPreventsSwitchAtUnpark() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger primaryBuildCount = new AtomicInteger();
        MockQueuedSynchronizer secondary = new MockQueuedSynchronizer(interrupted());

        // The primary synchronizer fails, so it is parked briefly. The secondary synchronizer reports that it is
        // interrupted. The fallback condition is met after 10ms, and the primary synchronizer unparks after
        // 80-160ms.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> {
                            primaryBuildCount.incrementAndGet();
                            return new MockQueuedSynchronizer(terminalError());
                        },
                        () -> secondary),
                null,
                /* fallbackTimeoutMillis= */ 10,
                300_000,
                /* parkInitialDelayMillis= */ 160,
                /* parkMaxDelayMillis= */ 1000,
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source, send a change set after the fallback is met, and another after the unpark.
        startDataSource(dataSource);
        executor.schedule(
                () -> secondary.addResult(FDv2SourceResult.changeSet(makeChangeSet(true), false)),
                50,
                TimeUnit.MILLISECONDS);
        executor.schedule(
                () -> secondary.addResult(FDv2SourceResult.changeSet(makeChangeSet(true), false)),
                210,
                TimeUnit.MILLISECONDS);

        // The data source applies both change sets, and does not start the primary synchronizer again.
        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, sink.getApplyCount());
        assertEquals(1, primaryBuildCount.get());
        stopDataSource(dataSource);
    }

    @Test
    public void recoveryWithPreferredSynchronizerParkedWaitsForItsUnpark() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger primaryBuildCount = new AtomicInteger();
        AtomicInteger secondaryBuildCount = new AtomicInteger();

        // The first instance of the primary synchronizer fails, and the second instance delivers data.
        // The secondary synchronizer delivers data and stays healthy.
        // The recovery condition is met after 50ms, and the primary synchronizer unparks after 100-200ms.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> primaryBuildCount.incrementAndGet() == 1
                                ? new MockQueuedSynchronizer(terminalError())
                                : new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), false)),
                        () -> {
                            secondaryBuildCount.incrementAndGet();
                            return new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), false));
                        }),
                null,
                120_000,
                /* recoveryTimeoutMillis= */ 50,
                /* parkInitialDelayMillis= */ 200,
                /* parkMaxDelayMillis= */ 1000,
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source.
        startDataSource(dataSource);

        // When the primary synchronizer unparks, the data source recovers to it and applies its data.
        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, primaryBuildCount.get());
        assertEquals(1, secondaryBuildCount.get());
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
                120_000, 300_000);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        stopDataSource(dataSource); // should not hang while condition is waiting for fallback timeout
    }

    @Test
    public void stopCompletesWhenSynchronizerCloseDoesNotCompleteNext() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        CountDownLatch nextCalled = new CountDownLatch(1);

        // The only synchronizer returns a result that never arrives, and closing it does not complete that result.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new Synchronizer() {
                    @Override
                    public LDAwaitFuture<FDv2SourceResult> next() {
                        nextCalled.countDown();
                        return new LDAwaitFuture<>();
                    }

                    @Override
                    public void close() {
                    }
                }));

        // Start the data source, and wait until it asks the synchronizer for a result.
        startDataSource(dataSource);
        assertTrue(nextCalled.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // Stopping the data source ends its wait for the result.
        stopDataSource(dataSource);
    }

    @Test
    public void noStatusEventsEmittedAfterOff() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        CountDownLatch workerBlocked = new CountDownLatch(1);
        LDAwaitFuture<FDv2SourceResult> controlledFuture = new LDAwaitFuture<>();

        Synchronizer sync = new Synchronizer() {
            private final AtomicBoolean firstReturned = new AtomicBoolean(false);

            @Override
            public LDAwaitFuture<FDv2SourceResult> next() {
                if (!firstReturned.getAndSet(true)) {
                    LDAwaitFuture<FDv2SourceResult> f = new LDAwaitFuture<>();
                    f.set(FDv2SourceResult.changeSet(makeChangeSet(false), false));
                    return f;
                }
                workerBlocked.countDown();
                return controlledFuture;
            }

            @Override
            public void close() {
                // Intentionally does NOT complete controlledFuture.
                // The test triggers it manually to control timing.
            }
        };

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> sync));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        assertTrue(workerBlocked.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // Stop the data source. On old code that had bug, this reports OFF synchronously.
        AwaitableCallback<Void> stopCb = new AwaitableCallback<>();
        dataSource.stop(stopCb);

        // Simulate a delayed error arriving after stop (e.g. network close exception).
        // Old code with the bug: OFF was already reported by stop(), so the INTERRUPTED comes after OFF.
        // New code with the fix: the worker handles INTERRUPTED first, then reports OFF.
        controlledFuture.setException(new RuntimeException("connection closed"));

        stopCb.await(AWAIT_TIMEOUT_SECONDS * 1000);
        Thread.sleep(200);

        List<MockComponents.MockDataSourceUpdateSink.StatusEvent> events = sink.statusEvents;
        int offIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).state == DataSourceState.OFF) {
                offIndex = i;
                break;
            }
        }
        assertTrue("Expected OFF status to be reported", offIndex >= 0);
        assertEquals("OFF should be the last status event, but found events after it",
                events.size() - 1, offIndex);
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
    public void exceptionFromSynchronizerCloseDoesNotStopSynchronization() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        CountDownLatch secondSyncStarted = new CountDownLatch(1);

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        // The first synchronizer reports a terminal error, and throws when the data source closes it.
                        () -> new MockQueuedSynchronizer(terminalError()) {
                            @Override
                            public void close() {
                                super.close();
                                throw new IllegalStateException("close failed");
                            }
                        },
                        // The second synchronizer records when it starts.
                        () -> {
                            secondSyncStarted.countDown();
                            return new MockQueuedSynchronizer();
                        }));

        // Start the data source.
        startDataSource(dataSource);

        // The data source starts the second synchronizer.
        assertTrue(secondSyncStarted.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
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
    // Fallback and Recovery Conditions
    // ============================================================================

    @Test
    public void conditionsInformedOfAllResults() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                        interrupted(),
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))),
                10_000, 20_000);

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(sink.getApplyCount() >= 2);
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
                1000, 2000);

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
                1000, 2000);

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
                1000, 2000);

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
                        FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(terminalErr), false))),
                null,
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ TimeUnit.MINUTES.toMillis(5),
                /* parkMaxDelayMillis= */ TimeUnit.HOURS.toMillis(1),
                /* failoverDelayMillis= */ 1000,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source with a synchronizer that fails with a terminal error.
        startDataSource(dataSource);
        DataSourceState status = sink.awaitStatus(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // The data source reports INTERRUPTED with the error from the synchronizer.
        assertEquals(DataSourceState.INTERRUPTED, status);
        assertSame(terminalErr, sink.getLastError());
        stopDataSource(dataSource);
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
    public void statusStaysInterruptedWhileOnlySynchronizerIsParked() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        RuntimeException err = new RuntimeException("server error");
        AtomicInteger buildCount = new AtomicInteger();
        BlockingQueue<Integer> builds = new LinkedBlockingQueue<>();

        // The first instance sends a changeset and then a terminal error. Later instances send nothing.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> {
                    int build = buildCount.incrementAndGet();
                    builds.offer(build);
                    return build == 1
                            ? new MockQueuedSynchronizer(
                                    FDv2SourceResult.changeSet(makeChangeSet(false), false),
                                    FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(err), false))
                            : new MockQueuedSynchronizer();
                }),
                null,
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ 100,
                /* parkMaxDelayMillis= */ 1000,
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source, and wait until the parked synchronizer is built again.
        startDataSource(dataSource);
        Integer firstBuild = builds.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Integer rebuild = builds.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // The status goes from VALID to INTERRUPTED, and stays INTERRUPTED through the park.
        assertEquals(Integer.valueOf(1), firstBuild);
        assertEquals(Integer.valueOf(2), rebuild);
        List<DataSourceState> statuses = sink.awaitStatuses(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(Arrays.asList(DataSourceState.VALID, DataSourceState.INTERRUPTED), statuses);
        assertEquals(DataSourceState.INTERRUPTED, sink.getLastState());
        stopDataSource(dataSource);
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

    // A payload without a selector that comes with the directive is applied, but it does not make the data source
    // ready. The data from the FDv1 fallback synchronizer does.
    @Test
    public void fdv1FallbackDuringInitializationWithoutSelectorWaitsForFdv1Data() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        CountDownLatch fdv1Built = new CountDownLatch(1);
        MockQueuedSynchronizer fdv1Sync = new MockQueuedSynchronizer();

        // The initializer returns a payload without a selector, with the directive. The FDv1 fallback synchronizer
        // sends the results that the test gives it.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(() -> new MockInitializer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), true))),
                Collections.singletonList(() -> new MockQueuedSynchronizer()),
                () -> {
                    fdv1Built.countDown();
                    return fdv1Sync;
                },
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ TimeUnit.MINUTES.toMillis(5),
                /* parkMaxDelayMillis= */ TimeUnit.HOURS.toMillis(1),
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source, and wait until it starts the FDv1 fallback synchronizer.
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(fdv1Built.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // The payload is applied, but the data source is not ready and has not reported VALID.
        assertEquals(1, sink.getApplyCount());
        try {
            startCallback.await(0);
            fail("Expected the data source not to be ready");
        } catch (TimeoutException expected) {
            // The data source is not ready.
        }
        assertFalse(sink.statusEvents.stream().anyMatch(event -> event.state == DataSourceState.VALID));

        // The data from the FDv1 fallback synchronizer makes the data source ready.
        fdv1Sync.addResult(FDv2SourceResult.changeSet(makeChangeSet(true), false));
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        assertEquals(DataSourceState.VALID, sink.getLastState());
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

        // The FDv2 payload and then the FDv1 payload are applied.
        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, sink.getApplyCount());
        assertEquals(DataSourceState.VALID, sink.getLastState());

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

        // The FDv2 payload and then the FDv1 payload are applied.
        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, sink.getApplyCount());

        assertEquals(1, fdv1BuildCount.get());

        stopDataSource(dataSource);
    }

    @Test
    public void fdv1FallbackSynchronizerIsParkedAndRestartedDuringDirectedFallback() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger fdv1BuildCount = new AtomicInteger();

        // The FDv2 synchronizer reports an interruption that carries the FDv1 fallback directive.
        // The first instance of the FDv1 fallback synchronizer fails, and the second instance delivers data.
        // The FDv1 fallback synchronizer is parked for 50-100ms.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.status(FDv2SourceResult.Status.interrupted(new RuntimeException("500")), true))),
                () -> fdv1BuildCount.incrementAndGet() == 1
                        ? new MockQueuedSynchronizer(terminalError())
                        : new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), false)),
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ 100,
                /* parkMaxDelayMillis= */ 1000,
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source.
        startDataSource(dataSource);

        // The FDv1 fallback synchronizer is built again after its unpark time, and its data is applied.
        sink.awaitApplyCount(1, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, sink.getApplyCount());
        assertEquals(2, fdv1BuildCount.get());
        stopDataSource(dataSource);
    }

    // When a synchronizer signals FDv1 fallback but no FDv1 fallback synchronizer is configured, the data source
    // reports INTERRUPTED, runs no synchronizer until the fallback TTL ends, and then restarts the FDv2 synchronizers.
    @Test
    public void fdv1FallbackOnSynchronizerWithoutFDv1ConfiguredRecoversAfterTTL() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger firstSyncBuildCount = new AtomicInteger();
        AtomicBoolean secondSyncCalled = new AtomicBoolean(false);

        // The first instance sends a payload, then one with the directive. The second sends a payload.
        // The second synchronizer must never run.
        // No FDv1 fallback synchronizer is configured, and the fallback TTL ends after 50-100ms.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> firstSyncBuildCount.incrementAndGet() == 1
                                ? new MockQueuedSynchronizer(
                                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                                        FDv2SourceResult.changeSet(makeChangeSet(false), true))
                                : new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), false)),
                        () -> {
                            secondSyncCalled.set(true);
                            return new MockQueuedSynchronizer();
                        }),
                null,
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ TimeUnit.MINUTES.toMillis(5),
                /* parkMaxDelayMillis= */ TimeUnit.HOURS.toMillis(1),
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ 100);

        // Start the data source.
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        // The data source reports INTERRUPTED, and restarts the first synchronizer after the TTL.
        List<DataSourceState> statuses = sink.awaitStatuses(3, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(Arrays.asList(
                DataSourceState.VALID,
                DataSourceState.INTERRUPTED,
                DataSourceState.VALID), statuses);
        assertEquals(2, firstSyncBuildCount.get());
        assertFalse(secondSyncCalled.get());
        stopDataSource(dataSource);
    }

    // While no FDv1 fallback synchronizer is configured, the FDv2 synchronizer that sent the directive is stopped.
    @Test
    public void directiveWithoutFDv1ClosesFDv2SynchronizerDuringFallback() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        CountDownLatch closed = new CountDownLatch(1);

        // The synchronizer sends a payload with the directive, and records when it is closed.
        // No FDv1 fallback synchronizer is configured, and the fallback lasts much longer than the test.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(true), true)) {
                    @Override
                    public void close() {
                        closed.countDown();
                        super.close();
                    }
                }));

        // Start the data source.
        startDataSource(dataSource);

        // The synchronizer is closed during the fallback.
        assertTrue(closed.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        stopDataSource(dataSource);
    }

    // When the fallback TTL ends, the data source stops the FDv1 synchronizer and restarts the FDv2 synchronizers.
    @Test
    public void fdv1FallbackEndsAfterTTL() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger fdv2BuildCount = new AtomicInteger();
        AtomicBoolean fdv1Closed = new AtomicBoolean(false);

        // The first FDv2 instance sends a payload with the directive. The second sends a payload.
        // The FDv1 fallback synchronizer sends a payload, and records when it is closed.
        // The fallback TTL ends after 50-100ms.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(true), fdv2BuildCount.incrementAndGet() == 1))),
                () -> new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), false)) {
                    @Override
                    public void close() {
                        fdv1Closed.set(true);
                        super.close();
                    }
                },
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ TimeUnit.MINUTES.toMillis(5),
                /* parkMaxDelayMillis= */ TimeUnit.HOURS.toMillis(1),
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ 100);

        // Start the data source.
        startDataSource(dataSource);

        // After the TTL, the data source has stopped the FDv1 fallback synchronizer and applied FDv2 data again.
        sink.awaitApplyCount(3, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, fdv2BuildCount.get());
        assertTrue(fdv1Closed.get());
        stopDataSource(dataSource);
    }

    // When the fallback TTL ends before the FDv1 synchronizer's unpark time, the FDv2 synchronizers restart at the TTL.
    @Test
    public void fdv1FallbackEndsWhileFDv1SynchronizerIsParked() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger fdv2BuildCount = new AtomicInteger();
        AtomicInteger fdv1BuildCount = new AtomicInteger();

        // The first FDv2 instance reports an interruption with the directive. The second sends a payload with the
        // directive. The FDv1 fallback synchronizer fails, so the data source parks it. The FDv1 fallback
        // synchronizer is parked for minutes, and the fallback TTL ends after 100-200ms.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> fdv2BuildCount.incrementAndGet() == 1
                        ? new MockQueuedSynchronizer(FDv2SourceResult.status(
                                FDv2SourceResult.Status.interrupted(new RuntimeException("500")), true))
                        : new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), true))),
                () -> {
                    fdv1BuildCount.incrementAndGet();
                    return new MockQueuedSynchronizer(terminalError());
                },
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ TimeUnit.MINUTES.toMillis(5),
                /* parkMaxDelayMillis= */ TimeUnit.HOURS.toMillis(1),
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ 200);

        // Start the data source.
        startDataSource(dataSource);

        // The FDv2 synchronizer is built again after the TTL, and its data is applied.
        sink.awaitApplyCount(1, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, sink.getApplyCount());
        assertEquals(2, fdv2BuildCount.get());

        // Each directive falls back to the FDv1 synchronizer, which is still parked, so nothing runs until the TTL.
        List<DataSourceState> statuses = sink.awaitStatuses(4, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(Arrays.asList(
                DataSourceState.INTERRUPTED,
                DataSourceState.INTERRUPTED,
                DataSourceState.VALID,
                DataSourceState.INTERRUPTED), statuses);
        assertEquals(1, fdv1BuildCount.get());
        stopDataSource(dataSource);
    }

    // A directive during the FDv1 fallback replaces the TTL, and the FDv1 synchronizer keeps running.
    @Test
    public void directiveDuringFDv1FallbackExtendsTTL() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger fdv2BuildCount = new AtomicInteger();
        AtomicInteger fdv1BuildCount = new AtomicInteger();
        BlockingQueue<Long> fdv2RestartTimes = new LinkedBlockingQueue<>();
        MockQueuedSynchronizer fdv1Sync = new MockQueuedSynchronizer();

        // The first FDv2 instance sends a payload with the directive. The second records when it is built.
        // The FDv1 fallback synchronizer sends the results that the test gives it.
        // The fallback TTL ends after 100-200ms.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> {
                    if (fdv2BuildCount.incrementAndGet() == 1) {
                        return new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), true));
                    }
                    fdv2RestartTimes.offer(System.nanoTime());
                    return new MockQueuedSynchronizer();
                }),
                () -> {
                    fdv1BuildCount.incrementAndGet();
                    return fdv1Sync;
                },
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ TimeUnit.MINUTES.toMillis(5),
                /* parkMaxDelayMillis= */ TimeUnit.HOURS.toMillis(1),
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ 200);

        // Start the data source. FDv1 sends a directive every 50ms until 300ms, which is longer than the TTL.
        long start = System.nanoTime();
        startDataSource(dataSource);
        for (int delay = 50; delay <= 300; delay += 50) {
            executor.schedule(
                    () -> fdv1Sync.addResult(FDv2SourceResult.changeSet(makeChangeSet(false), true)),
                    delay,
                    TimeUnit.MILLISECONDS);
        }

        // FDv2 restarts at least the shortest TTL after the last directive, and FDv1 never restarts.
        Long fdv2Restart = fdv2RestartTimes.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(fdv2Restart);
        long restartMillis = TimeUnit.NANOSECONDS.toMillis(fdv2Restart - start);
        assertTrue("restarted FDv2 after " + restartMillis + "ms", restartMillis >= 400);
        assertEquals(1, fdv1BuildCount.get());
        stopDataSource(dataSource);
    }

    // When the FDv2 synchronizers send the directive again after the fallback TTL ends, the data source falls back
    // again.
    @Test
    public void directiveDuringRecoveryFallsBackToFDv1Again() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger fdv2BuildCount = new AtomicInteger();
        AtomicInteger fdv1BuildCount = new AtomicInteger();

        // Every instance of the FDv2 synchronizer sends a payload with the directive.
        // Every instance of the FDv1 fallback synchronizer sends a payload.
        // The fallback TTL ends after 50-100ms.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> {
                    fdv2BuildCount.incrementAndGet();
                    return new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), true));
                }),
                () -> {
                    fdv1BuildCount.incrementAndGet();
                    return new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), false));
                },
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ TimeUnit.MINUTES.toMillis(5),
                /* parkMaxDelayMillis= */ TimeUnit.HOURS.toMillis(1),
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ 100);

        // Start the data source.
        startDataSource(dataSource);

        // After the TTL, the FDv2 synchronizer sends the directive again, and the data source starts FDv1 again.
        sink.awaitApplyCount(4, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, fdv2BuildCount.get());
        assertEquals(2, fdv1BuildCount.get());
        stopDataSource(dataSource);
    }

    // When an initializer signals FDv1 fallback but no FDv1 synchronizer is configured, the data source reports
    // INTERRUPTED, runs no synchronizer until the fallback TTL ends, and then starts the FDv2 synchronizers.
    @Test
    public void fdv1FallbackOnInitializerWithoutFDv1ConfiguredRecoversAfterTTL() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicInteger syncBuildCount = new AtomicInteger();

        // The initializer fails with the directive. The synchronizer delivers data.
        // No FDv1 fallback synchronizer is configured, and the fallback TTL ends after 50-100ms.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(() -> new MockInitializer(FDv2SourceResult.status(
                        FDv2SourceResult.Status.terminalError(new RuntimeException("fallback requested")), true))),
                Collections.singletonList(() -> {
                    syncBuildCount.incrementAndGet();
                    return new MockQueuedSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(true), false));
                }),
                null,
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ TimeUnit.MINUTES.toMillis(5),
                /* parkMaxDelayMillis= */ TimeUnit.HOURS.toMillis(1),
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ 100);

        // Start the data source, and wait for it to be initialized by the synchronizer.
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        // The data source never reported OFF, and started the synchronizer after the TTL.
        List<DataSourceState> statuses = sink.awaitStatuses(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertFalse(statuses.contains(DataSourceState.OFF));
        assertEquals(DataSourceState.VALID, sink.getLastState());
        assertEquals(1, syncBuildCount.get());
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

    @Test
    public void cacheInitializerRunsBeforeExecutorDispatch() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicReference<String> cacheInitializerThread = new AtomicReference<>();
        AtomicReference<String> callingThread = new AtomicReference<>();

        FDv2DataSource.DataSourceFactory<Initializer> cacheInitializerFactory = new CacheInitializerFactory(() ->
                new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(false), false)) {
                    @Override
                    public LDAwaitFuture<FDv2SourceResult> run() {
                        cacheInitializerThread.set(Thread.currentThread().getName());
                        return super.run();
                    }
                });

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(cacheInitializerFactory),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(true), false))));

        callingThread.set(Thread.currentThread().getName());
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertEquals("Cache initializer must run on the calling thread",
                callingThread.get(), cacheInitializerThread.get());
        stopDataSource(dataSource);
    }

    @Test
    public void generalInitializersRunsOnExecutorThread() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicReference<String> deferredThread = new AtomicReference<>();
        CountDownLatch deferredRan = new CountDownLatch(1);

        FDv2DataSource.DataSourceFactory<Initializer> deferredFactory = () ->
                new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false)) {
                    @Override
                    public LDAwaitFuture<FDv2SourceResult> run() {
                        deferredThread.set(Thread.currentThread().getName());
                        deferredRan.countDown();
                        return super.run();
                    }
                };

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(deferredFactory),
                Collections.emptyList());

        String callingThread = Thread.currentThread().getName();
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        assertTrue(deferredRan.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertFalse("Deferred initializer must NOT run on the calling thread",
                callingThread.equals(deferredThread.get()));
    }

    @Test
    public void eagerAndDeferredInitializersBothRun() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        AtomicBoolean eagerRan = new AtomicBoolean(false);
        CountDownLatch deferredRan = new CountDownLatch(1);

        FDv2DataSource.DataSourceFactory<Initializer> eagerFactory = new CacheInitializerFactory(() ->
                new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(false), false)) {
                    @Override
                    public LDAwaitFuture<FDv2SourceResult> run() {
                        eagerRan.set(true);
                        return super.run();
                    }
                });

        FDv2DataSource.DataSourceFactory<Initializer> deferredFactory = () ->
                new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false)) {
                    @Override
                    public LDAwaitFuture<FDv2SourceResult> run() {
                        deferredRan.countDown();
                        return super.run();
                    }
                };

        FDv2DataSource dataSource = buildDataSource(sink,
                Arrays.asList(eagerFactory, deferredFactory),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        assertTrue(deferredRan.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertTrue(eagerRan.get());
        sink.awaitApplyCount(2, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, sink.getApplyCount());
    }

    @Test
    public void offlineModeWithEagerCacheMissStillInitializes() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();

        FDv2DataSource.DataSourceFactory<Initializer> cacheMissFactory = new CacheInitializerFactory(() ->
                new MockInitializer(FDv2SourceResult.changeSet(new ChangeSet<>(
                        ChangeSetType.None,
                        com.launchdarkly.sdk.fdv2.Selector.EMPTY,
                        Collections.<String, DataModel.Flag>emptyMap(),
                        null,
                        false), false)));

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(cacheMissFactory),
                Collections.emptyList());

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));

        assertEquals(DataSourceState.VALID, sink.getLastState());
    }

    @Test
    public void eagerInitializerDataAvailableWithZeroTimeout() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        DataModel.Flag flag = new FlagBuilder("flag1").version(1).value(LDValue.of(true)).build();
        Map<String, DataModel.Flag> items = new HashMap<>();
        items.put(flag.getKey(), flag);

        FDv2DataSource.DataSourceFactory<Initializer> eagerFactory = new CacheInitializerFactory(() ->
                new MockInitializer(FDv2SourceResult.changeSet(makeFullChangeSet(items), false)));

        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(eagerFactory),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(true), false))));

        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);

        ChangeSet<Map<String, DataModel.Flag>> applied = sink.expectApply();
        assertNotNull(applied);
        assertEquals(1, applied.getData().size());
        assertTrue(applied.getData().containsKey("flag1"));

        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        stopDataSource(dataSource);
    }

    @Test
    public void orchestrationLogging_noSourcesConfigured_logsWarnPerSpec() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        FDv2DataSource dataSource = buildDataSource(sink, Collections.emptyList(), Collections.emptyList());
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        awaitLogContains(logging, "LaunchDarkly client will not connect to LaunchDarkly for feature flag data");
        awaitLogContains(logging, "no initializers or synchronizers configured");
        stopDataSource(dataSource);
    }

    @Test
    public void orchestrationLogging_initializerStartAndInitializedVia_logsInfo() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(() -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false))),
                Collections.emptyList());
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        awaitLogContains(logging, "Initializer 'MockInitializer' is starting.");
        awaitLogContains(logging, "Initialized via 'MockInitializer'.");
        stopDataSource(dataSource);
    }

    @Test
    public void orchestrationLogging_initializerFailedNonException_logsWarnWithDetail() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.singletonList(() -> new MockInitializer(
                        FDv2SourceResult.status(FDv2SourceResult.Status.terminalError(new RuntimeException("bootstrap read failed")), false))),
                Collections.emptyList());
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        awaitExpectingError(startCallback);
        awaitLogContains(logging, "Initializer 'MockInitializer' failed:");
        awaitLogContains(logging, "bootstrap read failed");
    }

    @Test
    public void orchestrationLogging_synchronizerStarting_logsInfo() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false))));
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        awaitLogContains(logging, "Synchronizer 'MockQueuedSynchronizer' is starting.");
        stopDataSource(dataSource);
    }

    @Test
    public void orchestrationLogging_synchronizerInterruptedStatus_logsInfo() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> new MockQueuedSynchronizer(
                        FDv2SourceResult.changeSet(makeChangeSet(false), false),
                        interrupted())));
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        awaitLogContains(logging, "Synchronizer 'MockQueuedSynchronizer' reported status: INTERRUPTED.");
        stopDataSource(dataSource);
    }

    @Test
    public void orchestrationLogging_consecutiveDuplicateStatus_logsOnce() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        // Two INTERRUPTEDs in a row (deduped), then a change set; fourth next() blocks until stop().
        // Second synchronizer tier only SHUTDOWN so the outer loop exits (same factory cannot safely repeat the queue).
        MockQueuedSynchronizer firstSync = new MockQueuedSynchronizer(
                interrupted(),
                interrupted(),
                FDv2SourceResult.changeSet(makeChangeSet(false), false));
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> firstSync,
                        () -> new MockQueuedSynchronizer(
                                FDv2SourceResult.status(FDv2SourceResult.Status.shutdown(), false))));
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        sink.awaitApplyCount(1, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitLogContains(logging, "reported status: INTERRUPTED.");
        assertEquals(1, logLineCountContaining(logging, "reported status: INTERRUPTED."));
        stopDataSource(dataSource);
    }

    @Test
    public void orchestrationLogging_fallback_logsInfo() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        BlockingQueue<Boolean> secondCalledQueue = new LinkedBlockingQueue<>();
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> new MockQueuedSynchronizer(
                                FDv2SourceResult.changeSet(makeChangeSet(false), false),
                                interrupted()),
                        () -> {
                            secondCalledQueue.offer(true);
                            return new MockQueuedSynchronizer(
                                    FDv2SourceResult.changeSet(makeChangeSet(false), false));
                        }),
                1000, 300_000);
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        assertNotNull(secondCalledQueue.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        awaitLogContains(logging, "Fallback condition met, falling back from synchronizer 'MockQueuedSynchronizer'.");
        stopDataSource(dataSource);
    }

    @Test
    public void orchestrationLogging_recovery_logsInfo() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        // A new synchronizer per build, for the reason fallbackAndRecoveryTasksWellBehaved gives.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> new MockQueuedSynchronizer(
                                FDv2SourceResult.changeSet(makeChangeSet(false), false),
                                interrupted()),
                        () -> new MockQueuedSynchronizer(
                                FDv2SourceResult.changeSet(makeChangeSet(false), false))),
                1000, 2000);
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        try {
            assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
            awaitLogContains(logging,
                    "Recovery condition met, moving from synchronizer 'MockQueuedSynchronizer' to primary synchronizer.");
        } finally {
            stopDataSource(dataSource);
        }
    }

    @Test
    public void orchestrationLogging_fdv1Fallback_logsInfo() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        MockQueuedSynchronizer fdv2Sync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(true), true));
        MockQueuedSynchronizer fdv1Sync = new MockQueuedSynchronizer(
                FDv2SourceResult.changeSet(makeChangeSet(true), false));
        FDv2DataSource dataSource = new FDv2DataSource(
                CONTEXT,
                Collections.emptyList(),
                Collections.singletonList(() -> fdv2Sync),
                () -> fdv1Sync,
                sink,
                executor,
                logging.logger);
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        awaitLogContains(logging, "Falling back to an FDv1 fallback synchronizer.");
        stopDataSource(dataSource);
    }

    @Test
    public void orchestrationLogging_logsSynchronizerParkedAndAllParked() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        BlockingQueue<Integer> builds = new LinkedBlockingQueue<>();
        // Every instance of the only synchronizer fails with a terminal error. Each build is recorded.
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Collections.singletonList(() -> {
                    builds.offer(1);
                    return new MockQueuedSynchronizer(terminalError());
                }),
                null,
                120_000,
                300_000,
                /* parkInitialDelayMillis= */ 100,
                /* parkMaxDelayMillis= */ 1000,
                /* failoverDelayMillis= */ 10,
                /* fdv1FallbackTtlMillis= */ TimeUnit.HOURS.toMillis(1));

        // Start the data source, and wait until the parked synchronizer is built again.
        startDataSource(dataSource);
        builds.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(builds.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // The data source logs the park, and logs that every synchronizer is parked.
        awaitLogContains(logging, "Synchronizer 'MockQueuedSynchronizer' is starting.");
        awaitLogContains(logging, "Synchronizer 'MockQueuedSynchronizer' reported status: TERMINAL_ERROR.");
        awaitLogContains(logging, "Synchronizer 'MockQueuedSynchronizer' is backing off for ");
        awaitLogContains(logging, "All synchronizers are backing off for at least ");
        stopDataSource(dataSource);
    }

    @Test
    public void orchestrationLogging_initializerException_logsError() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        FDv2DataSource dataSource = buildDataSource(sink,
                Arrays.asList(
                        () -> new MockInitializer(new RuntimeException("init-boom")),
                        () -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false))),
                Collections.emptyList());
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        awaitLogContains(logging, "Error running initializer 'MockInitializer':");
        awaitLogContains(logging, "init-boom");
    }

    @Test
    public void orchestrationLogging_initializerInterrupted_suppressesErrorLog() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        FDv2DataSource dataSource = buildDataSource(sink,
                Arrays.asList(
                        () -> new MockInitializer(new InterruptedException("interrupted")),
                        () -> new MockInitializer(FDv2SourceResult.changeSet(makeChangeSet(true), false))),
                Collections.emptyList());
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        awaitLogContains(logging, "Initialized via 'MockInitializer'.");
        assertFalse(logging.logCapture.getMessageStrings().stream()
                .anyMatch(s -> s.contains("ERROR:") && s.contains("Error running initializer")));
        stopDataSource(dataSource);
    }

    @Test
    public void orchestrationLogging_synchronizerException_logsError() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> new MockSynchronizer(new RuntimeException("sync-boom")),
                        () -> new MockSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(false), false))));
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        awaitLogContains(logging, "Error running synchronizer 'MockSynchronizer':");
        awaitLogContains(logging, "sync-boom");
        stopDataSource(dataSource);
    }

    @Test
    public void orchestrationLogging_synchronizerInterrupted_suppressesErrorLog() throws Exception {
        MockComponents.MockDataSourceUpdateSink sink = new MockComponents.MockDataSourceUpdateSink();
        FDv2DataSource dataSource = buildDataSource(sink,
                Collections.emptyList(),
                Arrays.asList(
                        () -> new MockSynchronizer(new InterruptedException("interrupted")),
                        () -> new MockSynchronizer(FDv2SourceResult.changeSet(makeChangeSet(false), false))));
        AwaitableCallback<Boolean> startCallback = startDataSource(dataSource);
        assertTrue(startCallback.await(AWAIT_TIMEOUT_SECONDS * 1000));
        sink.awaitApplyCount(1, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertFalse(logging.logCapture.getMessageStrings().stream()
                .anyMatch(s -> s.contains("ERROR:") && s.contains("Error running synchronizer")));
        stopDataSource(dataSource);
    }

    /**
     * Polls captured log messages until one contains {@code substring}, or fails after {@code timeoutMs}.
     * Use this instead of immediate {@code assert*Logged} so assertions are not racy with the worker thread.
     */
    private void awaitLogContains(LogCaptureRule rule, String substring, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (String s : rule.logCapture.getMessageStrings()) {
                if (s.contains(substring)) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        fail("Timed out after " + timeoutMs + " ms waiting for log containing: " + substring);
    }

    private void awaitLogContains(LogCaptureRule rule, String substring) throws InterruptedException {
        awaitLogContains(rule, substring, ORCHESTRATION_LOG_AWAIT_TIMEOUT_MS);
    }

    private static long logLineCountContaining(LogCaptureRule rule, String substring) {
        return rule.logCapture.getMessageStrings().stream()
                .filter(s -> s.contains(substring))
                .count();
    }
}
