package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class FDv1FallbackTTLTest {
    private static final long MIN_DEFAULT_SECONDS = 30 * 60;
    private static final long MAX_DEFAULT_SECONDS = 60 * 60;

    private static void assertIsDefault(FDv1FallbackTTL ttl) {
        long seconds = ttl.getSeconds();
        assertTrue("expected a default TTL but got " + seconds,
                seconds >= MIN_DEFAULT_SECONDS && seconds <= MAX_DEFAULT_SECONDS);
    }

    private static FDv1FallbackTTL fromGoodbye(String json) {
        return FDv1FallbackTTL.fromGoodbye(JsonParser.parseString(json));
    }

    @Test
    public void defaultIsJittered() {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            FDv1FallbackTTL ttl = FDv1FallbackTTL.defaultTTL();
            assertIsDefault(ttl);
            seen.add(ttl.getSeconds());
        }
        assertTrue(seen.size() > 1);
    }

    @Test
    public void validHeaderIsUsedUnchanged() {
        for (String ttl : new String[]{"1", "60", "1800", "3599", "3600"}) {
            assertEquals(Long.parseLong(ttl), FDv1FallbackTTL.fromHeader(ttl).getSeconds());
        }
    }

    @Test
    public void invalidHeaderUsesDefault() {
        String[] invalid = {null, "", "soon", "12.5", "0", "-1", "-3600", "3601", "99999999999999999999"};
        for (String ttl : invalid) {
            assertIsDefault(FDv1FallbackTTL.fromHeader(ttl));
        }
    }

    @Test
    public void goodbyeWithoutTtlIsNotADirective() {
        assertNull(FDv1FallbackTTL.fromGoodbye(null));
        assertNull(fromGoodbye("{\"reason\":\"x\"}"));
    }

    @Test
    public void validGoodbyeTtlIsUsedUnchanged() {
        for (long ttl : new long[]{1, 90, 3600}) {
            assertEquals(ttl, fromGoodbye("{\"protocolFallbackTTL\":" + ttl + "}").getSeconds());
        }
    }

    @Test
    public void invalidGoodbyeTtlUsesDefault() {
        for (String ttl : new String[]{"0", "-5", "3601", "\"90\"", "12.5", "null", "{}"}) {
            assertIsDefault(fromGoodbye("{\"protocolFallbackTTL\":" + ttl + "}"));
        }
    }
}
