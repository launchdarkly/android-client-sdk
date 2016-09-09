package com.launchdarkly.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BackgroundUpdater extends BroadcastReceiver {
    private static final String TAG = "LDBackgroundUpdater";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Alarm Background update starting...");
        try {
            FeatureFlagUpdater.getInstance().update().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Exception caught when awaiting update", e);
        } catch (TimeoutException e) {
            Log.e(TAG, "Feature Flag update timed out", e);
        }
    }
}
