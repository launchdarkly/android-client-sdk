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

    public void setAndNotifyConnectivityChangeListeners(boolean networkAvailable) {
        this.networkAvailable = networkAvailable;
        new Thread(() -> {
            for (ConnectivityChangeListener listener: connectivityChangeListeners) {
                listener.onConnectivityChanged(networkAvailable);
            }
        }).start();
    }

    /**
     * Notifies connectivity-change listeners with each value in {@code values}, in order, on a
     * single background thread. Unlike repeated {@link #setAndNotifyConnectivityChangeListeners}
     * calls — which each spawn their own thread and can therefore deliver notifications out of
     * order — this delivers the whole sequence deterministically. The last value becomes the
     * reported network state. Use this when a test depends on the final state after a burst of
     * rapid changes (e.g. debounce coalescing).
     */
    public void setAndNotifyConnectivityChangeListenersInSequence(boolean... values) {
        new Thread(() -> {
            for (boolean value : values) {
                this.networkAvailable = value;
                for (ConnectivityChangeListener listener : connectivityChangeListeners) {
                    listener.onConnectivityChanged(value);
                }
            }
        }).start();
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
        return new File(System.getProperty("java.io.tmpdir"));
    }

    @Override
    public void close() throws IOException {}
}
