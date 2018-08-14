package com.launchdarkly.android.response;

import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.google.gson.JsonPrimitive;
import com.launchdarkly.android.test.TestActivity;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

@RunWith(AndroidJUnit4.class)
public class UserFlagResponseSharedPreferencesTest {

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    @Test
    public void savesVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12, -1);
        final UserFlagResponse key2 = new UserFlagResponse("key2", new JsonPrimitive(true));

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Arrays.<FlagResponse>asList(key1, key2));

        Assert.assertEquals(versionSharedPreferences.getStoredVersion(key1.getKey()), 12, 0);
        Assert.assertEquals(versionSharedPreferences.getStoredVersion(key2.getKey()), -1, 0);
    }

    @Test
    public void deletesVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12, -1);

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Collections.<FlagResponse>singletonList(key1));
        versionSharedPreferences.deleteStoredFlagResponse(key1);

        Assert.assertEquals(versionSharedPreferences.getStoredVersion(key1.getKey()), -1, 0);
    }

    @Test
    public void updatesVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12, -1);
        UserFlagResponse updatedKey1 = new UserFlagResponse(key1.getKey(), key1.getValue(), 15, -1);

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Collections.<FlagResponse>singletonList(key1));

        versionSharedPreferences.updateStoredFlagResponse(updatedKey1);

        Assert.assertEquals(versionSharedPreferences.getStoredVersion(key1.getKey()), 15, 0);
    }

    @Test
    public void clearsFlags() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12, -1);
        final UserFlagResponse key2 = new UserFlagResponse("key2", new JsonPrimitive(true), 14, -1);

        UserFlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Arrays.<FlagResponse>asList(key1, key2));
        versionSharedPreferences.clear();

        Assert.assertEquals(versionSharedPreferences.getStoredVersion(key1.getKey()), -1, 0);
        Assert.assertEquals(versionSharedPreferences.getStoredVersion(key2.getKey()), -1, 0);
        Assert.assertEquals(0, versionSharedPreferences.getLength(), 0);
    }

    @Test
    public void savesVariation() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12, -1, 16, null, null);
        final UserFlagResponse key2 = new UserFlagResponse("key2", new JsonPrimitive(true), 14, -1, 23, null, null);
        final UserFlagResponse key3 = new UserFlagResponse("key3", new JsonPrimitive(true), 16, -1);

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Arrays.<FlagResponse>asList(key1, key2, key3));

        Assert.assertEquals(versionSharedPreferences.getStoredVariation(key1.getKey()), 16, 0);
        Assert.assertEquals(versionSharedPreferences.getStoredVariation(key2.getKey()), 23, 0);
        Assert.assertEquals(versionSharedPreferences.getStoredVariation(key3.getKey()), -1, 0);
    }

    @Test
    public void savesTrackEvents() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12, -1, 16, false, 123456789L);
        final UserFlagResponse key2 = new UserFlagResponse("key2", new JsonPrimitive(true), 14, -1, 23, true, 987654321L);
        final UserFlagResponse key3 = new UserFlagResponse("key3", new JsonPrimitive(true), 16, -1);

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Arrays.<FlagResponse>asList(key1, key2, key3));

        Assert.assertEquals(versionSharedPreferences.getStoredTrackEvents(key1.getKey()), false);
        Assert.assertEquals(versionSharedPreferences.getStoredTrackEvents(key2.getKey()), true);
        Assert.assertFalse(versionSharedPreferences.getStoredTrackEvents(key3.getKey()));
    }

    @Test
    public void savesDebugEventsUntilDate() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12, -1, 16, false, 123456789L);
        final UserFlagResponse key2 = new UserFlagResponse("key2", new JsonPrimitive(true), 14, -1, 23, true, 987654321L);
        final UserFlagResponse key3 = new UserFlagResponse("key3", new JsonPrimitive(true), 16, -1);

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Arrays.<FlagResponse>asList(key1, key2, key3));

        //noinspection ConstantConditions
        Assert.assertEquals(versionSharedPreferences.getStoredDebugEventsUntilDate(key1.getKey()), 123456789L, 0);
        //noinspection ConstantConditions
        Assert.assertEquals(versionSharedPreferences.getStoredDebugEventsUntilDate(key2.getKey()), 987654321L, 0);
        Assert.assertNull(versionSharedPreferences.getStoredDebugEventsUntilDate(key3.getKey()));
    }


    @Test
    public void savesFlagVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), -1, 12);
        final UserFlagResponse key2 = new UserFlagResponse("key2", new JsonPrimitive(true));

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Arrays.<FlagResponse>asList(key1, key2));

        Assert.assertEquals(versionSharedPreferences.getStoredFlagVersion(key1.getKey()), 12, 0);
        Assert.assertEquals(versionSharedPreferences.getStoredFlagVersion(key2.getKey()), -1, 0);
    }

    @Test
    public void deletesFlagVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), -1, 12);

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Collections.<FlagResponse>singletonList(key1));
        versionSharedPreferences.deleteStoredFlagResponse(key1);

        Assert.assertEquals(versionSharedPreferences.getStoredFlagVersion(key1.getKey()), -1, 0);
    }

    @Test
    public void updatesFlagVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), -1, 12);
        UserFlagResponse updatedKey1 = new UserFlagResponse(key1.getKey(), key1.getValue(), -1, 15);

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Collections.<FlagResponse>singletonList(key1));

        versionSharedPreferences.updateStoredFlagResponse(updatedKey1);

        Assert.assertEquals(versionSharedPreferences.getStoredFlagVersion(key1.getKey()), 15, 0);
    }

    @Test
    public void versionForEventsReturnsFlagVersionIfPresentOtherwiseReturnsVersion() {
        final UserFlagResponse withFlagVersion = new UserFlagResponse("withFlagVersion", new JsonPrimitive(true), 12, 13);
        final UserFlagResponse withOnlyVersion = new UserFlagResponse("withOnlyVersion", new JsonPrimitive(true), 12, -1);

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Arrays.<FlagResponse>asList(withFlagVersion, withOnlyVersion));

        Assert.assertEquals(versionSharedPreferences.getVersionForEvents(withFlagVersion.getKey()), 13, 0);
        Assert.assertEquals(versionSharedPreferences.getVersionForEvents(withOnlyVersion.getKey()), 12, 0);
    }

}
