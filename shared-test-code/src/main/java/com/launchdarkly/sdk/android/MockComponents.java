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
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
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
        public final BlockingQueue<Map<String, DataModel.Flag>> inits = new LinkedBlockingQueue<>();
        public final BlockingQueue<DataModel.Flag> upserts = new LinkedBlockingQueue<>();
        public final BlockingQueue<ChangeSet> appliedChangeSets = new LinkedBlockingQueue<>();

        private volatile Selector lastSelector = Selector.EMPTY;

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
            if (!changeSet.getSelector().isEmpty()) {
                lastSelector = changeSet.getSelector();
            }
        }

        public ChangeSet expectApply() {
            return requireValue(appliedChangeSets, 1, TimeUnit.SECONDS);
        }

        @Override
        @NonNull
        public Selector getSelector() {
            return lastSelector;
        }

        @Override
        public void setStatus(@NonNull ConnectionInformation.ConnectionMode connectionMode, @Nullable Throwable failure) {

        }

        @Override
        public void shutDown() {

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
