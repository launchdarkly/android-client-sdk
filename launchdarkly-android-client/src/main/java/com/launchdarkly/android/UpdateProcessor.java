package com.launchdarkly.android;

import com.google.common.util.concurrent.ListenableFuture;

interface UpdateProcessor {

    /**
     * Starts the UpdateProcessor.
     *
     * @return {@link ListenableFuture}'s completion status indicating when the UpdateProcessor has been initialized.
     */
    ListenableFuture<Void> start();

    /**
     * Stops the UpdateProcessor.
     * An UpdateProcessor can be stopped and started multiple times.
     */
    void stop();

    /**
     * Returns true once the UpdateProcessor has been initialized and will never return false again.
     *
     * @return
     */
    boolean isInitialized();

    /**
     * Restarts the UpdateProcessor.
     */
    ListenableFuture<Void> restart();
}
