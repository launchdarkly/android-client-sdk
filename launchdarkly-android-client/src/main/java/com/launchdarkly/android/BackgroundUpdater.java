package com.launchdarkly.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import java.util.concurrent.ExecutionException;

public class BackgroundUpdater extends BroadcastReceiver {
    private static final String TAG = "LDBackgroundUpdater";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Alarm Background update starting...");
        Toast.makeText(context, "I'm running", Toast.LENGTH_LONG).show();
//        try {
//            FeatureFlagUpdater.getInstance().update().get();
//        } catch (InterruptedException | ExecutionException e) {
//            Log.e(TAG, "Exception caught when awaiting update", e);
//        }
    }
}
