package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.json.JsonSerialization;

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
    public AndroidLoggingRule logging = new AndroidLoggingRule();

    private Application getApplication() {
        return ApplicationProvider.getApplicationContext();
    }

    private LDConfig makeConfig() {
        return new LDConfig.Builder().mobileKey("fake_mob_key")
                .logAdapter(logging.logAdapter)
                .loggerName(logging.loggerName)
                .build();
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
        // perform migration from fresh env
        Migration.migrateWhenNeeded(getApplication(), makeConfig());
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
        // perform migration from fresh env
        Migration.migrateWhenNeeded(getApplication(), makeConfig());
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
        Migration.migrateWhenNeeded(getApplication(), makeConfig());
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
        getApplication().getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + oldSharedPrefsKeyForUser(user1), Context.MODE_PRIVATE).edit().commit();
        getApplication().getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + oldSharedPrefsKeyForUser(user2), Context.MODE_PRIVATE).edit().commit();
        ArrayList<String> userKeys = getUserKeysPre_2_6(getApplication(), makeConfig());
        assertTrue(userKeys.contains(oldSharedPrefsKeyForUser(user1)));
        assertTrue(userKeys.contains(oldSharedPrefsKeyForUser(user2)));
        assertEquals(2, userKeys.size());
    }

    @Test
    public void getsCorrectUserKeys_2_6() {
        LDUser user1 = new LDUser.Builder("user1").build();
        LDUser user2 = new LDUser.Builder("user2").build();
        // Create shared prefs files
        getApplication().getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + FAKE_MOB_KEY + oldSharedPrefsKeyForUser(user1) + "-user", Context.MODE_PRIVATE).edit().commit();
        getApplication().getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + FAKE_MOB_KEY + oldSharedPrefsKeyForUser(user2) + "-user", Context.MODE_PRIVATE).edit().commit();
        Map<String, Set<String>> userKeys = getUserKeys_2_6(getApplication());
        assertTrue(userKeys.containsKey(FAKE_MOB_KEY));
        assertTrue(userKeys.get(FAKE_MOB_KEY).contains(oldSharedPrefsKeyForUser(user1)));
        assertTrue(userKeys.get(FAKE_MOB_KEY).contains(oldSharedPrefsKeyForUser(user2)));
        assertEquals(2, userKeys.get(FAKE_MOB_KEY).size());
        assertEquals(1, userKeys.keySet().size());
    }

    private static String oldSharedPrefsKeyForUser(LDUser user) {
        return ContextDataManager.HASHER.hash(JsonSerialization.serialize(user));
    }
}
