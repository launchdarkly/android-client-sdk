package com.launchdarkly.sdk.android;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotEquals;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LDClientLoggingTest {

    private static final String mobileKey = "test-mobile-key";
    private Application application;
    private LDContext ldContext;

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
        ldContext = LDContext.create("key");
    }

    @Test
    public void customLogAdapterWithDefaultLevel() throws Exception {
        LogCapture logCapture = Logs.capture();
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled).mobileKey(mobileKey).offline(true)
                .logAdapter(logCapture).build();
        try (LDClient ldClient = LDClient.init(application, config, ldContext, 1)) {
            for (LogCapture.Message m: logCapture.getMessages()) {
                assertNotEquals(LDLogLevel.DEBUG, m.getLevel());
            }
            LogCapture.Message m1 = logCapture.requireMessage(LDLogLevel.INFO, 2000);
            assertThat(m1.getText(), containsString("Initializing Client"));
        }
    }

    @Test
    public void customLogAdapterWithDebugLevel() throws Exception {
        LogCapture logCapture = Logs.capture();
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled).mobileKey(mobileKey).offline(true)
                .logAdapter(logCapture).logLevel(LDLogLevel.DEBUG).build();
        try (LDClient ldClient = LDClient.init(application, config, ldContext, 1)) {
            logCapture.requireMessage(LDLogLevel.DEBUG, 2000);
        }
    }
}
