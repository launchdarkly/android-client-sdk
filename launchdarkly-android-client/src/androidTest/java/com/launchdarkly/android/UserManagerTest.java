package com.launchdarkly.android;

import android.content.SharedPreferences;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.util.Pair;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.JsonObject;
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
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertFalse;
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

    @Mock
    private FeatureFlagFetcher fetcher;

    private UserManager userManager;

    @Before
    public void before() {
        userManager = new UserManager(activityTestRule.getActivity().getApplication(), fetcher);
    }

    @Test
    public void TestFailedFetchThrowsException() throws InterruptedException {
        setUserAndFailToFetchFlags("userKey");
    }

    @Test
    public void TestBasicRetrieval() throws ExecutionException, InterruptedException {
        String expectedStringFlagValue = "string1";
        boolean expectedBoolFlagValue = true;

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("boolFlag1", expectedBoolFlagValue);
        jsonObject.addProperty("stringFlag1", expectedStringFlagValue);

        Future<Void> future = setUser("userKey", jsonObject);
        future.get();

        SharedPreferences sharedPrefs = userManager.getCurrentUserSharedPrefs();
        assertEquals(2, sharedPrefs.getAll().size());
        assertEquals(expectedBoolFlagValue, sharedPrefs.getBoolean("boolFlag1", false));
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