package com.launchdarkly.sdk.android;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.anything;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import timber.log.Timber;

public class LogCaptureRule extends TestWatcher {
    public LDLogger logger;
    public LogCapture logCapture;

    public LogCaptureRule() {
        logCapture = Logs.capture();
        logger = LDLogger.withAdapter(logCapture, "");
    }

    @Override
    protected void failed(Throwable e, Description description) {
        for (String s: logCapture.getMessageStrings()) {
            System.out.println("LOG >>> " + s);
        }
    }

    public void assertNothingLogged() {
        assertThat(logCapture.getMessages(), not(hasItems(anything())));
    }

    public void assertInfoLogged(String messageSubstring) {
        assertThat(logCapture.getMessageStrings(),
                hasItems(allOf(containsString("INFO:")),
                        containsString(messageSubstring)));
    }

    public void assertErrorLogged(String messageSubstring) {
        assertThat(logCapture.getMessageStrings(),
                hasItems(allOf(containsString("ERROR:")),
                        containsString(messageSubstring)));
    }
}
