package com.launchdarkly.android;


import android.content.Context;

import com.google.common.util.concurrent.ListenableFuture;

import timber.log.Timber;

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
        Timber.d("Starting PollingUpdateProcessor");
        PollingUpdater.startPolling(context, config.getPollingIntervalMillis(), config.getPollingIntervalMillis());
        return userManager.updateCurrentUser();
    }

    @Override
    public void stop() {
        Timber.d("Stopping PollingUpdateProcessor");
        PollingUpdater.stop(context);
    }

    @Override
    public boolean isInitialized() {
        return userManager.isInitialized();
    }

    @Override
    public void restart() {
        stop();
        start();
    }
}
