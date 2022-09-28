package com.launchdarkly.sdk.android;

import android.app.Application;

import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

public class FlagStoreImplFactoryTest {

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    @Test
    public void createsFlagStoreImpl() {
        PersistentDataStore store = new NullPersistentDataStore();
        FlagStoreImplFactory factory = new FlagStoreImplFactory(
                new PersistentDataStoreWrapper(store, LDLogger.none()).perEnvironmentData("key"),
                logging.logger);
        FlagStore flagStore = factory.createFlagStore("flagstore_factory_test");
        assertTrue(flagStore instanceof FlagStoreImpl);
    }
}
