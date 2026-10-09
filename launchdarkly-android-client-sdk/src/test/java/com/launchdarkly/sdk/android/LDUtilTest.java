package com.launchdarkly.sdk.android;

import org.junit.Assert;
import org.junit.Test;

public class LDUtilTest {

    @Test
    public void testUrlSafeBase64Hash() {
        String input = "hashThis!";
        String expectedOutput = "sfXg3HewbCAVNQLJzPZhnFKntWYvN0nAYyUWFGy24dQ=";
        String output = LDUtil.urlSafeBase64Hash(input);
        Assert.assertEquals(expectedOutput, output);
    }

    @Test
    public void testValidateStringValue() {
        Assert.assertNotNull(LDUtil.validateStringValue(""));
        Assert.assertNotNull(LDUtil.validateStringValue("0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEFwhoops"));
        Assert.assertNotNull(LDUtil.validateStringValue("#@$%^&"));
        Assert.assertNull(LDUtil.validateStringValue("a-Az-Z0-9._-"));
    }

    @Test
    public void testSanitizeSpaces() {
        Assert.assertEquals("", LDUtil.sanitizeSpaces(""));
        Assert.assertEquals("--hello--", LDUtil.sanitizeSpaces("  hello  "));
        Assert.assertEquals("world", LDUtil.sanitizeSpaces("world"));
    }

    @Test
    public void testIsHttpErrorUnexpected() {
        // Statuses that are unlikely to resolve on their own.
        Assert.assertTrue(LDUtil.isHttpErrorUnexpected(401));
        Assert.assertTrue(LDUtil.isHttpErrorUnexpected(403));
        Assert.assertTrue(LDUtil.isHttpErrorUnexpected(404));
        Assert.assertTrue(LDUtil.isHttpErrorUnexpected(405));

        // Statuses that the service is likely to stop returning.
        Assert.assertFalse(LDUtil.isHttpErrorUnexpected(400));
        Assert.assertFalse(LDUtil.isHttpErrorUnexpected(408));
        Assert.assertFalse(LDUtil.isHttpErrorUnexpected(429));
        Assert.assertFalse(LDUtil.isHttpErrorUnexpected(500));
        Assert.assertFalse(LDUtil.isHttpErrorUnexpected(503));
    }
}
