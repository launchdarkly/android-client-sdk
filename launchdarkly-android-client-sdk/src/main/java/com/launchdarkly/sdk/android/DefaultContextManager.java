package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.google.gson.JsonObject;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.json.JsonSerialization;
import com.launchdarkly.sdk.json.SerializationException;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Persists and retrieves feature flag values for different {@link LDUser}s.
 * Also enables realtime updates via registering a {@link FeatureFlagChangeListener}
 * with a feature flag.
 */
class DefaultContextManager implements ContextManager {

    private final FeatureFetcher fetcher;

    private final Application application;
    private final FlagStoreManager flagStoreManager;
    private final SummaryEventStore summaryEventStore;
    private final String environmentName;
    private final LDLogger logger;

    private LDUser currentUser;
    private LDContext currentContext;

    private final ExecutorService executor;

    static synchronized DefaultContextManager newInstance(Application application, FeatureFetcher fetcher, String environmentName,
                                                       String mobileKey, int maxCachedUsers, LDLogger logger) {
        return new DefaultContextManager(application, fetcher, environmentName, mobileKey, maxCachedUsers, logger);
    }

    DefaultContextManager(Application application, FeatureFetcher fetcher, String environmentName, String mobileKey,
                       int maxCachedUsers, LDLogger logger) {
        this.application = application;
        this.fetcher = fetcher;
        this.flagStoreManager = new SharedPrefsFlagStoreManager(application, mobileKey,
                new SharedPrefsFlagStoreFactory(application, logger), maxCachedUsers, logger);
        this.summaryEventStore = new SharedPrefsSummaryEventStore(application,
                LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + "-summaryevents", logger);
        this.environmentName = environmentName;
        this.logger = logger;

        executor = new BackgroundThreadExecutor().newFixedThreadPool(1);
    }

    public LDUser getCurrentUser() {
        return currentUser;
    }

    public LDContext getCurrentContext() {
        return currentContext;
    }

    FlagStore getCurrentUserFlagStore() {
        return flagStoreManager.getCurrentContextStore();
    }

    SummaryEventStore getSummaryEventStore() {
        return summaryEventStore;
    }

    private static String toJson(final LDUser user) {
        return LDConfig.GSON.toJson(user);
    }

    public static String base64Url(final LDUser user) {
        return Base64.encodeToString(toJson(user).getBytes(), Base64.URL_SAFE + Base64.NO_WRAP);
    }

    static final UserHasher HASHER = new UserHasher();

    public static String sharedPreferencesKey(final LDContext context) {
        return HASHER.hash(context.getFullyQualifiedKey());
    }

    /**
     * Sets the current user. If there are more than MAX_USERS stored in shared preferences,
     * the oldest one is deleted.
     *
     * @param user The user to switch to.
     */
    void setCurrentUser(final LDUser user) {
        String userBase64 = base64Url(user);
        logger.debug("Setting current user to: [{}] [{}]", userBase64, userBase64ToJson(userBase64));
        currentUser = user;
        currentContext = userToContext(user);
        flagStoreManager.switchToContext(DefaultContextManager.sharedPreferencesKey(currentContext));
    }

    static LDContext userToContext(LDUser user) {
        // This is a temporary method to allow us to migrate some pieces of SDK implementation to
        // the context model while the API is still using the old user model. Because LDUser has
        // looser validation than LDContext - specifically, it allows an empty string key - we'll
        // do this conversion inefficiently by converting to and from JSON; LDContext's JSON
        // deserializer allows an empty string key only when parsing from old-style user JSON.
        try {
            return JsonSerialization.deserialize(JsonSerialization.serialize(user), LDContext.class);
        } catch (SerializationException e) {
            throw new RuntimeException(e); // shouldn't be possible
        }
    }

    /**
     * fetch flags for the current user then update the current flags with the new flags
     */
    public void updateCurrentUser(final LDUtil.ResultCallback<Void> onCompleteListener) {
        fetcher.fetch(currentUser, new LDUtil.ResultCallback<JsonObject>() {
            @Override
            public void onSuccess(JsonObject result) {
                saveFlagSettings(result, onCompleteListener);
            }

            @Override
            public void onError(Throwable e) {
                if (LDUtil.isClientConnected(application, environmentName)) {
                    logger.error("Error when attempting to set user: [{}] [{}]: {}",
                            base64Url(currentUser),
                            userBase64ToJson(base64Url(currentUser)),
                            LogValues.exceptionSummary(e));
                }
                onCompleteListener.onError(e);
            }
        });
    }

    void registerListener(final String key, final FeatureFlagChangeListener listener) {
        flagStoreManager.registerListener(key, listener);
    }

    void unregisterListener(String key, FeatureFlagChangeListener listener) {
        flagStoreManager.unRegisterListener(key, listener);
    }

    void registerAllFlagsListener(@NonNull final LDAllFlagsListener listener) {
        flagStoreManager.registerAllFlagsListener(listener);
    }

    void unregisterAllFlagsListener(@NonNull final LDAllFlagsListener listener) {
        flagStoreManager.unregisterAllFlagsListener(listener);
    }

    /**
     * Saves the flags param to {@link SharedPreferences} for the current user.
     * Completely overwrites all values in the current user's {@link SharedPreferences} and
     * saves those values to the active user, triggering any registered {@link FeatureFlagChangeListener}
     * objects.
     *
     * @param flagsJson
     */
    @SuppressWarnings("JavaDoc")
    private void saveFlagSettings(JsonObject flagsJson, LDUtil.ResultCallback<Void> onCompleteListener) {
        logger.debug("saveFlagSettings for user key: {}", currentUser.getKey());

        try {
            final List<Flag> flags = GsonCache.getGson().fromJson(flagsJson, FlagsResponse.class).getFlags();
            flagStoreManager.getCurrentContextStore().clearAndApplyFlagUpdates(flags);
            onCompleteListener.onSuccess(null);
        } catch (Exception e) {
            logger.debug("Invalid JsonObject for flagSettings: {}", flagsJson);
            onCompleteListener.onError(new LDFailure("Invalid Json received from flags endpoint", e, LDFailure.FailureType.INVALID_RESPONSE_BODY));
        }
    }

    private static String userBase64ToJson(String base64) {
        return new String(Base64.decode(base64, Base64.URL_SAFE));
    }

    public void deleteCurrentUserFlag(@NonNull final String json, final LDUtil.ResultCallback<Void> onCompleteListener) {
        try {
            final DeleteFlagResponse deleteFlagResponse = GsonCache.getGson().fromJson(json, DeleteFlagResponse.class);
            executor.submit(() -> {
                if (deleteFlagResponse != null) {
                    flagStoreManager.getCurrentContextStore().applyFlagUpdate(deleteFlagResponse);
                    onCompleteListener.onSuccess(null);
                } else {
                    logger.debug("Invalid DELETE payload: {}", json);
                    onCompleteListener.onError(new LDFailure("Invalid DELETE payload",
                            LDFailure.FailureType.INVALID_RESPONSE_BODY));
                }
            });
        } catch (Exception ex) {
            logger.debug("Invalid DELETE payload: {}", json);
            onCompleteListener.onError(new LDFailure("Invalid DELETE payload", ex,
                    LDFailure.FailureType.INVALID_RESPONSE_BODY));
        }
    }

    public void putCurrentUserFlags(final String json, final LDUtil.ResultCallback<Void> onCompleteListener) {
        try {
            final List<Flag> flags = GsonCache.getGson().fromJson(json, FlagsResponse.class).getFlags();
            executor.submit(() -> {
                logger.debug("PUT for user key: {}", currentUser.getKey());
                flagStoreManager.getCurrentContextStore().clearAndApplyFlagUpdates(flags);
                onCompleteListener.onSuccess(null);
            });
        } catch (Exception ex) {
            logger.debug("Invalid PUT payload: {}", json);
            onCompleteListener.onError(new LDFailure("Invalid PUT payload", ex,
                    LDFailure.FailureType.INVALID_RESPONSE_BODY));
        }
    }

    public void patchCurrentUserFlags(@NonNull final String json, final LDUtil.ResultCallback<Void> onCompleteListener) {
        try {
            final Flag flag = GsonCache.getGson().fromJson(json, Flag.class);
            executor.submit(() -> {
                if (flag != null) {
                    flagStoreManager.getCurrentContextStore().applyFlagUpdate(flag);
                    onCompleteListener.onSuccess(null);
                } else {
                    logger.debug("Invalid PATCH payload: {}", json);
                    onCompleteListener.onError(new LDFailure("Invalid PATCH payload",
                            LDFailure.FailureType.INVALID_RESPONSE_BODY));
                }
            });
        } catch (Exception ex) {
            logger.debug("Invalid PATCH payload: {}", json);
            onCompleteListener.onError(new LDFailure("Invalid PATCH payload", ex,
                    LDFailure.FailureType.INVALID_RESPONSE_BODY));
        }
    }

    @VisibleForTesting
    Collection<FeatureFlagChangeListener> getListenersByKey(String key) {
        return flagStoreManager.getListenersByKey(key);
    }
}
