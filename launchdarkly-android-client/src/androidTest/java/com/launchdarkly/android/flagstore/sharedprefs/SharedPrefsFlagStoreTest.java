package com.launchdarkly.android.flagstore.sharedprefs;

import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.google.gson.JsonPrimitive;
import com.launchdarkly.android.EvaluationReason;
import com.launchdarkly.android.flagstore.Flag;
import com.launchdarkly.android.flagstore.FlagUpdate;
import com.launchdarkly.android.response.DeleteFlagResponse;
import com.launchdarkly.android.test.TestActivity;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

@RunWith(AndroidJUnit4.class)
public class SharedPrefsFlagStoreTest {

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    @Test
    public void savesVersions() {
        final Flag key1 = new Flag("key1", new JsonPrimitive(true), 12, null, null, null, null, null);
        final Flag key2 = new Flag("key2", new JsonPrimitive(true), null, null, null, null, null, null);

        SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(activityTestRule.getActivity().getApplication(), "abc");
        flagStore.clear();
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(key1, key2));

        Assert.assertEquals(flagStore.getFlag(key1.getKey()).getVersion(), 12, 0);
        Assert.assertEquals(flagStore.getFlag(key2.getKey()).getVersion(), null);
    }

    @Test
    public void deletesVersions() {
        final Flag key1 = new Flag("key1", new JsonPrimitive(true), 12, null, null, null, null, null);

        SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(activityTestRule.getActivity().getApplication(), "abc");
        flagStore.clear();
        flagStore.applyFlagUpdates(Collections.<FlagUpdate>singletonList(key1));
        flagStore.applyFlagUpdate(new DeleteFlagResponse(key1.getKey(), null));

        Assert.assertNull(flagStore.getFlag(key1.getKey()));
    }

    @Test
    public void updatesVersions() {
        final Flag key1 = new Flag("key1", new JsonPrimitive(true), 12, null, null, null, null, null);
        final Flag updatedKey1 = new Flag(key1.getKey(), key1.getValue(), 15, null, null, null, null, null);

        SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(activityTestRule.getActivity().getApplication(), "abc");
        flagStore.clear();
        flagStore.applyFlagUpdates(Collections.<FlagUpdate>singletonList(key1));

        flagStore.applyFlagUpdate(updatedKey1);

        Assert.assertEquals(flagStore.getFlag(key1.getKey()).getVersion(), 15, 0);
    }

    @Test
    public void clearsFlags() {
        final Flag key1 = new Flag("key1", new JsonPrimitive(true), 12, null, null, null, null, null);
        final Flag key2 = new Flag("key2", new JsonPrimitive(true), 14, null, null, null, null, null);

        SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(activityTestRule.getActivity().getApplication(), "abc");
        flagStore.clear();
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(key1, key2));
        flagStore.clear();

        Assert.assertNull(flagStore.getFlag(key1.getKey()));
        Assert.assertNull(flagStore.getFlag(key2.getKey()));
        Assert.assertEquals(0, flagStore.getAllFlags().size(), 0);
    }

    @Test
    public void savesVariation() {
        final Flag key1 = new Flag("key1", new JsonPrimitive(true), 12, null, 16, null, null, null);
        final Flag key2 = new Flag("key2", new JsonPrimitive(true), 14, null, 23, null, null, null);
        final Flag key3 = new Flag("key3", new JsonPrimitive(true), 16, null, null, null, null, null);

        SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(activityTestRule.getActivity().getApplication(), "abc");
        flagStore.clear();
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(key1, key2, key3));

        Assert.assertEquals(flagStore.getFlag(key1.getKey()).getVariation(), 16, 0);
        Assert.assertEquals(flagStore.getFlag(key2.getKey()).getVariation(), 23, 0);
        Assert.assertEquals(flagStore.getFlag(key3.getKey()).getVariation(), null);
    }

    @Test
    public void savesTrackEvents() {
        final Flag key1 = new Flag("key1", new JsonPrimitive(true), 12, null, 16, false, 123456789L, null);
        final Flag key2 = new Flag("key2", new JsonPrimitive(true), 14, null, 23, true, 987654321L, null);
        final Flag key3 = new Flag("key3", new JsonPrimitive(true), 16, null, null, null, null, null);

        SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(activityTestRule.getActivity().getApplication(), "abc");
        flagStore.clear();
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(key1, key2, key3));

        Assert.assertEquals(flagStore.getFlag(key1.getKey()).getTrackEvents(), false);
        Assert.assertEquals(flagStore.getFlag(key2.getKey()).getTrackEvents(), true);
        Assert.assertFalse(flagStore.getFlag(key3.getKey()).getTrackEvents());
    }

    @Test
    public void savesDebugEventsUntilDate() {
        final Flag key1 = new Flag("key1", new JsonPrimitive(true), 12, null, 16, false, 123456789L, null);
        final Flag key2 = new Flag("key2", new JsonPrimitive(true), 14, null, 23, true, 987654321L, null);
        final Flag key3 = new Flag("key3", new JsonPrimitive(true), 16, null, null, null, null, null);

        SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(activityTestRule.getActivity().getApplication(), "abc");
        flagStore.clear();
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(key1, key2, key3));

        //noinspection ConstantConditions
        Assert.assertEquals(flagStore.getFlag(key1.getKey()).getDebugEventsUntilDate(), 123456789L, 0);
        //noinspection ConstantConditions
        Assert.assertEquals(flagStore.getFlag(key2.getKey()).getDebugEventsUntilDate(), 987654321L, 0);
        Assert.assertNull(flagStore.getFlag(key3.getKey()).getDebugEventsUntilDate());
    }


    @Test
    public void savesFlagVersions() {
        final Flag key1 = new Flag("key1", new JsonPrimitive(true), null, 12, null, null, null, null);
        final Flag key2 = new Flag("key2", new JsonPrimitive(true), null, null, null, null, null, null);

        SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(activityTestRule.getActivity().getApplication(), "abc");
        flagStore.clear();
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(key1, key2));

        Assert.assertEquals(flagStore.getFlag(key1.getKey()).getFlagVersion(), 12, 0);
        Assert.assertEquals(flagStore.getFlag(key2.getKey()).getFlagVersion(), null);
    }

    @Test
    public void deletesFlagVersions() {
        final Flag key1 = new Flag("key1", new JsonPrimitive(true), null, 12, null, null, null, null);

        SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(activityTestRule.getActivity().getApplication(), "abc");
        flagStore.clear();
        flagStore.applyFlagUpdates(Collections.<FlagUpdate>singletonList(key1));
        flagStore.applyFlagUpdate(new DeleteFlagResponse(key1.getKey(), null));

        Assert.assertNull(flagStore.getFlag(key1.getKey()));
    }

    @Test
    public void updatesFlagVersions() {
        final Flag key1 = new Flag("key1", new JsonPrimitive(true), null, 12, null, null, null, null);
        final Flag updatedKey1 = new Flag(key1.getKey(), key1.getValue(), null, 15, null, null, null, null);

        SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(activityTestRule.getActivity().getApplication(), "abc");
        flagStore.clear();
        flagStore.applyFlagUpdates(Collections.<FlagUpdate>singletonList(key1));

        flagStore.applyFlagUpdate(updatedKey1);

        Assert.assertEquals(flagStore.getFlag(key1.getKey()).getFlagVersion(), 15, 0);
    }

    @Test
    public void versionForEventsReturnsFlagVersionIfPresentOtherwiseReturnsVersion() {
        final Flag withFlagVersion = new Flag("withFlagVersion", new JsonPrimitive(true), 12, 13, null, null, null, null);
        final Flag withOnlyVersion = new Flag("withOnlyVersion", new JsonPrimitive(true), 12, null, null, null, null, null);

        SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(activityTestRule.getActivity().getApplication(), "abc");
        flagStore.clear();
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(withFlagVersion, withOnlyVersion));

        Assert.assertEquals(flagStore.getFlag(withFlagVersion.getKey()).getVersionForEvents(), 13, 0);
        Assert.assertEquals(flagStore.getFlag(withOnlyVersion.getKey()).getVersionForEvents(), 12, 0);
    }

    @Test
    public void savesReasons() {
        // This test assumes that if the store correctly serializes and deserializes one kind of EvaluationReason, it can handle any kind,
        // since the actual marshaling is being done by UserFlagResponse. Therefore, the other variants of EvaluationReason are tested by
        // FlagTest.
        final EvaluationReason reason = EvaluationReason.ruleMatch(1, "id");
        final Flag flag1 = new Flag("key1", new JsonPrimitive(true), 11,
                1, 1, null, null, reason);
        final Flag flag2 = new Flag("key2", new JsonPrimitive(true), 11,
                1, 1, null, null, null);

        SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(activityTestRule.getActivity().getApplication(), "abc");
        flagStore.clear();
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(flag1, flag2));

        Assert.assertEquals(reason, flagStore.getFlag(flag1.getKey()).getReason());
        Assert.assertNull(flagStore.getFlag(flag2.getKey()).getReason());
    }
}
