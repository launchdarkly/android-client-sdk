package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertNotNull;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;

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
                    clientContext.getDataSourceUpdateSink().init(data.getAll());
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