package com.launchdarkly.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import static com.launchdarkly.android.LDUtil.isInternetConnected;

/**
 * Used internally by the SDK.
 */
public class ConnectivityReceiver extends BroadcastReceiver {

    static final String CONNECTIVITY_CHANGE = "android.net.conn.CONNECTIVITY_CHANGE";
    private boolean knownState = false;
    private boolean lastState = false;

    @Override
    public synchronized void onReceive(Context context, Intent intent) {
        if (!CONNECTIVITY_CHANGE.equals(intent.getAction())) {
            return;
        }

        boolean connectionStatus = isInternetConnected(context);
        if (knownState && lastState == connectionStatus) {
            return;
        }

        LDClient.onNetworkConnectivityChangeInstances(connectionStatus);
        knownState = true;
        lastState = connectionStatus;
    }
}
