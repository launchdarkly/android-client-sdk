package com.launchdarkly.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BackgroundUpdater extends BroadcastReceiver {
    private static final String TAG = "LDBackgroundReceiver";
    private static final int BACKGROUND_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "onReceive...");
        try {
            FeatureFlagUpdater featureFlagUpdater = FeatureFlagUpdater.getInstance();
            if (featureFlagUpdater == null) {
                Log.e(TAG, "FeatureFlagUpdater singleton was accessed before it was initialized! doing nothing");
                return;
            }
            featureFlagUpdater.update().get(15, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Exception caught when awaiting update", e);
        } catch (TimeoutException e) {
            Log.e(TAG, "Feature Flag update timed out", e);
        }
    }

    static void start(Context context) {
        PendingIntent pendingIntent = getPendingIntent(context);
        AlarmManager alarmMgr = getAlarmManager(context);

        alarmMgr.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + BACKGROUND_INTERVAL_MS,
                BACKGROUND_INTERVAL_MS,
                pendingIntent);
    }

    static void stop(Context context) {
        PendingIntent pendingIntent = getPendingIntent(context);
        AlarmManager alarmMgr = getAlarmManager(context);

        alarmMgr.cancel(pendingIntent);
    }

    private static Intent getAlarmIntent(Context context) {
        return new Intent(context, BackgroundUpdater.class);
    }

    private static PendingIntent getPendingIntent(Context context) {
        return PendingIntent.getBroadcast(context, 0, getAlarmIntent(context), 0);
    }

    private static AlarmManager getAlarmManager(Context context) {
        return (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
    }
}