package com.launchdarkly.sdk.android;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.launchdarkly.sdk.android.GsonCache;
import com.launchdarkly.sdk.android.LDFailure;
import com.launchdarkly.sdk.android.LDInvalidResponseCodeFailure;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LDFailureTest {
    private static final Gson gson = GsonCache.getGson();
    private static final Exception cause = new Exception("root cause");

    @Test
    public void constructor() {
        LDFailure ldFailure = new LDFailure("testMessage", LDFailure.FailureType.INVALID_RESPONSE_BODY);
        assertEquals("testMessage", ldFailure.getMessage());
        assertEquals(LDFailure.FailureType.INVALID_RESPONSE_BODY, ldFailure.getFailureType());
        assertNull(ldFailure.getCause());
    }

    @Test
    public void constructorWithCause() {
        LDFailure ldFailure = new LDFailure("unknown", cause, LDFailure.FailureType.UNKNOWN_ERROR);
        assertEquals("unknown", ldFailure.getMessage());
        assertEquals(LDFailure.FailureType.UNKNOWN_ERROR, ldFailure.getFailureType());
        assertSame(cause, ldFailure.getCause());
    }

    @Test
    public void responseCodeConstructor() {
        LDInvalidResponseCodeFailure ldFailure = new LDInvalidResponseCodeFailure("401", 401, false);
        assertEquals("401", ldFailure.getMessage());
        assertEquals(LDFailure.FailureType.UNEXPECTED_RESPONSE_CODE, ldFailure.getFailureType());
        assertEquals(401, ldFailure.getResponseCode());
        assertFalse(ldFailure.isRetryable());
        assertNull(ldFailure.getCause());
    }

    @Test
    public void responseCodeConstructorWithCause() {
        LDInvalidResponseCodeFailure ldFailure = new LDInvalidResponseCodeFailure("500", cause, 500, true);
        assertEquals("500", ldFailure.getMessage());
        assertEquals(LDFailure.FailureType.UNEXPECTED_RESPONSE_CODE, ldFailure.getFailureType());
        assertEquals(500, ldFailure.getResponseCode());
        assertTrue(ldFailure.isRetryable());
        assertSame(cause, ldFailure.getCause());
    }

    @Test
    public void serializeAndDeserialize() {
        ArrayList<LDFailure.FailureType> types = new ArrayList<>(Arrays.asList(LDFailure.FailureType.values()));
        // Unexpected response code is tested separately
        types.remove(LDFailure.FailureType.UNEXPECTED_RESPONSE_CODE);
        for (LDFailure.FailureType failureType: types) {
            // Without cause
            LDFailure ldFailure = new LDFailure(failureType.toString(), failureType);
            String asJson = gson.toJson(ldFailure);
            LDFailure restored = gson.fromJson(asJson, LDFailure.class);
            assertEquals(failureType.toString(), restored.getMessage());
            assertEquals(failureType, restored.getFailureType());
            assertNull(restored.getCause());
            // With cause - cause is not expected to be serialized
            ldFailure = new LDFailure(failureType.toString(), cause, failureType);
            asJson = gson.toJson(ldFailure);
            restored = gson.fromJson(asJson, LDFailure.class);
            assertEquals(failureType.toString(), restored.getMessage());
            assertEquals(failureType, restored.getFailureType());
            assertNull(restored.getCause());
        }
    }

    @Test
    public void serializeAndDeserializeResponseCodeFailure() {
        // Without cause
        LDInvalidResponseCodeFailure ldFailure = new LDInvalidResponseCodeFailure("401", 401, false);
        String asJson = gson.toJson(ldFailure);
        LDInvalidResponseCodeFailure restored = gson.fromJson(asJson, LDInvalidResponseCodeFailure.class);
        assertEquals("401", restored.getMessage());
        assertEquals(LDFailure.FailureType.UNEXPECTED_RESPONSE_CODE, restored.getFailureType());
        assertNull(restored.getCause());
        assertEquals(401, restored.getResponseCode());
        assertFalse(restored.isRetryable());
        // With cause - cause is not expected to be serialized
        ldFailure = new LDInvalidResponseCodeFailure("500", cause, 500, true);
        asJson = gson.toJson(ldFailure);
        restored = gson.fromJson(asJson, LDInvalidResponseCodeFailure.class);
        assertEquals("500", restored.getMessage());
        assertEquals(LDFailure.FailureType.UNEXPECTED_RESPONSE_CODE, restored.getFailureType());
        assertNull(restored.getCause());
        assertEquals(500, restored.getResponseCode());
        assertTrue(restored.isRetryable());
    }

    @Test
    public void serializeAndDeserializeResponseCodeFailureAsLDFailure() {
        // Without cause
        LDInvalidResponseCodeFailure ldFailure = new LDInvalidResponseCodeFailure("401", 401, false);
        String asJson = gson.toJson((LDFailure)ldFailure);
        LDFailure restored = gson.fromJson(asJson, LDFailure.class);
        assertEquals("401", restored.getMessage());
        assertEquals(LDFailure.FailureType.UNEXPECTED_RESPONSE_CODE, restored.getFailureType());
        assertNull(restored.getCause());
        LDInvalidResponseCodeFailure typed = (LDInvalidResponseCodeFailure)restored;
        assertEquals(401, typed.getResponseCode());
        assertFalse(typed.isRetryable());
        // With cause - cause is not expected to be serialized
        ldFailure = new LDInvalidResponseCodeFailure("500", cause, 500, true);
        asJson = gson.toJson((LDFailure)ldFailure);
        restored = gson.fromJson(asJson, LDFailure.class);
        assertEquals("500", restored.getMessage());
        assertEquals(LDFailure.FailureType.UNEXPECTED_RESPONSE_CODE, restored.getFailureType());
        assertNull(restored.getCause());
        typed = (LDInvalidResponseCodeFailure)restored;
        assertEquals(500, typed.getResponseCode());
        assertTrue(typed.isRetryable());
    }

    @Test
    public void serializationUsesExpectedKeysAndTypes() {
        LDInvalidResponseCodeFailure ldFailure = new LDInvalidResponseCodeFailure("401", 401, false);
        JsonObject asJsonTree = gson.toJsonTree((LDFailure)ldFailure).getAsJsonObject();
        assertEquals(asJsonTree.size(), 4);
        assertEquals("UNEXPECTED_RESPONSE_CODE", asJsonTree.get("failureType").getAsString());
        assertEquals("401", asJsonTree.get("message").getAsString());
        assertEquals(401, asJsonTree.get("responseCode").getAsInt());
        assertFalse(asJsonTree.get("retryable").getAsBoolean());
    }
}
