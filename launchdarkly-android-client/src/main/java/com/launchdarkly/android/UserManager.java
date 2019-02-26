package com.launchdarkly.android;

import android.app.Application;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.util.Base64;

import com.google.common.base.Function;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.gson.JsonObject;
import com.launchdarkly.android.flagstore.Flag;
import com.launchdarkly.android.flagstore.FlagStore;
import com.launchdarkly.android.flagstore.FlagStoreManager;
import com.launchdarkly.android.flagstore.sharedprefs.SharedPrefsFlagStoreFactory;
import com.launchdarkly.android.flagstore.sharedprefs.SharedPrefsFlagStoreManager;
import com.launchdarkly.android.gson.GsonCache;
import com.launchdarkly.android.response.DeleteFlagResponse;
import com.launchdarkly.android.response.FlagsResponse;

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
    private final FlagStoreManager flagStoreManager;
    private final SummaryEventSharedPreferences summaryEventSharedPreferences;
    private final String environmentName;

    private LDUser currentUser;

    private final ExecutorService executor;

    static synchronized UserManager newInstance(Application application, FeatureFlagFetcher fetcher, String environmentName, String mobileKey) {
        return new UserManager(application, fetcher, environmentName, mobileKey);
    }

    UserManager(Application application, FeatureFlagFetcher fetcher, String environmentName, String mobileKey) {
        this.application = application;
        this.fetcher = fetcher;
        this.flagStoreManager = new SharedPrefsFlagStoreManager(application, mobileKey, new SharedPrefsFlagStoreFactory(application));
        this.summaryEventSharedPreferences = new UserSummaryEventSharedPreferences(application, LDConfig.SHARED_PREFS_BASE_KEY + mobileKey + "-summaryevents");
        this.environmentName = environmentName;

        executor = new BackgroundThreadExecutor().newFixedThreadPool(1);
    }

    LDUser getCurrentUser() {
        return currentUser;
    }

    FlagStore getCurrentUserFlagStore() {
        return flagStoreManager.getCurrentUserStore();
    }

    SummaryEventSharedPreferences getSummaryEventSharedPreferences() {
        return summaryEventSharedPreferences;
    }

    /**
     * Sets the current user. If there are more than MAX_USERS stored in shared preferences,
     * the oldest one is deleted.
     *
     * @param user The user to switch to.
     */
    void setCurrentUser(final LDUser user) {
        String userBase64 = user.getAsUrlSafeBase64();
        Timber.d("Setting current user to: [%s] [%s]", userBase64, userBase64ToJson(userBase64));
        currentUser = user;
        flagStoreManager.switchToUser(user.getSharedPrefsKey());
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
                if (Util.isClientConnected(application, environmentName)) {
                    Timber.e(t, "Error when attempting to set user: [%s] [%s]", currentUser.getAsUrlSafeBase64(), userBase64ToJson(currentUser.getAsUrlSafeBase64()));
                }
//                syncCurrentUserToActiveUserAndLog();
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

    void registerListener(final String key, final FeatureFlagChangeListener listener) {
        flagStoreManager.registerListener(key, listener);
    }

    void unregisterListener(String key, FeatureFlagChangeListener listener) {
        flagStoreManager.unRegisterListener(key, listener);
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
    private void saveFlagSettings(JsonObject flagsJson) {
        Timber.d("saveFlagSettings for user key: %s", currentUser.getKey());

        try {
            final List<Flag> flags = GsonCache.getGson().fromJson(flagsJson, FlagsResponse.class).getFlags();
            flagStoreManager.getCurrentUserStore().clearAndApplyFlagUpdates(flags);
        } catch (Exception e) {
            Timber.d("Invalid JsonObject for flagSettings: %s", flagsJson);
        }
    }

    private static String userBase64ToJson(String base64) {
        return new String(Base64.decode(base64, Base64.URL_SAFE));
    }

    boolean isInitialized() {
        return initialized;
    }

    ListenableFuture<Void> deleteCurrentUserFlag(@NonNull final String json) {
        try {
            final DeleteFlagResponse deleteFlagResponse = GsonCache.getGson().fromJson(json, DeleteFlagResponse.class);
            ListeningExecutorService service = MoreExecutors.listeningDecorator(executor);
            return service.submit(new Runnable() {
                @Override
                public void run() {
                    initialized = true;
                    if (deleteFlagResponse != null) {
                        flagStoreManager.getCurrentUserStore().applyFlagUpdate(deleteFlagResponse);
                    } else {
                        Timber.d("Invalid DELETE payload: %s", json);
                    }
                }
            }, null);
        } catch (Exception ex) {
            Timber.d(ex, "Invalid DELETE payload: %s", json);
            // In future should this be an immediateFailedFuture?
            return Futures.immediateFuture(null);
        }
    }

    ListenableFuture<Void> putCurrentUserFlags(final String json) {
        try {
            final List<Flag> flags = GsonCache.getGson().fromJson(json, FlagsResponse.class).getFlags();
            ListeningExecutorService service = MoreExecutors.listeningDecorator(executor);
            return service.submit(new Runnable() {
                @Override
                public void run() {
                    initialized = true;
                    Timber.d("PUT for user key: %s", currentUser.getKey());
                    flagStoreManager.getCurrentUserStore().clearAndApplyFlagUpdates(flags);
                }
            }, null);
        } catch (Exception ex) {
            Timber.d(ex, "Invalid PUT payload: %s", json);
            // In future should this be an immediateFailedFuture?
            return Futures.immediateFuture(null);
        }
    }

    ListenableFuture<Void> patchCurrentUserFlags(@NonNull final String json) {
        try {
            final Flag flag = GsonCache.getGson().fromJson(json, Flag.class);
            ListeningExecutorService service = MoreExecutors.listeningDecorator(executor);
            return service.submit(new Runnable() {
                @Override
                public void run() {
                    initialized = true;
                    if (flag != null) {
                        flagStoreManager.getCurrentUserStore().applyFlagUpdate(flag);
                    } else {
                        Timber.d("Invalid PATCH payload: %s", json);
                    }
                }
            }, null);
        } catch (Exception ex) {
            Timber.d(ex, "Invalid PATCH payload: %s", json);
            // In future should this be an immediateFailedFuture?
            return Futures.immediateFuture(null);
        }
    }

    @VisibleForTesting
    public Collection<FeatureFlagChangeListener> getListenersByKey(String key) {
        return flagStoreManager.getListenersByKey(key);
    }
}
