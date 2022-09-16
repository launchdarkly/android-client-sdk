package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.launchdarkly.sdk.ArrayBuilder;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FlagTest {
    private static final Gson gson = gsonInstance();

    private Map<EvaluationReason, String> TEST_REASONS;

    @Before
    public void setUp() {
        TEST_REASONS = new HashMap<>();
        TEST_REASONS.put(EvaluationReason.off(), "{\"kind\": \"OFF\"}");
        TEST_REASONS.put(EvaluationReason.fallthrough(), "{\"kind\": \"FALLTHROUGH\"}");
        TEST_REASONS.put(EvaluationReason.targetMatch(), "{\"kind\": \"TARGET_MATCH\"}");
        TEST_REASONS.put(EvaluationReason.ruleMatch(1, "id"), "{\"kind\": \"RULE_MATCH\", \"ruleIndex\": 1, \"ruleId\": \"id\"}");
        TEST_REASONS.put(EvaluationReason.prerequisiteFailed("flag"), "{\"kind\": \"PREREQUISITE_FAILED\", \"prerequisiteKey\": \"flag\"}");
        TEST_REASONS.put(EvaluationReason.error(EvaluationReason.ErrorKind.FLAG_NOT_FOUND), "{\"kind\": \"ERROR\", \"errorKind\": \"FLAG_NOT_FOUND\"}");
    }

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
        LDValue boolVal = LDValue.of(true);
        LDValue stringVal = LDValue.of("string");
        LDValue numVal = LDValue.of(5.3);
        LDValue arrVal = new ArrayBuilder()
                .add(boolVal)
                .add(stringVal)
                .add(numVal)
                .add(new ArrayBuilder().build())
                .add(new ObjectBuilder().build())
                .build();
        LDValue objVal = new ObjectBuilder()
                .put("bool", boolVal)
                .put("num", numVal)
                .put("string", stringVal)
                .put("array", arrVal)
                .put("obj", new ObjectBuilder().build())
                .build();

        List<LDValue> testValues = Arrays.asList(boolVal, stringVal, numVal, arrVal, objVal);
        for (LDValue value : testValues) {
            final Flag r = new FlagBuilder("flag").value(value).build();
            final JsonObject json = gson.toJsonTree(r).getAsJsonObject();
            final JsonElement val = gson.toJsonTree(value);

            assertEquals(val, json.get("value"));
        }
    }

    @Test
    public void valueIsDeserialized() {
        final String jsonStr = "{\"value\": \"yes\"}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertEquals(LDValue.of("yes"), r.getValue());
    }

    @Test
    public void nullValueIsReturnedAsLDValue() {
        final String jsonStr = "{\"value\": null}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertEquals(LDValue.ofNull(), r.getValue());
    }

    @Test
    public void valueDefaultWhenOmitted() {
        final String jsonStr = "{\"key\": \"flag\"}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertEquals(LDValue.ofNull(), r.getValue());
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
        assertTrue(r.isTrackEvents());
    }

    @Test
    public void trackEventsDefaultWhenOmitted() {
        final String jsonStr = "{\"version\": 99}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertFalse(r.isTrackEvents());
    }

    @Test
    public void trackReasonIsSerialized() {
        final Flag r = new FlagBuilder("flag").trackReason(true).build();
        final JsonObject json = gson.toJsonTree(r).getAsJsonObject();
        assertEquals(new JsonPrimitive(true), json.get("trackReason"));
    }

    @Test
    public void trackReasonIsDeserialized() {
        final String jsonStr = "{\"version\": 99, \"trackReason\": true}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertTrue(r.isTrackReason());
    }

    @Test
    public void trackReasonDefaultWhenOmitted() {
        final String jsonStr = "{\"version\": 99}";
        final Flag r = gson.fromJson(jsonStr, Flag.class);
        assertFalse(r.isTrackReason());
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
        final Flag r = new FlagBuilder("flag").value(LDValue.of("yes")).version(99).flagVersion(100).trackEvents(false).build();
        final JsonObject json = gson.toJsonTree(r).getAsJsonObject();
        assertEquals(5, json.keySet().size());
        assertTrue(json.keySet().containsAll(Arrays.asList("key", "trackEvents", "value", "version", "flagVersion")));
    }

    @Test
    public void testIsDeleted() {
        final Flag normalFlag = new FlagBuilder("flag").version(10).build();
        final Flag placeholder = Flag.deletedItemPlaceholder("flag", 10);
        assertFalse(normalFlag.isDeleted());
        assertTrue(placeholder.isDeleted());
        assertEquals(normalFlag.getVersion(), placeholder.getVersion());
    }

    @Test
    public void testGetVersionForEvents() {
        final Flag withVersion = new FlagBuilder("flag").version(10).build();
        final Flag withVersionAndFlagVersion = new FlagBuilder("flag").version(10).flagVersion(5).build();

        assertEquals(10, withVersion.getVersionForEvents());
        assertEquals(5, withVersionAndFlagVersion.getVersionForEvents());
    }
}
