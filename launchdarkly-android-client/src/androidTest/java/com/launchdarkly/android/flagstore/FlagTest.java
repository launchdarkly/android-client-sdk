package com.launchdarkly.android.flagstore;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.launchdarkly.android.EvaluationReason;
import com.launchdarkly.android.gson.GsonCache;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FlagTest {
    private static final Gson gson = GsonCache.getGson();

    private static final Map<EvaluationReason, String> TEST_REASONS = ImmutableMap.<EvaluationReason, String>builder()
            .put(EvaluationReason.off(), "{\"kind\": \"OFF\"}")
            .put(EvaluationReason.fallthrough(), "{\"kind\": \"FALLTHROUGH\"}")
            .put(EvaluationReason.targetMatch(), "{\"kind\": \"TARGET_MATCH\"}")
            .put(EvaluationReason.ruleMatch(1, "id"), "{\"kind\": \"RULE_MATCH\", \"ruleIndex\": 1, \"ruleId\": \"id\"}")
            .put(EvaluationReason.prerequisiteFailed("flag"), "{\"kind\": \"PREREQUISITE_FAILED\", \"prerequisiteKey\": \"flag\"}")
            .put(EvaluationReason.error(EvaluationReason.ErrorKind.FLAG_NOT_FOUND), "{\"kind\": \"ERROR\", \"errorKind\": \"FLAG_NOT_FOUND\"}")
            .build();

    @Test
    public void keyIsSerialized() {
        final Flag r = new FlagBuilder("flag").build();
        final JsonObject json = gson.toJsonTree(r).getAsJsonObject();
        assertEquals(new JsonPrimitive("flag"), json.get("key"));
    }

    @Test
    public void keyIsDeserialized() {
        final String jsonStr = "{\"key\": \"flag\"}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertEquals("flag", r.getKey());
    }

    @Test
    public void valueIsSerialized() {
        JsonElement jsonBool = new JsonPrimitive(true);
        JsonElement jsonString = new JsonPrimitive("string");
        JsonElement jsonNum = new JsonPrimitive(5.3);
        JsonArray jsonArray = new JsonArray();
        jsonArray.add(jsonBool);
        jsonArray.add(jsonString);
        jsonArray.add(jsonNum);
        jsonArray.add(new JsonArray());
        jsonArray.add(new JsonObject());
        JsonObject jsonObj = new JsonObject();
        jsonObj.add("bool", jsonBool);
        jsonObj.add("num", jsonNum);
        jsonObj.add("string", jsonString);
        jsonObj.add("array", jsonArray);
        jsonObj.add("obj", new JsonObject());

        List<JsonElement> testValues = Arrays.asList(jsonBool, jsonString, jsonNum, jsonArray, jsonObj);
        for (JsonElement value : testValues) {
            final Flag r = new FlagBuilder("flag").value(value).build();
            final JsonObject json = gson.toJsonTree(r).getAsJsonObject();
            assertEquals(value, json.get("value"));
        }
    }

    @Test
    public void valueIsDeserialized() {
        final String jsonStr = "{\"value\": \"yes\"}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertEquals(new JsonPrimitive("yes"), r.getValue());
    }

    @Test
    public void valueDefaultWhenOmitted() {
        final String jsonStr = "{\"key\": \"flag\"}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertNull(r.getValue());
    }

    @Test
    public void versionIsSerialized() {
        final Flag r = new FlagBuilder("flag").version(99).build();
        final JsonObject json = gson.toJsonTree(r).getAsJsonObject();
        assertEquals(new JsonPrimitive(99), json.get("version"));
    }

    @Test
    public void versionIsDeserialized() {
        final String jsonStr = "{\"version\": 99}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertNotNull(r.getVersion());
        assertEquals(99, (int) r.getVersion());
    }

    @Test
    public void versionDefaultWhenOmitted() {
        final String jsonStr = "{\"flagVersion\": 99}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertNull(r.getVersion());
    }

    @Test
    public void flagVersionIsSerialized() {
        final Flag r = new FlagBuilder("flag").flagVersion(100).build();
        final JsonObject json = gson.toJsonTree(r).getAsJsonObject();
        assertEquals(new JsonPrimitive(100), json.get("flagVersion"));
    }

    @Test
    public void flagVersionIsDeserialized() {
        final String jsonStr = "{\"version\": 99, \"flagVersion\": 100}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertNotNull(r.getFlagVersion());
        assertEquals(100, (int) r.getFlagVersion());
    }

    @Test
    public void flagVersionDefaultWhenOmitted() {
        final String jsonStr = "{\"version\": 99}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertNull(r.getFlagVersion());
    }

    @Test
    public void variationIsSerialized() {
        final Flag r = new FlagBuilder("flag").variation(2).build();
        final JsonObject json = gson.toJsonTree(r).getAsJsonObject();
        assertEquals(new JsonPrimitive(2), json.get("variation"));
    }

    @Test
    public void variationIsDeserialized() {
        final String jsonStr = "{\"version\": 99, \"variation\": 2}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertEquals(new Integer(2), r.getVariation());
    }

    @Test
    public void variationDefaultWhenOmitted() {
        final String jsonStr = "{\"version\": 99}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertNull(r.getVariation());
    }

    @Test
    public void trackEventsIsSerialized() {
        final Flag r = new FlagBuilder("flag").trackEvents(true).build();
        final JsonObject json = gson.toJsonTree(r).getAsJsonObject();
        assertEquals(new JsonPrimitive(true), json.get("trackEvents"));
    }

    @Test
    public void trackEventsIsDeserialized() {
        final String jsonStr = "{\"version\": 99, \"trackEvents\": true}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertTrue(r.getTrackEvents());
    }

    @Test
    public void trackEventsDefaultWhenOmitted() {
        final String jsonStr = "{\"version\": 99}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertFalse(r.getTrackEvents());
    }

    @Test
    public void debugEventsUntilDateIsSerialized() {
        final long date = 12345L;
        final Flag r = new FlagBuilder("flag").debugEventsUntilDate(date).build();
        final JsonObject json = gson.toJsonTree(r).getAsJsonObject();
        assertEquals(new JsonPrimitive(date), json.get("debugEventsUntilDate"));

        // Test long sized number
        final long datel = 2500000000L;
        final Flag rl = new FlagBuilder("flag").debugEventsUntilDate(datel).build();
        final JsonObject jsonl = gson.toJsonTree(rl).getAsJsonObject();
        assertEquals(new JsonPrimitive(datel), jsonl.get("debugEventsUntilDate"));
    }

    @Test
    public void debugEventsUntilDateIsDeserialized() {
        final String jsonStr = "{\"version\": 99, \"debugEventsUntilDate\": 12345}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertEquals(new Long(12345L), r.getDebugEventsUntilDate());

        // Test long sized number
        final String jsonStrl = "{\"version\": 99, \"debugEventsUntilDate\": 2500000000}";
        final Flag rl = gson.fromJson(jsonStrl, Flag.class);
        assertEquals(new Long(2500000000L), rl.getDebugEventsUntilDate());
    }

    @Test
    public void debugEventsUntilDateDefaultWhenOmitted() {
        final String jsonStr = "{\"version\": 99}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertNull(r.getDebugEventsUntilDate());
    }

    @Test
    public void reasonIsSerialized() {
        for (Map.Entry<EvaluationReason, String> e : TEST_REASONS.entrySet()) {
            final EvaluationReason reason = e.getKey();
            final String expectedJsonStr = e.getValue();
            final JsonObject expectedJson = gson.fromJson(expectedJsonStr, JsonObject.class);
            final Flag r = new FlagBuilder("flag").reason(reason).build();
            final JsonObject json = gson.toJsonTree(r).getAsJsonObject();
            assertEquals(expectedJson, json.get("reason"));
        }
    }

    @Test
    public void reasonIsDeserialized() {
        for (Map.Entry<EvaluationReason, String> e : TEST_REASONS.entrySet()) {
            final EvaluationReason reason = e.getKey();
            final String reasonJsonStr = e.getValue();
            final JsonObject reasonJson = gson.fromJson(reasonJsonStr, JsonObject.class);
            final JsonObject json = new JsonObject();
            json.add("reason", reasonJson);
            final Flag r = gson.fromJson(json, Flag.class);
            assertEquals(reason, r.getReason());
        }
    }

    @Test
    public void reasonDefaultWhenOmitted() {
        final String jsonStr = "{\"version\": 99}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertNull(r.getReason());
    }

    @Test
    public void emptyPropertiesAreNotSerialized() {
        final Flag r = new FlagBuilder("flag").value(new JsonPrimitive("yes")).version(99).flagVersion(100).trackEvents(false).build();
        final JsonObject json = gson.toJsonTree(r).getAsJsonObject();
        assertEquals(ImmutableSet.of("key", "trackEvents", "value", "version", "flagVersion"), json.keySet());
    }

    @Test
    public void testIsVersionMissing() {
        final Flag noVersion = new FlagBuilder("flag").build();
        final Flag withVersion = new FlagBuilder("flag").version(10).build();
        assertTrue(noVersion.isVersionMissing());
        assertFalse(withVersion.isVersionMissing());
    }

    @Test
    public void testGetVersionForEvents() {
        final Flag noVersions = new FlagBuilder("flag").build();
        final Flag withVersion = new FlagBuilder("flag").version(10).build();
        final Flag withFlagVersion = new FlagBuilder("flag").flagVersion(5).build();
        final Flag withBothVersions = new FlagBuilder("flag").version(10).flagVersion(5).build();

        assertEquals(-1, noVersions.getVersionForEvents());
        assertEquals(10, withVersion.getVersionForEvents());
        assertEquals(5, withFlagVersion.getVersionForEvents());
        assertEquals(5, withBothVersions.getVersionForEvents());
    }

    @Test
    public void flagToUpdateReturnsKey() {
        final Flag flag = new FlagBuilder("flag").build();
        assertEquals(flag.getKey(), flag.flagToUpdate());
    }

    @Test
    public void testUpdateFlag() {
        final Flag flagNoVersion = new FlagBuilder("flagNoVersion").build();
        final Flag flagNoVersion2 = new FlagBuilder("flagNoVersion2").build();
        final Flag flagLowVersion = new FlagBuilder("flagLowVersion").version(50).build();
        final Flag flagSameVersion = new FlagBuilder("flagSameVersion").version(50).build();
        final Flag flagHighVersion = new FlagBuilder("flagHighVersion").version(100).build();

        assertEquals(flagNoVersion, flagNoVersion.updateFlag(null));
        assertEquals(flagLowVersion, flagLowVersion.updateFlag(null));
        assertEquals(flagNoVersion, flagNoVersion.updateFlag(flagNoVersion2));
        assertEquals(flagLowVersion, flagLowVersion.updateFlag(flagNoVersion));
        assertEquals(flagNoVersion, flagNoVersion.updateFlag(flagLowVersion));
        assertEquals(flagSameVersion, flagLowVersion.updateFlag(flagSameVersion));
        assertEquals(flagHighVersion, flagHighVersion.updateFlag(flagLowVersion));
        assertEquals(flagHighVersion, flagLowVersion.updateFlag(flagHighVersion));
    }
}
