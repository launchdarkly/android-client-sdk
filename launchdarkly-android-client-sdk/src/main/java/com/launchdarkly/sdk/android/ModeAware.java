package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.DataSource;

/**
 * A {@link DataSource} that supports runtime connection mode switching.
 * <p>
 * {@link ConnectivityManager} checks {@code instanceof ModeAware} to decide
 * whether to use mode resolution (FDv2) or legacy teardown/rebuild behavior (FDv1).
 * <p>
 * Package-private — not part of the public SDK API.
 *
 * @see ConnectionMode
 * @see ModeResolutionTable
 */
interface ModeAware extends DataSource {

    /**
     * Switches the data source to the specified connection mode. The implementation
     * stops the current synchronizers and starts the new mode's synchronizers without
     * re-running initializers (per CONNMODE spec 2.0.1).
     *
     * @param newMode the target connection mode
     */
    void switchMode(@NonNull ConnectionMode newMode);
}
