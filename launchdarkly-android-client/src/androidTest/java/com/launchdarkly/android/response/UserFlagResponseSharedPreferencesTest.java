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

        Assert.assertEquals(versionSharedPreferences.getStoredFlagResponse(key1.getKey()).getVersion(), 12, 0);
        Assert.assertEquals(versionSharedPreferences.getStoredFlagResponse(key2.getKey()).getVersion(), -1, 0);
    }

    @Test
    public void deletesVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12, -1);

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Collections.<FlagResponse>singletonList(key1));
        versionSharedPreferences.deleteStoredFlagResponse(key1);

        Assert.assertNull(versionSharedPreferences.getStoredFlagResponse(key1.getKey()));
    }

    @Test
    public void updatesVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12, -1);
        UserFlagResponse updatedKey1 = new UserFlagResponse(key1.getKey(), key1.getValue(), 15, -1);

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Collections.<FlagResponse>singletonList(key1));

        versionSharedPreferences.updateStoredFlagResponse(updatedKey1);

        Assert.assertEquals(versionSharedPreferences.getStoredFlagResponse(key1.getKey()).getVersion(), 15, 0);
    }

    @Test
    public void clearsFlags() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12, -1);
        final UserFlagResponse key2 = new UserFlagResponse("key2", new JsonPrimitive(true), 14, -1);

        UserFlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Arrays.<FlagResponse>asList(key1, key2));
        versionSharedPreferences.clear();

        Assert.assertNull(versionSharedPreferences.getStoredFlagResponse(key1.getKey()));
        Assert.assertNull(versionSharedPreferences.getStoredFlagResponse(key2.getKey()));
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

        Assert.assertEquals(16, versionSharedPreferences.getStoredFlagResponse(key1.getKey()).getVariation(), 0);
        Assert.assertEquals(23, versionSharedPreferences.getStoredFlagResponse(key2.getKey()).getVariation(),0);
        Assert.assertNull(versionSharedPreferences.getStoredFlagResponse(key3.getKey()).getVariation());
    }

    @Test
    public void savesTrackEvents() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), 12, -1, 16, false, 123456789L);
        final UserFlagResponse key2 = new UserFlagResponse("key2", new JsonPrimitive(true), 14, -1, 23, true, 987654321L);
        final UserFlagResponse key3 = new UserFlagResponse("key3", new JsonPrimitive(true), 16, -1);

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Arrays.<FlagResponse>asList(key1, key2, key3));

        Assert.assertEquals(versionSharedPreferences.getStoredFlagResponse(key1.getKey()).isTrackEvents(), false);
        Assert.assertEquals(versionSharedPreferences.getStoredFlagResponse(key2.getKey()).isTrackEvents(), true);
        Assert.assertFalse(versionSharedPreferences.getStoredFlagResponse(key3.getKey()).isTrackEvents());
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
        Assert.assertEquals(versionSharedPreferences.getStoredFlagResponse(key1.getKey()).getDebugEventsUntilDate(), 123456789L, 0);
        //noinspection ConstantConditions
        Assert.assertEquals(versionSharedPreferences.getStoredFlagResponse(key2.getKey()).getDebugEventsUntilDate(), 987654321L, 0);
        Assert.assertNull(versionSharedPreferences.getStoredFlagResponse(key3.getKey()).getDebugEventsUntilDate());
    }


    @Test
    public void savesFlagVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), -1, 12);
        final UserFlagResponse key2 = new UserFlagResponse("key2", new JsonPrimitive(true));

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Arrays.<FlagResponse>asList(key1, key2));

        Assert.assertEquals(versionSharedPreferences.getStoredFlagResponse(key1.getKey()).getFlagVersion(), 12, 0);
        Assert.assertEquals(versionSharedPreferences.getStoredFlagResponse(key2.getKey()).getFlagVersion(), -1, 0);
    }

    @Test
    public void deletesFlagVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), -1, 12);

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Collections.<FlagResponse>singletonList(key1));
        versionSharedPreferences.deleteStoredFlagResponse(key1);

        Assert.assertNull(versionSharedPreferences.getStoredFlagResponse(key1.getKey()));
    }

    @Test
    public void updatesFlagVersions() {
        final UserFlagResponse key1 = new UserFlagResponse("key1", new JsonPrimitive(true), -1, 12);
        UserFlagResponse updatedKey1 = new UserFlagResponse(key1.getKey(), key1.getValue(), -1, 15);

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Collections.<FlagResponse>singletonList(key1));

        versionSharedPreferences.updateStoredFlagResponse(updatedKey1);

        Assert.assertEquals(versionSharedPreferences.getStoredFlagResponse(key1.getKey()).getFlagVersion(), 15, 0);
    }

    @Test
    public void versionForEventsReturnsFlagVersionIfPresentOtherwiseReturnsVersion() {
        final UserFlagResponse withFlagVersion = new UserFlagResponse("withFlagVersion", new JsonPrimitive(true), 12, 13);
        final UserFlagResponse withOnlyVersion = new UserFlagResponse("withOnlyVersion", new JsonPrimitive(true), 12, -1);

        FlagResponseSharedPreferences versionSharedPreferences
                = new UserFlagResponseSharedPreferences(activityTestRule.getActivity().getApplication(), "abc");
        versionSharedPreferences.saveAll(Arrays.<FlagResponse>asList(withFlagVersion, withOnlyVersion));

        Assert.assertEquals(versionSharedPreferences.getStoredFlagResponse(withFlagVersion.getKey()).getVersionForEvents(), 13, 0);
        Assert.assertEquals(versionSharedPreferences.getStoredFlagResponse(withOnlyVersion.getKey()).getVersionForEvents(), 12, 0);
    }

}
