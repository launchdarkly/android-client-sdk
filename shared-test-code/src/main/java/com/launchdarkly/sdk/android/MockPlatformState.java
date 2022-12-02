package com.launchdarkly.sdk.android;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test fixture implementation of {@link PlatformState} that is easier to manipulate in tests than
 * a regular mock.
 */
public class MockPlatformState implements PlatformState {
    private final CopyOnWriteArrayList<ConnectivityChangeListener> connectivityChangeListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ForegroundChangeListener> foregroundChangeListeners =
            new CopyOnWriteArrayList<>();

    private volatile boolean foreground = true;
    private volatile boolean networkAvailable = true;

    @Override
    public boolean isNetworkAvailable() {
        return networkAvailable;
    }

    public void setNetworkAvailable(boolean networkAvailable) {
        this.networkAvailable = networkAvailable;
    }

    @Override
    public void addConnectivityChangeListener(ConnectivityChangeListener listener) {
        connectivityChangeListeners.add(listener);
    }

    @Override
    public void removeConnectivityChangeListener(ConnectivityChangeListener listener) {
        connectivityChangeListeners.remove(listener);
    }

    public void notifyConnectivityChangeListeners(boolean networkAvailable) {
        new Thread(() -> {
            for (ConnectivityChangeListener listener: connectivityChangeListeners) {
                listener.onConnectivityChanged(networkAvailable);
            }
        }).start();
    }

    @Override
    public boolean isForeground() {
        return foreground;
    }

    public void setForeground(boolean foreground) {
        this.foreground = foreground;
    }

    @Override
    public void addForegroundChangeListener(ForegroundChangeListener listener) {
        foregroundChangeListeners.add(listener);
    }

    @Override
    public void removeForegroundChangeListener(ForegroundChangeListener listener) {
        foregroundChangeListeners.remove(listener);
    }

    public void setAndNotifyForegroundChangeListeners(boolean foreground) {
        this.foreground = foreground;
        new Thread(() -> {
            for (ForegroundChangeListener listener: foregroundChangeListeners) {
                listener.onForegroundChanged(foreground);
            }
        }).start();
    }

    @Override
    public File getCacheDir() {
        return null;
    }

    @Override
    public void close() throws IOException {}
}
