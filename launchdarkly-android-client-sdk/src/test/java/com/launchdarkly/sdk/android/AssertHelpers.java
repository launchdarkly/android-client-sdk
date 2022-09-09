package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.launchdarkly.sdk.LDValue;

public class AssertHelpers {
    public static void assertFlagsEqual(Flag expected, Flag actual) {
        if (expected == null) {
            assertNull(actual);
        } else {
            assertNotNull(actual);
            assertJsonEqual(gsonInstance().toJson(expected), gsonInstance().toJson(actual));
        }
    }
    public static void assertJsonEqual(String expected, String actual) {
        assertEquals(LDValue.parse(expected), LDValue.parse(actual));
    }
}
