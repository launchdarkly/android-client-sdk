package com.launchdarkly.android;

import android.content.SharedPreferences;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.util.Pair;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.JsonObject;
import com.launchdarkly.android.response.FlagResponseSharedPreferences;
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
    public void TestFailedFetchThrowsException() throws InterruptedException {
        setUserAndFailToFetchFlags("userKey");
    }

    @Test
    public void TestBasicRetrieval() throws ExecutionException, InterruptedException {
        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("boolFlag1", true);
        jsonObject.addProperty("stringFlag1", expectedStringFlagValue);

        Future<Void> future = setUser("userKey", jsonObject);
        future.get();

        SharedPreferences sharedPrefs = userManager.getCurrentUserSharedPrefs();
        assertEquals(2, sharedPrefs.getAll().size());
        assertEquals(true, sharedPrefs.getBoolean("boolFlag1", false));
        assertEquals(expectedStringFlagValue, sharedPrefs.getString("stringFlag1", ""));
    }

    @Test
    public void TestNewUserUpdatesFlags() {
        JsonObject flags = new JsonObject();

        String flagKey = "stringFlag";
        flags.addProperty(flagKey, "user1");

        setUser("user1", flags);
        assertFlagValue(flagKey, "user1");

        flags.addProperty(flagKey, "user2");

        setUser("user2", flags);
        assertFlagValue(flagKey, "user2");
    }

    @Test
    public void TestCanStoreExactly5Users() throws InterruptedException {
        JsonObject flags = new JsonObject();
        String flagKey = "stringFlag";

        String user1 = "user1";
        String user5 = "user5";
        List<String> users = Arrays.asList(user1, "user2", "user3", "user4", user5, "user6");

        for (String user : users) {
            flags.addProperty(flagKey, user);
            setUser(user, flags);
            assertFlagValue(flagKey, user);
        }

        //we now have 5 users in SharedPreferences. The very first one we added shouldn't be saved anymore.
        setUserAndFailToFetchFlags(user1);
        assertFlagValue(flagKey, null);

        // user5 should still be saved:
        setUserAndFailToFetchFlags(user5);
        assertFlagValue(flagKey, user5);
    }

    @Test
    public void TestRegisterUnregisterListener() {
        FeatureFlagChangeListener listener = new FeatureFlagChangeListener() {
            @Override
            public void onFeatureFlagChange(String flagKey) {
            }
        };

        userManager.registerListener("key", listener);
        Collection<Pair<FeatureFlagChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>> listeners = userManager.getListenersByKey("key");
        assertNotNull(listeners);
        assertFalse(listeners.isEmpty());

        userManager.unregisterListener("key", listener);
        listeners = userManager.getListenersByKey("key");
        assertNotNull(listeners);
        assertTrue(listeners.isEmpty());
    }

    @Test
    public void TestUnregisterListenerWithDuplicates() {
        FeatureFlagChangeListener listener = new FeatureFlagChangeListener() {
            @Override
            public void onFeatureFlagChange(String flagKey) {
            }
        };

        userManager.registerListener("key", listener);
        userManager.registerListener("key", listener);
        userManager.unregisterListener("key", listener);

        Collection<Pair<FeatureFlagChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>> listeners = userManager.getListenersByKey("key");
        assertNotNull(listeners);
        assertTrue(listeners.isEmpty());
    }

    @Test
    public void TestDeleteFlag() throws ExecutionException, InterruptedException {
        userManager.clearFlagResponseSharedPreferences();

        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("boolFlag1", true);
        jsonObject.addProperty("stringFlag1", expectedStringFlagValue);

        Future<Void> future = setUser("userKey", jsonObject);
        future.get();

        SharedPreferences sharedPrefs = userManager.getCurrentUserSharedPrefs();
        assertEquals(2, sharedPrefs.getAll().size());
        assertEquals(true, sharedPrefs.getBoolean("boolFlag1", false));
        assertEquals(expectedStringFlagValue, sharedPrefs.getString("stringFlag1", ""));

        userManager.deleteCurrentUserFlag("{\"key\":\"stringFlag1\",\"version\":16}").get();
        assertEquals("", sharedPrefs.getString("stringFlag1", ""));
        assertEquals(true, sharedPrefs.getBoolean("boolFlag1", false));

        userManager.deleteCurrentUserFlag("{\"key\":\"nonExistentFlag\",\"version\":16,\"value\":false}").get();
    }

    @Test
    public void TestDeleteForInvalidResponse() throws ExecutionException, InterruptedException {
        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("boolFlag1", true);
        jsonObject.addProperty("stringFlag1", expectedStringFlagValue);

        Future<Void> future = setUser("userKey", jsonObject);
        future.get();

        userManager.deleteCurrentUserFlag("{}").get();

        //noinspection ConstantConditions
        userManager.deleteCurrentUserFlag(null).get();

        userManager.deleteCurrentUserFlag("abcd").get();
    }

    @Test
    public void TestDeleteWithVersion() throws ExecutionException, InterruptedException {
        userManager.clearFlagResponseSharedPreferences();

        Future<Void> future = setUser("userKey", new JsonObject());
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
        SharedPreferences sharedPrefs = userManager.getCurrentUserSharedPrefs();
        assertEquals("string1", sharedPrefs.getString("stringFlag1", ""));

        userManager.deleteCurrentUserFlag("{\"key\":\"stringFlag1\",\"version\":127}").get();
        assertEquals("", sharedPrefs.getString("stringFlag1", ""));

        userManager.deleteCurrentUserFlag("{\"key\":\"nonExistent\",\"version\":1}").get();
    }

    @Test
    public void TestPatchForAddAndReplaceFlags() throws ExecutionException, InterruptedException {
        userManager.clearFlagResponseSharedPreferences();

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("boolFlag1", true);
        jsonObject.addProperty("stringFlag1", "string1");
        jsonObject.addProperty("floatFlag1", 3.0f);

        Future<Void> future = setUser("userKey", jsonObject);
        future.get();

        userManager.patchCurrentUserFlags("{\"key\":\"new-flag\",\"version\":16,\"value\":false}").get();

        SharedPreferences sharedPrefs = userManager.getCurrentUserSharedPrefs();
        assertEquals(false, sharedPrefs.getBoolean("new-flag", true));


        userManager.patchCurrentUserFlags("{\"key\":\"stringFlag1\",\"version\":16,\"value\":\"string2\"}").get();
        assertEquals("string2", sharedPrefs.getString("stringFlag1", ""));

        userManager.patchCurrentUserFlags("{\"key\":\"boolFlag1\",\"version\":16,\"value\":false}").get();
        assertEquals(false, sharedPrefs.getBoolean("boolFlag1", false));

        assertEquals(3.0f, sharedPrefs.getFloat("floatFlag1", Float.MIN_VALUE));

        userManager.patchCurrentUserFlags("{\"key\":\"floatFlag2\",\"version\":16,\"value\":8.0}").get();
        assertEquals(8.0f, sharedPrefs.getFloat("floatFlag2", Float.MIN_VALUE));
    }

    @Test
    public void TestPatchSucceedsForMissingVersionInPatch() throws ExecutionException, InterruptedException {
        userManager.clearFlagResponseSharedPreferences();

        Future<Void> future = setUser("userKey", new JsonObject());
        future.get();

        SharedPreferences sharedPrefs = userManager.getCurrentUserSharedPrefs();
        FlagResponseSharedPreferences flagResponseSharedPreferences = userManager.getFlagResponseSharedPreferences();

        // version does not exist in shared preferences and patch.
        // ---------------------------
        //// case 1: value does not exist in shared preferences.
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"value\":\"value-from-patch\"}").get();
        assertEquals("value-from-patch", sharedPrefs.getString("flag1", ""));
        assertEquals(-1, flagResponseSharedPreferences.getStoredFlagResponse("flag1").getVersion());

        //// case 2: value exists in shared preferences without version.
        userManager.putCurrentUserFlags("{\"flag1\": {\"value\": \"value1\"}}");
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"value\":\"value-from-patch\"}").get();
        assertEquals("value-from-patch", sharedPrefs.getString("flag1", ""));
        assertEquals(-1, flagResponseSharedPreferences.getStoredFlagResponse("flag1").getVersion());

        // version does not exist in shared preferences but exists in patch.
        // ---------------------------
        //// case 1: value does not exist in shared preferences.
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"version\":558,\"flagVersion\":3,\"value\":\"value-from-patch\",\"variation\":1,\"trackEvents\":false}").get();
        assertEquals("value-from-patch", sharedPrefs.getString("flag1", ""));
        assertEquals(558, flagResponseSharedPreferences.getStoredFlagResponse("flag1").getVersion());
        assertEquals(3, flagResponseSharedPreferences.getStoredFlagResponse("flag1").getFlagVersion());
        assertEquals(3, flagResponseSharedPreferences.getStoredFlagResponse("flag1").getVersionForEvents());

        //// case 2: value exists in shared preferences without version.
        userManager.putCurrentUserFlags("{\"flag1\": {\"value\": \"value1\"}}");
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"version\":558,\"flagVersion\":3,\"value\":\"value-from-patch\",\"variation\":1,\"trackEvents\":false}").get();
        assertEquals("value-from-patch", sharedPrefs.getString("flag1", ""));
        assertEquals(558, flagResponseSharedPreferences.getStoredFlagResponse("flag1").getVersion());
        assertEquals(3, flagResponseSharedPreferences.getStoredFlagResponse("flag1").getFlagVersion());
        assertEquals(3, flagResponseSharedPreferences.getStoredFlagResponse("flag1").getVersionForEvents());

        // version exists in shared preferences but does not exist in patch.
        // ---------------------------
        userManager.putCurrentUserFlags("{\"flag1\": {\"version\": 558, \"flagVersion\": 110,\"value\": \"value1\", \"variation\": 1, \"trackEvents\": false}}");
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"value\":\"value-from-patch\"}").get();
        assertEquals("value-from-patch", sharedPrefs.getString("flag1", ""));
        assertEquals(-1, flagResponseSharedPreferences.getStoredFlagResponse("flag1").getVersion());
        assertEquals(-1, flagResponseSharedPreferences.getStoredFlagResponse("flag1").getFlagVersion());
        assertEquals(-1, flagResponseSharedPreferences.getStoredFlagResponse("flag1").getVersionForEvents());

        // version exists in shared preferences and patch.
        // ---------------------------
        userManager.putCurrentUserFlags("{\"flag1\": {\"version\": 558, \"flagVersion\": 110,\"value\": \"value1\", \"variation\": 1, \"trackEvents\": false}}");
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"version\":559,\"flagVersion\":3,\"value\":\"value-from-patch\",\"variation\":1,\"trackEvents\":false}").get();
        assertEquals("value-from-patch", sharedPrefs.getString("flag1", ""));
        assertEquals(559, flagResponseSharedPreferences.getStoredFlagResponse("flag1").getVersion());
        assertEquals(3, flagResponseSharedPreferences.getStoredFlagResponse("flag1").getFlagVersion());
        assertEquals(3, flagResponseSharedPreferences.getStoredFlagResponse("flag1").getVersionForEvents());
    }

    @Test
    public void TestPatchWithVersion() throws ExecutionException, InterruptedException {
        userManager.clearFlagResponseSharedPreferences();

        Future<Void> future = setUser("userKey", new JsonObject());
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
        SharedPreferences sharedPrefs = userManager.getCurrentUserSharedPrefs();
        FlagResponseSharedPreferences flagResponseSharedPreferences = userManager.getFlagResponseSharedPreferences();
        assertEquals("string1", sharedPrefs.getString("stringFlag1", ""));
        assertEquals(-1, flagResponseSharedPreferences.getStoredFlagResponse("stringFlag1").getFlagVersion());
        assertEquals(125, flagResponseSharedPreferences.getStoredFlagResponse("stringFlag1").getVersionForEvents());

        userManager.patchCurrentUserFlags("{\"key\":\"stringFlag1\",\"version\":126,\"value\":\"string2\"}").get();
        assertEquals("string2", sharedPrefs.getString("stringFlag1", ""));
        assertEquals(126, flagResponseSharedPreferences.getStoredFlagResponse("stringFlag1").getVersion());
        assertEquals(-1, flagResponseSharedPreferences.getStoredFlagResponse("stringFlag1").getFlagVersion());
        assertEquals(126, flagResponseSharedPreferences.getStoredFlagResponse("stringFlag1").getVersionForEvents());

        userManager.patchCurrentUserFlags("{\"key\":\"stringFlag1\",\"version\":127,\"flagVersion\":3,\"value\":\"string3\"}").get();
        assertEquals("string3", sharedPrefs.getString("stringFlag1", ""));
        assertEquals(127, flagResponseSharedPreferences.getStoredFlagResponse("stringFlag1").getVersion());
        assertEquals(3, flagResponseSharedPreferences.getStoredFlagResponse("stringFlag1").getFlagVersion());
        assertEquals(3, flagResponseSharedPreferences.getStoredFlagResponse("stringFlag1").getVersionForEvents());

        userManager.patchCurrentUserFlags("{\"key\":\"stringFlag20\",\"version\":1,\"value\":\"stringValue\"}").get();
        assertEquals("stringValue", sharedPrefs.getString("stringFlag20", ""));
    }

    @Test
    public void TestPatchForInvalidResponse() throws ExecutionException, InterruptedException {
        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("boolFlag1", true);
        jsonObject.addProperty("stringFlag1", expectedStringFlagValue);

        Future<Void> future = setUser("userKey", jsonObject);
        future.get();

        userManager.patchCurrentUserFlags("{}").get();

        //noinspection ConstantConditions
        userManager.patchCurrentUserFlags(null).get();

        userManager.patchCurrentUserFlags("abcd").get();
    }

    @Test
    public void TestPutForReplaceFlags() throws ExecutionException, InterruptedException {

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("stringFlag1", "string1");
        jsonObject.addProperty("boolFlag1", true);
        jsonObject.addProperty("floatFlag1", 3.0f);

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

        SharedPreferences sharedPrefs = userManager.getCurrentUserSharedPrefs();

        assertEquals("string2", sharedPrefs.getString("stringFlag1", ""));
        assertEquals(false, sharedPrefs.getBoolean("boolFlag1", false));

        // Should have value Float.MIN_VALUE instead of 3.0f which was deleted by PUT.
        assertEquals(Float.MIN_VALUE, sharedPrefs.getFloat("floatFlag1", Float.MIN_VALUE));

        assertEquals(8.0f, sharedPrefs.getFloat("floatFlag2", 1.0f));
    }

    @Test
    public void TestPutForInvalidResponse() throws ExecutionException, InterruptedException {
        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("boolFlag1", true);
        jsonObject.addProperty("stringFlag1", expectedStringFlagValue);

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

    private void assertFlagValue(String flagKey, Object expectedValue) {
        SharedPreferences sharedPrefs = userManager.getCurrentUserSharedPrefs();
        assertEquals(expectedValue, sharedPrefs.getAll().get(flagKey));
    }

}
