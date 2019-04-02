package com.launchdarkly.android.flagstore.sharedprefs;

import android.app.Application;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.launchdarkly.android.EvaluationReason;
import com.launchdarkly.android.flagstore.Flag;
import com.launchdarkly.android.flagstore.FlagBuilder;
import com.launchdarkly.android.flagstore.FlagStore;
import com.launchdarkly.android.flagstore.FlagStoreTest;
import com.launchdarkly.android.flagstore.FlagUpdate;
import com.launchdarkly.android.response.DeleteFlagResponse;
import com.launchdarkly.android.test.TestActivity;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(AndroidJUnit4.class)
public class SharedPrefsFlagStoreTest extends FlagStoreTest {

    private Application testApplication;

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    @Before
    public void setUp() {
        this.testApplication = activityTestRule.getActivity().getApplication();
    }

    public FlagStore createFlagStore(String identifier) {
        return new SharedPrefsFlagStore(testApplication, identifier);
    }

    @Test
    public void savesVersions() {
        final Flag key1 = new FlagBuilder("key1").version(12).build();
        final Flag key2 = new FlagBuilder("key2").version(null).build();

        final SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(testApplication, "abc");
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(key1, key2));

        assertEquals(flagStore.getFlag(key1.getKey()).getVersion(), 12, 0);
        assertNull(flagStore.getFlag(key2.getKey()).getVersion());
    }

    @Test
    public void deletesVersions() {
        final Flag key1 = new FlagBuilder("key1").version(12).build();

        final SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(testApplication, "abc");
        flagStore.applyFlagUpdates(Collections.<FlagUpdate>singletonList(key1));
        flagStore.applyFlagUpdate(new DeleteFlagResponse(key1.getKey(), null));

        Assert.assertNull(flagStore.getFlag(key1.getKey()));
    }

    @Test
    public void updatesVersions() {
        final Flag key1 = new FlagBuilder("key1").version(12).build();
        final Flag updatedKey1 = new FlagBuilder(key1.getKey()).version(15).build();

        final SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(testApplication, "abc");
        flagStore.applyFlagUpdates(Collections.<FlagUpdate>singletonList(key1));

        flagStore.applyFlagUpdate(updatedKey1);

        assertEquals(flagStore.getFlag(key1.getKey()).getVersion(), 15, 0);
    }

    @Test
    public void clearsFlags() {
        final Flag key1 = new FlagBuilder("key1").version(12).build();
        final Flag key2 = new FlagBuilder("key2").version(14).build();

        final SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(testApplication, "abc");
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(key1, key2));
        flagStore.clear();

        Assert.assertNull(flagStore.getFlag(key1.getKey()));
        Assert.assertNull(flagStore.getFlag(key2.getKey()));
        assertEquals(0, flagStore.getAllFlags().size(), 0);
    }

    @Test
    public void savesVariation() {
        final Flag key1 = new FlagBuilder("key1").variation(16).build();
        final Flag key2 = new FlagBuilder("key2").variation(null).build();

        final SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(testApplication, "abc");
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(key1, key2));

        assertEquals(flagStore.getFlag(key1.getKey()).getVariation(), 16, 0);
        assertNull(flagStore.getFlag(key2.getKey()).getVariation());
    }

    @Test
    public void savesTrackEvents() {
        final Flag key1 = new FlagBuilder("key1").trackEvents(false).build();
        final Flag key2 = new FlagBuilder("key2").trackEvents(true).build();
        final Flag key3 = new FlagBuilder("key3").trackEvents(null).build();

        final SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(testApplication, "abc");
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(key1, key2, key3));

        assertEquals(flagStore.getFlag(key1.getKey()).getTrackEvents(), false);
        assertEquals(flagStore.getFlag(key2.getKey()).getTrackEvents(), true);
        Assert.assertFalse(flagStore.getFlag(key3.getKey()).getTrackEvents());
    }

    @Test
    public void savesDebugEventsUntilDate() {
        final Flag key1 = new FlagBuilder("key1").debugEventsUntilDate(123456789L).build();
        final Flag key2 = new FlagBuilder("key2").debugEventsUntilDate(2500000000L).build();
        final Flag key3 = new FlagBuilder("key3").debugEventsUntilDate(null).build();

        final SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(testApplication, "abc");
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(key1, key2, key3));

        assertEquals(flagStore.getFlag(key1.getKey()).getDebugEventsUntilDate(), 123456789L, 0);
        assertEquals(flagStore.getFlag(key2.getKey()).getDebugEventsUntilDate(), 2500000000L, 0);
        Assert.assertNull(flagStore.getFlag(key3.getKey()).getDebugEventsUntilDate());
    }


    @Test
    public void savesFlagVersions() {
        final Flag key1 = new FlagBuilder("key1").flagVersion(12).build();
        final Flag key2 = new FlagBuilder("key2").flagVersion(null).build();

        final SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(testApplication, "abc");
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(key1, key2));

        assertEquals(flagStore.getFlag(key1.getKey()).getFlagVersion(), 12, 0);
        assertNull(flagStore.getFlag(key2.getKey()).getFlagVersion());
    }

    @Test
    public void deletesFlagVersions() {
        final Flag key1 = new FlagBuilder("key1").flagVersion(12).build();

        final SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(testApplication, "abc");
        flagStore.applyFlagUpdates(Collections.<FlagUpdate>singletonList(key1));
        flagStore.applyFlagUpdate(new DeleteFlagResponse(key1.getKey(), null));

        Assert.assertNull(flagStore.getFlag(key1.getKey()));
    }

    @Test
    public void updatesFlagVersions() {
        final Flag key1 = new FlagBuilder("key1").flagVersion(12).build();
        final Flag updatedKey1 = new FlagBuilder(key1.getKey()).flagVersion(15).build();

        final SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(testApplication, "abc");
        flagStore.applyFlagUpdates(Collections.<FlagUpdate>singletonList(key1));

        flagStore.applyFlagUpdate(updatedKey1);

        assertEquals(flagStore.getFlag(key1.getKey()).getFlagVersion(), 15, 0);
    }

    @Test
    public void versionForEventsReturnsFlagVersionIfPresentOtherwiseReturnsVersion() {
        final Flag withFlagVersion =
                new FlagBuilder("withFlagVersion").version(12).flagVersion(13).build();
        final Flag withOnlyVersion = new FlagBuilder("withOnlyVersion").version(12).build();

        final SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(testApplication, "abc");
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(withFlagVersion, withOnlyVersion));

        assertEquals(flagStore.getFlag(withFlagVersion.getKey()).getVersionForEvents(), 13, 0);
        assertEquals(flagStore.getFlag(withOnlyVersion.getKey()).getVersionForEvents(), 12, 0);
    }

    @Test
    public void savesReasons() {
        // This test assumes that if the store correctly serializes and deserializes one kind of
        // EvaluationReason, it can handle any kind,
        // since the actual marshaling is being done by UserFlagResponse. Therefore, the other
        // variants of EvaluationReason are tested by
        // FlagTest.
        final EvaluationReason reason = EvaluationReason.ruleMatch(1, "id");
        final Flag flag1 = new FlagBuilder("key1").reason(reason).build();
        final Flag flag2 = new FlagBuilder("key2").build();

        final SharedPrefsFlagStore flagStore = new SharedPrefsFlagStore(testApplication, "abc");
        flagStore.applyFlagUpdates(Arrays.<FlagUpdate>asList(flag1, flag2));

        assertEquals(reason, flagStore.getFlag(flag1.getKey()).getReason());
        assertNull(flagStore.getFlag(flag2.getKey()).getReason());
    }
}