package com.launchdarkly.android.response;

import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.google.gson.JsonPrimitive;
import com.launchdarkly.android.test.TestActivity;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class UserFlagVersionSharedPreferencesTest {

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    @Test
    public void savesFlagVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12.0f);
        final UserFlagResponse key2 = new UserFlagResponse("key2", new JsonPrimitive(true));

        VersionSharedPreferences versionSharedPreferences
                = new UserFlagVersionSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(new ArrayList<FlagResponse>() {
            {
                add(key1);
                add(key2);
            }
        });

        Assert.assertEquals(versionSharedPreferences.getStoredVersion(key1), 12.0f, 0);
        Assert.assertEquals(versionSharedPreferences.getStoredVersion(key2), Float.MIN_VALUE, 0);
    }

    @Test
    public void deletesFlagVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12.0f);

        VersionSharedPreferences versionSharedPreferences
                = new UserFlagVersionSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(new ArrayList<FlagResponse>() {
            {
                add(key1);
            }
        });
        versionSharedPreferences.deleteStoredVersion(key1);

        Assert.assertEquals(versionSharedPreferences.getStoredVersion(key1), Float.MIN_VALUE, 0);
    }

    @Test
    public void updatesFlagVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12.0f);
        UserFlagResponse updatedKey1 = new UserFlagResponse(key1.getKey(), key1.getValue(), 15.0f);

        VersionSharedPreferences versionSharedPreferences
                = new UserFlagVersionSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(new ArrayList<FlagResponse>() {
            {
                add(key1);
            }
        });

        versionSharedPreferences.updateStoredVersion(updatedKey1);

        Assert.assertEquals(versionSharedPreferences.getStoredVersion(key1), 15.0f, 0);
    }

    @Test
    public void clearsFlagVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12.0f);
        final UserFlagResponse key2 = new UserFlagResponse("key2", new JsonPrimitive(true), 14.0f);

        VersionSharedPreferences versionSharedPreferences
                = new UserFlagVersionSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(new ArrayList<FlagResponse>() {
            {
                add(key1);
                add(key2);
            }
        });
        versionSharedPreferences.clear();

        Assert.assertEquals(versionSharedPreferences.getStoredVersion(key1), Float.MIN_VALUE, 0);
        Assert.assertEquals(versionSharedPreferences.getStoredVersion(key2), Float.MIN_VALUE, 0);
    }
}
