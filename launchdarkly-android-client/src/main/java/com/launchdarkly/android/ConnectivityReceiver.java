package com.launchdarkly.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import timber.log.Timber;

import static com.launchdarkly.android.Util.isInternetConnected;

public class ConnectivityReceiver extends BroadcastReceiver {

    static final String CONNECTIVITY_CHANGE = "android.net.conn.CONNECTIVITY_CHANGE";
    private boolean knownState = false;
    private boolean lastState = false;

    @Override
    public synchronized void onReceive(Context context, Intent intent) {
        if(!CONNECTIVITY_CHANGE.equals(intent.getAction())) {
            return;
        }

        boolean connectionStatus = isInternetConnected(context);
        if (knownState && lastState == connectionStatus) {
            return;
        }

        try {
            for (String environmentName : LDClient.getEnvironmentNames()) {
                LDClient.getForMobileKey(environmentName).onNetworkConnectivityChange(connectionStatus);
            }
            knownState = true;
            lastState = connectionStatus;
        } catch (LaunchDarklyException e) {
            Timber.e(e, "Tried to update LDClients with network connectivity status, but LDClient has not yet been initialized.");
        }
    }
}
