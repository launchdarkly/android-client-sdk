package com.launchdarkly.android;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SharedPrefsFlagStoreManagerTest extends FlagStoreManagerTest {

    private Application testApplication;

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    @Before
    public void setUp() {
        this.testApplication = ApplicationProvider.getApplicationContext();
    }

    public FlagStoreManager createFlagStoreManager(String mobileKey, FlagStoreFactory flagStoreFactory, int maxCachedUsers) {
        return new SharedPrefsFlagStoreManager(testApplication, mobileKey, flagStoreFactory, maxCachedUsers);
    }

}
