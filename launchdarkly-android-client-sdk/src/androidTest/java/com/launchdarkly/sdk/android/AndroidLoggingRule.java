package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogger;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import timber.log.Timber;

/**
 * Adding this rule to a test provides an {@link LDLogger} that writes to the device logs, tagged
 * with the name of the current test (although the name may be truncated due to platform limits on
 * log tag length).
 */
public class AndroidLoggingRule extends TestWatcher {
    // This length limit exists in Android API 25 and above.
    private static final int ANDROID_MAX_LOG_TAG_LENGTH = 23;

    public LDLogAdapter logAdapter;
    public String loggerName;
    public LDLogger logger;

    public AndroidLoggingRule() {}

    protected void starting(Description description) {
        logAdapter = LDAndroidLogging.adapter();
        loggerName = description.getMethodName();
        if (loggerName.length() > ANDROID_MAX_LOG_TAG_LENGTH) {
            loggerName = loggerName.substring(0, ANDROID_MAX_LOG_TAG_LENGTH);
        }
        logger = LDLogger.withAdapter(logAdapter, loggerName);
    }
}
