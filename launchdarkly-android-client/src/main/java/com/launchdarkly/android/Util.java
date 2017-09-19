package com.launchdarkly.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import com.launchdarkly.eventsource.MessageEvent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Util {
    private static final String TAG = "LDUtil";
    private static ScheduledFuture<?> functionHandler;
    private static ScheduledExecutorService functionScheduler;

    static {
        Runnable initialEvent = new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Started function queue");
            }
        };
        functionScheduler = Executors.newScheduledThreadPool(1);
        functionHandler = functionScheduler.schedule(initialEvent, 1, TimeUnit.MILLISECONDS);
    }

    /**
     * Looks at both the Android device status and the {@link LDClient} to determine if any network calls should be made.
     *
     * @param context
     * @return
     */
    static boolean isInternetConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean deviceConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        try {
            return deviceConnected && !LDClient.get().isOffline();
        } catch (LaunchDarklyException e) {
            Log.e(TAG, "Exception caught when getting LDClient", e);
            return false;
        }
    }

    static synchronized boolean queue(Runnable consumeFunction) {
        boolean scheduled = false;
        boolean interruptRunningTask = false;
        boolean cancelledPreviousHandler = functionHandler.cancel(interruptRunningTask);
        if (cancelledPreviousHandler || functionHandler.isDone()) {
            Log.i(TAG, "Put function in queue");
            functionHandler = functionScheduler.schedule(consumeFunction, LDConfig.DEFAULT_CONNECTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            scheduled = true;
        }
        return scheduled;
    }

}
