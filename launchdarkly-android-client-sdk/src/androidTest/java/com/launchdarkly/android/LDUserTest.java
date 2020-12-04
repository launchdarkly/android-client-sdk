package com.launchdarkly.android;

import android.support.test.runner.AndroidJUnit4;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.launchdarkly.android.value.LDValue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class LDUserTest {

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    @Test
    public void testBasicFields() {
        LDUser.Builder builder = new LDUser.Builder("a")
                .anonymous(true)
                .avatar("theAvatar")
                .country("US")
                .email("foo@mail.co")
                .firstName("tester")
                .lastName("one")
                .name("tester one")
                .ip("1.1.1.1")
                .secondary("b")
                .custom("ckeystring", "cvaluestring")
                .custom("ckeynum", 7.3)
                .custom("ckeybool", false)
                .customNumber("ckeynumlist", Arrays.<Number>asList(1, 2, 3))
                .customString("ckeystringlist", Arrays.asList("abc", "def"));

        LDUser ldUser = builder.build();
        assertEquals("a", ldUser.getKey());
        assertTrue(ldUser.getAnonymous());
        assertEquals("theAvatar", ldUser.getAvatar());
        assertEquals("US", ldUser.getCountry());
        assertEquals("foo@mail.co", ldUser.getEmail());
        assertEquals("tester", ldUser.getFirstName());
        assertEquals("one", ldUser.getLastName());
        assertEquals("tester one", ldUser.getName());
        assertEquals("1.1.1.1", ldUser.getIp());
        assertEquals("b", ldUser.getSecondary());
        assertEquals(LDValue.of("cvaluestring"), ldUser.getCustom("ckeystring"));
        assertEquals(LDValue.of(7.3), ldUser.getCustom("ckeynum"));
        assertEquals(LDValue.of(false), ldUser.getCustom("ckeybool"));
        assertEquals(3, ldUser.getCustom("ckeynumlist").size());
        assertEquals(LDValue.of(1), ldUser.getCustom("ckeynumlist").get(0));
        assertEquals(LDValue.of(2), ldUser.getCustom("ckeynumlist").get(1));
        assertEquals(LDValue.of(3), ldUser.getCustom("ckeynumlist").get(2));
        assertEquals(2, ldUser.getCustom("ckeystringlist").size());
        assertEquals(LDValue.of("abc"), ldUser.getCustom("ckeystringlist").get(0));
        assertEquals(LDValue.of("def"), ldUser.getCustom("ckeystringlist").get(1));

        assertEquals(0, ldUser.getPrivateAttributeNames().size());

        JsonObject jsonUser = (new Gson()).fromJson(ldUser.getJson(), JsonObject.class);
        assertEquals("a", jsonUser.getAsJsonPrimitive("key").getAsString());
        assertTrue(jsonUser.getAsJsonPrimitive("anonymous").getAsBoolean());
        assertEquals("theAvatar", jsonUser.getAsJsonPrimitive(LDUser.AVATAR).getAsString());
        assertEquals("US", jsonUser.get(LDUser.COUNTRY).getAsString());
        assertEquals("foo@mail.co", jsonUser.getAsJsonPrimitive(LDUser.EMAIL).getAsString());
        assertEquals("tester", jsonUser.getAsJsonPrimitive(LDUser.FIRST_NAME).getAsString());
        assertEquals("one", jsonUser.getAsJsonPrimitive(LDUser.LAST_NAME).getAsString());
        assertEquals("tester one", jsonUser.getAsJsonPrimitive(LDUser.NAME).getAsString());
        assertEquals("1.1.1.1", jsonUser.getAsJsonPrimitive(LDUser.IP).getAsString());
        assertEquals("b", jsonUser.getAsJsonPrimitive(LDUser.SECONDARY).getAsString());

        LDConfig ldConfig = new LDConfig.Builder().build();
        JsonObject eventJson = ldConfig.getFilteredEventGson().toJsonTree(ldUser).getAsJsonObject();
        assertEquals(11, eventJson.size());
        assertEquals("a", eventJson.getAsJsonPrimitive("key").getAsString());
        assertTrue(eventJson.getAsJsonPrimitive("anonymous").getAsBoolean());
        assertEquals("theAvatar", eventJson.getAsJsonPrimitive(LDUser.AVATAR).getAsString());
        assertEquals("US", eventJson.getAsJsonPrimitive(LDUser.COUNTRY).getAsString());
        assertEquals("foo@mail.co", eventJson.getAsJsonPrimitive(LDUser.EMAIL).getAsString());
        assertEquals("tester", eventJson.getAsJsonPrimitive(LDUser.FIRST_NAME).getAsString());
        assertEquals("one", eventJson.getAsJsonPrimitive(LDUser.LAST_NAME).getAsString());
        assertEquals("tester one", eventJson.getAsJsonPrimitive(LDUser.NAME).getAsString());
        assertEquals("1.1.1.1", eventJson.getAsJsonPrimitive(LDUser.IP).getAsString());
        assertEquals("b", eventJson.getAsJsonPrimitive(LDUser.SECONDARY).getAsString());
        JsonObject eventCustom = eventJson.getAsJsonObject("custom").getAsJsonObject();
        assertEquals(7, eventCustom.size());
        assertEquals(new JsonPrimitive("cvaluestring"), eventCustom.getAsJsonPrimitive("ckeystring"));
        assertEquals(new JsonPrimitive(7.3), eventCustom.getAsJsonPrimitive("ckeynum"));
        assertEquals(new JsonPrimitive(false), eventCustom.getAsJsonPrimitive("ckeybool"));
        assertEquals(3, eventCustom.getAsJsonArray("ckeynumlist").size());
        assertTrue(eventCustom.getAsJsonArray("ckeynumlist").contains(new JsonPrimitive(1)));
        assertTrue(eventCustom.getAsJsonArray("ckeynumlist").contains(new JsonPrimitive(2)));
        assertTrue(eventCustom.getAsJsonArray("ckeynumlist").contains(new JsonPrimitive(3)));
        assertEquals(2, eventCustom.getAsJsonArray("ckeystringlist").size());
        assertTrue(eventCustom.getAsJsonArray("ckeystringlist").contains(new JsonPrimitive("abc")));
        assertTrue(eventCustom.getAsJsonArray("ckeystringlist").contains(new JsonPrimitive("def")));
    }

    @Test
    public void testBasicFieldsPrivate() {
        LDUser.Builder builder = new LDUser.Builder("a")
                .anonymous(true)
                .privateAvatar("theAvatar")
                .privateCountry("US")
                .privateEmail("foo@mail.co")
                .privateFirstName("tester")
                .privateLastName("one")
                .privateName("tester one")
                .privateIp("1.1.1.1")
                .privateSecondary("b")
                .privateCustom("ckeystring", "cvaluestring")
                .privateCustom("ckeynum", 7.3)
                .privateCustom("ckeybool", false)
                .privateCustomNumber("ckeynumlist", Arrays.<Number>asList(1, 2, 3))
                .privateCustomString("ckeystringlist", Arrays.asList("abc", "def"));

        LDUser ldUser = builder.build();
        assertEquals("a", ldUser.getKey());
        assertTrue(ldUser.getAnonymous());
        assertEquals("theAvatar", ldUser.getAvatar());
        assertEquals("US", ldUser.getCountry());
        assertEquals("foo@mail.co", ldUser.getEmail());
        assertEquals("tester", ldUser.getFirstName());
        assertEquals("one", ldUser.getLastName());
        assertEquals("tester one", ldUser.getName());
        assertEquals("1.1.1.1", ldUser.getIp());
        assertEquals("b", ldUser.getSecondary());
        assertEquals(LDValue.of("cvaluestring"), ldUser.getCustom("ckeystring"));
        assertEquals(LDValue.of(7.3), ldUser.getCustom("ckeynum"));
        assertEquals(LDValue.of(false), ldUser.getCustom("ckeybool"));
        assertEquals(3, ldUser.getCustom("ckeynumlist").size());
        assertEquals(LDValue.of(1), ldUser.getCustom("ckeynumlist").get(0));
        assertEquals(LDValue.of(2), ldUser.getCustom("ckeynumlist").get(1));
        assertEquals(LDValue.of(3), ldUser.getCustom("ckeynumlist").get(2));
        assertEquals(2, ldUser.getCustom("ckeystringlist").size());
        assertEquals(LDValue.of("abc"), ldUser.getCustom("ckeystringlist").get(0));
        assertEquals(LDValue.of("def"), ldUser.getCustom("ckeystringlist").get(1));

        assertEquals(13, ldUser.getPrivateAttributeNames().size());
        assertTrue(ldUser.getPrivateAttributeNames().contains(LDUser.AVATAR));
        assertTrue(ldUser.getPrivateAttributeNames().contains(LDUser.COUNTRY));
        assertTrue(ldUser.getPrivateAttributeNames().contains(LDUser.EMAIL));
        assertTrue(ldUser.getPrivateAttributeNames().contains(LDUser.FIRST_NAME));
        assertTrue(ldUser.getPrivateAttributeNames().contains(LDUser.LAST_NAME));
        assertTrue(ldUser.getPrivateAttributeNames().contains(LDUser.NAME));
        assertTrue(ldUser.getPrivateAttributeNames().contains(LDUser.IP));
        assertTrue(ldUser.getPrivateAttributeNames().contains(LDUser.SECONDARY));
        assertTrue(ldUser.getPrivateAttributeNames().contains("ckeystring"));
        assertTrue(ldUser.getPrivateAttributeNames().contains("ckeynum"));
        assertTrue(ldUser.getPrivateAttributeNames().contains("ckeybool"));
        assertTrue(ldUser.getPrivateAttributeNames().contains("ckeynumlist"));
        assertTrue(ldUser.getPrivateAttributeNames().contains("ckeystringlist"));

        JsonObject jsonUser = (new Gson()).fromJson(ldUser.getJson(), JsonObject.class);
        assertEquals("a", jsonUser.getAsJsonPrimitive("key").getAsString());
        assertTrue(jsonUser.getAsJsonPrimitive("anonymous").getAsBoolean());
        assertEquals("theAvatar", jsonUser.getAsJsonPrimitive(LDUser.AVATAR).getAsString());
        assertEquals("US", jsonUser.get(LDUser.COUNTRY).getAsString());
        assertEquals("foo@mail.co", jsonUser.getAsJsonPrimitive(LDUser.EMAIL).getAsString());
        assertEquals("tester", jsonUser.getAsJsonPrimitive(LDUser.FIRST_NAME).getAsString());
        assertEquals("one", jsonUser.getAsJsonPrimitive(LDUser.LAST_NAME).getAsString());
        assertEquals("tester one", jsonUser.getAsJsonPrimitive(LDUser.NAME).getAsString());
        assertEquals("1.1.1.1", jsonUser.getAsJsonPrimitive(LDUser.IP).getAsString());
        assertEquals("b", jsonUser.getAsJsonPrimitive(LDUser.SECONDARY).getAsString());

        LDConfig ldConfig = new LDConfig.Builder().build();
        JsonObject eventJson = ldConfig.getFilteredEventGson().toJsonTree(ldUser).getAsJsonObject();
        assertEquals(4, eventJson.size());
        assertEquals("a", eventJson.getAsJsonPrimitive("key").getAsString());
        assertTrue(eventJson.getAsJsonPrimitive("anonymous").getAsBoolean());
        assertEquals(13, eventJson.getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS).size());
        assertTrue(eventJson.getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS).contains(new JsonPrimitive(LDUser.AVATAR)));
        assertTrue(eventJson.getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS).contains(new JsonPrimitive(LDUser.COUNTRY)));
        assertTrue(eventJson.getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS).contains(new JsonPrimitive(LDUser.EMAIL)));
        assertTrue(eventJson.getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS).contains(new JsonPrimitive(LDUser.FIRST_NAME)));
        assertTrue(eventJson.getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS).contains(new JsonPrimitive(LDUser.LAST_NAME)));
        assertTrue(eventJson.getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS).contains(new JsonPrimitive(LDUser.NAME)));
        assertTrue(eventJson.getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS).contains(new JsonPrimitive(LDUser.IP)));
        assertTrue(eventJson.getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS).contains(new JsonPrimitive(LDUser.SECONDARY)));
        assertTrue(eventJson.getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS).contains(new JsonPrimitive("ckeystring")));
        assertTrue(eventJson.getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS).contains(new JsonPrimitive("ckeynum")));
        assertTrue(eventJson.getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS).contains(new JsonPrimitive("ckeybool")));
        assertTrue(eventJson.getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS).contains(new JsonPrimitive("ckeynumlist")));
        assertTrue(eventJson.getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS).contains(new JsonPrimitive("ckeystringlist")));
    }

    @Test
    public void testPrivateAttributesAreAddedToTheList() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .privateAvatar("privateAvatar")
                .privateCustom("privateValue1", "123")
                .email("email@server.net");

        LDUser ldUser = builder.build();

        assertNotNull(ldUser);
        assertEquals(2, ldUser.getPrivateAttributeNames().size());
        assertTrue(ldUser.getPrivateAttributeNames().contains(LDUser.AVATAR));
        assertFalse(ldUser.getPrivateAttributeNames().contains(LDUser.EMAIL));
        assertTrue(ldUser.getPrivateAttributeNames().contains("privateValue1"));
    }


    @Test
    public void testBuilderCustomWhenPrivateAttributesProvided() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .custom("k1", "v1")
                .privateCustom("k2", "v2")
                .custom("k3", "v3");

        Set<String> privateAttributeNames = builder.getPrivateAttributeNames();

        assertNotNull(privateAttributeNames);
        assertFalse(privateAttributeNames.contains("k1"));
        assertTrue(privateAttributeNames.contains("k2"));
        assertFalse(privateAttributeNames.contains("k3"));
    }

    @Test
    public void testBuilderCustomWhenPrivateAttributesNotProvided() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .custom("k1", "v1")
                .custom("k2", "v2");

        Set<String> privateAttributeNames = builder.getPrivateAttributeNames();

        assertNotNull(privateAttributeNames);
        assertEquals(0, privateAttributeNames.size());
    }

    @Test
    public void testModifyExistingUserPrivateAttributes() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .custom("k1", "v1");
        LDUser user = builder.build();
        LDUser.Builder existingUserBuilder = new LDUser.Builder(user);

        // An UnsupportedOperationException was previously being thrown in this case
        existingUserBuilder.custom("k2", "v2");
    }

    @Test
    public void testLDUserPrivateAttributesAdapter() {
        LDUser.Builder builder = new LDUser.Builder("1")
                .privateAvatar("privateAvatar")
                .privateCustom("privateValue1", "123")
                .email("email@server.net");
        LDUser user = builder.build();
        LDConfig ldConfig = new LDConfig.Builder().build();
        JsonElement element = ldConfig.getFilteredEventGson().toJsonTree(user);
        JsonArray privateAttrs = element.getAsJsonObject().getAsJsonArray(LDUser.LDUserPrivateAttributesTypeAdapter.PRIVATE_ATTRS);
        assertEquals(2, privateAttrs.size());
        assertTrue(privateAttrs.contains(new JsonPrimitive(LDUser.AVATAR)));
        assertTrue(privateAttrs.contains(new JsonPrimitive("privateValue1")));
    }

    @Test
    public void nullCustomValuesIgnoredInArrays() {
        LDUser user = new LDUser.Builder("1")
                .customNumber("nums", Arrays.<Number>asList(5.5, null, -2))
                .customString("strs", Arrays.asList("abc", null, "def"))
                .build();
        LDValue expectedNumArray = LDValue.buildArray().add(5.5).add(-2).build();
        LDValue expectedStrArray = LDValue.buildArray().add("abc").add("def").build();
        assertEquals(user.getCustom("nums"), expectedNumArray);
        assertEquals(user.getCustom("strs"), expectedStrArray);
    }
}
