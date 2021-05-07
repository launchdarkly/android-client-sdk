package com.launchdarkly.sdk.android;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;

// From: https://gist.github.com/steveliles/11116937

/**
 * Usage:
 * <p>
 * 1. Get the Foreground Singleton, passing a Context or Application object unless you
 * are sure that the Singleton has definitely already been initialised elsewhere.
 * <p>
 * 2.a) Perform a direct, synchronous check: Foreground.isForeground() / .isBackground()
 * <p>
 * or
 * <p>
 * 2.b) Register to be notified (useful in Service or other non-UI components):
 * <p>
 * Foreground.Listener myListener = new Foreground.Listener(){
 * void onBecameForeground(){
 * // ... whatever you want to do
 * }
 * void onBecameBackground(){
 * // ... whatever you want to do
 * }
 * }
 * <p>
 * void onCreate(){
 * super.onCreate();
 * Foreground.get(this).addListener(listener);
 * }
 * <p>
 * void onDestroy(){
 * super.onCreate();
 * Foreground.get(this).removeListener(listener);
 * }
 */
class Foreground implements Application.ActivityLifecycleCallbacks {

    private static final long CHECK_DELAY = 500;

    interface Listener {
        void onBecameForeground();
        void onBecameBackground();
    }

    private static Foreground instance;
    private boolean foreground = false;
    private boolean paused = true;
    private final HandlerThread listenerThread;
    private final Handler handler;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private Runnable check;

    Foreground() {
        listenerThread = new HandlerThread("LDForegroundListener");
        listenerThread.start();
        handler = new Handler(listenerThread.getLooper());
    }

    /**
     * Its not strictly necessary to use this method - _usually_ invoking
     * get with a Context gives us a path to retrieve the Application and
     * initialise, but sometimes (e.g. in test harness) the ApplicationContext
     * is != the Application, and the docs make no guarantees.
     *
     * @param application Application for registering lifecycle callbacks on
     * @return an initialised Foreground instance
     */
    static Foreground init(Application application) {
        if (instance == null) {
            instance = new Foreground();
            ActivityManager.RunningAppProcessInfo appProcessInfo = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(appProcessInfo);
            instance.foreground = appProcessInfo.importance == IMPORTANCE_FOREGROUND
                    || appProcessInfo.importance == IMPORTANCE_VISIBLE;

            application.registerActivityLifecycleCallbacks(instance);
        }
        return instance;
    }

    static Foreground get(Application application) {
        if (instance == null) {
            init(application);
        }
        return instance;
    }

    static Foreground get(Context ctx) {
        if (instance == null) {
            Context appCtx = ctx.getApplicationContext();
            if (appCtx instanceof Application) {
                init((Application) appCtx);
            }
            throw new IllegalStateException("Foreground is not initialised and " +
                            "cannot obtain the Application object");
        }
        return instance;
    }

    static Foreground get() {
        if (instance == null) {
            throw new IllegalStateException("Foreground is not initialised - invoke " +
                    "at least once with parameterised init/get");
        }
        return instance;
    }

    boolean isForeground() {
        return foreground;
    }

    boolean isBackground() {
        return !foreground;
    }

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        paused = false;
        boolean wasBackground = !foreground;
        foreground = true;

        if (check != null) {
            handler.removeCallbacks(check);
            check = null;
        }

        if (wasBackground) {
            handler.post(() -> {
                LDConfig.LOG.d("went foreground");
                for (Listener l : listeners) {
                    try {
                        l.onBecameForeground();
                    } catch (Exception exc) {
                        LDConfig.LOG.e(exc, "Listener threw exception!");
                    }
                }
            });
        } else {
            LDConfig.LOG.d("still foreground");
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        paused = true;

        if (check != null) {
            handler.removeCallbacks(check);
            check = null;
        }

        handler.postDelayed(check = () -> {
            if (foreground && paused) {
                foreground = false;
                LDConfig.LOG.d("went background");
                for (Listener l : listeners) {
                    try {
                        l.onBecameBackground();
                    } catch (Exception exc) {
                        LDConfig.LOG.e(exc, "Listener threw exception!");
                    }
                }
            } else {
                LDConfig.LOG.d("still background");
            }
        }, CHECK_DELAY);
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