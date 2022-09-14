package com.launchdarkly.sdk.android;

import android.os.Looper;
import android.util.Pair;

import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.Rule;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.newCapture;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

public class FlagStoreManagerImplTest extends EasyMockSupport {
    private static final String MOBILE_KEY = "test-mobile-key";

    private final PersistentDataStore store = new InMemoryPersistentDataStore();

    @Rule
    public final AndroidLoggingRule logging = new AndroidLoggingRule();

    public FlagStoreManager createFlagStoreManager(String mobileKey, FlagStoreFactory flagStoreFactory, int maxCachedUsers) {
        return new FlagStoreManagerImpl(flagStoreFactory,
                TestUtil.makeSimplePersistentDataStoreWrapper().perEnvironmentData(mobileKey),
                maxCachedUsers, logging.logger);
    }

    public FlagStoreManager createFlagStoreManager(
            PersistentDataStoreWrapper.PerEnvironmentData environmentStore,
            int maxCachedUsers
    ) {
        return new FlagStoreManagerImpl(
                new FlagStoreImplFactory(environmentStore, logging.logger),
                environmentStore,
                maxCachedUsers,
                logging.logger
        );
    }

    private static String makeContextHash(int index) {
        return "contexthash" + index;
    }

    private static Flag makeFlag(int index) {
        return new FlagBuilder("flag" + index).build();
    }

    private static void assertContextIsCached(PersistentDataStoreWrapper.PerEnvironmentData environmentStore,
                                              String contextHash, Flag flag) {
        EnvironmentData data = environmentStore.getContextData(contextHash);
        assertNotNull("flag data for context " + contextHash + " not found in store", data);
        assertEquals("expected flag data for " + contextHash + " to only have 1 flag",
                1, data.values().size());
        assertNotNull("expected flag key " + flag.getKey() + " to be in flag data for " + contextHash,
                data.getFlag(flag.getKey()));

        ContextIndex index = environmentStore.getIndex();
        assertNotNull(index);
        for (ContextIndex.IndexEntry e: index.data) {
            if (e.contextId.equals(contextHash)) {
                return;
            }
        }
        fail("context hash " + contextHash + " not found in index");
    }

    private static void assertContextIsNotCached(PersistentDataStoreWrapper.PerEnvironmentData environmentStore,
                                                 String contextHash) {
        assertNull("flag data for context " + contextHash + " should not have been in store",
                environmentStore.getContextData(contextHash));

        ContextIndex index = environmentStore.getIndex();
        if (index != null) {
            for (ContextIndex.IndexEntry e: index.data) {
                assertNotEquals("context hash " + contextHash + " should not have been in index",
                        contextHash, e.contextId);
            }
        }
    }

    @Test
    public void initialFlagStoreIsNull() {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate, 0);
        assertNull(manager.getCurrentContextStore());
    }

    @Test
    public void deletePreviousUserAfterSwitchForZeroCached() {
        final PersistentDataStore store = new InMemoryPersistentDataStore();
        final PersistentDataStoreWrapper wrapper = new PersistentDataStoreWrapper(store, logging.logger);
        final PersistentDataStoreWrapper.PerEnvironmentData environmentStore = wrapper.perEnvironmentData(MOBILE_KEY);

        FlagStoreManager manager = createFlagStoreManager(environmentStore,0);

        for (int i = 1; i <= 2; i++) {
            manager.switchToContext(makeContextHash(i));
            manager.getCurrentContextStore().applyFlagUpdate(makeFlag(i));
        }

        assertContextIsNotCached(environmentStore, makeContextHash(1));
    }

    @Test
    public void canCacheManyUsersWithNegativeMaxCachedUsers() {
        final PersistentDataStore store = new InMemoryPersistentDataStore();
        final PersistentDataStoreWrapper wrapper = new PersistentDataStoreWrapper(store, logging.logger);
        final PersistentDataStoreWrapper.PerEnvironmentData environmentStore = wrapper.perEnvironmentData(MOBILE_KEY);

        FlagStoreManager manager = createFlagStoreManager(environmentStore,-1);

        int numUsers = 20;
        for (int i = 1; i <= numUsers; i++) {
            manager.switchToContext("user" + i);
            manager.getCurrentContextStore().applyFlagUpdate(new FlagBuilder("user" + i + "flag").build());
        }

        for (int i = 1; i <= numUsers; i++) {
            assertNotNull(environmentStore.getContextData("user" + i));
        }
        assertEquals(numUsers, environmentStore.getIndex().data.size());
    }

    @Test
    public void deletesExcessUsersFromPreviousManager() {
        final PersistentDataStore store = new InMemoryPersistentDataStore();
        final PersistentDataStoreWrapper wrapper = new PersistentDataStoreWrapper(store, logging.logger);
        final PersistentDataStoreWrapper.PerEnvironmentData environmentStore = wrapper.perEnvironmentData(MOBILE_KEY);

        FlagStoreManager manager = createFlagStoreManager(environmentStore,1);

        for (int i = 1; i <= 2; i++) {
            manager.switchToContext(makeContextHash(i));
            manager.getCurrentContextStore().applyFlagUpdate(makeFlag(i));
            assertContextIsCached(environmentStore, makeContextHash(i), makeFlag(i));
        }

        manager = createFlagStoreManager(environmentStore, 1);
        manager.switchToContext(makeContextHash(3));
        manager.getCurrentContextStore().applyFlagUpdate(makeFlag(3));

        assertContextIsNotCached(environmentStore, makeContextHash(1));
        assertContextIsNotCached(environmentStore, makeContextHash(2));
        assertContextIsCached(environmentStore, makeContextHash(3), makeFlag(3));
    }

    public void verifyDeletesOldestWithMaxCachedUsers(int maxCachedUsers) {
        final PersistentDataStore store = new InMemoryPersistentDataStore();
        final PersistentDataStoreWrapper wrapper = new PersistentDataStoreWrapper(store, logging.logger);
        final PersistentDataStoreWrapper.PerEnvironmentData environmentStore = wrapper.perEnvironmentData(MOBILE_KEY);

        FlagStoreManager manager = createFlagStoreManager(environmentStore,maxCachedUsers);

        for (int i = 1; i <= maxCachedUsers + 1; i++) {
            manager.switchToContext(makeContextHash(i));
            manager.getCurrentContextStore().applyFlagUpdate(makeFlag(i));
        }

        assertContextIsNotCached(environmentStore, makeContextHash(1));
        for (int i = 2; i <= maxCachedUsers + 1; i++) {
            assertContextIsCached(environmentStore, makeContextHash(i), makeFlag(i));
        }
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

        manager.switchToContext("user1");
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

        manager.switchToContext("user1");
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

        manager.switchToContext("user1");
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

        manager.switchToContext("user1");
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

        manager.switchToContext("user1");
        manager.registerListener("flag", mockFlagListener);
        Pair<String, FlagStoreUpdateType> update = new Pair<>("flag", FlagStoreUpdateType.FLAG_CREATED);
        managerListener.getValue().onStoreUpdate(Collections.singletonList(update));
        waitLatch.await(1000, TimeUnit.MILLISECONDS);

        verifyAll();
    }
}
