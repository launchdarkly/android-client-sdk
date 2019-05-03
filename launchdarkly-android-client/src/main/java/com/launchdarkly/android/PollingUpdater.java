package com.launchdarkly.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import timber.log.Timber;

import static com.launchdarkly.android.Util.isClientConnected;
import static com.launchdarkly.android.Util.isInternetConnected;

public class PollingUpdater extends BroadcastReceiver {

    private static int backgroundPollingIntervalMillis = LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS;

    synchronized static void setBackgroundPollingIntervalMillis(int backgroundPollingInterval) {
        backgroundPollingIntervalMillis = backgroundPollingInterval;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Set<String> environments = LDClient.getEnvironmentNames();
            for (String environment : environments) {
                 LDClient.getForMobileKey(environment).triggerPoll();
            }
        } catch (LaunchDarklyException e) {
            Timber.e(e, "Exception when getting client");
        }
    }

    synchronized static void startBackgroundPolling(Context context) {
        Timber.d("Starting background polling");
        startPolling(context, backgroundPollingIntervalMillis, backgroundPollingIntervalMillis);
    }

    synchronized static void startPolling(Context context, int initialDelayMillis, int intervalMillis) {
        stop(context);
        Timber.d("startPolling with initialDelayMillis: %d and intervalMillis: %d", initialDelayMillis, intervalMillis);
        PendingIntent pendingIntent = getPendingIntent(context);
        AlarmManager alarmMgr = getAlarmManager(context);

        alarmMgr.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + initialDelayMillis,
                intervalMillis,
                pendingIntent);
    }

    synchronized static void stop(Context context) {
        Timber.d("Stopping pollingUpdater");
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
