package com.launchdarkly.sdk.android;

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
}
