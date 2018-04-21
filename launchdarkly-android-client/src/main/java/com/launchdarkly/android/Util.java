package com.launchdarkly.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import timber.log.Timber;

class Util {

    /**
     * Looks at both the Android device status and the {@link LDClient} to determine if any network calls should be made.
     *
     * @param context
     * @return
     */
    static boolean isInternetConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean deviceConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        try {
            return deviceConnected && !LDClient.get().isOffline();
        } catch (LaunchDarklyException e) {
            Timber.e(e,"Exception caught when getting LDClient");
            return false;
        }
    }

    static class LazySingleton<T> {

        private final Provider<T> provider;
        private T instance;

        LazySingleton(Provider<T> provider) {
            this.provider = provider;
        }

        public T get() {
            if (instance == null) {
                instance = provider.get();
            }
            return instance;
        }
    }

    interface Provider<T> {

        T get();
    }
}
