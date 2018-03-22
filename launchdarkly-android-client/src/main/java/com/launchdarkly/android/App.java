package com.launchdarkly.android;

import android.app.Application;

import timber.log.Timber;

/**
 * Farhan
 * 2018-01-10
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }
    }
}
