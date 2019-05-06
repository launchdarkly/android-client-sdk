package com.launchdarkly.android;

import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.launchdarkly.android.test.TestActivity;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Created by Farhan on 2018-01-04.
 */
@RunWith(AndroidJUnit4.class)
public class EventTest {

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    @Test
    public void testPrivateAttributesAreConcatenated() {

        LDUser.Builder builder = new LDUser.Builder("1")
                .privateAvatar("privateAvatar")
                .privateCustom("privateValue1", "123")
                .custom("Value2", "123")
                .email("email@server.net")
                .lastName("LastName");

        LDUser user = builder.build();

        LDConfig config = new LDConfig.Builder()
                .setPrivateAttributeNames(new HashSet<String>() {
                    {
                        add(LDUser.EMAIL);
                        add("Value2");
                    }
                })
                .build();

        final Event event = new GenericEvent("kind1", "key1", user);

        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(event);
        JsonArray privateAttrs = jsonElement.getAsJsonObject().get("user").getAsJsonObject().getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS);

        assertNotNull(jsonElement);
        assertNotNull(privateAttrs);
        assertEquals(privateAttrs.getAsJsonArray().size(), 4);

        assertTrue(privateAttrs.toString().contains(LDUser.AVATAR));
        assertTrue(privateAttrs.toString().contains("privateValue1"));
        assertTrue(privateAttrs.toString().contains(LDUser.EMAIL));
        assertTrue(privateAttrs.toString().contains("Value2"));
        assertFalse(privateAttrs.toString().contains(LDUser.LAST_NAME));
    }

    @Test
    public void testPrivateAttributes() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .privateAvatar("privateAvatar")
                .custom("value1", "123")
                .email("email@server.net");

        LDUser user = builder.build();

        LDConfig config = new LDConfig.Builder()
                .build();

        final Event event = new GenericEvent("kind1", "key1", user);

        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(event);

        assertTrue(jsonElement.toString().contains("email@server.net"));
        assertFalse(jsonElement.toString().contains("privateAvatar"));
    }

    @Test
    public void testRegularAttributesAreFilteredWithPrivateAttributes() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .avatar("avatarValue")
                .custom("value1", "123")
                .email("email@server.net");

        LDUser user = builder.build();

        LDConfig config = new LDConfig.Builder()
                .setPrivateAttributeNames(new HashSet<String>() {
                    {
                        add(LDUser.AVATAR);
                    }
                })
                .build();

        final Event event = new GenericEvent("kind1", "key1", user);

        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(event);
        JsonArray privateAttrs = jsonElement.getAsJsonObject().get("user").getAsJsonObject().getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS);

        assertNotNull(jsonElement);
        assertEquals("avatarValue", user.getAvatar());
        assertTrue(privateAttrs.toString().contains(LDUser.AVATAR));
        assertFalse(jsonElement.toString().contains("avatarValue"));

    }

    @Test
    public void testPrivateAttributesJsonOnLDUserObject() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .privateEmail("email@server.net")
                .privateAvatar("avatarValue");

        LDUser user = builder.build();

        LDConfig config = new LDConfig.Builder()
                .build();

        final Event event = new GenericEvent("kind1", "key1", user);

        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(event);

        assertNotNull(user);
        JsonObject userEval = (new Gson()).fromJson(user.getJson(), JsonObject.class);
        assertFalse(userEval.has("privateAttrs"));
        JsonArray privateAttrs = jsonElement.getAsJsonObject().getAsJsonObject("user").getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS);
        assertEquals(2, privateAttrs.size());
        assertTrue(privateAttrs.contains(new JsonPrimitive(LDUser.AVATAR)));
        assertTrue(privateAttrs.contains(new JsonPrimitive(LDUser.EMAIL)));
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

        final Event event = new GenericEvent("kind1", "key1", user);

        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(event);
        JsonArray privateAttrs = jsonElement.getAsJsonObject().getAsJsonObject("user").getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS);

        assertNotNull(user);
        assertNotNull(jsonElement);
        assertNotNull(privateAttrs);
        assertEquals(3, privateAttrs.size());

        assertTrue(privateAttrs.contains(new JsonPrimitive(LDUser.AVATAR)));
        assertTrue(privateAttrs.contains(new JsonPrimitive(LDUser.EMAIL)));
        assertTrue(privateAttrs.contains(new JsonPrimitive("value1")));
    }

    @Test
    public void testKeyAndAnonymousAreNotFilteredWithAllAttributesPrivate() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .email("email@server.net")
                .anonymous(true);

        LDUser user = builder.build();

        LDConfig config = new LDConfig.Builder()
                .allAttributesPrivate()
                .build();

        final Event event = new GenericEvent("kind1", "key1", user);

        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(event);
        JsonArray privateAttrs = jsonElement.getAsJsonObject().getAsJsonObject("user").getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS);

        assertNotNull(user);
        assertNotNull(jsonElement);
        assertNotNull(privateAttrs);
        assertEquals(1, privateAttrs.getAsJsonArray().size());

        assertTrue(jsonElement.toString().contains("key"));
        assertTrue(jsonElement.toString().contains("anonymous"));
    }

    @Test
    public void testUserObjectRemovedFromFeatureEvent() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .email("email@server.net");

        LDUser user = builder.build();

        final FeatureRequestEvent event = new FeatureRequestEvent("key1", user.getKey(), JsonNull.INSTANCE, JsonNull.INSTANCE, -1, -1, null);

        assertNull(event.user);
        assertEquals(user.getKey(), event.userKey);
    }

    @Test
    public void testFullUserObjectIncludedInFeatureEvent() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .email("email@server.net");

        LDUser user = builder.build();

        final FeatureRequestEvent event = new FeatureRequestEvent("key1", user, JsonNull.INSTANCE, JsonNull.INSTANCE, -1, -1, null);

        assertEquals(user, event.user);
        assertNull(event.userKey);
    }

    @Test
    public void testUserObjectRemovedFromCustomEvent() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .email("email@server.net");

        LDUser user = builder.build();

        final CustomEvent event = new CustomEvent("key1", user.getKey(), null);

        assertNull(event.user);
        assertEquals(user.getKey(), event.userKey);
    }

    @Test
    public void testFullUserObjectIncludedInCustomEvent() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .email("email@server.net");

        LDUser user = builder.build();

        final CustomEvent event = new CustomEvent("key1", user, null);

        assertEquals(user, event.user);
        assertNull(event.userKey);
    }

    @Test
    public void testOptionalFieldsAreExcludedAppropriately() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .email("email@server.net");

        LDUser user = builder.build();

        final EvaluationReason reason = EvaluationReason.fallthrough();

        final FeatureRequestEvent hasVersionEvent = new FeatureRequestEvent("key1", user, JsonNull.INSTANCE, JsonNull.INSTANCE, 5, null, null);
        final FeatureRequestEvent hasVariationEvent = new FeatureRequestEvent("key1", user, JsonNull.INSTANCE, JsonNull.INSTANCE, -1, 20, null);
        final FeatureRequestEvent hasReasonEvent = new FeatureRequestEvent("key1", user, JsonNull.INSTANCE, JsonNull.INSTANCE, 5, 20, reason);

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
        LDUser.Builder builder = new LDUser.Builder("1")
                .email("email@server.net");
        LDUser user = builder.build();
        final EvaluationReason reason = EvaluationReason.fallthrough();

        final FeatureRequestEvent hasReasonEvent = new FeatureRequestEvent("key1", user, JsonNull.INSTANCE, JsonNull.INSTANCE, 5, 20, reason);

        LDConfig config = new LDConfig.Builder()
                .build();
        JsonElement jsonElement = config.getFilteredEventGson().toJsonTree(hasReasonEvent);

        JsonElement expected = config.getFilteredEventGson().fromJson("{\"kind\":\"FALLTHROUGH\"}", JsonElement.class);
        assertEquals(expected, jsonElement.getAsJsonObject().get("reason"));
    }
}
