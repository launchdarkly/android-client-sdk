package com.launchdarkly.android;

import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.google.gson.JsonObject;
import com.launchdarkly.android.test.TestActivity;

import org.easymock.Capture;
import org.easymock.EasyMockRule;
import org.easymock.EasyMockSupport;
import org.easymock.IAnswer;
import org.easymock.Mock;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.reset;

@RunWith(AndroidJUnit4.class)
public class DefaultUserManagerTest extends EasyMockSupport {

    @Rule
    public final ActivityTestRule<TestActivity> activityTestRule =
            new ActivityTestRule<>(TestActivity.class, false, true);

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    @Rule
    public EasyMockRule easyMockRule = new EasyMockRule(this);

    @SuppressWarnings("unused")
    @Mock
    private FeatureFetcher fetcher;

    private DefaultUserManager userManager;

    @Before
    public void before() {
        userManager = new DefaultUserManager(activityTestRule.getActivity().getApplication(), fetcher, "test", "test", 3);
    }

    @Test
    public void testFailedFetchThrowsException() {
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
    public void testBasicRetrieval() throws ExecutionException {
        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        addSimpleFlag(jsonObject, "boolFlag1", true);
        addSimpleFlag(jsonObject, "stringFlag1", expectedStringFlagValue);

        setUserAwait("userKey", jsonObject);

        FlagStore flagStore = userManager.getCurrentUserFlagStore();
        assertEquals(2, flagStore.getAllFlags().size());
        assertEquals(true, flagStore.getFlag("boolFlag1").getValue().getAsBoolean());
        assertEquals(expectedStringFlagValue, flagStore.getFlag("stringFlag1").getValue().getAsString());
    }

    @Test
    public void testNewUserUpdatesFlags() throws ExecutionException {
        JsonObject flags = new JsonObject();

        String flagKey = "stringFlag";
        addSimpleFlag(flags, flagKey, "user1");

        setUserAwait("user1", flags);
        assertFlagValue(flagKey, "user1");

        addSimpleFlag(flags, flagKey, "user2");

        setUserAwait("user2", flags);
        assertFlagValue(flagKey, "user2");
    }

    @Test
    public void usesCachedFlagsOnFailure() throws ExecutionException {
        JsonObject flagsInitial = new JsonObject();
        JsonObject flagsNew = new JsonObject();

        addSimpleFlag(flagsInitial, "flagInitial", "value1");
        addSimpleFlag(flagsNew, "flagNew", "value2");

        setUserAwait("user1", flagsInitial);
        assertFlagValue("flagInitial", "value1");

        setUserAwait("user2", flagsNew);
        assertFlagValue("flagNew", "value2");

        setUserAndFailToFetchFlags("user1");
        assertFlagValue("flagInitial", "value1");
        assertNull(userManager.getCurrentUserFlagStore().getFlag("flagNew"));
    }

    @Test
    public void cachesExactlyMaxCachedUsers() throws ExecutionException {
        JsonObject flags = new JsonObject();
        String flagKey = "stringFlag";

        String user1 = "user1";
        String user4 = "user4";
        List<String> users = Arrays.asList(user1, "user2", "user3", user4, "user5");

        for (String user : users) {
            addSimpleFlag(flags, flagKey, user);
            setUserAwait(user, flags);
            assertFlagValue(flagKey, user);
        }

        // We have now added 5 users in SharedPreferences. The very first one we added shouldn't
        // be saved anymore. (3 cached, one active, one evicted)
        setUserAndFailToFetchFlags(user1);
        assertNull(userManager.getCurrentUserFlagStore().getFlag(flagKey));

        // user4 should still be saved:
        setUserAndFailToFetchFlags(user4);
        assertFlagValue(flagKey, user4);
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
    public void testDeleteFlag() throws ExecutionException {
        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        addSimpleFlag(jsonObject, "boolFlag1", true);
        addSimpleFlag(jsonObject, "stringFlag1", expectedStringFlagValue);

        setUserClear("userKey", jsonObject);

        FlagStore flagStore = userManager.getCurrentUserFlagStore();
        assertEquals(2, flagStore.getAllFlags().size());
        assertEquals(true, flagStore.getFlag("boolFlag1").getValue().getAsBoolean());
        assertEquals(expectedStringFlagValue, flagStore.getFlag("stringFlag1").getValue().getAsString());

        AwaitableCallback<Void> deleteAwait = new AwaitableCallback<>();
        userManager.deleteCurrentUserFlag("{\"key\":\"stringFlag1\",\"version\":16}", deleteAwait);
        deleteAwait.await();
        assertNull(flagStore.getFlag("stringFlag1"));
        assertEquals(true, flagStore.getFlag("boolFlag1").getValue().getAsBoolean());

        deleteAwait.reset();
        userManager.deleteCurrentUserFlag("{\"key\":\"nonExistentFlag\",\"version\":16,\"value\":false}", deleteAwait);
        deleteAwait.await();
    }

    @Test
    public void testDeleteForInvalidResponse() throws ExecutionException {
        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        addSimpleFlag(jsonObject, "boolFlag1", true);
        addSimpleFlag(jsonObject, "stringFlag1", expectedStringFlagValue);

        setUserAwait("userKey", jsonObject);

        AwaitableCallback<Void> deleteAwait = new AwaitableCallback<>();
        userManager.deleteCurrentUserFlag("{}", deleteAwait);
        try {
            deleteAwait.await();
        } catch (ExecutionException ex) {
            Throwable t = ex.getCause();
            assertTrue(t instanceof LDFailure);
            LDFailure ldFailure = (LDFailure)t;
            assertEquals(LDFailure.FailureType.INVALID_RESPONSE_BODY, ldFailure.getFailureType());
        }
        deleteAwait.reset();

        //noinspection ConstantConditions
        userManager.deleteCurrentUserFlag(null, deleteAwait);
        try {
            deleteAwait.await();
        } catch (ExecutionException ex) {
            Throwable t = ex.getCause();
            assertTrue(t instanceof LDFailure);
            LDFailure ldFailure = (LDFailure)t;
            assertEquals(LDFailure.FailureType.INVALID_RESPONSE_BODY, ldFailure.getFailureType());
        }
        deleteAwait.reset();

        userManager.deleteCurrentUserFlag("abcd", deleteAwait);
        try {
            deleteAwait.await();
        } catch (ExecutionException ex) {
            Throwable t = ex.getCause();
            assertTrue(t instanceof LDFailure);
            LDFailure ldFailure = (LDFailure)t;
            assertEquals(LDFailure.FailureType.INVALID_RESPONSE_BODY, ldFailure.getFailureType());
        }
    }

    @Test
    public void testDeleteWithVersion() throws ExecutionException {
        setUserClear("userKey", new JsonObject());

        String json = "{\n" +
                "  \"stringFlag1\": {\n" +
                "    \"key\": \"\",\n" +
                "    \"version\": 125,\n" +
                "    \"value\": \"string1\"\n" +
                "  }\n" +
                " }";

        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        userManager.putCurrentUserFlags(json, awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();

        userManager.deleteCurrentUserFlag("{\"key\":\"stringFlag1\",\"version\":16}", awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();
        FlagStore flagStore = userManager.getCurrentUserFlagStore();
        assertEquals("string1", flagStore.getFlag("stringFlag1").getValue().getAsString());

        userManager.deleteCurrentUserFlag("{\"key\":\"stringFlag1\",\"version\":127}", awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();
        assertNull(flagStore.getFlag("stringFlag1"));

        userManager.deleteCurrentUserFlag("{\"key\":\"nonExistent\",\"version\":1}", awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();
    }

    @Test
    public void testPatchForAddAndReplaceFlags() throws ExecutionException {
        JsonObject jsonObject = new JsonObject();
        addSimpleFlag(jsonObject, "boolFlag1", true);
        addSimpleFlag(jsonObject, "stringFlag1", "string1");
        addSimpleFlag(jsonObject, "floatFlag1", 3.0f);

        setUserClear("userKey", jsonObject);

        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        userManager.patchCurrentUserFlags("{\"key\":\"new-flag\",\"version\":16,\"value\":false}", awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();

        FlagStore flagStore = userManager.getCurrentUserFlagStore();
        assertEquals(false, flagStore.getFlag("new-flag").getValue().getAsBoolean());


        userManager.patchCurrentUserFlags("{\"key\":\"stringFlag1\",\"version\":16,\"value\":\"string2\"}", awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();
        assertEquals("string2", flagStore.getFlag("stringFlag1").getValue().getAsString());

        userManager.patchCurrentUserFlags("{\"key\":\"boolFlag1\",\"version\":16,\"value\":false}", awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();
        assertEquals(false, flagStore.getFlag("boolFlag1").getValue().getAsBoolean());

        assertEquals(3.0f, flagStore.getFlag("floatFlag1").getValue().getAsFloat());

        userManager.patchCurrentUserFlags("{\"key\":\"floatFlag2\",\"version\":16,\"value\":8.0}", awaitableCallback);
        awaitableCallback.await();
        assertEquals(8.0f, flagStore.getFlag("floatFlag2").getValue().getAsFloat());
    }

    @Test
    public void testPatchSucceedsForMissingVersionInPatch() throws ExecutionException {
        setUserClear("userKey", new JsonObject());

        FlagStore flagStore = userManager.getCurrentUserFlagStore();
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();

        // version does not exist in shared preferences and patch.
        // ---------------------------
        //// case 1: value does not exist in shared preferences.
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"value\":\"value-from-patch\"}", awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();
        Flag flag1 = flagStore.getFlag("flag1");
        assertEquals("value-from-patch", flag1.getValue().getAsString());
        assertNull(flag1.getVersion());

        //// case 2: value exists in shared preferences without version.
        userManager.putCurrentUserFlags("{\"flag1\": {\"value\": \"value1\"}}", null);
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"value\":\"value-from-patch\"}", awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();
        flag1 = flagStore.getFlag("flag1");
        assertEquals("value-from-patch", flag1.getValue().getAsString());
        assertNull(flag1.getVersion());

        // version does not exist in shared preferences but exists in patch.
        // ---------------------------
        //// case 1: value does not exist in shared preferences.
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"version\":558,\"flagVersion\":3,\"value\":\"value-from-patch\",\"variation\":1,\"trackEvents\":false}", awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();
        flag1 = flagStore.getFlag("flag1");
        assertEquals("value-from-patch", flag1.getValue().getAsString());
        assertEquals(558, (int) flag1.getVersion());
        assertEquals(3, (int) flag1.getFlagVersion());
        assertEquals(3, flag1.getVersionForEvents());

        //// case 2: value exists in shared preferences without version.
        userManager.putCurrentUserFlags("{\"flag1\": {\"value\": \"value1\"}}", null);
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"version\":558,\"flagVersion\":3,\"value\":\"value-from-patch\",\"variation\":1,\"trackEvents\":false}", awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();
        flag1 = flagStore.getFlag("flag1");
        assertEquals("value-from-patch", flag1.getValue().getAsString());
        assertEquals(558, (int) flag1.getVersion());
        assertEquals(3, (int) flag1.getFlagVersion());
        assertEquals(3, flag1.getVersionForEvents());

        // version exists in shared preferences but does not exist in patch.
        // ---------------------------
        userManager.putCurrentUserFlags("{\"flag1\": {\"version\": 558, \"flagVersion\": 110,\"value\": \"value1\", \"variation\": 1, \"trackEvents\": false}}", null);
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"value\":\"value-from-patch\"}", awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();
        flag1 = flagStore.getFlag("flag1");
        assertEquals("value-from-patch", flag1.getValue().getAsString());
        assertNull(flag1.getVersion());
        assertNull(flag1.getFlagVersion());
        assertEquals(-1, flag1.getVersionForEvents());

        // version exists in shared preferences and patch.
        // ---------------------------
        userManager.putCurrentUserFlags("{\"flag1\": {\"version\": 558, \"flagVersion\": 110,\"value\": \"value1\", \"variation\": 1, \"trackEvents\": false}}", null);
        userManager.patchCurrentUserFlags("{\"key\":\"flag1\",\"version\":559,\"flagVersion\":3,\"value\":\"value-from-patch\",\"variation\":1,\"trackEvents\":false}", awaitableCallback);
        awaitableCallback.await();
        flag1 = flagStore.getFlag("flag1");
        assertEquals("value-from-patch", flag1.getValue().getAsString());
        assertEquals(559, (int) flag1.getVersion());
        assertEquals(3, (int) flag1.getFlagVersion());
        assertEquals(3, flag1.getVersionForEvents());
    }

    @Test
    public void testPatchWithVersion() throws ExecutionException {
        setUserClear("userKey", new JsonObject());

        String json = "{\n" +
                "  \"stringFlag1\": {\n" +
                "    \"key\": \"\",\n" +
                "    \"version\": 125,\n" +
                "    \"value\": \"string1\"\n" +
                "  }\n" +
                " }";

        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        userManager.putCurrentUserFlags(json, awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();

        userManager.patchCurrentUserFlags("{\"key\":\"stringFlag1\",\"version\":16,\"value\":\"string2\"}", awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();
        FlagStore flagStore = userManager.getCurrentUserFlagStore();
        Flag stringFlag1 = flagStore.getFlag("stringFlag1");
        assertEquals("string1", stringFlag1.getValue().getAsString());
        assertNull(stringFlag1.getFlagVersion());
        assertEquals(125, stringFlag1.getVersionForEvents());

        userManager.patchCurrentUserFlags("{\"key\":\"stringFlag1\",\"version\":126,\"value\":\"string2\"}", awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();
        stringFlag1 = flagStore.getFlag("stringFlag1");
        assertEquals("string2", stringFlag1.getValue().getAsString());
        assertEquals(126, (int) stringFlag1.getVersion());
        assertNull(stringFlag1.getFlagVersion());
        assertEquals(126, stringFlag1.getVersionForEvents());

        userManager.patchCurrentUserFlags("{\"key\":\"stringFlag1\",\"version\":127,\"flagVersion\":3,\"value\":\"string3\"}", awaitableCallback);
        awaitableCallback.await();
        awaitableCallback.reset();
        stringFlag1 = flagStore.getFlag("stringFlag1");
        assertEquals("string3", stringFlag1.getValue().getAsString());
        assertEquals(127, (int) stringFlag1.getVersion());
        assertEquals(3, (int) stringFlag1.getFlagVersion());
        assertEquals(3, stringFlag1.getVersionForEvents());

        userManager.patchCurrentUserFlags("{\"key\":\"stringFlag20\",\"version\":1,\"value\":\"stringValue\"}", awaitableCallback);
        awaitableCallback.await();
        Flag stringFlag20 = flagStore.getFlag("stringFlag20");
        assertEquals("stringValue", stringFlag20.getValue().getAsString());
    }

    @Test
    public void testPatchForInvalidResponse() throws ExecutionException {
        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        addSimpleFlag(jsonObject, "boolFlag1", true);
        addSimpleFlag(jsonObject, "stringFlag1", expectedStringFlagValue);

        setUserAwait("userKey", jsonObject);

        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        userManager.patchCurrentUserFlags("{}", awaitableCallback);
        try {
            awaitableCallback.await();
        } catch (ExecutionException ex) {
            Throwable t = ex.getCause();
            assertTrue(t instanceof LDFailure);
            LDFailure ldFailure = (LDFailure)t;
            assertEquals(LDFailure.FailureType.INVALID_RESPONSE_BODY, ldFailure.getFailureType());
        }
        awaitableCallback.reset();

        //noinspection ConstantConditions
        userManager.patchCurrentUserFlags(null, awaitableCallback);
        try {
            awaitableCallback.await();
        } catch (ExecutionException ex) {
            Throwable t = ex.getCause();
            assertTrue(t instanceof LDFailure);
            LDFailure ldFailure = (LDFailure)t;
            assertEquals(LDFailure.FailureType.INVALID_RESPONSE_BODY, ldFailure.getFailureType());
        }
        awaitableCallback.reset();

        userManager.patchCurrentUserFlags("abcd", awaitableCallback);
        try {
            awaitableCallback.await();
        } catch (ExecutionException ex) {
            Throwable t = ex.getCause();
            assertTrue(t instanceof LDFailure);
            LDFailure ldFailure = (LDFailure)t;
            assertEquals(LDFailure.FailureType.INVALID_RESPONSE_BODY, ldFailure.getFailureType());
        }
    }

    @Test
    public void testPutForReplaceFlags() throws ExecutionException {

        JsonObject jsonObject = new JsonObject();
        addSimpleFlag(jsonObject, "stringFlag1", "string1");
        addSimpleFlag(jsonObject, "boolFlag1", true);
        addSimpleFlag(jsonObject, "floatFlag1", 3.0f);

        setUserAwait("userKey", jsonObject);

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

        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        userManager.putCurrentUserFlags(json, awaitableCallback);
        awaitableCallback.await();

        FlagStore flagStore = userManager.getCurrentUserFlagStore();

        assertEquals("string2", flagStore.getFlag("stringFlag1").getValue().getAsString());
        assertEquals(false, flagStore.getFlag("boolFlag1").getValue().getAsBoolean());

        // Should no exist as was deleted by PUT.
        assertNull(flagStore.getFlag("floatFlag1"));

        assertEquals(8.0f, flagStore.getFlag("floatFlag2").getValue().getAsFloat());
    }

    @Test
    public void testPutForInvalidResponse() throws ExecutionException {
        String expectedStringFlagValue = "string1";

        JsonObject jsonObject = new JsonObject();
        addSimpleFlag(jsonObject, "boolFlag1", true);
        addSimpleFlag(jsonObject, "stringFlag1", expectedStringFlagValue);

        setUserAwait("userKey", jsonObject);

        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        userManager.putCurrentUserFlags("{}", awaitableCallback);
        try {
            awaitableCallback.await();
        } catch (ExecutionException ex) {
            Throwable t = ex.getCause();
            assertTrue(t instanceof LDFailure);
            LDFailure ldFailure = (LDFailure)t;
            assertEquals(LDFailure.FailureType.INVALID_RESPONSE_BODY, ldFailure.getFailureType());
        }
        awaitableCallback.reset();

        userManager.putCurrentUserFlags(null, awaitableCallback);
        try {
            awaitableCallback.await();
        } catch (ExecutionException ex) {
            Throwable t = ex.getCause();
            assertTrue(t instanceof LDFailure);
            LDFailure ldFailure = (LDFailure)t;
            assertEquals(LDFailure.FailureType.INVALID_RESPONSE_BODY, ldFailure.getFailureType());
        }
        awaitableCallback.reset();

        userManager.putCurrentUserFlags("abcd", awaitableCallback);
        try {
            awaitableCallback.await();
        } catch (ExecutionException ex) {
            Throwable t = ex.getCause();
            assertTrue(t instanceof LDFailure);
            LDFailure ldFailure = (LDFailure)t;
            assertEquals(LDFailure.FailureType.INVALID_RESPONSE_BODY, ldFailure.getFailureType());
        }
    }

    private void setUserAwait(String userKey, final JsonObject flags) throws ExecutionException {
        LDUser user = new LDUser.Builder(userKey).build();
        final Capture<LDUtil.ResultCallback<JsonObject>> callbackCapture = Capture.newInstance();
        fetcher.fetch(eq(user), capture(callbackCapture));
        expectLastCall().andAnswer(new IAnswer<Void>() {
            @Override
            public Void answer() {
                callbackCapture.getValue().onSuccess(flags);
                return null;
            }
        });

        replayAll();
        userManager.setCurrentUser(user);
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        userManager.updateCurrentUser(awaitableCallback);
        awaitableCallback.await();
        verifyAll();
        reset(fetcher);
    }

    private void setUserClear(String userKey, final JsonObject flags) throws ExecutionException {
        LDUser user = new LDUser.Builder(userKey).build();
        final Capture<LDUtil.ResultCallback<JsonObject>> callbackCapture = Capture.newInstance();
        fetcher.fetch(eq(user), capture(callbackCapture));
        expectLastCall().andAnswer(new IAnswer<Void>() {
            @Override
            public Void answer() {
                callbackCapture.getValue().onSuccess(flags);
                return null;
            }
        });

        replayAll();
        userManager.setCurrentUser(user);
        userManager.getCurrentUserFlagStore().clear();
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        userManager.updateCurrentUser(awaitableCallback);
        awaitableCallback.await();
        verifyAll();
        reset(fetcher);
    }

    private void setUserAndFailToFetchFlags(String userKey) {
        LDUser user = new LDUser.Builder(userKey).build();
        final LaunchDarklyException expectedException = new LaunchDarklyException("Could not fetch feature flags");
        final Capture<LDUtil.ResultCallback<JsonObject>> callbackCapture = Capture.newInstance();
        fetcher.fetch(eq(user), capture(callbackCapture));
        expectLastCall().andAnswer(new IAnswer<Void>() {
            @Override
            public Void answer() {
                callbackCapture.getValue().onError(expectedException);
                return null;
            }
        });

        replayAll();
        userManager.setCurrentUser(user);
        AwaitableCallback<Void> awaitableCallback = new AwaitableCallback<>();
        userManager.updateCurrentUser(awaitableCallback);
        try {
            awaitableCallback.await();
        } catch (ExecutionException e) {
            assertEquals(expectedException, e.getCause());
        }
        verifyAll();
        reset(fetcher);
    }

    private void assertFlagValue(String flagKey, String expectedValue) {
        FlagStore flagStore = userManager.getCurrentUserFlagStore();
        assertEquals(expectedValue, flagStore.getFlag(flagKey).getValue().getAsString());
    }

}
