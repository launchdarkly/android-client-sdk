package com.launchdarkly.sdk.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FlagStoreManagerImplTest extends FlagStoreManagerTest {

    private PersistentDataStore store = new InMemoryPersistentDataStore();

    public FlagStoreManager createFlagStoreManager(String mobileKey, FlagStoreFactory flagStoreFactory, int maxCachedUsers) {
        return new FlagStoreManagerImpl(mobileKey, flagStoreFactory, store, maxCachedUsers, LDLogger.none());
    }
}
