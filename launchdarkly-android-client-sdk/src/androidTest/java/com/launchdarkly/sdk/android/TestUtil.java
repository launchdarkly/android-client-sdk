package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

public class TestUtil {
    public static void markMigrationComplete(Application application) {
        SharedPreferences migrations = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "migrations", Context.MODE_PRIVATE);
        migrations.edit().putString("v2.7.0", "v2.7.0").apply();
    }
}
