package com.launchdarkly.sdk.android;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import timber.log.Timber;

public class TimberLoggingRule extends TestWatcher {
    @Override
    protected void starting(Description description) {
        Timber.plant(new Timber.DebugTree());
    }

    @Override
    protected void finished(Description description) {
        Timber.uprootAll();
    }
}
