package com.launchdarkly.android;

import android.os.Looper;
import android.util.Pair;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.easymock.IArgumentMatcher;
import org.junit.Test;

import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.easymock.EasyMock.and;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.checkOrder;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.newCapture;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public abstract class FlagStoreManagerTest extends EasyMockSupport {

    public abstract FlagStoreManager createFlagStoreManager(String mobileKey, FlagStoreFactory flagStoreFactory, int maxCachedUsers);

    @Test
    public void initialFlagStoreIsNull() {
        final FlagStoreManager manager = createFlagStoreManager("testKey", null, 0);
        assertNull(manager.getCurrentUserStore());
    }

    @Test
    public void testSwitchToUser() {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore mockStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate, 0);

        expect(mockCreate.createFlagStore(anyString())).andReturn(mockStore);
        mockStore.registerOnStoreUpdatedListener(isA(StoreUpdatedListener.class));

        replayAll();

        manager.switchToUser("user1");

        verifyAll();

        assertSame(mockStore, manager.getCurrentUserStore());
    }

    @Test
    public void deletePreviousUserAfterSwitchForZeroCached() throws InterruptedException {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore firstUserStore = strictMock(FlagStore.class);
        final Capture<String> firstUserIdentifier = newCapture();
        final FlagStore secondUserStore = strictMock(FlagStore.class);
        final Capture<String> secondUserIdentifier = newCapture();

        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate, 0);

        expect(mockCreate.createFlagStore(capture(firstUserIdentifier))).andReturn(firstUserStore).once();
        firstUserStore.registerOnStoreUpdatedListener(isA(StoreUpdatedListener.class));
        firstUserStore.unregisterOnStoreUpdatedListener();

        expect(mockCreate.createFlagStore(capture(secondUserIdentifier))).andReturn(secondUserStore).once();
        secondUserStore.registerOnStoreUpdatedListener(isA(StoreUpdatedListener.class));

        expect(mockCreate.createFlagStore(captureEq(firstUserIdentifier))).andReturn(firstUserStore).once();
        firstUserStore.delete();

        replayAll();

        manager.switchToUser("user1");
        Thread.sleep(2);
        manager.switchToUser("user2");

        verifyAll();

        assertSame(secondUserStore, manager.getCurrentUserStore());
        assertNotEquals(firstUserIdentifier.getValue(), secondUserIdentifier.getValue());
    }

    @Test
    public void canCacheManyUsersWithNegativeMaxCachedUsers() {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore fillerStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate,-1);

        checkOrder(fillerStore, false);
        fillerStore.registerOnStoreUpdatedListener(anyObject(StoreUpdatedListener.class));
        expectLastCall().anyTimes();
        fillerStore.unregisterOnStoreUpdatedListener();
        expectLastCall().anyTimes();
        expect(mockCreate.createFlagStore(anyString())).andReturn(fillerStore).times(10);

        replayAll();

        for (int i = 0; i < 10; i++) {
            manager.switchToUser("user" + i);
        }

        verifyAll();
    }

    @Test
    public void deletesExcessUsersFromPreviousManager() throws InterruptedException {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore fillerStore1 = strictMock(FlagStore.class);
        final FlagStore fillerStore2 = strictMock(FlagStore.class);
        final FlagStore newStore = strictMock(FlagStore.class);
        final Capture<String> storeId1 = newCapture();
        final Capture<String> storeId2 = newCapture();
        FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate,1);

        fillerStore1.registerOnStoreUpdatedListener(anyObject(StoreUpdatedListener.class));
        expectLastCall();
        fillerStore1.unregisterOnStoreUpdatedListener();
        expectLastCall();
        fillerStore2.registerOnStoreUpdatedListener(anyObject(StoreUpdatedListener.class));
        expectLastCall();
        expect(mockCreate.createFlagStore(capture(storeId1))).andReturn(fillerStore1);
        expect(mockCreate.createFlagStore(capture(storeId2))).andReturn(fillerStore2);

        replayAll();

        manager.switchToUser("user1");
        Thread.sleep(2);
        manager.switchToUser("user2");
        Thread.sleep(2);

        verifyAll();
        resetAll();

        checkOrder(mockCreate, false);
        expect(mockCreate.createFlagStore(and(captureNeq(storeId1), captureNeq(storeId2)))).andReturn(newStore);
        expect(mockCreate.createFlagStore(captureEq(storeId1))).andReturn(fillerStore1);
        expect(mockCreate.createFlagStore(captureEq(storeId2))).andReturn(fillerStore2);
        newStore.registerOnStoreUpdatedListener(anyObject(StoreUpdatedListener.class));
        fillerStore1.delete();
        expectLastCall();
        fillerStore2.delete();

        replayAll();

        manager = createFlagStoreManager("testKey", mockCreate, 0);
        manager.switchToUser("user3");

        verifyAll();
    }

    public void verifyDeletesOldestWithMaxCachedUsers(int maxCachedUsers) throws InterruptedException {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore oldestStore = strictMock(FlagStore.class);
        final FlagStore fillerStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate, maxCachedUsers);
        final Capture<String> oldestIdentifier = newCapture();
        final int[] oldestCountBox = {0};
        final FlagStoreFactory delegate = identifier -> {
            if (identifier.equals(oldestIdentifier.getValue())) {
                oldestCountBox[0]++;
                return oldestStore;
            }
            return fillerStore;
        };

        checkOrder(fillerStore, false);
        fillerStore.registerOnStoreUpdatedListener(anyObject(StoreUpdatedListener.class));
        expectLastCall().anyTimes();
        fillerStore.unregisterOnStoreUpdatedListener();
        expectLastCall().anyTimes();
        expect(mockCreate.createFlagStore(capture(oldestIdentifier))).andReturn(oldestStore);
        oldestStore.registerOnStoreUpdatedListener(anyObject(StoreUpdatedListener.class));
        expectLastCall();
        oldestStore.unregisterOnStoreUpdatedListener();
        expectLastCall();
        expect(mockCreate.createFlagStore(captureNeq(oldestIdentifier))).andReturn(fillerStore).times(maxCachedUsers + 1);
        expect(mockCreate.createFlagStore(captureEq(oldestIdentifier))).andReturn(oldestStore);
        oldestStore.delete();
        expectLastCall();

        replayAll();

        // Adds 2 to maxCachedUsers as one is for the active user, and we want one extra to evict the last.
        for (int i = 0; i < maxCachedUsers + 2; i++) {
            manager.switchToUser("user" + i);
            // Unfortunately we need to use Thread.sleep() to stagger the loading of users for this test
            // otherwise the millisecond precision is not good enough to guarantee an ordering of the
            // users for removing the oldest.
            Thread.sleep(2);
        }

        verifyAll();
    }

    @Test
    public void deletesOldestWithDefaultMaxCachedUsers() throws InterruptedException {
        verifyDeletesOldestWithMaxCachedUsers(5);
    }

    @Test
    public void deletesOldestWithGreaterMaxCachedUsers() throws InterruptedException {
        verifyDeletesOldestWithMaxCachedUsers(7);
    }

    @Test
    public void deletesOldestWithLessMaxCachedUsers() throws InterruptedException {
        verifyDeletesOldestWithMaxCachedUsers(3);
    }

    @Test
    public void testGetListenersForKey() {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate, 0);
        final FeatureFlagChangeListener mockFlagListener = strictMock(FeatureFlagChangeListener.class);
        final FeatureFlagChangeListener mockFlagListener2 = strictMock(FeatureFlagChangeListener.class);

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
    public void listenerIsCalledOnCreate() throws InterruptedException {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore mockStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate, 0);
        final FeatureFlagChangeListener mockFlagListener = strictMock(FeatureFlagChangeListener.class);
        final Capture<StoreUpdatedListener> managerListener = newCapture();
        final CountDownLatch waitLatch = new CountDownLatch(1);

        expect(mockCreate.createFlagStore(anyString())).andReturn(mockStore);
        mockStore.registerOnStoreUpdatedListener(capture(managerListener));
        mockFlagListener.onFeatureFlagChange("flag");
        expectLastCall().andAnswer(() -> {
            waitLatch.countDown();
            return null;
        });

        replayAll();

        manager.switchToUser("user1");
        manager.registerListener("flag", mockFlagListener);
        Pair<String, FlagStoreUpdateType> update = new Pair<>("flag", FlagStoreUpdateType.FLAG_CREATED);
        managerListener.getValue().onStoreUpdate(Collections.singletonList(update));
        waitLatch.await(1000, TimeUnit.MILLISECONDS);

        verifyAll();
    }

    @Test
    public void listenerIsCalledOnUpdate() throws InterruptedException {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore mockStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate, 0);
        final FeatureFlagChangeListener mockFlagListener = strictMock(FeatureFlagChangeListener.class);
        final Capture<StoreUpdatedListener> managerListener = newCapture();
        final CountDownLatch waitLatch = new CountDownLatch(1);

        expect(mockCreate.createFlagStore(anyString())).andReturn(mockStore);
        mockStore.registerOnStoreUpdatedListener(capture(managerListener));
        mockFlagListener.onFeatureFlagChange("flag");
        expectLastCall().andAnswer(() -> {
            waitLatch.countDown();
            return null;
        });

        replayAll();

        manager.switchToUser("user1");
        manager.registerListener("flag", mockFlagListener);
        Pair<String, FlagStoreUpdateType> update = new Pair<>("flag", FlagStoreUpdateType.FLAG_UPDATED);
        managerListener.getValue().onStoreUpdate(Collections.singletonList(update));
        waitLatch.await(1000, TimeUnit.MILLISECONDS);

        verifyAll();
    }

    @Test
    public void listenerIsNotCalledOnDelete() throws InterruptedException {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore mockStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate, 0);
        final FeatureFlagChangeListener mockFlagListener = strictMock(FeatureFlagChangeListener.class);
        final Capture<StoreUpdatedListener> managerListener = newCapture();

        expect(mockCreate.createFlagStore(anyString())).andReturn(mockStore);
        mockStore.registerOnStoreUpdatedListener(capture(managerListener));

        replayAll();

        manager.switchToUser("user1");
        manager.registerListener("flag", mockFlagListener);
        Pair<String, FlagStoreUpdateType> update = new Pair<>("flag", FlagStoreUpdateType.FLAG_DELETED);
        managerListener.getValue().onStoreUpdate(Collections.singletonList(update));
        // Unfortunately we are testing that an asynchronous method is *not* called, we just have to
        // wait a bit to be sure.
        Thread.sleep(100);

        verifyAll();
    }

    @Test
    public void listenerIsNotCalledAfterUnregistering() throws InterruptedException {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore mockStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate, 0);
        final FeatureFlagChangeListener mockFlagListener = strictMock(FeatureFlagChangeListener.class);
        final Capture<StoreUpdatedListener> managerListener = newCapture();

        expect(mockCreate.createFlagStore(anyString())).andReturn(mockStore);
        mockStore.registerOnStoreUpdatedListener(capture(managerListener));

        replayAll();

        manager.switchToUser("user1");
        manager.registerListener("flag", mockFlagListener);
        manager.unRegisterListener("flag", mockFlagListener);
        Pair<String, FlagStoreUpdateType> update = new Pair<>("flag", FlagStoreUpdateType.FLAG_CREATED);
        managerListener.getValue().onStoreUpdate(Collections.singletonList(update));
        // Unfortunately we are testing that an asynchronous method is *not* called, we just have to
        // wait a bit to be sure.
        Thread.sleep(100);

        verifyAll();
    }

    @Test
    public void listenerIsCalledOnMainThread() throws InterruptedException {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore mockStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate, 0);
        final FeatureFlagChangeListener mockFlagListener = strictMock(FeatureFlagChangeListener.class);
        final Capture<StoreUpdatedListener> managerListener = newCapture();
        final CountDownLatch waitLatch = new CountDownLatch(1);

        expect(mockCreate.createFlagStore(anyString())).andReturn(mockStore);
        mockStore.registerOnStoreUpdatedListener(capture(managerListener));
        mockFlagListener.onFeatureFlagChange("flag");
        expectLastCall().andDelegateTo(new FeatureFlagChangeListener() {
            @Override
            public void onFeatureFlagChange(String flagKey) {
                assertSame(Looper.myLooper(), Looper.getMainLooper());
                waitLatch.countDown();
            }
        });

        replayAll();

        manager.switchToUser("user1");
        manager.registerListener("flag", mockFlagListener);
        Pair<String, FlagStoreUpdateType> update = new Pair<>("flag", FlagStoreUpdateType.FLAG_CREATED);
        managerListener.getValue().onStoreUpdate(Collections.singletonList(update));
        waitLatch.await(1000, TimeUnit.MILLISECONDS);

        verifyAll();
    }

    static <T> T captureEq(Capture<T> in) {
        //noinspection unchecked
        EasyMock.reportMatcher(new CaptureEq(in, false));
        return null;
    }

    static <T> T captureNeq(Capture<T> in) {
        //noinspection unchecked
        EasyMock.reportMatcher(new CaptureEq(in, true));
        return null;
    }

    static class CaptureEq<T> implements IArgumentMatcher {

        private final boolean invert;
        private final Capture<T> expected;

        CaptureEq(Capture<T> expected, boolean invert) {
            this.expected = expected;
            this.invert = invert;
        }

        public boolean matches(Object actual) {
            return invert != equalTo(actual);
        }

        private boolean equalTo(Object actual) {
            return Objects.equals(expected.getValue(), actual);
        }

        public void appendTo(StringBuffer buffer) {
            buffer.append("captureEq(expected: ");
            buffer.append(expected.getValue());
            buffer.append(", invert: ");
            buffer.append(invert);
            buffer.append(")");
        }
    }
}
