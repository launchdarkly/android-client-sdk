package com.launchdarkly.sdk.android;

import java.io.Closeable;
import java.io.File;

interface PlatformState extends Closeable {
    interface ConnectivityChangeListener {
        void onConnectivityChanged(boolean networkAvailable);
    }

    interface ForegroundChangeListener {
        void onForegroundChanged(boolean foreground);
    }

    /**
     * Returns true if (as far as the OS knows) the network should be working.
     * @return true if the network should be available
     */
    boolean isNetworkAvailable();

    /**
     * Registers a listener to be called if the state of {@link #isNetworkAvailable()}} changes.
     * @param listener a listener
     */
    void addConnectivityChangeListener(ConnectivityChangeListener listener);

    /**
     * Undoes the effect of {@link #addConnectivityChangeListener(ConnectivityChangeListener)}. Has
     * no effect if no such listener is registered.
     * @param listener a listener
     */
    void removeConnectivityChangeListener(ConnectivityChangeListener listener);

    /**
     * Returns true if we believe the application is in the foreground, false if we believe it is in
     * the background.
     * @return true if in the foreground
     */
    boolean isForeground();

    /**
     * Registers a listener to be called if the state of {@link #isForeground()} changes.
     * @param listener a listener
     */
    void addForegroundChangeListener(ForegroundChangeListener listener);

    /**
     * Undoes the effect of {@link #addForegroundChangeListener(ForegroundChangeListener)}.
     * @param listener
     */
    void removeForegroundChangeListener(ForegroundChangeListener listener);

    /**
     * Returns the preferred filesystem location for cache files.
     * @return a directory path
     */
    File getCacheDir();
}
