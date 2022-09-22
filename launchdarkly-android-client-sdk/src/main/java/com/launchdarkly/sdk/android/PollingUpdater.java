package com.launchdarkly.sdk.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.LDContext;

class PollingUpdater extends BroadcastReceiver {
    private static final String TASK_IDENTIFIER = PollingUpdater.class.getName();

    @Override
    public void onReceive(Context context, Intent intent) {
        LDClient.triggerPollInstances();
    }

    static void triggerPoll(
            PlatformState platformState,
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
                if (platformState.isNetworkAvailable()) {
                    logger.error("Error when attempting to get flag data: [{}] [{}]: {}",
                            LDUtil.base64Url(currentContext),
                            currentContext,
                            LogValues.exceptionSummary(e));
                }
                onCompleteListener.onError(e);
            }
        });
    }

    synchronized static void startPolling(
            TaskExecutor taskExecutor,
            int initialDelayMillis,
            int intervalMillis
    ) {
        taskExecutor.stopRepeatingTask(TASK_IDENTIFIER);
        taskExecutor.startRepeatingTask(
                TASK_IDENTIFIER,
                new Runnable() {
                    @Override
                    public void run() {
                        LDClient.triggerPollInstances();
                    }
                },
                intervalMillis,
                initialDelayMillis
        );
    }

    synchronized static void stop(TaskExecutor taskExecutor) {
        taskExecutor.stopRepeatingTask(TASK_IDENTIFIER);
    }
}
