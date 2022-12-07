package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.android.AssertHelpers.assertDataSetsEqual;
import static com.launchdarkly.sdk.android.AssertHelpers.assertFlagsEqual;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import org.junit.Rule;
import org.junit.Test;

public class ContextDataManagerContextCachingTest extends ContextDataManagerTestBase {
    @Test
    public void deletePreviousDataAfterSwitchForZeroCached() {
        ContextDataManager manager = createDataManager(0);

        for (int i = 1; i <= 2; i++) {
            manager.initData(makeContext(i), makeFlagData(i));
        }

        assertContextIsNotCached(makeContext(1));
    }

    @Test
    public void canCacheManyContextsWithNegativeMaxCachedContexts() {
        ContextDataManager manager = createDataManager(-1);

        int numContexts = 20;
        for (int i = 1; i <= numContexts; i++) {
            manager.initData(makeContext(i), makeFlagData(i));
        }

        for (int i = 1; i <= numContexts; i++) {
            assertContextIsCached(makeContext(i), makeFlagData(i));
        }
        assertEquals(numContexts, environmentStore.getIndex().data.size());
    }

    @Test
    public void deletesExcessContexts() {
        int maxCachedContexts = 10, excess = 2;
        ContextDataManager manager = createDataManager(maxCachedContexts);

        for (int i = 1; i <= maxCachedContexts + excess; i++) {
            manager.initData(makeContext(i), makeFlagData(i));
        }

        for (int i = 1; i <= excess; i++) {
            assertContextIsNotCached(makeContext(i));
        }
        for (int i = excess + 1; i <= maxCachedContexts + excess; i++) {
            assertContextIsCached(makeContext(i), makeFlagData(i));
        }
    }

    @Test
    public void deletesExcessContextsFromPreviousManagerInstance() {
        ContextDataManager manager = createDataManager(1);

        for (int i = 1; i <= 2; i++) {
            manager.initData(makeContext(i), makeFlagData(i));
            assertContextIsCached(makeContext(i), makeFlagData(i));
        }

        ContextDataManager newManagerInstance = createDataManager(1);
        newManagerInstance.initData(makeContext(3), makeFlagData(3));

        assertContextIsNotCached(makeContext(1));
        assertContextIsNotCached(makeContext(2));
        assertContextIsCached(makeContext(3), makeFlagData(3));
    }

}
