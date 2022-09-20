package com.launchdarkly.sdk.android;

import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.rules.ActivityScenarioRule;

import java.util.function.Consumer;

public class AndroidTestUtil {
    public static void doSynchronouslyOnMainThreadForTestScenario(
            ActivityScenarioRule<TestActivity> testScenario,
            Consumer<TestActivity> action
    ) {
        testScenario.getScenario().onActivity(act -> {
            TestUtil.doSynchronouslyOnNewThread(() -> {
                action.accept(act);
            });
        });
    }

    public static void markMigrationComplete(Application application) {
        SharedPreferences migrations = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "migrations", Context.MODE_PRIVATE);
        migrations.edit().putString("v2.7.0", "v2.7.0").apply();
    }
}
