package com.launchdarkly.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.support.test.rule.ActivityTestRule;

import static com.launchdarkly.android.Migration.getUserKeysPre_2_6;
import static com.launchdarkly.android.Migration.getUserKeys_2_6;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.Multimap;
import com.launchdarkly.android.test.TestActivity;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;

public class MigrationTest {

    private static final String FAKE_MOB_KEY = "mob-fakemob6-key9-fake-mob0-keyfakemob22";

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    private Application getApplication() {
        return activityTestRule.getActivity().getApplication();
    }

    @Before
    public void setUp() {
        File directory = new File(activityTestRule.getActivity().getApplication().getFilesDir().getParent() + "/shared_prefs/");
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private void set_version_2_6() {
        SharedPreferences migrations = getApplication().getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "migrations", Context.MODE_PRIVATE);
        migrations.edit()
                .putString("v2.6.0", "v2.6.0")
                .apply();
    }

    @Test
    public void setsCurrentVersionInMigrationsPrefs() {
        LDConfig ldConfig = new LDConfig.Builder().setMobileKey("fake_mob_key").build();
        // perform migration from fresh env
        Migration.migrateWhenNeeded(getApplication(), ldConfig);
        assertTrue(getApplication().getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "migrations", Context.MODE_PRIVATE).contains("v2.7.0"));
    }

    @Test
    public void maintainsExistingSharedPrefsFresh() {
        // Create existing shared prefs
        SharedPreferences existing = getApplication().getSharedPreferences("arbitrary", Context.MODE_PRIVATE);
        existing.edit()
                .putString("test", "string")
                .commit();
        //noinspection UnusedAssignment
        existing = null;
        LDConfig ldConfig = new LDConfig.Builder().setMobileKey("fake_mob_key").build();
        // perform migration from fresh env
        Migration.migrateWhenNeeded(getApplication(), ldConfig);
        setUp();
        // Check existing shared prefs still exist
        existing = getApplication().getSharedPreferences("arbitrary", Context.MODE_PRIVATE);
        assertEquals("string", existing.getString("test", null));
    }

    @Test
    public void maintainsExistingSharedPrefs_2_6() {
        set_version_2_6();
        maintainsExistingSharedPrefsFresh();
    }

    @Test
    public void migrationNoMobileKeysFresh() {
        LDConfig ldConfig = new LDConfig.Builder().setMobileKey("fake_mob_key").build();
        Migration.migrateWhenNeeded(getApplication(), ldConfig);
    }

    @Test
    public void migrationNoMobileKeys_2_6() {
        set_version_2_6();
        migrationNoMobileKeysFresh();
    }

    @Test
    public void getsCorrectUserKeysPre_2_6() {
        LDUser user1 = new LDUser.Builder("user1").build();
        LDUser user2 = new LDUser.Builder("user2").build();
        // Create shared prefs files
        getApplication().getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + user1.getSharedPrefsKey(), Context.MODE_PRIVATE).edit().commit();
        getApplication().getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + user2.getSharedPrefsKey(), Context.MODE_PRIVATE).edit().commit();
        LDConfig ldConfig = new LDConfig.Builder().setMobileKey(FAKE_MOB_KEY).build();
        ArrayList<String> userKeys = getUserKeysPre_2_6(getApplication(), ldConfig);
        assertTrue(userKeys.contains(user1.getSharedPrefsKey()));
        assertTrue(userKeys.contains(user2.getSharedPrefsKey()));
        assertEquals(2, userKeys.size());
    }

    @Test
    public void getsCorrectUserKeys_2_6() {
        LDUser user1 = new LDUser.Builder("user1").build();
        LDUser user2 = new LDUser.Builder("user2").build();
        // Create shared prefs files
        getApplication().getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + FAKE_MOB_KEY + user1.getSharedPrefsKey() + "-user", Context.MODE_PRIVATE).edit().commit();
        getApplication().getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + FAKE_MOB_KEY + user2.getSharedPrefsKey() + "-user", Context.MODE_PRIVATE).edit().commit();
        Multimap<String, String> userKeys = getUserKeys_2_6(getApplication());
        assertTrue(userKeys.containsKey(FAKE_MOB_KEY));
        assertTrue(userKeys.get(FAKE_MOB_KEY).contains(user1.getSharedPrefsKey()));
        assertTrue(userKeys.get(FAKE_MOB_KEY).contains(user2.getSharedPrefsKey()));
        assertEquals(2, userKeys.get(FAKE_MOB_KEY).size());
        assertEquals(1, userKeys.keySet().size());
    }
}
