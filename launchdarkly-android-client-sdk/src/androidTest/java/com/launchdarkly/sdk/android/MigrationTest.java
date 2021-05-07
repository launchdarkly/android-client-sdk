package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.launchdarkly.sdk.LDUser;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import static com.launchdarkly.sdk.android.Migration.getUserKeysPre_2_6;
import static com.launchdarkly.sdk.android.Migration.getUserKeys_2_6;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MigrationTest {

    private static final String FAKE_MOB_KEY = "mob-fakemob6-key9-fake-mob0-keyfakemob22";

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    private Application getApplication() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        File directory = new File(getApplication().getFilesDir().getParent() + "/shared_prefs/");
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
        LDConfig ldConfig = new LDConfig.Builder().mobileKey("fake_mob_key").build();
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
        LDConfig ldConfig = new LDConfig.Builder().mobileKey("fake_mob_key").build();
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
        LDConfig ldConfig = new LDConfig.Builder().mobileKey("fake_mob_key").build();
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
        getApplication().getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + DefaultUserManager.sharedPrefs(user1), Context.MODE_PRIVATE).edit().commit();
        getApplication().getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + DefaultUserManager.sharedPrefs(user2), Context.MODE_PRIVATE).edit().commit();
        LDConfig ldConfig = new LDConfig.Builder().mobileKey(FAKE_MOB_KEY).build();
        ArrayList<String> userKeys = getUserKeysPre_2_6(getApplication(), ldConfig);
        assertTrue(userKeys.contains(DefaultUserManager.sharedPrefs(user1)));
        assertTrue(userKeys.contains(DefaultUserManager.sharedPrefs(user2)));
        assertEquals(2, userKeys.size());
    }

    @Test
    public void getsCorrectUserKeys_2_6() {
        LDUser user1 = new LDUser.Builder("user1").build();
        LDUser user2 = new LDUser.Builder("user2").build();
        // Create shared prefs files
        getApplication().getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + FAKE_MOB_KEY + DefaultUserManager.sharedPrefs(user1) + "-user", Context.MODE_PRIVATE).edit().commit();
        getApplication().getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + FAKE_MOB_KEY + DefaultUserManager.sharedPrefs(user2) + "-user", Context.MODE_PRIVATE).edit().commit();
        Map<String, Set<String>> userKeys = getUserKeys_2_6(getApplication());
        assertTrue(userKeys.containsKey(FAKE_MOB_KEY));
        assertTrue(userKeys.get(FAKE_MOB_KEY).contains(DefaultUserManager.sharedPrefs(user1)));
        assertTrue(userKeys.get(FAKE_MOB_KEY).contains(DefaultUserManager.sharedPrefs(user2)));
        assertEquals(2, userKeys.get(FAKE_MOB_KEY).size());
        assertEquals(1, userKeys.keySet().size());
    }
}
