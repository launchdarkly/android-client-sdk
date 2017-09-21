package com.launchdarkly.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

public class Util {
    private static final String TAG = "LDUtil";

    static {
        Runnable initialEvent = new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Started function queue");
            }
        };
    }

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
            Log.e(TAG, "Exception caught when getting LDClient", e);
            return false;
        }
    }

}
