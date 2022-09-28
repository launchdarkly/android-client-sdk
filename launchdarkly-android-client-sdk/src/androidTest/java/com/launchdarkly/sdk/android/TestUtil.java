package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

public class TestUtil {
    public static PersistentDataStoreWrapper makeSimplePersistentDataStoreWrapper() {
        return new PersistentDataStoreWrapper(
                new InMemoryPersistentDataStore(),
                LDLogger.none()
        );
    }

    public static void writeFlagUpdateToStore(
            PersistentDataStore store,
            String mobileKey,
            LDContext context,
            Flag flag
    ) {
        new FlagStoreImpl(
                new PersistentDataStoreWrapper(store, LDLogger.none()).perEnvironmentData(mobileKey),
                DefaultContextManager.storageKey(context),
                LDLogger.none()
        ).applyFlagUpdate(flag);
    }
    public static void markMigrationComplete(Application application) {
        SharedPreferences migrations = application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "migrations", Context.MODE_PRIVATE);
        migrations.edit().putString("v2.7.0", "v2.7.0").apply();
    }
}
