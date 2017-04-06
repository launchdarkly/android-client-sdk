package com.launchdarkly.android;


import android.content.Context;
import android.util.Log;

import com.google.common.util.concurrent.ListenableFuture;

class PollingUpdateProcessor implements UpdateProcessor {
    private final String TAG = "LDPollingUpdater";
    private final Context context;
    private final UserManager userManager;
    private final LDConfig config;

    PollingUpdateProcessor(Context context, UserManager userManager, LDConfig config) {
        this.context = context;
        this.userManager = userManager;
        this.config = config;
    }

    @Override
    public ListenableFuture<Void> start() {
        Log.d(TAG, "Starting PollingUpdateProcessor");
        PollingUpdater.startPolling(context, config.getPollingIntervalMillis(), config.getPollingIntervalMillis());
        return userManager.updateCurrentUser();
    }

    @Override
    public void stop() {
        Log.d(TAG, "Stopping PollingUpdateProcessor");
        PollingUpdater.stop(context);
    }

    @Override
    public boolean isInitialized() {
        return userManager.isInitialized();
    }
}
