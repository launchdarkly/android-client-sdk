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
    public void isHttpErrorRecoverableClassifiesStatusCodes() {
        // 400, 408, 429, and 5xx are normal; every other 4xx is unexpected.
        Assert.assertTrue(LDUtil.isHttpErrorRecoverable(400));
        Assert.assertTrue(LDUtil.isHttpErrorRecoverable(408));
        Assert.assertTrue(LDUtil.isHttpErrorRecoverable(429));
        Assert.assertTrue(LDUtil.isHttpErrorRecoverable(500));
        Assert.assertTrue(LDUtil.isHttpErrorRecoverable(503));
        Assert.assertTrue(LDUtil.isHttpErrorRecoverable(302));

        Assert.assertFalse(LDUtil.isHttpErrorRecoverable(401));
        Assert.assertFalse(LDUtil.isHttpErrorRecoverable(403));
        Assert.assertFalse(LDUtil.isHttpErrorRecoverable(404));
        Assert.assertFalse(LDUtil.isHttpErrorRecoverable(405));
    }

    @Test
    public void isUnexpectedFailureIsTrueOnlyForUnexpectedHttpStatuses() {
        Assert.assertTrue(LDUtil.isUnexpectedFailure(new LDInvalidResponseCodeFailure("x", 401, false)));
        Assert.assertTrue(LDUtil.isUnexpectedFailure(new LDInvalidResponseCodeFailure("x", 403, false)));
        // Classification is by status code, not by the retryable flag the failure was built with.
        Assert.assertTrue(LDUtil.isUnexpectedFailure(new LDInvalidResponseCodeFailure("x", 401, true)));

        Assert.assertFalse(LDUtil.isUnexpectedFailure(new LDInvalidResponseCodeFailure("x", 429, true)));
        Assert.assertFalse(LDUtil.isUnexpectedFailure(new LDInvalidResponseCodeFailure("x", 500, true)));
        // Malformed bodies and transport errors are normal failures.
        Assert.assertFalse(LDUtil.isUnexpectedFailure(new LDFailure("x", LDFailure.FailureType.INVALID_RESPONSE_BODY)));
        Assert.assertFalse(LDUtil.isUnexpectedFailure(new LDFailure("x", LDFailure.FailureType.NETWORK_FAILURE)));
        Assert.assertFalse(LDUtil.isUnexpectedFailure(new RuntimeException("x")));
        Assert.assertFalse(LDUtil.isUnexpectedFailure(null));
    }
}
