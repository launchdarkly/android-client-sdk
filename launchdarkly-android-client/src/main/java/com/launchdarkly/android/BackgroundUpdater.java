package com.launchdarkly.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

public class BackgroundUpdater {
    private static final int BACKGROUND_INTERVAL_MS = 10000;

    static void start(Context context) {
        Intent alarmIntent = new Intent(context, BackgroundUpdaterReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, alarmIntent, 0);
        AlarmManager alarmMgr = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
        alarmMgr.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + BACKGROUND_INTERVAL_MS,
                BACKGROUND_INTERVAL_MS,
                pendingIntent);
    }

    static void stop(Context context) {
        Intent alarmIntent = new Intent(context, BackgroundUpdaterReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, alarmIntent, 0);
        AlarmManager alarmMgr = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
        alarmMgr.cancel(pendingIntent);
    }


}
