package com.launchdarkly.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.launchdarkly.android.gson.GsonCache;

import java.util.HashMap;
import java.util.Map;

import timber.log.Timber;

public class Util {

    /**
     * Looks at the Android device status to determine if the device is online.
     *
     * @param context Context for getting the ConnectivityManager
     * @return whether device is connected to the internet
     */
    static boolean isInternetConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    /**
     * Looks at both the Android device status and the default {@link LDClient} to determine if any network calls should be made.
     *
     * @param context Context for getting the ConnectivityManager
     * @return whether the device is connected to the internet and the default LDClient instance is online
     */
    static boolean isClientConnected(Context context) {
        boolean deviceConnected = isInternetConnected(context);
        try {
            return deviceConnected && !LDClient.get().isOffline();
        } catch (LaunchDarklyException e) {
            Timber.e(e, "Exception caught when getting LDClient");
            return false;
        }
    }

    /**
     * Looks at both the Android device status and the environment's {@link LDClient} to determine if any network calls should be made.
     *
     * @param context         Context for getting the ConnectivityManager
     * @param environmentName Name of the environment to get the LDClient for
     * @return whether the device is connected to the internet and the LDClient instance is online
     */
    static boolean isClientConnected(Context context, String environmentName) {
        boolean deviceConnected = isInternetConnected(context);
        try {
            return deviceConnected && !LDClient.getForMobileKey(environmentName).isOffline();
        } catch (LaunchDarklyException e) {
            Timber.e(e, "Exception caught when getting LDClient");
            return false;
        }
    }

    // Android API v16 doesn't support Objects.equals()
    public static <T> boolean objectsEqual(@Nullable T a, @Nullable T b) {
        return a == b || (a != null && a.equals(b));
    }

    @NonNull
    public static <T> Map<String, T> sharedPrefsGetAllGson(SharedPreferences sharedPreferences, Class<T> typeOf) {
        Map<String, ?> flags = sharedPreferences.getAll();
        Map<String, T> deserialized = new HashMap<>();
        for (Map.Entry<String, ?> entry : flags.entrySet()) {
            if (entry.getValue() instanceof String) {
                try {
                    T obj = GsonCache.getGson().fromJson((String) entry.getValue(), typeOf);
                    deserialized.put(entry.getKey(), obj);
                } catch (Exception ignored) {
                }
            }
        }
        return deserialized;
    }

    public static <T> T sharedPrefsGetGson(SharedPreferences sharedPreferences, Class<T> typeOf, String key) {
        String data = sharedPreferences.getString(key, null);
        if (data == null) return null;
        try {
            return GsonCache.getGson().fromJson(data, typeOf);
        } catch (Exception ignored) {
            return null;
        }
    }

    public interface ResultCallback<T> {
        void onSuccess(T result);
        void onError(Throwable e);
    }
}
