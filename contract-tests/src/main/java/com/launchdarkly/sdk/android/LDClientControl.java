package com.launchdarkly.sdk.android;

import android.app.Application;

/**
 * A class that is only here to allow the contract test service to instantiate multiple
 * LDClients. Contains one static method `resetInstances()` that resets the static global
 * state in LDClient.
 */
public class LDClientControl {

    /**
     * Resets the global state that prevents creating more than one LDClient.
     *
     * This is a workaround that allows testing the Android SDK from a long-lived
     * test service.
     */
    public static void resetInstances() {
        LDClient.instances = null;
    }
}
