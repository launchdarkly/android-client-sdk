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
}
