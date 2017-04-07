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

import static com.launchdarkly.android.Util.isInternetConnected;

public class PollingUpdater extends BroadcastReceiver {
    private static final String TAG = "LDPollingUpdater";

    // This is set in com.launchdarkly.android.LDConfig.Builder.build()
    static int backgroundPollingIntervalMillis = LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS;

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            LDClient client = LDClient.get();
            if (client != null && !client.isOffline() && isInternetConnected(context)) {
                Log.d(TAG, "onReceive connected to the internet!");
                UserManager userManager = UserManager.get();
                if (userManager == null) {
                    Log.e(TAG, "UserManager singleton was accessed before it was initialized! doing nothing");
                    return;
                }
                userManager.updateCurrentUser().get(15, TimeUnit.SECONDS);
            } else {
                Log.d(TAG, "onReceive with no internet connection! Skipping fetch.");
            }
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "Exception caught when awaiting update", e);
        } catch (TimeoutException e) {
            Log.e(TAG, "Feature Flag update timed out", e);
        } catch (LaunchDarklyException e) {
            Log.e(TAG, "Exception when getting client", e);
        }
    }

    synchronized static void startBackgroundPolling(Context context) {
        Log.d(TAG, "Starting background polling");
        startPolling(context, backgroundPollingIntervalMillis, backgroundPollingIntervalMillis);
    }

    synchronized static void startPolling(Context context, int initialDelayMillis, int intervalMillis) {
        stop(context);
        Log.d(TAG, "startPolling with initialDelayMillis: " + initialDelayMillis + " intervalMillis: " + intervalMillis);
        PendingIntent pendingIntent = getPendingIntent(context);
        AlarmManager alarmMgr = getAlarmManager(context);

        alarmMgr.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + initialDelayMillis,
                intervalMillis,
                pendingIntent);
    }

    synchronized static void stop(Context context) {
        Log.d(TAG, "Stopping pollingUpdater");
        PendingIntent pendingIntent = getPendingIntent(context);
        AlarmManager alarmMgr = getAlarmManager(context);

        alarmMgr.cancel(pendingIntent);
    }

    private static Intent getAlarmIntent(Context context) {
        return new Intent(context, PollingUpdater.class);
    }

    private static PendingIntent getPendingIntent(Context context) {
        return PendingIntent.getBroadcast(context, 0, getAlarmIntent(context), 0);
    }

    private static AlarmManager getAlarmManager(Context context) {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }
}
