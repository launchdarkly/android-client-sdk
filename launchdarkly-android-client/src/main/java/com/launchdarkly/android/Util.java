package com.launchdarkly.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import timber.log.Timber;

class Util {

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
}
