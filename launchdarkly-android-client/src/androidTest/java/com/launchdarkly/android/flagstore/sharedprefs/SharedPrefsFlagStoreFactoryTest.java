package com.launchdarkly.android.flagstore.sharedprefs;

import android.app.Application;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.launchdarkly.android.flagstore.FlagStore;
import com.launchdarkly.android.test.TestActivity;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SharedPrefsFlagStoreFactoryTest {

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    @Test
    public void createsSharedPrefsFlagStore() {
        Application application = activityTestRule.getActivity().getApplication();
        SharedPrefsFlagStoreFactory factory = new SharedPrefsFlagStoreFactory(application);
        FlagStore flagStore = factory.createFlagStore("flagstore_factory_test");
        assertTrue(flagStore instanceof SharedPrefsFlagStore);
    }
}
