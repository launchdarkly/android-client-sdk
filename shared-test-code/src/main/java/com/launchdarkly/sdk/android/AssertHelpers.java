package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import com.launchdarkly.sdk.LDValue;

public class AssertHelpers {
    private static final Gson gson = new Gson();

    public static void assertDataSetsEqual(EnvironmentData expected, EnvironmentData actual) {
        assertJsonEqual(expected.toJson(), actual.toJson());
    }

    public static void assertFlagsEqual(Flag expected, Flag actual) {
        if (expected == null) {
            assertNull(actual);
        } else {
            assertNotNull(actual);
            assertJsonEqual(gson.toJson(expected), gson.toJson(actual));
        }
    }
    public static void assertJsonEqual(String expected, String actual) {
        assertEquals(LDValue.parse(expected), LDValue.parse(actual));
    }
}
