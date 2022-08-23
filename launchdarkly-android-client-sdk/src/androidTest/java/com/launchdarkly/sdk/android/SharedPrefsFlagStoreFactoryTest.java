package com.launchdarkly.sdk.android;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

import com.launchdarkly.logging.LDLogger;

@RunWith(AndroidJUnit4.class)
public class SharedPrefsFlagStoreFactoryTest {

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    @Test
    public void createsSharedPrefsFlagStore() {
        Application application = ApplicationProvider.getApplicationContext();
        SharedPrefsFlagStoreFactory factory = new SharedPrefsFlagStoreFactory(application, LDLogger.none());
        FlagStore flagStore = factory.createFlagStore("flagstore_factory_test");
        assertTrue(flagStore instanceof SharedPrefsFlagStore);
    }
}
