package com.launchdarkly.android;

import android.app.Application;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.launchdarkly.android.test.TestActivity;

import org.junit.Before;
import org.junit.Rule;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SharedPrefsFlagStoreManagerTest extends FlagStoreManagerTest {

    private Application testApplication;

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    @Before
    public void setUp() {
        this.testApplication = activityTestRule.getActivity().getApplication();
    }

    public FlagStoreManager createFlagStoreManager(String mobileKey, FlagStoreFactory flagStoreFactory, int maxCachedUsers) {
        return new SharedPrefsFlagStoreManager(testApplication, mobileKey, flagStoreFactory, maxCachedUsers);
    }

}
