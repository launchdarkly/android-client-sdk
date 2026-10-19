package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class FDv1FallbackTtlTest {
    private static final long MIN_DEFAULT_SECONDS = 30 * 60;
    private static final long MAX_DEFAULT_SECONDS = 60 * 60;

    private static void assertIsDefault(long ttlSeconds) {
        assertTrue("expected a default TTL but got " + ttlSeconds,
                ttlSeconds >= MIN_DEFAULT_SECONDS && ttlSeconds <= MAX_DEFAULT_SECONDS);
    }

    @Test
    public void validServiceTtlIsUsedUnchanged() {
        for (long ttl : new long[]{1, 60, 1800, 3599, 3600}) {
            assertEquals(ttl, FDv1FallbackTtl.resolveSeconds(ttl));
        }
    }

    @Test
    public void invalidServiceTtlUsesDefault() {
        for (long ttl : new long[]{0, -1, -3600, 3601, Long.MAX_VALUE, Long.MIN_VALUE}) {
            assertIsDefault(FDv1FallbackTtl.resolveSeconds(ttl));
        }
    }

    @Test
    public void defaultIsJittered() {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            long ttl = FDv1FallbackTtl.defaultSeconds();
            assertIsDefault(ttl);
            seen.add(ttl);
        }
        assertTrue(seen.size() > 1);
    }

    @Test
    public void parseHeader() {
        assertEquals(120, FDv1FallbackTtl.parseHeader("120"));
        assertEquals(-5, FDv1FallbackTtl.parseHeader("-5"));
        assertEquals(0, FDv1FallbackTtl.parseHeader(null));
        assertEquals(0, FDv1FallbackTtl.parseHeader(""));
        assertEquals(0, FDv1FallbackTtl.parseHeader("soon"));
        assertEquals(0, FDv1FallbackTtl.parseHeader("12.5"));
        assertEquals(0, FDv1FallbackTtl.parseHeader("99999999999999999999"));
    }

    @Test
    public void fromGoodbyeWithoutTtlIsNotADirective() {
        assertNull(FDv1FallbackTtl.fromGoodbye(null));
        assertNull(FDv1FallbackTtl.fromGoodbye(JsonParser.parseString("{\"reason\":\"x\"}")));
    }

    @Test
    public void fromGoodbyeWithTtl() {
        assertEquals(Long.valueOf(90),
                FDv1FallbackTtl.fromGoodbye(JsonParser.parseString("{\"protocolFallbackTTL\":90}")));
        assertEquals(Long.valueOf(-5),
                FDv1FallbackTtl.fromGoodbye(JsonParser.parseString("{\"protocolFallbackTTL\":-5}")));
    }

    @Test
    public void fromGoodbyeWithMalformedTtlIsADirectiveWithNoTtl() {
        for (String ttl : new String[]{"\"90\"", "12.5", "null", "{}"}) {
            assertEquals(Long.valueOf(0),
                    FDv1FallbackTtl.fromGoodbye(JsonParser.parseString("{\"protocolFallbackTTL\":" + ttl + "}")));
        }
    }
}
