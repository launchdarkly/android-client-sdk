package com.launchdarkly.sdk.android;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import static android.app.PendingIntent.FLAG_IMMUTABLE;

import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;

import com.google.gson.JsonObject;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.LDContext;

import java.util.List;

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

    static void triggerPoll(
            Application application,
            ContextDataManager contextDataManager,
            FeatureFetcher fetcher,
            final LDUtil.ResultCallback<Void> onCompleteListener,
            LDLogger logger
    ) {
        LDContext currentContext = contextDataManager.getCurrentContext();
        fetcher.fetch(currentContext, new LDUtil.ResultCallback<String>() {
            @Override
            public void onSuccess(String flagsJson) {
                contextDataManager.initDataFromJson(currentContext, flagsJson, onCompleteListener);
            }

            @Override
            public void onError(Throwable e) {
                if (LDUtil.isInternetConnected(application)) {
                    logger.error("Error when attempting to get flag data: [{}] [{}]: {}",
                            LDUtil.base64Url(currentContext),
                            currentContext,
                            LogValues.exceptionSummary(e));
                }
                onCompleteListener.onError(e);
            }
        });
    }

    synchronized static void startBackgroundPolling(Context context, LDLogger logger) {
        logger.debug("Starting background polling");
        startPolling(context, backgroundPollingIntervalMillis, backgroundPollingIntervalMillis, logger);
    }

    synchronized static void startPolling(Context context, int initialDelayMillis, int intervalMillis, LDLogger logger) {
        stop(context, logger);
        logger.debug("startPolling with initialDelayMillis: {} and intervalMillis: {}", initialDelayMillis, intervalMillis);
        PendingIntent pendingIntent = getPendingIntent(context);
        AlarmManager alarmMgr = getAlarmManager(context);

        try {
            alarmMgr.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + initialDelayMillis,
                    intervalMillis,
                    pendingIntent);
        } catch (Exception ex) {
            LDUtil.logExceptionAtWarnLevel(logger, ex,
                    "Exception occurred when creating [background] polling alarm, likely due to the host application having too many existing alarms");
        }
    }

    synchronized static void stop(Context context, LDLogger logger) {
        logger.debug("Stopping pollingUpdater");
        PendingIntent pendingIntent = getPendingIntent(context);
        AlarmManager alarmMgr = getAlarmManager(context);

        alarmMgr.cancel(pendingIntent);
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
