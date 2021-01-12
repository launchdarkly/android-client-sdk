package com.launchdarkly.android;

import android.os.Build;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.net.NetworkInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import timber.log.Timber;

class LDUtil {

    /**
     * Looks at the Android device status to determine if the device is online.
     *
     * @param context Context for getting the ConnectivityManager
     * @return whether device is connected to the internet
     */
    @SuppressWarnings("deprecation")
    static boolean isInternetConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        // TODO: at the point our min version is >= 23 we can remove the old compat code
        if (Build.VERSION.SDK_INT >= 23) {
            Network net = cm.getActiveNetwork();
            if (net == null)
                return false;

            NetworkCapabilities nwc = cm.getNetworkCapabilities(net);

            // the older solution was cleaner but android went and
            // deprecated it :^)
            // hasTransport(NET_CAPABILITY_INTERNET) always returns false on emulators
            // so we check these instead
            return nwc != null && (
                nwc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || nwc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || nwc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || nwc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
            );
        } else {
            NetworkInfo active = cm.getActiveNetworkInfo();
            return active != null && active.isConnectedOrConnecting();
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

    @NonNull
    static <T> Map<String, T> sharedPrefsGetAllGson(SharedPreferences sharedPreferences, Class<T> typeOf) {
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

    static <T> T sharedPrefsGetGson(SharedPreferences sharedPreferences, Class<T> typeOf, String key) {
        String data = sharedPreferences.getString(key, null);
        if (data == null) return null;
        try {
            return GsonCache.getGson().fromJson(data, typeOf);
        } catch (Exception ignored) {
            return null;
        }
    }

    interface ResultCallback<T> {
        void onSuccess(T result);
        void onError(Throwable e);
    }

    /**
     * Tests whether an HTTP error status represents a condition that might resolve on its own if we retry.
     * @param statusCode the HTTP status
     * @return true if retrying makes sense; false if it should be considered a permanent failure
     */
    static boolean isHttpErrorRecoverable(int statusCode) {
        if (statusCode >= 400 && statusCode < 500) {
            switch (statusCode) {
                case 400: // bad request
                case 408: // request timeout
                case 429: // too many requests
                    return true;
                default:
                    return false; // all other 4xx errors are unrecoverable
            }
        }
        return true;
    }
}
