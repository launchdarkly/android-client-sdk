package com.launchdarkly.android;

import android.app.Application;
import android.os.ConditionVariable;

/**
 * Test fixture for allowing testing of behavior dependent on the SDK detecting whether the
 * application is currently in the foreground or the background. For the test controller to work
 * correctly, {@link ForegroundTestController#setup(boolean)} must be called before any other code
 * initializes the {@link Foreground} class. This is so the test controller can pre-initialize the
 * class with a proxy application class.
 */
public class ForegroundTestController {

    private static ForegroundApplication foregroundApplication;
    private static Foreground instance;

    public static void setup(boolean startInForeground) {
        foregroundApplication = new ForegroundApplication(startInForeground);
        instance = Foreground.init(foregroundApplication);
        if (startInForeground) {
            moveToForeground();
        } else {
            moveToBackground();
        }
    }

    public static synchronized void moveToForeground() {
        foregroundApplication.moveToForeground();
    }

    public static synchronized void moveToBackground() {
        if (instance.isForeground()) {
            WaitChangeListener waitChangeListener = new WaitChangeListener();
            instance.addListener(waitChangeListener);
            foregroundApplication.moveToBackground();
            waitChangeListener.block();
            instance.removeListener(waitChangeListener);
        }
    }


    private static class WaitChangeListener implements Foreground.Listener {

        private ConditionVariable state = new ConditionVariable();

        @Override
        public void onBecameForeground() {
            state.open();
        }

        @Override
        public void onBecameBackground() {
            state.open();
        }

        void block() {
            state.block();
        }
    }

    private static class ForegroundApplication extends Application {
        private boolean startInForeground;
        private ActivityLifecycleCallbacks callbacks;

        ForegroundApplication(boolean startInForeground) {
            this.startInForeground = startInForeground;
        }

        @Override
        public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
            callbacks = callback;
            if (startInForeground) {
                callbacks.onActivityResumed(null);
            } else {
                callbacks.onActivityPaused(null);
            }
        }

        void moveToForeground() {
            startInForeground = true;
            if (callbacks != null) {
                callbacks.onActivityResumed(null);
            }
        }

        void moveToBackground() {
            startInForeground = false;
            if (callbacks != null) {
                callbacks.onActivityPaused(null);
            }
        }
    }
}
