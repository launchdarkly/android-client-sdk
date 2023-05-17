package com.launchdarkly.sdk.android;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;

import com.launchdarkly.logging.LDLogger;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.internal.concurrent.Task;

/**
 * Standard implementation of {@link PlatformState}, providing a facade for detecting system state
 * via the Android APIs. The SDK always uses this implementation, but tests can substitute a mock.
 */
final class AndroidPlatformState implements PlatformState {
    private final Application application;
    private final TaskExecutor taskExecutor;
    private final LDLogger logger;
    private final ConnectivityReceiver connectivityReceiver;
    private final Application.ActivityLifecycleCallbacks lifecycleCallbacks;
    private final CopyOnWriteArrayList<ConnectivityChangeListener> connectivityChangeListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ForegroundChangeListener> foregroundChangeListeners =
            new CopyOnWriteArrayList<>();

    private final AtomicBoolean foreground = new AtomicBoolean(false);
    private volatile boolean paused = true;

    interface TestApplicationForegroundStateOverride {
        boolean isTestFixtureInitiallyInForeground();
    }

    public AndroidPlatformState(
            Application application,
            TaskExecutor taskExecutor,
            LDLogger logger
    ) {
        this.application = application;
        this.taskExecutor = taskExecutor;
        this.logger = logger;

        connectivityReceiver = new ConnectivityReceiver();
        IntentFilter filter = new IntentFilter(ConnectivityReceiver.CONNECTIVITY_CHANGE);
        application.registerReceiver(connectivityReceiver, filter);

        // When we are first starting up, we can't use the ActivityLifecycleCallbacks mechanism to
        // determine whether we're in the foreground or not, because the last foreground or
        // background transition already happened before we had a chance to register a listener.
        // Instead, we're calling ActivityManager.getMyMemoryState() and checking the "importance"
        // field.
        //
        // This is *not* considered a very reliable mechanism for detecting foreground/background
        // state changes (see for instance: https://stackoverflow.com/questions/5504632/how-can-i-tell-if-android-app-is-running-in-the-foreground/26662639#26662639)
        // but we haven't found a better option. It is probably good enough for the usual scenario
        // where the app initializes the SDK as the app is starting up, which would normally be in
        // the foreground and not happening in the middle of an activity state transition. We have
        // not been getting reports like "the SDK didn't attempt to open a stream connection at
        // startup time", as we would expect if it were wrongly perceiving a background state.
        //
        // Unfortunately, since ActivityManager.getMyMemoryState() is a static method, we can't mock
        // or override it for testing. As a compromise, in order to still be able to test the rest
        // of the OS interactions in AndroidPlatformState, we've defined a special interface that
        // our test implementation of Application can use to override the initial state
        if (application instanceof TestApplicationForegroundStateOverride) {
            foreground.set(((TestApplicationForegroundStateOverride)application)
                    .isTestFixtureInitiallyInForeground());
        } else {
            ActivityManager.RunningAppProcessInfo appProcessInfo = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(appProcessInfo);
            foreground.set(appProcessInfo.importance == IMPORTANCE_FOREGROUND
                    || appProcessInfo.importance == IMPORTANCE_VISIBLE);
        }
        lifecycleCallbacks = new ActivityCallbacks();
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks);
    }

    @Override
    public boolean isNetworkAvailable() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    application.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network net = cm.getActiveNetwork();
                if (net == null)
                    return false;

                NetworkCapabilities nwc = cm.getNetworkCapabilities(net);

                // the older solution was cleaner but android went and
                // deprecated it :^)
                // hasTransport(NET_CAPABILITY_INTERNET) always returns false on emulators
                // so we check these instead
                return nwc != null && (
                        nwc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                || nwc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                                || nwc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                                || nwc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                                || nwc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                );
            } else {
                NetworkInfo active = cm.getActiveNetworkInfo();
                return active != null && active.isConnectedOrConnecting();
            }
        } catch (SecurityException ignored) {
            // See https://issuetracker.google.com/issues/175055271
            // We should fallback to assuming network is available
            return true;
        }
    }

    @Override
    public void addConnectivityChangeListener(ConnectivityChangeListener listener) {
        connectivityChangeListeners.add(listener);
    }

    @Override
    public void removeConnectivityChangeListener(ConnectivityChangeListener listener) {
        connectivityChangeListeners.remove(listener);
    }

    @Override
    public boolean isForeground() {
        return foreground.get();
    }

    @Override
    public void addForegroundChangeListener(ForegroundChangeListener listener) {
        foregroundChangeListeners.add(listener);
    }

    @Override
    public void removeForegroundChangeListener(ForegroundChangeListener listener) {
        foregroundChangeListeners.remove(listener);
    }

    @Override
    public File getCacheDir() {
        return application.getCacheDir();
    }

    @Override
    public void close() {
        connectivityChangeListeners.clear();
        foregroundChangeListeners.clear();
        application.unregisterReceiver(connectivityReceiver);
        application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
    }

    private final class ConnectivityReceiver extends BroadcastReceiver {
        static final String CONNECTIVITY_CHANGE = "android.net.conn.CONNECTIVITY_CHANGE";
        private boolean knownState = false;
        private boolean lastState = false;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (!CONNECTIVITY_CHANGE.equals(intent.getAction())) {
                return;
            }

            boolean connectionStatus;
            synchronized (this) {
                connectionStatus = isNetworkAvailable();
                if (knownState && lastState == connectionStatus) {
                    return;
                }
                knownState = true;
                lastState = connectionStatus;
            }

            for (ConnectivityChangeListener listener: connectivityChangeListeners) {
                listener.onConnectivityChanged(connectionStatus);
            }
        }
    }

    private final class ActivityCallbacks implements Application.ActivityLifecycleCallbacks {
        private static final int WAIT_AFTER_PAUSED_MILLIS = 500;

        private volatile ScheduledFuture<?> deferredOnPauseTask = null;

        // The implementation of this class uses an approach similar to what is described here:
        //   https://stackoverflow.com/a/15573121
        // The basic issue is that ActivityLifecycleCallbacks methods are called for state
        // transitions of any activity within the application-- not for the application as a
        // whole. See comments in each method below.

        @Override
        public void onActivityResumed(Activity activity) {
            // If any activity in the app has resumed, then it's safe to say the app is now in
            // the foreground.

            paused = false;
            boolean wasForeground = foreground.getAndSet(true);

            if (wasForeground) {
                logger.debug("activity resumed while already in foreground");
            } else {
                logger.debug("activity resumed, we are now in foreground");
                taskExecutor.scheduleTask(() -> {
                    for (ForegroundChangeListener listener: foregroundChangeListeners) {
                        listener.onForegroundChanged(true);
                    }
                }, 0);
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
            // This is trickier than onActivityResumed, because if an activity is paused, it could
            // simply mean that the user has switched to a different activity in the same app-- or
            // it could mean the entire app has been backgrounded. The latter state is what we're
            // interested in, and we detect it by simply waiting a little while and seeing that no
            // other activity has resumed.

            if (!foreground.get()) {
                return;
            }
            paused = true;

            if (deferredOnPauseTask != null) {
                deferredOnPauseTask.cancel(false);
            }
            logger.debug("activity paused; waiting to see if another activity resumes");

            deferredOnPauseTask = taskExecutor.scheduleTask(() -> {
                if (!paused) {
                    // An activity has resumed while we were delaying, so we're not in the background
                    return;
                }
                if (foreground.getAndSet(false)) {
                    logger.debug("went background");
                    for (ForegroundChangeListener listener : foregroundChangeListeners) {
                        listener.onForegroundChanged(false);
                    }
                }
            }, WAIT_AFTER_PAUSED_MILLIS);
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }
}
