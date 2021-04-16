package com.launchdarkly.sdk.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Used internally by the SDK.
 */
public class PollingUpdater extends BroadcastReceiver {

    private static int backgroundPollingIntervalMillis = LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS;

    synchronized static void setBackgroundPollingIntervalMillis(int backgroundPollingInterval) {
        backgroundPollingIntervalMillis = backgroundPollingInterval;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        LDClient.triggerPollInstances();
    }

    synchronized static void startBackgroundPolling(Context context) {
        LDConfig.LOG.d("Starting background polling");
        startPolling(context, backgroundPollingIntervalMillis, backgroundPollingIntervalMillis);
    }

    synchronized static void startPolling(Context context, int initialDelayMillis, int intervalMillis) {
        stop(context);
        LDConfig.LOG.d("startPolling with initialDelayMillis: %d and intervalMillis: %d", initialDelayMillis, intervalMillis);
        PendingIntent pendingIntent = getPendingIntent(context);
        AlarmManager alarmMgr = getAlarmManager(context);

        try {
            alarmMgr.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + initialDelayMillis,
                    intervalMillis,
                    pendingIntent);
        } catch (Exception ex) {
            LDConfig.LOG.w(ex, "Exception occurred when creating [background] polling alarm, likely due to the host application having too many existing alarms.");
        }
    }

    synchronized static void stop(Context context) {
        LDConfig.LOG.d("Stopping pollingUpdater");
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
