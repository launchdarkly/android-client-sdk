package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;

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
            manager.switchToContext(makeContext(i), false, LDUtil.noOpCallback());
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
            manager.switchToContext(makeContext(i), false, LDUtil.noOpCallback());
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
            manager.switchToContext(makeContext(i), false, LDUtil.noOpCallback());
            manager.initData(makeContext(i), makeFlagData(i));
            assertContextIsCached(makeContext(i), makeFlagData(i));
        }

        ContextDataManager newManagerInstance = createDataManager(1);
        newManagerInstance.switchToContext(makeContext(3), false, LDUtil.noOpCallback());
        newManagerInstance.initData(makeContext(3), makeFlagData(3));

        assertContextIsNotCached(makeContext(1));
        assertContextIsNotCached(makeContext(2));
        assertContextIsCached(makeContext(3), makeFlagData(3));
    }

}
