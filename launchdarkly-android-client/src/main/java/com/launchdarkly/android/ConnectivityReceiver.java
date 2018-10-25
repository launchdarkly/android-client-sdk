package com.launchdarkly.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import timber.log.Timber;

import static com.launchdarkly.android.Util.isInternetConnected;

public class ConnectivityReceiver extends BroadcastReceiver {

    static final String CONNECTIVITY_CHANGE = "android.net.conn.CONNECTIVITY_CHANGE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (isInternetConnected(context)) {
            Timber.d("Connected to the internet");
            try {
                LDClient ldClient = LDClient.get();
                if (!ldClient.isOffline()) {
                    if (Foreground.get(context).isForeground()) {
                        ldClient.startForegroundUpdating();
                    } else if (!ldClient.isDisableBackgroundPolling()){
                        PollingUpdater.startBackgroundPolling(context);
                    }
                }
            } catch (LaunchDarklyException e) {
                Timber.e(e, "Tried to restart foreground updating, but LDClient has not yet been initialized.");
            }
        } else {
            Timber.d("Not Connected to the internet");
            try {
                LDClient ldClient = LDClient.get();
                ldClient.stopForegroundUpdating();
            } catch (LaunchDarklyException e) {
                Timber.e(e, "Tried to stop foreground updating, but LDClient has not yet been initialized.");
            }
        }
    }
}
