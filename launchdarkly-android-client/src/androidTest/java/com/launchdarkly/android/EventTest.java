package com.launchdarkly.android;

import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.launchdarkly.android.test.TestActivity;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;

/**
 * Created by Farhan on 2018-01-04.
 */
@RunWith(AndroidJUnit4.class)
public class EventTest {

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

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

        Assert.assertNotNull(jsonElement);
        Assert.assertNotNull(privateAttrs);
        Assert.assertEquals(privateAttrs.getAsJsonArray().size(), 4);

        Assert.assertTrue(privateAttrs.toString().contains(LDUser.AVATAR));
        Assert.assertTrue(privateAttrs.toString().contains("privateValue1"));
        Assert.assertTrue(privateAttrs.toString().contains(LDUser.EMAIL));
        Assert.assertTrue(privateAttrs.toString().contains("Value2"));
        Assert.assertFalse(privateAttrs.toString().contains(LDUser.LAST_NAME));
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

        Assert.assertTrue(jsonElement.toString().contains("email@server.net"));
        Assert.assertFalse(jsonElement.toString().contains("privateAvatar"));
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

        Assert.assertNotNull(jsonElement);
        Assert.assertTrue(user.getAvatar().getAsString().equals("avatarValue"));
        Assert.assertTrue(privateAttrs.toString().contains(LDUser.AVATAR));
        Assert.assertFalse(jsonElement.toString().contains("avatarValue"));

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

        Assert.assertNotNull(user);
        Assert.assertFalse(user.getJson().contains("\"privateAttrs\":[\"avatar\",\"email\"]"));
        Assert.assertTrue(jsonElement.toString().contains("\"privateAttrs\":[\"avatar\",\"email\"]"));
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
        JsonArray privateAttrs = jsonElement.getAsJsonObject().get("user").getAsJsonObject().getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS);

        Assert.assertNotNull(user);
        Assert.assertNotNull(jsonElement);
        Assert.assertNotNull(privateAttrs);
        Assert.assertEquals(privateAttrs.getAsJsonArray().size(), 3);

        Assert.assertTrue(privateAttrs.toString().contains(LDUser.AVATAR));
        Assert.assertTrue(privateAttrs.toString().contains(LDUser.EMAIL));
        Assert.assertTrue(privateAttrs.toString().contains("value1"));
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
        JsonArray privateAttrs = jsonElement.getAsJsonObject().get("user").getAsJsonObject().getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS);

        Assert.assertNotNull(user);
        Assert.assertNotNull(jsonElement);
        Assert.assertNotNull(privateAttrs);
        Assert.assertEquals(privateAttrs.getAsJsonArray().size(), 1);

        Assert.assertTrue(jsonElement.toString().contains("key"));
        Assert.assertTrue(jsonElement.toString().contains("anonymous"));
    }

    @Test
    public void testUserObjectRemovedFromFeatureEvent() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .email("email@server.net");

        LDUser user = builder.build();

        final FeatureRequestEvent event = new FeatureRequestEvent("key1", user.getKeyAsString(), JsonNull.INSTANCE, JsonNull.INSTANCE, -1, -1);

        Assert.assertNull(event.user);
        Assert.assertEquals(user.getKeyAsString(), event.userKey);
    }

    @Test
    public void testFullUserObjectIncludedInFeatureEvent() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .email("email@server.net");

        LDUser user = builder.build();

        final FeatureRequestEvent event = new FeatureRequestEvent("key1", user, JsonNull.INSTANCE, JsonNull.INSTANCE, -1, -1);

        Assert.assertEquals(user, event.user);
        Assert.assertNull(event.userKey);
    }

    @Test
    public void testUserObjectRemovedFromCustomEvent() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .email("email@server.net");

        LDUser user = builder.build();

        final CustomEvent event = new CustomEvent("key1", user.getKeyAsString(), null);

        Assert.assertNull(event.user);
        Assert.assertEquals(user.getKeyAsString(), event.userKey);
    }

    @Test
    public void testFullUserObjectIncludedInCustomEvent() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .email("email@server.net");

        LDUser user = builder.build();

        final CustomEvent event = new CustomEvent("key1", user, null);

        Assert.assertEquals(user, event.user);
        Assert.assertNull(event.userKey);
    }

    @Test
    public void testOptionalFieldsAreExcludedAppropriately() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .email("email@server.net");

        LDUser user = builder.build();

        final FeatureRequestEvent hasVersionEvent = new FeatureRequestEvent("key1", user, JsonNull.INSTANCE, JsonNull.INSTANCE, 5, null);
        final FeatureRequestEvent hasVariationEvent = new FeatureRequestEvent("key1", user, JsonNull.INSTANCE, JsonNull.INSTANCE, -1, 20);

        Assert.assertEquals(5, hasVersionEvent.version, 0.0f);
        Assert.assertNull(hasVersionEvent.variation);

        Assert.assertEquals(20, hasVariationEvent.variation, 0);
        Assert.assertNull(hasVariationEvent.version);

    }


}
