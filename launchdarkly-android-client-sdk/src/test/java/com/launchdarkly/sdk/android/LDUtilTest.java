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
}
