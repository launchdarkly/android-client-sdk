package com.launchdarkly.android.response;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.launchdarkly.android.EvaluationReason;
import com.launchdarkly.android.response.interpreter.UserFlagResponseParser;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UserFlagResponseTest {
    private static final Gson gson = new Gson();

    private static final Map<EvaluationReason, String> TEST_REASONS = ImmutableMap.<EvaluationReason, String>builder()
            .put(EvaluationReason.off(), "{\"kind\": \"OFF\"}")
            .put(EvaluationReason.fallthrough(), "{\"kind\": \"FALLTHROUGH\"}")
            .put(EvaluationReason.targetMatch(), "{\"kind\": \"TARGET_MATCH\"}")
            .put(EvaluationReason.ruleMatch(1, "id"), "{\"kind\": \"RULE_MATCH\", \"ruleIndex\": 1, \"ruleId\": \"id\"}")
            .put(EvaluationReason.prerequisiteFailed("flag"), "{\"kind\": \"PREREQUISITE_FAILED\", \"prerequisiteKey\": \"flag\"}")
            .put(EvaluationReason.error(EvaluationReason.ErrorKind.FLAG_NOT_FOUND), "{\"kind\": \"ERROR\", \"errorKind\": \"FLAG_NOT_FOUND\"}")
            .build();

    @Test
    public void valueIsSerialized() {
        final UserFlagResponse r = new UserFlagResponse("flag", new JsonPrimitive("yes"));
        final JsonObject json = r.getAsJsonObject();
        assertEquals(new JsonPrimitive("yes"), json.get("value"));
    }

    @Test
    public void valueIsDeserialized() {
        final String jsonStr = "{\"value\": \"yes\"}";
        final JsonObject json = gson.fromJson(jsonStr, JsonObject.class);
        final UserFlagResponse r = UserFlagResponseParser.parseFlag(json, "flag");
        assertEquals(new JsonPrimitive("yes"), r.getValue());
    }

    @Test
    public void versionIsSerialized() {
        final UserFlagResponse r = new UserFlagResponse("flag", new JsonPrimitive("yes"), 99, 100, null, null, null, null);
        final JsonObject json = r.getAsJsonObject();
        assertEquals(new JsonPrimitive(99), json.get("version"));
    }

    @Test
    public void versionIsDeserialized() {
        final String jsonStr = "{\"version\": 99}";
        final JsonObject json = gson.fromJson(jsonStr, JsonObject.class);
        final UserFlagResponse r = UserFlagResponseParser.parseFlag(json, "flag");
        assertEquals(99, r.getVersion());
    }

    @Test
    public void flagVersionIsSerialized() {
        final UserFlagResponse r = new UserFlagResponse("flag", new JsonPrimitive("yes"), 99, 100, null, null, null, null);
        final JsonObject json = r.getAsJsonObject();
        assertEquals(new JsonPrimitive(100), json.get("flagVersion"));
    }

    @Test
    public void flagVersionIsDeserialized() {
        final String jsonStr = "{\"version\": 99, \"flagVersion\": 100}";
        final JsonObject json = gson.fromJson(jsonStr, JsonObject.class);
        final UserFlagResponse r = UserFlagResponseParser.parseFlag(json, "flag");
        assertEquals(100, r.getFlagVersion());
    }

    @Test
    public void flagVersionDefaultWhenOmitted() {
        final String jsonStr = "{\"version\": 99}";
        final JsonObject json = gson.fromJson(jsonStr, JsonObject.class);
        final UserFlagResponse r = UserFlagResponseParser.parseFlag(json, "flag");
        assertEquals(-1, r.getFlagVersion());
    }

    @Test
    public void variationIsSerialized() {
        final UserFlagResponse r = new UserFlagResponse("flag", new JsonPrimitive("yes"), 99, 100, 2, null, null, null);
        final JsonObject json = r.getAsJsonObject();
        assertEquals(new JsonPrimitive(2), json.get("variation"));
    }

    @Test
    public void variationIsDeserialized() {
        final String jsonStr = "{\"version\": 99, \"variation\": 2}";
        final JsonObject json = gson.fromJson(jsonStr, JsonObject.class);
        final UserFlagResponse r = UserFlagResponseParser.parseFlag(json, "flag");
        assertEquals(new Integer(2), r.getVariation());
    }

    @Test
    public void variationDefaultWhenOmitted() {
        final String jsonStr = "{\"version\": 99}";
        final JsonObject json = gson.fromJson(jsonStr, JsonObject.class);
        final UserFlagResponse r = UserFlagResponseParser.parseFlag(json, "flag");
        assertNull(r.getVariation());
    }

    @Test
    public void trackEventsIsSerialized() {
        final UserFlagResponse r = new UserFlagResponse("flag", new JsonPrimitive("yes"), 99, 100, 2, true, null, null);
        final JsonObject json = r.getAsJsonObject();
        assertEquals(new JsonPrimitive(true), json.get("trackEvents"));
    }

    @Test
    public void trackEventsIsDeserialized() {
        final String jsonStr = "{\"version\": 99, \"trackEvents\": true}";
        final JsonObject json = gson.fromJson(jsonStr, JsonObject.class);
        final UserFlagResponse r = UserFlagResponseParser.parseFlag(json, "flag");
        assertTrue(r.isTrackEvents());
    }

    @Test
    public void trackEventsDefaultWhenOmitted() {
        final String jsonStr = "{\"version\": 99}";
        final JsonObject json = gson.fromJson(jsonStr, JsonObject.class);
        final UserFlagResponse r = UserFlagResponseParser.parseFlag(json, "flag");
        assertFalse(r.isTrackEvents());
    }

    @Test
    public void debugEventsUntilDateIsSerialized() {
        final long date = 12345L;
        final UserFlagResponse r = new UserFlagResponse("flag", new JsonPrimitive("yes"), 99, 100, 2, false, date, null);
        final JsonObject json = r.getAsJsonObject();
        assertEquals(new JsonPrimitive(date), json.get("debugEventsUntilDate"));
    }

    @Test
    public void debugEventsUntilDateIsDeserialized() {
        final String jsonStr = "{\"version\": 99, \"debugEventsUntilDate\": 12345}";
        final JsonObject json = gson.fromJson(jsonStr, JsonObject.class);
        final UserFlagResponse r = UserFlagResponseParser.parseFlag(json, "flag");
        assertEquals(new Long(12345L), r.getDebugEventsUntilDate());
    }

    @Test
    public void debugEventsUntilDateDefaultWhenOmitted() {
        final String jsonStr = "{\"version\": 99}";
        final JsonObject json = gson.fromJson(jsonStr, JsonObject.class);
        final UserFlagResponse r = UserFlagResponseParser.parseFlag(json, "flag");
        assertNull(r.getDebugEventsUntilDate());
    }

    @Test
    public void reasonIsSerialized() {
        for (Map.Entry<EvaluationReason, String> e: TEST_REASONS.entrySet()) {
            final EvaluationReason reason = e.getKey();
            final String expectedJsonStr = e.getValue();
            final JsonObject expectedJson = gson.fromJson(expectedJsonStr, JsonObject.class);
            final UserFlagResponse r = new UserFlagResponse("flag", new JsonPrimitive("yes"), 99, 100, null, false, null, reason);
            final JsonObject json = r.getAsJsonObject();
            assertEquals(expectedJson, json.get("reason"));
        }
    }

    @Test
    public void reasonIsDeserialized() {
        for (Map.Entry<EvaluationReason, String> e: TEST_REASONS.entrySet()) {
            final EvaluationReason reason = e.getKey();
            final String reasonJsonStr = e.getValue();
            final JsonObject reasonJson = gson.fromJson(reasonJsonStr, JsonObject.class);
            final JsonObject json = new JsonObject();
            json.add("reason", reasonJson);
            final UserFlagResponse r = UserFlagResponseParser.parseFlag(json, "flag");
            assertEquals(reason, r.getReason());
        }
    }

    @Test
    public void reasonDefaultWhenOmitted() {
        final String jsonStr = "{\"version\": 99}";
        final JsonObject json = gson.fromJson(jsonStr, JsonObject.class);
        final UserFlagResponse r = UserFlagResponseParser.parseFlag(json, "flag");
        assertNull(r.getReason());
    }

    @Test
    public void emptyPropertiesAreNotSerialized() {
        final UserFlagResponse r = new UserFlagResponse("flag", new JsonPrimitive("yes"), 99, 100, null, false, null, null);
        final JsonObject json = r.getAsJsonObject();
        assertEquals(ImmutableSet.<String>of("value", "version", "flagVersion"), json.keySet());
    }
}
