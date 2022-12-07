package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.TestData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TestDataWithLDClientTest {
    private final TestData td = TestData.dataSource();
    private final LDConfig config;
    private final LDContext user = LDContext.create("userkey");

    private Application application;

    public TestDataWithLDClientTest() {
        config = new LDConfig.Builder().mobileKey("mobile-key")
                .dataSource(td)
                .events(Components.noEvents())
                .build();
    }

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
    }

    private LDClient makeClient() throws Exception {
        return LDClient.init(application, config, user, 5);
    }

    @Test
    public void initializesWithEmptyData() throws Exception {
        try (LDClient client = makeClient()) {
            assertTrue(client.isInitialized());
        }
    }

    @Test
    public void initializesWithFlag() throws Exception {
        td.update(td.flag("flag").variation(true));

        try (LDClient client = makeClient()) {
            assertTrue(client.boolVariation("flag", false));
        }
    }

    @Test
    public void updateFlag() throws Exception {
        try (LDClient client = makeClient()) {
            assertFalse(client.boolVariation("flag", false));

            td.update(td.flag("flag").variation(true));

            assertTrue(client.boolVariation("flag", false));
        }
    }

    @Test
    public void canSetValuePerContext() throws Exception {
        td.update(td.flag("flag")
                .variations(LDValue.of("red"), LDValue.of("green"), LDValue.of("blue"))
                .variation(0)
                .variationValueFunc(c -> c.getValue("favoriteColor"))
                );
        LDContext user1 = LDContext.create("user1");
        LDContext user2 = LDContext.builder("user2").set("favoriteColor", "green").build();
        LDContext user3 = LDContext.builder("user3").set("favoriteColor", "blue").build();

        try (LDClient client = LDClient.init(application, config, user1, 5);) {
            assertEquals("red", client.stringVariation("flag", ""));

            client.identify(user2).get();
            assertEquals("green", client.stringVariation("flag", ""));

            client.identify(user3).get();
            assertEquals("blue", client.stringVariation("flag", ""));
        }
    }
}
