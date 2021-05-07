package com.launchdarkly.sdk.android;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class SharedPrefsFlagStoreTest extends FlagStoreTest {

    private Application testApplication;

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    @Before
    public void setUp() {
        this.testApplication = ApplicationProvider.getApplicationContext();
    }

    public FlagStore createFlagStore(String identifier) {
        return new SharedPrefsFlagStore(testApplication, identifier);
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
}