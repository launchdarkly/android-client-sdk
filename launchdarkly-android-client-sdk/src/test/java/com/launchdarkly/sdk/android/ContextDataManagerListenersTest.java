package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.launchdarkly.sdk.android.DataModel.Flag;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ContextDataManagerListenersTest extends ContextDataManagerTestBase {
    @Test
    public void testGetListenersForKey() {
        final ContextDataManager manager = createDataManager();
        final FeatureFlagChangeListener mockFlagListener = new AwaitableFlagListener();
        final FeatureFlagChangeListener mockFlagListener2 = new AwaitableFlagListener();

        assertEquals(0, manager.getListenersByKey("flag").size());
        manager.registerListener("flag", mockFlagListener);
        assertEquals(1, manager.getListenersByKey("flag").size());
        assertTrue(manager.getListenersByKey("flag").contains(mockFlagListener));
        assertEquals(0, manager.getListenersByKey("otherKey").size());
        manager.registerListener("flag", mockFlagListener2);
        assertEquals(2, manager.getListenersByKey("flag").size());
        assertTrue(manager.getListenersByKey("flag").contains(mockFlagListener));
        assertTrue(manager.getListenersByKey("flag").contains(mockFlagListener2));
        assertEquals(0, manager.getListenersByKey("otherKey").size());
    }

    @Test
    public void listenerIsCalledOnUpdate() throws InterruptedException {
        Flag flag = new FlagBuilder("flag").version(1).build();
        final ContextDataManager manager = createDataManager();
        AwaitableFlagListener listener = new AwaitableFlagListener();
        AwaitableFlagListener allFlagsListener = new AwaitableFlagListener();

        manager.registerListener(flag.getKey(), listener);
        manager.registerAllFlagsListener(allFlagsListener);

        manager.upsert(flag);

        assertEquals(flag.getKey(), listener.expectUpdate(5, TimeUnit.SECONDS));
        assertEquals(flag.getKey(), allFlagsListener.expectUpdate(5, TimeUnit.SECONDS));
    }

    @Test
    public void listenerIsCalledOnDelete() throws InterruptedException {
        Flag flag = Flag.deletedItemPlaceholder("flag", 1);
        final ContextDataManager manager = createDataManager();
        AwaitableFlagListener listener = new AwaitableFlagListener();
        AwaitableFlagListener allFlagsListener = new AwaitableFlagListener();

        manager.registerListener(flag.getKey(), listener);
        manager.registerAllFlagsListener(allFlagsListener);

        manager.upsert(flag);

        assertEquals(flag.getKey(), listener.expectUpdate(5, TimeUnit.SECONDS));
        assertEquals(flag.getKey(), allFlagsListener.expectUpdate(5, TimeUnit.SECONDS));
    }

    @Test
    public void listenerIsNotCalledAfterUnregistering() throws InterruptedException {
        Flag flag = new FlagBuilder("flag").version(1).build();
        final ContextDataManager manager = createDataManager();
        AwaitableFlagListener listener = new AwaitableFlagListener();
        AwaitableFlagListener allFlagsListener = new AwaitableFlagListener();

        manager.registerListener(flag.getKey(), listener);
        manager.unregisterListener(flag.getKey(), listener);

        manager.upsert(flag);

        // Unfortunately we are testing that an asynchronous method is *not* called, we just have to
        // wait a bit to be sure.
        listener.expectNoUpdates(100, TimeUnit.MILLISECONDS);
        allFlagsListener.expectNoUpdates(100, TimeUnit.MILLISECONDS);

        verifyAll();
    }

    @Test
    public void listenerIsCalledOnMainThread() throws InterruptedException {
        Flag flag = Flag.deletedItemPlaceholder("flag", 1);
        final ContextDataManager manager = createDataManager();
        AwaitableFlagListener listener = new AwaitableFlagListener();
        AwaitableFlagListener allFlagsListener = new AwaitableFlagListener();

        manager.registerListener(flag.getKey(), listener);
        manager.registerAllFlagsListener(allFlagsListener);

        manager.upsert(flag);

        listener.expectUpdate(5, TimeUnit.SECONDS);
        allFlagsListener.expectUpdate(5, TimeUnit.SECONDS);
        assertTrue(listener.isCalledFromMainThread());
        assertTrue(allFlagsListener.isCalledFromMainThread());
    }

    private class AwaitableFlagListener implements FeatureFlagChangeListener, LDAllFlagsListener {
        private final BlockingQueue<String> flagKeysUpdated = new LinkedBlockingQueue<>();
        private volatile boolean calledFromMainThread;

        @Override
        public void onFeatureFlagChange(String flagKey) {
            calledFromMainThread = taskExecutor.isThisTheFakeMainThread();
            flagKeysUpdated.add(flagKey);
        }

        @Override
        public void onChange(List<String> flagKey) {
            calledFromMainThread = taskExecutor.isThisTheFakeMainThread();
            flagKeysUpdated.addAll(flagKey);
        }

        public String expectUpdate(long timeout, TimeUnit timeoutUnit) {
            try {
                String flagKey = flagKeysUpdated.poll(timeout, timeoutUnit);
                if (flagKey == null) {
                    fail("timed out waiting for listener to be called");
                }
                return flagKey;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        public void expectNoUpdates(long timeout, TimeUnit timeoutUnit) {
            try {
                String flagKey = flagKeysUpdated.poll(timeout, timeoutUnit);
                if (flagKey != null) {
                    fail("listener was unexpectedly called for flag: " + flagKey);
                }
            } catch (InterruptedException e) {}
        }

        public boolean isCalledFromMainThread() {
            return calledFromMainThread;
        }
    }
}
