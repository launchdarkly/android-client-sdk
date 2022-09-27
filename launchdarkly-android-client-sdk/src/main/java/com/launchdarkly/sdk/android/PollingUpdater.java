package com.launchdarkly.sdk.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

class PollingUpdater extends BroadcastReceiver {
    private static final String TASK_IDENTIFIER = PollingUpdater.class.getName();

    @Override
    public void onReceive(Context context, Intent intent) {
        LDClient.triggerPollInstances();
    }

    synchronized static void startPolling(
            TaskExecutor taskExecutor,
            int initialDelayMillis,
            int intervalMillis
    ) {
        taskExecutor.stopRepeatingTask(TASK_IDENTIFIER);
        taskExecutor.startRepeatingTask(
                TASK_IDENTIFIER,
                () -> LDClient.triggerPollInstances(),
                intervalMillis,
                initialDelayMillis
        );
    }

    synchronized static void stop(TaskExecutor taskExecutor) {
        taskExecutor.stopRepeatingTask(TASK_IDENTIFIER);
    }
}
