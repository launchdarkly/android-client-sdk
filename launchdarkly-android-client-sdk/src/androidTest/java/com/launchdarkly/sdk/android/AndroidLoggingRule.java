package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogger;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import timber.log.Timber;

/**
 * Adding this rule to a test provides an {@link LDLogger} that writes to the device logs, tagged
 * with the name of the current test.
 */
public class AndroidLoggingRule extends TestWatcher {
    public LDLogAdapter logAdapter;
    public String loggerName;
    public LDLogger logger;

    public AndroidLoggingRule() {}

    protected void starting(Description description) {
        logAdapter = LDAndroidLogging.adapter();
        loggerName = description.getMethodName();
        logger = LDLogger.withAdapter(logAdapter, loggerName);
    }
}
