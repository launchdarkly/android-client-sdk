package com.launchdarkly.android;

import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.JsonObject;
import com.launchdarkly.android.flagstore.Flag;
import com.launchdarkly.android.flagstore.FlagStore;
import com.launchdarkly.android.test.TestActivity;

import org.easymock.EasyMockRule;
import org.easymock.EasyMockSupport;
import org.easymock.Mock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.reset;

@RunWith(AndroidJUnit4.class)
public class UserManagerTest extends EasyMockSupport {

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    @Rule
    public EasyMockRule easyMockRule = new EasyMockRule(this);

    @SuppressWarnings("unused")
    @Mock
    private FeatureFlagFetcher fetcher;

    private UserManager userManager;

    @Before
    public void before() {
        userManager = new UserManager(activityTestRule.getActivity().getApplication(), fetcher, "test", "test");
    }

    @Test
    public void testFailedFetchThrowsException() throws InterruptedException {
        setUserAndFailToFetchFlags("userKey");
    }

    private void addSimpleFlag(JsonObject jsonObject, String flagKey, String value) {
        JsonObject flagBody = new JsonObject();
        flagBody.addProperty("value", value);
        jsonObject.add(flagKey, flagBody);
    }

    private void addSimpleFlag(JsonObject jsonObject, String flagKey, boolean value) {
        JsonObject flagBody = new JsonObject();
        flagBody.addProperty("value", value);
        jsonObject.add(flagKey, flagBody);
    }

    private void addSimpleFlag(JsonObject jsonObject, String flagKey, Number value) {
        JsonObject flagBody = new JsonObject();
        flagBody.addProperty("value", value);
        jsonObject.add(flagKey, flagBody);
    }

    @Test
    public void testBasicRetrieval() throws ExecutionException, InterruptedException {
        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        addSimpleFlag(jsonObject, "boolFlag1", true);
        addSimpleFlag(jsonObject, "stringFlag1", expectedStringFlagValue);

        Future<Void> future = setUser("userKey", jsonObject);
        future.get();

        FlagStore flagStore = userManager.getCurrentUserFlagStore();
        assertEquals(2, flagStore.getAllFlags().size());
        assertEquals(true, flagStore.getFlag("boolFlag1").getValue().getAsBoolean());
        assertEquals(expectedStringFlagValue, flagStore.getFlag("stringFlag1").getValue().getAsString());
    }

    @Test
    public void testNewUserUpdatesFlags() {
        JsonObject flags = new JsonObject();

        String flagKey = "stringFlag";
        addSimpleFlag(flags, flagKey, "user1");

        setUser("user1", flags);
        assertFlagValue(flagKey, "user1");

        addSimpleFlag(flags, flagKey, "user2");

        setUser("user2", flags);
        assertFlagValue(flagKey, "user2");
    }

    @Test
    public void testCanStoreExactly5Users() throws InterruptedException {
        JsonObject flags = new JsonObject();
        String flagKey = "stringFlag";

        String user1 = "user1";
        String user5 = "user5";
        List<String> users = Arrays.asList(user1, "user2", "user3", "user4", user5, "user6");

        for (String user : users) {
            addSimpleFlag(flags, flagKey, user);
            setUser(user, flags);
            assertFlagValue(flagKey, user);
        }

        //we now have 5 users in SharedPreferences. The very first one we added shouldn't be saved anymore.
        setUserAndFailToFetchFlags(user1);
        assertNull(userManager.getCurrentUserFlagStore().getFlag(flagKey));

        // user5 should still be saved:
        setUserAndFailToFetchFlags(user5);
        assertFlagValue(flagKey, user5);
    }

    @Test
    public void testRegisterUnregisterListener() {
        FeatureFlagChangeListener listener = new FeatureFlagChangeListener() {
            @Override
            public void onFeatureFlagChange(String flagKey) {
            }
        };

        userManager.registerListener("key", listener);
        Collection<FeatureFlagChangeListener> listeners = userManager.getListenersByKey("key");
        assertNotNull(listeners);
        assertFalse(listeners.isEmpty());

        userManager.unregisterListener("key", listener);
        listeners = userManager.getListenersByKey("key");
        assertNotNull(listeners);
        assertTrue(listeners.isEmpty());
    }

    @Test
    public void testUnregisterListenerWithDuplicates() {
        FeatureFlagChangeListener listener = new FeatureFlagChangeListener() {
            @Override
            public void onFeatureFlagChange(String flagKey) {
            }
        };

        userManager.registerListener("key", listener);
        userManager.registerListener("key", listener);
        userManager.unregisterListener("key", listener);

        Collection<FeatureFlagChangeListener> listeners = userManager.getListenersByKey("key");
        assertNotNull(listeners);
        assertTrue(listeners.isEmpty());
    }

    @Test
    public void testDeleteFlag() throws ExecutionException, InterruptedException {
        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        addSimpleFlag(jsonObject, "boolFlag1", true);
        addSimpleFlag(jsonObject, "stringFlag1", expectedStringFlagValue);

        Future<Void> future = setUserClear("userKey", jsonObject);
        future.get();

        FlagStore flagStore = userManager.getCurrentUserFlagStore();
        assertEquals(2, flagStore.getAllFlags().size());
        assertEquals(true, flagStore.getFlag("boolFlag1").getValue().getAsBoolean());
        assertEquals(expectedStringFlagValue, flagStore.getFlag("stringFlag1").getValue().getAsString());

        userManager.deleteCurrentUserFlag("{\"key\":\"stringFlag1\",\"version\":16}").get();
        assertNull(flagStore.getFlag("stringFlag1"));
        assertEquals(true, flagStore.getFlag("boolFlag1").getValue().getAsBoolean());

        userManager.deleteCurrentUserFlag("{\"key\":\"nonExistentFlag\",\"version\":16,\"value\":false}").get();
    }

    @Test
    public void testDeleteForInvalidResponse() throws ExecutionException, InterruptedException {
        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        addSimpleFlag(jsonObject, "boolFlag1", true);
        addSimpleFlag(jsonObject, "stringFlag1", expectedStringFlagValue);

        Future<Void> future = setUser("userKey", jsonObject);
        future.get();

        userManager.deleteCurrentUserFlag("{}").get();

        //noinspection ConstantConditions
        userManager.deleteCurrentUserFlag(null).get();

        userManager.deleteCurrentUserFlag("abcd").get();
    }

    @Test
    public void testDeleteWithVersion() throws ExecutionException, InterruptedException {
        Future<Void> future = setUserClear("userKey", new JsonObject());
        future.get();

        String json = "{\n" +
                "  \"stringFlag1\": {\n" +
                "    \"key\": \"\",\n" +
                "    \"version\": 125,\n" +
                "    \"value\": \"string1\"\n" +
                "  }\n" +
                " }";

        userManager.putCurrentUserFlags(json).get();

        userManager.deleteCurrentUserFlag("{\"key\":\"stringFlag1\",\"version\":16}").get();
        FlagStore flagStore = userManager.getCurrentUserFlagStore();
        assertEquals("string1", flagStore.getFlag("stringFlag1").getValue().getAsString());

        userManager.deleteCurrentUserFlag("{\"key\":\"stringFlag1\",\"version\":127}").get();
        assertNull(flagStore.getFlag("stringFlag1"));

        userManager.deleteCurrentUserFlag("{\"key\":\"nonExistent\",\"version\":1}").get();
    }

    @Test
    public void testPatchForAddAndReplaceFlags() throws ExecutionException, InterruptedException {
        JsonObject jsonObject = new JsonObject();
        addSimpleFlag(jsonObject, "boolFlag1", true);
        addSimpleFlag(jsonObject, "stringFlag1", "string1");
        addSimpleFlag(jsonObject, "floatFlag1", 3.0f);

        Future<Void> future = setUserClear("userKey", jsonObject);
        future.get();

        userManager.patchCurrentUserFlags("{\"key\":\"new-flag\",\"version\":16,\"value\":false}").get();

        FlagStore flagStore = userManager.getCurrentUserFlagStore();
        assertEquals(false, flagStore.getFlag("new-flag").getValue().getAsBoolean());


        userManager.patchCurrentUserFlags("{\"key\":\"stringFlag1\",\"version\":16,\"value\":\"string2\"}").get();
        assertEquals("string2", flagStore.getFlag("stringFlag1").getValue().getAsString());

        userManager.patchCurrentUserFlags("{\"key\":\"boolFlag1\",\"version\":16,\"value\":false}").get();
        assertEquals(false, flagStore.getFlag("boolFlag1").getValue().getAsBoolean());

        assertEquals(3.0f, flagStore.getFlag("floatFlag1").getValue().getAsFloat());

        userManager.patchCurrentUserFlags("{\"key\":\"floatFlag2\",\"version\":16,\"value\":8.0}").get();
        assertEquals(8.0f, flagStore.getFlag("floatFlag2").getValue().getAsFloat());
    }

    @Test
    public void testPatchSucceedsForMissingVersionInPatch() throws ExecutionException, InterruptedException {
        Future<Void> future = setUserClear("userKey", new JsonObject());
        future.get();

        FlagStore flagStore = userManager.getCurrentUserFlagStore();

        // version does not exist in shared preferences and patch.
        // ---------------------------
        //// case 1: value does not exist in shared preferences.
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"value\":\"value-from-patch\"}").get();
        Flag flag1 = flagStore.getFlag("flag1");
        assertEquals("value-from-patch", flag1.getValue().getAsString());
        assertNull(flag1.getVersion());

        //// case 2: value exists in shared preferences without version.
        userManager.putCurrentUserFlags("{\"flag1\": {\"value\": \"value1\"}}");
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"value\":\"value-from-patch\"}").get();
        flag1 = flagStore.getFlag("flag1");
        assertEquals("value-from-patch", flag1.getValue().getAsString());
        assertNull(flag1.getVersion());

        // version does not exist in shared preferences but exists in patch.
        // ---------------------------
        //// case 1: value does not exist in shared preferences.
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"version\":558,\"flagVersion\":3,\"value\":\"value-from-patch\",\"variation\":1,\"trackEvents\":false}").get();
        flag1 = flagStore.getFlag("flag1");
        assertEquals("value-from-patch", flag1.getValue().getAsString());
        assertEquals(558, (int) flag1.getVersion());
        assertEquals(3, (int) flag1.getFlagVersion());
        assertEquals(3, flag1.getVersionForEvents());

        //// case 2: value exists in shared preferences without version.
        userManager.putCurrentUserFlags("{\"flag1\": {\"value\": \"value1\"}}");
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"version\":558,\"flagVersion\":3,\"value\":\"value-from-patch\",\"variation\":1,\"trackEvents\":false}").get();
        flag1 = flagStore.getFlag("flag1");
        assertEquals("value-from-patch", flag1.getValue().getAsString());
        assertEquals(558, (int) flag1.getVersion());
        assertEquals(3, (int) flag1.getFlagVersion());
        assertEquals(3, flag1.getVersionForEvents());

        // version exists in shared preferences but does not exist in patch.
        // ---------------------------
        userManager.putCurrentUserFlags("{\"flag1\": {\"version\": 558, \"flagVersion\": 110,\"value\": \"value1\", \"variation\": 1, \"trackEvents\": false}}");
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"value\":\"value-from-patch\"}").get();
        flag1 = flagStore.getFlag("flag1");
        assertEquals("value-from-patch", flag1.getValue().getAsString());
        assertNull(flag1.getVersion());
        assertNull(flag1.getFlagVersion());
        assertEquals(-1, flag1.getVersionForEvents());

        // version exists in shared preferences and patch.
        // ---------------------------
        userManager.putCurrentUserFlags("{\"flag1\": {\"version\": 558, \"flagVersion\": 110,\"value\": \"value1\", \"variation\": 1, \"trackEvents\": false}}");
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"version\":559,\"flagVersion\":3,\"value\":\"value-from-patch\",\"variation\":1,\"trackEvents\":false}").get();
        flag1 = flagStore.getFlag("flag1");
        assertEquals("value-from-patch", flag1.getValue().getAsString());
        assertEquals(559, (int) flag1.getVersion());
        assertEquals(3, (int) flag1.getFlagVersion());
        assertEquals(3, flag1.getVersionForEvents());
    }

    @Test
    public void testPatchWithVersion() throws ExecutionException, InterruptedException {
        Future<Void> future = setUserClear("userKey", new JsonObject());
        future.get();

        String json = "{\n" +
                "  \"stringFlag1\": {\n" +
                "    \"key\": \"\",\n" +
                "    \"version\": 125,\n" +
                "    \"value\": \"string1\"\n" +
                "  }\n" +
                " }";

        userManager.putCurrentUserFlags(json).get();


        userManager.patchCurrentUserFlags("{\"key\":\"stringFlag1\",\"version\":16,\"value\":\"string2\"}").get();
        FlagStore flagStore = userManager.getCurrentUserFlagStore();
        Flag stringFlag1 = flagStore.getFlag("stringFlag1");
        assertEquals("string1", stringFlag1.getValue().getAsString());
        assertNull(stringFlag1.getFlagVersion());
        assertEquals(125, stringFlag1.getVersionForEvents());

        userManager.patchCurrentUserFlags("{\"key\":\"stringFlag1\",\"version\":126,\"value\":\"string2\"}").get();
        stringFlag1 = flagStore.getFlag("stringFlag1");
        assertEquals("string2", stringFlag1.getValue().getAsString());
        assertEquals(126, (int) stringFlag1.getVersion());
        assertNull(stringFlag1.getFlagVersion());
        assertEquals(126, stringFlag1.getVersionForEvents());

        userManager.patchCurrentUserFlags("{\"key\":\"stringFlag1\",\"version\":127,\"flagVersion\":3,\"value\":\"string3\"}").get();
        stringFlag1 = flagStore.getFlag("stringFlag1");
        assertEquals("string3", stringFlag1.getValue().getAsString());
        assertEquals(127, (int) stringFlag1.getVersion());
        assertEquals(3, (int) stringFlag1.getFlagVersion());
        assertEquals(3, stringFlag1.getVersionForEvents());

        userManager.patchCurrentUserFlags("{\"key\":\"stringFlag20\",\"version\":1,\"value\":\"stringValue\"}").get();
        Flag stringFlag20 = flagStore.getFlag("stringFlag20");
        assertEquals("stringValue", stringFlag20.getValue().getAsString());
    }

    @Test
    public void testPatchForInvalidResponse() throws ExecutionException, InterruptedException {
        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        addSimpleFlag(jsonObject, "boolFlag1", true);
        addSimpleFlag(jsonObject, "stringFlag1", expectedStringFlagValue);

        Future<Void> future = setUser("userKey", jsonObject);
        future.get();

        userManager.patchCurrentUserFlags("{}").get();

        //noinspection ConstantConditions
        userManager.patchCurrentUserFlags(null).get();

        userManager.patchCurrentUserFlags("abcd").get();
    }

    @Test
    public void testPutForReplaceFlags() throws ExecutionException, InterruptedException {

        JsonObject jsonObject = new JsonObject();
        addSimpleFlag(jsonObject, "stringFlag1", "string1");
        addSimpleFlag(jsonObject, "boolFlag1", true);
        addSimpleFlag(jsonObject, "floatFlag1", 3.0f);

        Future<Void> future = setUser("userKey", jsonObject);
        future.get();

        String json = "{\n" +
                "  \"stringFlag1\": {\n" +
                "    \"key\": \"\",\n" +
                "    \"version\": 125,\n" +
                "    \"value\": \"string2\"\n" +
                "  },\n" +
                "  \"boolFlag1\": {\n" +
                "    \"key\": \"\",\n" +
                "    \"version\": 2,\n" +
                "    \"value\": false\n" +
                "  },\n" +
                "  \"floatFlag2\": {\n" +
                "    \"key\": \"\",\n" +
                "    \"version\": 3,\n" +
                "    \"value\": 8.0\n" +
                "  }\n" +
                " }";

        userManager.putCurrentUserFlags(json).get();

        FlagStore flagStore = userManager.getCurrentUserFlagStore();

        assertEquals("string2", flagStore.getFlag("stringFlag1").getValue().getAsString());
        assertEquals(false, flagStore.getFlag("boolFlag1").getValue().getAsBoolean());

        // Should no exist as was deleted by PUT.
        assertNull(flagStore.getFlag("floatFlag1"));

        assertEquals(8.0f, flagStore.getFlag("floatFlag2").getValue().getAsFloat());
    }

    @Test
    public void testPutForInvalidResponse() throws ExecutionException, InterruptedException {
        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        addSimpleFlag(jsonObject, "boolFlag1", true);
        addSimpleFlag(jsonObject, "stringFlag1", expectedStringFlagValue);

        Future<Void> future = setUser("userKey", jsonObject);
        future.get();

        userManager.putCurrentUserFlags("{}").get();

        //noinspection ConstantConditions
        userManager.putCurrentUserFlags(null).get();

        userManager.putCurrentUserFlags("abcd").get();
    }

    private Future<Void> setUser(String userKey, JsonObject flags) {
        LDUser user = new LDUser.Builder(userKey).build();
        ListenableFuture<JsonObject> jsonObjectFuture = Futures.immediateFuture(flags);
        expect(fetcher.fetch(user)).andReturn(jsonObjectFuture);
        replayAll();
        userManager.setCurrentUser(user);
        Future<Void> future = userManager.updateCurrentUser();
        reset(fetcher);
        return future;
    }

    private Future<Void> setUserClear(String userKey, JsonObject flags) {
        LDUser user = new LDUser.Builder(userKey).build();
        ListenableFuture<JsonObject> jsonObjectFuture = Futures.immediateFuture(flags);
        expect(fetcher.fetch(user)).andReturn(jsonObjectFuture);
        replayAll();
        userManager.setCurrentUser(user);
        userManager.getCurrentUserFlagStore().clear();
        Future<Void> future = userManager.updateCurrentUser();
        reset(fetcher);
        return future;
    }

    private void setUserAndFailToFetchFlags(String userKey) throws InterruptedException {
        LaunchDarklyException expectedException = new LaunchDarklyException("Could not fetch feature flags");
        ListenableFuture<JsonObject> failedFuture = immediateFailedFuture(expectedException);

        LDUser user = new LDUser.Builder(userKey).build();

        expect(fetcher.fetch(user)).andReturn(failedFuture);
        replayAll();
        userManager.setCurrentUser(user);
        Future<Void> future = userManager.updateCurrentUser();
        try {
            future.get();
        } catch (ExecutionException e) {
            assertEquals(expectedException, e.getCause());
        }
        reset(fetcher);
    }

    private void assertFlagValue(String flagKey, String expectedValue) {
        FlagStore flagStore = userManager.getCurrentUserFlagStore();
        assertEquals(expectedValue, flagStore.getFlag(flagKey).getValue().getAsString());
    }

}
