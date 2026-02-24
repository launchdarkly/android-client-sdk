package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.android.AssertHelpers.requireValue;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.mock;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.env.IEnvironmentReporter;
import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.ChangeSet;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DataSourceState;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public abstract class MockComponents {
    private MockComponents() {}

    public interface Consumer<T> { // because java's Consumer isn't available in all Android versions
        void apply(T value);
    }

    public static class CapturingConfigurer<T> implements ComponentConfigurer<T> {
        public BlockingQueue<ClientContext> receivedClientContexts = new LinkedBlockingQueue<>();
        private final ComponentConfigurer<T> factory;

        public CapturingConfigurer(ComponentConfigurer<T> factory) {
            this.factory = factory;
        }

        @Override
        public T build(ClientContext clientContext) {
            receivedClientContexts.add(clientContext);
            return factory.build(clientContext);
        }

        public ClientContext requireReceivedClientContext() {
            try {
                ClientContext ret = receivedClientContexts.poll(1, TimeUnit.SECONDS);
                assertNotNull("timed out waiting for data source creation", ret);
                return ret;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class MockDataSourceUpdateSink implements DataSourceUpdateSink, DataSourceUpdateSinkV2 {
        /** A paired status update as received by {@link #setStatus(DataSourceState, Throwable)}. */
        public static final class StatusEvent {
            public final DataSourceState state;
            @Nullable public final Throwable error;

            StatusEvent(DataSourceState state, @Nullable Throwable error) {
                this.state = state;
                this.error = error;
            }
        }

        public final BlockingQueue<Map<String, DataModel.Flag>> inits = new LinkedBlockingQueue<>();
        public final BlockingQueue<DataModel.Flag> upserts = new LinkedBlockingQueue<>();

        /** Full ordered history of every {@link #apply} call, safe for concurrent read. */
        public final List<ChangeSet> appliedChangeSets = new CopyOnWriteArrayList<>();

        /** Full ordered history of every {@link #setStatus(DataSourceState, Throwable)} call. */
        public final List<StatusEvent> statusEvents = new CopyOnWriteArrayList<>();

        private volatile Selector lastSelector = Selector.EMPTY;

        private final BlockingQueue<ChangeSet> applyQueue = new LinkedBlockingQueue<>();
        private final BlockingQueue<Boolean> applySignals = new LinkedBlockingQueue<>();
        private final BlockingQueue<DataSourceState> statusUpdates = new LinkedBlockingQueue<>();

        @Override
        public void init(@NonNull LDContext context, @NonNull Map<String, DataModel.Flag> items) {
            inits.add(items);
        }

        public Map<String, DataModel.Flag> expectInit() {
            return requireValue(inits, 1, TimeUnit.SECONDS);
        }

        @Override
        public void upsert(@NonNull LDContext context, @NonNull DataModel.Flag item) {
            upserts.add(item);
        }

        public DataModel.Flag expectUpsert(String flagKey) {
            DataModel.Flag flag = requireValue(upserts, 1, TimeUnit.SECONDS);
            assertEquals(flagKey, flag.getKey());
            return flag;
        }

        @Override
        public void apply(@NonNull LDContext context, @NonNull ChangeSet changeSet) {
            appliedChangeSets.add(changeSet);
            applyQueue.offer(changeSet);
            applySignals.offer(true);
            if (!changeSet.getSelector().isEmpty()) {
                lastSelector = changeSet.getSelector();
            }
        }

        /** Blocks until the next apply arrives or the 1-second timeout expires. */
        public ChangeSet expectApply() {
            return requireValue(applyQueue, 1, TimeUnit.SECONDS);
        }

        @Override
        public void setStatus(@NonNull ConnectionInformation.ConnectionMode connectionMode, @Nullable Throwable failure) {
        }

        @Override
        public void setStatus(@NonNull DataSourceState state, @Nullable Throwable failure) {
            statusEvents.add(new StatusEvent(state, failure));
            statusUpdates.offer(state);
        }

        @Override
        public void shutDown() {
        }

        /** Returns the total number of apply calls received. */
        public int getApplyCount() {
            return appliedChangeSets.size();
        }

        /** Returns the most recently applied {@link ChangeSet}, or {@code null} if none. */
        @Nullable
        public ChangeSet getLastChangeSet() {
            List<ChangeSet> list = appliedChangeSets;
            return list.isEmpty() ? null : list.get(list.size() - 1);
        }

        /** Returns the state from the most recent status update, or {@code null} if none. */
        @Nullable
        public DataSourceState getLastState() {
            List<StatusEvent> list = statusEvents;
            return list.isEmpty() ? null : list.get(list.size() - 1).state;
        }

        /** Returns the error from the most recent status update, or {@code null} if none or no error. */
        @Nullable
        public Throwable getLastError() {
            List<StatusEvent> list = statusEvents;
            return list.isEmpty() ? null : list.get(list.size() - 1).error;
        }

        /** Blocks until a status update is available or the timeout expires. Returns null on timeout. */
        public DataSourceState awaitStatus(long timeout, TimeUnit unit) throws InterruptedException {
            return statusUpdates.poll(timeout, unit);
        }

        /**
         * Blocks until {@code count} status updates have been received or the timeout expires.
         * Returns however many were collected before the timeout.
         */
        public List<DataSourceState> awaitStatuses(int count, long timeout, TimeUnit unit) throws InterruptedException {
            List<DataSourceState> statuses = new ArrayList<>();
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            for (int i = 0; i < count; i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                DataSourceState status = statusUpdates.poll(remaining, TimeUnit.MILLISECONDS);
                if (status == null) break;
                statuses.add(status);
            }
            return statuses;
        }

        /** Blocks until the total apply count reaches {@code expectedCount} or the timeout expires. */
        public void awaitApplyCount(int expectedCount, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            while (appliedChangeSets.size() < expectedCount) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                applySignals.poll(remaining, TimeUnit.MILLISECONDS);
            }
        }
    }

    public static class SingleComponentConfigurer<T> implements ComponentConfigurer<T> {
        private final T instance;

        public SingleComponentConfigurer(T instance) {
            this.instance = instance;
        }

        @Override
        public T build(ClientContext clientContext) {
            return instance;
        }
    }

    public static DataSource successfulDataSource(
        final ClientContext clientContext,
        final EnvironmentData data,
        final ConnectionInformation.ConnectionMode connectionMode,
        final BlockingQueue<DataSource> startedQueue,
        final BlockingQueue<DataSource> stoppedQueue
        ) {
        return new DataSource() {
            @Override
            public void start(@NonNull Callback<Boolean> resultCallback) {
                new Thread(() -> {
                    clientContext.getDataSourceUpdateSink().init(clientContext.getEvaluationContext(), data.getAll());
                    clientContext.getDataSourceUpdateSink().setStatus(connectionMode, null);
                    resultCallback.onSuccess(true);
                }).start();
                if (startedQueue != null) {
                    startedQueue.add(this);
                }
            }

            @Override
            public void stop(@NonNull Callback<Void> completionCallback) {
                if (stoppedQueue != null) {
                    stoppedQueue.add(this);
                }
            }
        };
    }

    public static DataSource failingDataSource(
            final ClientContext clientContext,
            final ConnectionInformation.ConnectionMode connectionMode,
            final Throwable error
    ) {
        return new DataSource() {
            @Override
            public void start(@NonNull Callback<Boolean> resultCallback) {
                new Thread(() -> {
                    clientContext.getDataSourceUpdateSink().setStatus(connectionMode, error);
                    resultCallback.onError(error);
                }).start();
            }

            @Override
            public void stop(@NonNull Callback<Void> completionCallback) {}
        };
    }
}
