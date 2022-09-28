package com.launchdarkly.sdk.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import static android.app.PendingIntent.FLAG_IMMUTABLE;

import com.launchdarkly.logging.LogValues;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Used internally by the SDK.
 */
public class PollingUpdater extends BroadcastReceiver {

    private static int backgroundPollingIntervalMillis = LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS;

    private static AtomicBoolean pollingActive = new AtomicBoolean(false);
    private static AtomicInteger pollingInterval = new AtomicInteger();

    synchronized static void setBackgroundPollingIntervalMillis(int backgroundPollingInterval) {
        backgroundPollingIntervalMillis = backgroundPollingInterval;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (pollingActive.get()) {
            LDClient.triggerPollInstances();
        } else {
            // We received an alarm notification, but we had not set an alarm-- at least, not during
            // the current lifetime of the app. So this must be a leftover alarm from a previous run
            // of the app, which may have crashed or forgotten to shut down the SDK. If so,
            // AlarmManager might have restarted the app just for this alarm. That's unfortunate but
            // at least we can stop it from happening again, by cancelling the alarm now.
            stop(context);
        }
    }

    synchronized static void startBackgroundPolling(Context context) {
        LDClient.getSharedLogger().debug("Starting background polling");
        startPolling(context, backgroundPollingIntervalMillis, backgroundPollingIntervalMillis);
    }

    synchronized static void startPolling(Context context, int initialDelayMillis, int intervalMillis) {
        if (pollingActive.get()) {
            if (pollingInterval.get() == intervalMillis) {
                return;
            }
        }
        stop(context);
        LDClient.getSharedLogger().debug("startPolling with initialDelayMillis: %d and intervalMillis: %d", initialDelayMillis, intervalMillis);
        PendingIntent pendingIntent = getPendingIntent(context);
        AlarmManager alarmMgr = getAlarmManager(context);

        pollingActive.set(true);
        pollingInterval.set(intervalMillis);
        try {
            alarmMgr.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + initialDelayMillis,
                    intervalMillis,
                    pendingIntent);
        } catch (Exception ex) {
            LDUtil.logExceptionAtWarnLevel(LDClient.getSharedLogger(), ex,
                    "Exception occurred when creating [background] polling alarm, likely due to the host application having too many existing alarms");
            pollingActive.set(false);
        }
    }

    synchronized static void stop(Context context) {
        if (pollingActive.get()) {
            // We may have been called even if pollingActive wasn't true, just to stop any obsolete
            // alarm that may have been set in the past. But there's no point in logging a message
            // in that case.
            LDClient.getSharedLogger().debug("Stopping pollingUpdater");
        }
        PendingIntent pendingIntent = getPendingIntent(context);
        AlarmManager alarmMgr = getAlarmManager(context);

        alarmMgr.cancel(pendingIntent);
        pollingActive.set(false);
    }

    private static Intent getAlarmIntent(Context context) {
        return new Intent(context, PollingUpdater.class);
    }

    private static PendingIntent getPendingIntent(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.getBroadcast(context, 0, getAlarmIntent(context), FLAG_IMMUTABLE);
        } else {
            return PendingIntent.getBroadcast(context, 0, getAlarmIntent(context), 0);
        }
    }

    private static AlarmManager getAlarmManager(Context context) {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }
}
