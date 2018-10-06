package com.launchdarkly.android;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.Base64;
import android.util.Pair;

import com.google.common.base.Function;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.launchdarkly.android.response.FlagResponse;
import com.launchdarkly.android.response.FlagResponseSharedPreferences;
import com.launchdarkly.android.response.FlagResponseStore;
import com.launchdarkly.android.response.SummaryEventSharedPreferences;
import com.launchdarkly.android.response.UserFlagResponseSharedPreferences;
import com.launchdarkly.android.response.UserFlagResponseStore;
import com.launchdarkly.android.response.UserSummaryEventSharedPreferences;
import com.launchdarkly.android.response.interpreter.DeleteFlagResponseInterpreter;
import com.launchdarkly.android.response.interpreter.PatchFlagResponseInterpreter;
import com.launchdarkly.android.response.interpreter.PingFlagResponseInterpreter;
import com.launchdarkly.android.response.interpreter.PutFlagResponseInterpreter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import timber.log.Timber;

/**
 * Persists and retrieves feature flag values for different {@link LDUser}s.
 * Also enables realtime updates via registering a {@link FeatureFlagChangeListener}
 * with a feature flag.
 */
class UserManager {

    private final FeatureFlagFetcher fetcher;
    private volatile boolean initialized = false;

    private final Application application;
    private final UserLocalSharedPreferences userLocalSharedPreferences;
    private final FlagResponseSharedPreferences flagResponseSharedPreferences;
    private final SummaryEventSharedPreferences summaryEventSharedPreferences;

    private LDUser currentUser;
    private final Util.LazySingleton<JsonParser> jsonParser;

    private final ExecutorService executor;

    static synchronized UserManager newInstance(Application application, FeatureFlagFetcher fetcher, String environment) {
        return new UserManager(application, fetcher, environment);
    }

    UserManager(Application application, FeatureFlagFetcher fetcher) {
        this(application, fetcher, LDConfig.primaryEnvironmentName);
    }

    UserManager(Application application, FeatureFlagFetcher fetcher, String environment) {
        this.application = application;
        this.fetcher = fetcher;
        this.userLocalSharedPreferences = new UserLocalSharedPreferences(application);
        this.flagResponseSharedPreferences = new UserFlagResponseSharedPreferences(application, LDConfig.SHARED_PREFS_BASE_KEY + "version");
        this.summaryEventSharedPreferences = new UserSummaryEventSharedPreferences(application, LDConfig.SHARED_PREFS_BASE_KEY + "summaryevents");

        jsonParser = new Util.LazySingleton<>(new Util.Provider<JsonParser>() {
            @Override
            public JsonParser get() {
                return new JsonParser();
            }
        });
        executor = new BackgroundThreadExecutor().newFixedThreadPool(1);
    }

    LDUser getCurrentUser() {
        return currentUser;
    }

    SharedPreferences getCurrentUserSharedPrefs() {
        return userLocalSharedPreferences.getCurrentUserSharedPrefs();
    }

    FlagResponseSharedPreferences getFlagResponseSharedPreferences() {
        return flagResponseSharedPreferences;
    }

    SummaryEventSharedPreferences getSummaryEventSharedPreferences() {
        return summaryEventSharedPreferences;
    }


    /**
     * Sets the current user. If there are more than MAX_USERS stored in shared preferences,
     * the oldest one is deleted.
     *
     * @param user
     */
    @SuppressWarnings("JavaDoc")
    void setCurrentUser(final LDUser user) {
        String userBase64 = user.getAsUrlSafeBase64();
        Timber.d("Setting current user to: [" + userBase64 + "] [" + userBase64ToJson(userBase64) + "]");
        currentUser = user;
        userLocalSharedPreferences.setCurrentUser(user);
    }

    ListenableFuture<Void> updateCurrentUser() {
        ListenableFuture<JsonObject> fetchFuture = fetcher.fetch(currentUser);

        Futures.addCallback(fetchFuture, new FutureCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                initialized = true;
                saveFlagSettings(result);
            }

            @Override
            public void onFailure(@NonNull Throwable t) {
                if (Util.isInternetConnected(application)) {
                    Timber.e(t, "Error when attempting to set user: [" + currentUser.getAsUrlSafeBase64()
                            + "] [" + userBase64ToJson(currentUser.getAsUrlSafeBase64()) + "]");
                }
                syncCurrentUserToActiveUserAndLog();
            }
        }, MoreExecutors.directExecutor());

        // Transform the Future<JsonObject> to Future<Void> since the caller doesn't care about the result.
        return Futures.transform(fetchFuture, new Function<JsonObject, Void>() {
            @javax.annotation.Nullable
            @Override
            public Void apply(@javax.annotation.Nullable JsonObject input) {
                return null;
            }
        }, MoreExecutors.directExecutor());
    }

    @SuppressWarnings("SameParameterValue")
    Collection<Pair<FeatureFlagChangeListener, OnSharedPreferenceChangeListener>> getListenersByKey(String key) {
        return userLocalSharedPreferences.getListener(key);
    }

    void registerListener(final String key, final FeatureFlagChangeListener listener) {
        userLocalSharedPreferences.registerListener(key, listener);
    }

    void unregisterListener(String key, FeatureFlagChangeListener listener) {
        userLocalSharedPreferences.unRegisterListener(key, listener);
    }

    /**
     * Saves the flags param to {@link SharedPreferences} for the current user.
     * Completely overwrites all values in the current user's {@link SharedPreferences} and
     * saves those values to the active user, triggering any registered {@link FeatureFlagChangeListener}
     * objects.
     *
     * @param flags
     */
    @SuppressWarnings("JavaDoc")
    private void saveFlagSettings(JsonObject flags) {

        Timber.d("saveFlagSettings for user key: %s", currentUser.getKey());

        FlagResponseStore<List<FlagResponse>> responseStore = new UserFlagResponseStore<>(flags, new PingFlagResponseInterpreter());
        List<FlagResponse> flagResponseList = responseStore.getFlagResponse();
        if (flagResponseList != null) {
            flagResponseSharedPreferences.clear();
            flagResponseSharedPreferences.saveAll(flagResponseList);
            userLocalSharedPreferences.saveCurrentUserFlags(getSharedPreferencesEntries(flagResponseList));
            syncCurrentUserToActiveUserAndLog();
        }
    }

    private void syncCurrentUserToActiveUserAndLog() {
        userLocalSharedPreferences.syncCurrentUserToActiveUser();
        userLocalSharedPreferences.logCurrentUserFlags();
    }

    private static String userBase64ToJson(String base64) {
        return new String(Base64.decode(base64, Base64.URL_SAFE));
    }

    boolean isInitialized() {
        return initialized;
    }

    ListenableFuture<Void> deleteCurrentUserFlag(@NonNull final String json) {

        JsonObject jsonObject = parseJson(json);
        final FlagResponseStore<FlagResponse> responseStore
                = new UserFlagResponseStore<>(jsonObject, new DeleteFlagResponseInterpreter());

        ListeningExecutorService service = MoreExecutors.listeningDecorator(executor);
        return service.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                initialized = true;
                FlagResponse flagResponse = responseStore.getFlagResponse();
                if (flagResponse != null) {
                    if (flagResponseSharedPreferences.isVersionValid(flagResponse)) {
                        flagResponseSharedPreferences.deleteStoredFlagResponse(flagResponse);

                        userLocalSharedPreferences.deleteCurrentUserFlag(flagResponse.getKey());
                        UserManager.this.syncCurrentUserToActiveUserAndLog();
                    }
                } else {
                    Timber.d("Invalid DELETE payload: %s", json);
                }
                return null;
            }
        });
    }

    ListenableFuture<Void> putCurrentUserFlags(final String json) {

        JsonObject jsonObject = parseJson(json);
        final FlagResponseStore<List<FlagResponse>> responseStore =
                new UserFlagResponseStore<>(jsonObject, new PutFlagResponseInterpreter());

        ListeningExecutorService service = MoreExecutors.listeningDecorator(executor);
        return service.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                initialized = true;
                Timber.d("PUT for user key: %s", currentUser.getKey());

                List<FlagResponse> flagResponseList = responseStore.getFlagResponse();
                if (flagResponseList != null) {
                    flagResponseSharedPreferences.clear();
                    flagResponseSharedPreferences.saveAll(flagResponseList);

                    userLocalSharedPreferences.saveCurrentUserFlags(UserManager.this.getSharedPreferencesEntries(flagResponseList));
                    UserManager.this.syncCurrentUserToActiveUserAndLog();
                } else {
                    Timber.d("Invalid PUT payload: %s", json);
                }
                return null;
            }
        });
    }

    ListenableFuture<Void> patchCurrentUserFlags(@NonNull final String json) {

        JsonObject jsonObject = parseJson(json);
        final FlagResponseStore<FlagResponse> responseStore
                = new UserFlagResponseStore<>(jsonObject, new PatchFlagResponseInterpreter());

        ListeningExecutorService service = MoreExecutors.listeningDecorator(executor);
        return service.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                initialized = true;
                FlagResponse flagResponse = responseStore.getFlagResponse();
                if (flagResponse != null) {
                    if (flagResponse.isVersionMissing() || flagResponseSharedPreferences.isVersionValid(flagResponse)) {
                        flagResponseSharedPreferences.updateStoredFlagResponse(flagResponse);

                        UserLocalSharedPreferences.SharedPreferencesEntries sharedPreferencesEntries = UserManager.this.getSharedPreferencesEntries(flagResponse);
                        userLocalSharedPreferences.patchCurrentUserFlags(sharedPreferencesEntries);
                        UserManager.this.syncCurrentUserToActiveUserAndLog();
                    }
                } else {
                    Timber.d("Invalid PATCH payload: %s", json);
                }
                return null;
            }
        });

    }

    @NonNull
    private JsonObject parseJson(String json) {
        JsonParser parser = jsonParser.get();
        if (json != null) {
            try {
                return parser.parse(json).getAsJsonObject();
            } catch (JsonSyntaxException | IllegalStateException exception) {
                Timber.e(exception);
            }
        }
        return new JsonObject();
    }

    @NonNull
    private UserLocalSharedPreferences.SharedPreferencesEntries getSharedPreferencesEntries(@Nullable FlagResponse flagResponse) {
        List<UserLocalSharedPreferences.SharedPreferencesEntry> sharedPreferencesEntryList
                = new ArrayList<>();

        if (flagResponse != null) {
            JsonElement v = flagResponse.getValue();
            String key = flagResponse.getKey();

            UserLocalSharedPreferences.SharedPreferencesEntry sharedPreferencesEntry = getSharedPreferencesEntry(flagResponse);
            if (sharedPreferencesEntry == null) {
                Timber.w("Found some unknown feature flag type for key: [" + key + "] value: [" + v.toString() + "]");
            } else {
                sharedPreferencesEntryList.add(sharedPreferencesEntry);
            }
        }

        return new UserLocalSharedPreferences.SharedPreferencesEntries(sharedPreferencesEntryList);

    }

    @NonNull
    private UserLocalSharedPreferences.SharedPreferencesEntries getSharedPreferencesEntries(@NonNull List<FlagResponse> flagResponseList) {
        List<UserLocalSharedPreferences.SharedPreferencesEntry> sharedPreferencesEntryList
                = new ArrayList<>();

        for (FlagResponse flagResponse : flagResponseList) {
            JsonElement v = flagResponse.getValue();
            String key = flagResponse.getKey();

            UserLocalSharedPreferences.SharedPreferencesEntry sharedPreferencesEntry = getSharedPreferencesEntry(flagResponse);
            if (sharedPreferencesEntry == null) {
                Timber.w("Found some unknown feature flag type for key: [" + key + "] value: [" + v.toString() + "]");
            } else {
                sharedPreferencesEntryList.add(sharedPreferencesEntry);
            }
        }

        return new UserLocalSharedPreferences.SharedPreferencesEntries(sharedPreferencesEntryList);

    }


    @Nullable
    private UserLocalSharedPreferences.SharedPreferencesEntry getSharedPreferencesEntry(@NonNull FlagResponse flagResponse) {
        String key = flagResponse.getKey();
        JsonElement element = flagResponse.getValue();

        if (element.isJsonObject() || element.isJsonArray()) {
            return new UserLocalSharedPreferences.StringSharedPreferencesEntry(key, element.toString());
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            return new UserLocalSharedPreferences.BooleanSharedPreferencesEntry(key, element.getAsBoolean());
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return new UserLocalSharedPreferences.FloatSharedPreferencesEntry(key, element.getAsFloat());
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return new UserLocalSharedPreferences.StringSharedPreferencesEntry(key, element.getAsString());
        }
        return null;
    }

    @VisibleForTesting
    void clearFlagResponseSharedPreferences() {
        this.flagResponseSharedPreferences.clear();
    }

}
