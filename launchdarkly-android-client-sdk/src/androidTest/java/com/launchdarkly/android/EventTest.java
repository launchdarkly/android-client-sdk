package com.launchdarkly.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.UserAttribute;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class EventTest {

    @Test
    public void testPrivateAttributesAreConcatenated() {

        LDUser.Builder builder = new LDUser.Builder("1")
                .privateAvatar("privateAvatar")
                .privateFirstName("privateName")
                .privateCustom("privateValue1", "123")
                .custom("Value2", "123")
                .email("email@server.net")
                .lastName("LastName");

        LDUser user = builder.build();

        LDConfig config = new LDConfig.Builder()
                .privateAttributes(UserAttribute.EMAIL, UserAttribute.forName("Value2"))
                .build();

        final Event event = new GenericEvent("kind1", "key1", user);

        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(event);
        JsonArray privateAttrs = jsonElement.getAsJsonObject().get("user").getAsJsonObject().getAsJsonArray("privateAttrs");

        assertNotNull(jsonElement);
        assertNotNull(privateAttrs);

        // we should get the 3 private attributes from the user and the 2 from the config
        assertEquals(5, privateAttrs.getAsJsonArray().size());

        for (String attrName: Arrays.asList(
                UserAttribute.AVATAR.getName(),
                UserAttribute.EMAIL.getName(),
                UserAttribute.FIRST_NAME.getName(),
                "privateValue1",
                "Value2")) {
            assertTrue(privateAttrs.contains(new JsonPrimitive(attrName)));
        }
    }

    @Test
    public void testPrivateAttributes() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .privateAvatar("privateAvatar")
                .custom("value1", "123")
                .email("email@server.net");

        LDUser user = builder.build();
        LDConfig config = new LDConfig.Builder().build();

        Event event = new GenericEvent("kind1", "key1", user);

        JsonObject userJson = config.getFilteredEventGson().toJsonTree(event).getAsJsonObject().getAsJsonObject("user");
        JsonArray privateAttrs = userJson.getAsJsonArray("privateAttrs");

        assertEquals(1, privateAttrs.size());
        assertEquals("avatar", privateAttrs.get(0).getAsString());
        assertEquals("email@server.net", userJson.getAsJsonPrimitive("email").getAsString());
        assertNull(userJson.getAsJsonPrimitive("avatar"));
    }

    @Test
    public void testRegularAttributesAreFilteredWithPrivateAttributes() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .avatar("avatarValue")
                .custom("value1", "123")
                .email("email@server.net");

        LDUser user = builder.build();

        LDConfig config = new LDConfig.Builder()
                .privateAttributes(UserAttribute.AVATAR)
                .build();

        Event event = new GenericEvent("kind1", "key1", user);

        JsonObject userJson = config.getFilteredEventGson().toJsonTree(event).getAsJsonObject().getAsJsonObject("user");
        JsonArray privateAttrs = userJson.getAsJsonArray("privateAttrs");

        assertEquals(1, privateAttrs.size());
        assertEquals("avatar", privateAttrs.get(0).getAsString());
        assertNull(userJson.getAsJsonPrimitive("avatar"));
    }

    @Test
    public void testPrivateAttributesJsonOnLDUserObject() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .privateEmail("email@server.net")
                .privateAvatar("avatarValue");

        LDUser user = builder.build();

        LDConfig config = new LDConfig.Builder()
                .build();

        Event event = new GenericEvent("kind1", "key1", user);

        Gson gson = new Gson();

        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(event);
        JsonObject userEval = gson.fromJson(gson.toJson(user), JsonObject.class);
        JsonArray privateAttrs = jsonElement.getAsJsonObject().getAsJsonObject("user").getAsJsonArray("privateAttrs");

        assertNotNull(jsonElement);
        assertNotNull(userEval);
        assertNotNull(privateAttrs);

        assertFalse(userEval.has("privateAttrs"));
        assertEquals(2, privateAttrs.size());
        assertTrue(privateAttrs.contains(new JsonPrimitive(UserAttribute.AVATAR.getName())));
        assertTrue(privateAttrs.contains(new JsonPrimitive(UserAttribute.EMAIL.getName())));
    }

    @Test
    public void testRegularAttributesAreFilteredWithAllAttributesPrivate() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .avatar("avatar")
                .custom("value1", "123")
                .email("email@server.net");

        LDUser user = builder.build();

        LDConfig config = new LDConfig.Builder()
                .allAttributesPrivate()
                .build();

        Event event = new GenericEvent("kind1", "key1", user);

        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(event);
        JsonArray privateAttrs = jsonElement.getAsJsonObject().getAsJsonObject("user").getAsJsonArray("privateAttrs");

        assertNotNull(user);
        assertNotNull(jsonElement);
        assertNotNull(privateAttrs);
        assertEquals(3, privateAttrs.size());

        assertTrue(privateAttrs.contains(new JsonPrimitive(UserAttribute.AVATAR.getName())));
        assertTrue(privateAttrs.contains(new JsonPrimitive(UserAttribute.EMAIL.getName())));
        assertTrue(privateAttrs.contains(new JsonPrimitive("value1")));
    }

    @Test
    public void testKeyAndAnonymousAreNotFilteredWithAllAttributesPrivate() {
        LDUser user = new LDUser.Builder("1")
                .email("email@server.net")
                .anonymous(true)
                .build();

        LDConfig config = new LDConfig.Builder()
                .allAttributesPrivate()
                .build();

        Event event = new GenericEvent("kind1", "key1", user);

        JsonObject userJson = config.getFilteredEventGson().toJsonTree(event).getAsJsonObject().getAsJsonObject("user");
        JsonArray privateAttrs = userJson.getAsJsonArray("privateAttrs");

        assertEquals(1, privateAttrs.size());
        assertEquals("email", privateAttrs.get(0).getAsString());
        assertEquals("1", userJson.getAsJsonPrimitive("key").getAsString());
        assertTrue(userJson.getAsJsonPrimitive("anonymous").getAsBoolean());
    }

    @Test
    public void featureRequestEventConstructor() {
        LDUser user = new LDUser.Builder("1").email("email@server.net").build();
        LDUser anonUser = new LDUser.Builder("3").email("email@server.net").anonymous(true).build();
        LDValue val1 = LDValue.of(7);
        LDValue val2 = LDValue.buildArray().add(5.4).add("abc").build();
        FeatureRequestEvent fe1, fe2, de1, de2;

        fe1 = new FeatureRequestEvent("key1", user, val1, LDValue.ofNull(), -1, 5, null, false, false);
        fe2 = new FeatureRequestEvent("key2", anonUser, LDValue.ofNull(), val2, 3, null, null, true, false);
        de1 = new FeatureRequestEvent("key3", user, val1, LDValue.ofNull(), -1, 3, null, false, true);
        de2 = new FeatureRequestEvent("key4", anonUser, val2, val1, 4, null, null, true, true);

        assertEquals(fe1.kind, "feature");
        assertEquals(fe2.kind, "feature");
        assertEquals(de1.kind, "debug");
        assertEquals(de2.kind, "debug");

        assertEquals(fe1.key, "key1");
        assertEquals(fe2.key, "key2");
        assertEquals(de1.key, "key3");
        assertEquals(de2.key, "key4");

        assertEquals(fe1.userKey, "1");
        assertNull(fe2.userKey);
        assertNull(de1.userKey);
        assertNull(de2.userKey);

        assertNull(fe1.user);
        assertEquals(fe2.user, anonUser);
        assertEquals(de1.user, user);
        assertEquals(de2.user, anonUser);

        assertEquals(fe1.value, val1);
        assertEquals(fe2.value, LDValue.ofNull());
        assertEquals(de1.value, val1);
        assertEquals(de2.value, val2);

        assertEquals(fe1.defaultVal, LDValue.ofNull());
        assertEquals(fe2.defaultVal, val2);
        assertEquals(de1.defaultVal, LDValue.ofNull());
        assertEquals(de2.defaultVal, val1);

        assertNull(fe1.reason);
        assertNull(fe2.reason);
        assertNull(de1.reason);
        assertNull(de2.reason);

        assertNull(fe1.version);
        assertEquals((long)fe2.version, 3);
        assertNull(de1.version);
        assertEquals((long)de2.version, 4);

        assertEquals((long)fe1.variation, 5);
        assertNull(fe2.variation);
        assertEquals((long)de1.variation, 3);
        assertNull(de2.variation);

        assertNull(fe1.contextKind);
        assertEquals(fe2.contextKind, "anonymousUser");
        assertNull(de1.contextKind);
        assertNull(de2.contextKind);
    }

    @Test
    public void aliasEventConstructor() {
        LDUser user = new LDUser.Builder("1").email("email@server.net").build();
        LDUser anonUser = new LDUser.Builder("3").email("email@server.net").anonymous(true).build();
        AliasEvent ae = new AliasEvent(user, anonUser);
        assertEquals(ae.kind, "alias");
        assertEquals(ae.key, "1");
        assertEquals(ae.contextKind, "user");
        assertEquals(ae.previousKey, "3");
        assertEquals(ae.previousContextKind, "anonymousUser");
    }

    @Test
    public void testUserObjectRemovedFromCustomEvent() {
        LDUser user = new LDUser.Builder("key1").email("email@server.net").build();

        CustomEvent e1 = new CustomEvent("c1", user, null, null, false);
        CustomEvent e2 = new CustomEvent("c2", user, null, null, true);

        assertEquals(e1.userKey, "key1");
        assertNull(e2.userKey);

        assertNull(e1.user);
        assertEquals(e2.user, user);

        assertNull(e1.contextKind);
        assertNull(e2.contextKind);
    }

    @Test
    public void contextKindInCustomEvent() {
        LDUser user = new LDUser.Builder("1").anonymous(true).build();
        CustomEvent e1 = new CustomEvent("key1", user, null, null, false);
        CustomEvent e2 = new CustomEvent("key2", user, null, null, true);
        assertEquals(e1.contextKind, "anonymousUser");
        assertEquals(e2.contextKind, "anonymousUser");
    }

    @Test
    public void testCustomEventWithoutDataSerialization() {
        CustomEvent event = new CustomEvent("key1", new LDUser.Builder("a").build(), null, null, false);

        LDConfig config = new LDConfig.Builder().build();
        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(event);
        JsonObject eventObject = jsonElement.getAsJsonObject();

        assertEquals(4, eventObject.size());
        assertEquals("custom", eventObject.getAsJsonPrimitive("kind").getAsString());
        assertEquals("key1", eventObject.getAsJsonPrimitive("key").getAsString());
        assertEquals("a", eventObject.getAsJsonPrimitive("userKey").getAsString());
        assertEquals(event.creationDate, eventObject.getAsJsonPrimitive("creationDate").getAsLong(), 0);
    }

    @Test
    public void testCustomEventWithNullValueDataSerialization() {
        CustomEvent event = new CustomEvent("key1", new LDUser.Builder("a").build(), LDValue.ofNull(), null, false);

        LDConfig config = new LDConfig.Builder().build();
        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(event);
        JsonObject eventObject = jsonElement.getAsJsonObject();

        assertEquals(4, eventObject.size());
        assertEquals("custom", eventObject.getAsJsonPrimitive("kind").getAsString());
        assertEquals("key1", eventObject.getAsJsonPrimitive("key").getAsString());
        assertEquals("a", eventObject.getAsJsonPrimitive("userKey").getAsString());
        assertEquals(event.creationDate, eventObject.getAsJsonPrimitive("creationDate").getAsLong(), 0);
    }

    @Test
    public void testCustomEventWithDataSerialization() {
        CustomEvent event = new CustomEvent("key1", new LDUser.Builder("a").build(), LDValue.of("abc"), null, false);

        LDConfig config = new LDConfig.Builder().build();
        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(event);
        JsonObject eventObject = jsonElement.getAsJsonObject();

        assertEquals(5, eventObject.size());
        assertEquals("custom", eventObject.getAsJsonPrimitive("kind").getAsString());
        assertEquals("key1", eventObject.getAsJsonPrimitive("key").getAsString());
        assertEquals("a", eventObject.getAsJsonPrimitive("userKey").getAsString());
        assertEquals("abc", eventObject.getAsJsonPrimitive("data").getAsString());
        assertEquals(event.creationDate, eventObject.getAsJsonPrimitive("creationDate").getAsLong(), 0);
    }

    @Test
    public void testCustomEventWithMetricSerialization() {
        CustomEvent event = new CustomEvent("key1", new LDUser.Builder("a").build(), null, 5.5, false);

        LDConfig config = new LDConfig.Builder().build();
        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(event);
        JsonObject eventObject = jsonElement.getAsJsonObject();

        assertEquals(5, eventObject.size());
        assertEquals("custom", eventObject.getAsJsonPrimitive("kind").getAsString());
        assertEquals("key1", eventObject.getAsJsonPrimitive("key").getAsString());
        assertEquals("a", eventObject.getAsJsonPrimitive("userKey").getAsString());
        assertEquals(5.5, eventObject.getAsJsonPrimitive("metricValue").getAsDouble(), 0);
        assertEquals(event.creationDate, eventObject.getAsJsonPrimitive("creationDate").getAsLong(), 0);
    }

    @Test
    public void testCustomEventWithDataAndMetricSerialization() {
        LDValue objVal = new ObjectBuilder()
                .put("data", LDValue.of(10))
                .build();
        CustomEvent event = new CustomEvent("key1", new LDUser.Builder("a").build(), objVal, -10.0, false);

        LDConfig config = new LDConfig.Builder().build();
        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(event);
        JsonObject eventObject = jsonElement.getAsJsonObject();

        assertEquals(6, eventObject.size());
        assertEquals("custom", eventObject.getAsJsonPrimitive("kind").getAsString());
        assertEquals("key1", eventObject.getAsJsonPrimitive("key").getAsString());
        assertEquals("a", eventObject.getAsJsonPrimitive("userKey").getAsString());
        assertEquals(-10, eventObject.getAsJsonPrimitive("metricValue").getAsDouble(), 0);
        assertEquals(event.creationDate, eventObject.getAsJsonPrimitive("creationDate").getAsLong(), 0);
    }

    @Test
    public void testOptionalFieldsAreExcludedAppropriately() {
        LDUser user = new LDUser.Builder("1").email("email@server.net").build();

        EvaluationReason reason = EvaluationReason.fallthrough();

        FeatureRequestEvent hasVersionEvent = new FeatureRequestEvent("key1", user, LDValue.ofNull(), LDValue.ofNull(), 5, null, null, true, false);
        FeatureRequestEvent hasVariationEvent = new FeatureRequestEvent("key1", user, LDValue.ofNull(), LDValue.ofNull(), -1, 20, null, true, false);
        FeatureRequestEvent hasReasonEvent = new FeatureRequestEvent("key1", user, LDValue.ofNull(), LDValue.ofNull(), 5, 20, reason, true, false);

        assertEquals(5, hasVersionEvent.version, 0.0f);
        assertNull(hasVersionEvent.variation);
        assertNull(hasVersionEvent.reason);

        assertEquals(20, hasVariationEvent.variation, 0);
        assertNull(hasVariationEvent.version);
        assertNull(hasVariationEvent.reason);

        assertEquals(5, hasReasonEvent.version, 0);
        assertEquals(20, hasReasonEvent.variation, 0);
        assertEquals(reason, hasReasonEvent.reason);
    }

    @Test
    public void reasonIsSerialized() {
        LDUser user = new LDUser.Builder("1").email("email@server.net").build();
        EvaluationReason reason = EvaluationReason.fallthrough();

        FeatureRequestEvent hasReasonEvent = new FeatureRequestEvent("key1", user, LDValue.ofNull(), LDValue.ofNull(), 5, 20, reason, true, false);

        LDConfig config = new LDConfig.Builder()
                .build();
        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(hasReasonEvent);

        JsonElement expected = config.getFilteredEventGson().fromJson("{\"kind\":\"FALLTHROUGH\"}", JsonElement.class);
        assertEquals(expected, jsonElement.getAsJsonObject().get("reason"));
    }
}
