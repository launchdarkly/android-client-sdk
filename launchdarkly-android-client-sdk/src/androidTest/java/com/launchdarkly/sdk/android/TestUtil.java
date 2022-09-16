package com.launchdarkly.sdk.android;

import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
        PersistentDataStoreWrapper.PerEnvironmentData environmentStore =
                new PersistentDataStoreWrapper(store, LDLogger.none()).perEnvironmentData(mobileKey);
        EnvironmentData data = environmentStore.getContextData(ContextDataManager.hashedContextId(context));
        EnvironmentData newData = (data == null ? new EnvironmentData() : data).withFlagUpdatedOrAdded(flag);
        environmentStore.setContextData(ContextDataManager.hashedContextId(context), newData);
    }

    public static void doSynchronouslyOnNewThread(Runnable action) {
        // This is a workaround for Android's prohibition on doing certain network operations on
        // the main thread-- even in tests. There is *supposed* to be a way around this with
        // `StrictMode#setThreadPolicy` but we've had trouble using it in emulators.
        try {
            AtomicReference<RuntimeException> thrown = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    thrown.set(e);
                }
            });
            t.start();
            t.join();
            if (thrown.get() != null) {
                throw thrown.get();
            }
        } catch (InterruptedException err) {
            fail("failed to run thread");
        }
    }

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
