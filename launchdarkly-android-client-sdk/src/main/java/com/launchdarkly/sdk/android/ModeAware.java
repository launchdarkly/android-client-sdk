package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

/**
 * Supports runtime connection mode switching.
 * <p>
 * {@link ConnectivityManager} checks {@code instanceof ModeAware} to decide
 * whether to use mode resolution (FDv2) or legacy teardown/rebuild behavior (FDv1).
 * <p>
 * The data source receives the full {@link ResolvedModeDefinition} — it has no
 * internal mode table and does not know which named {@link ConnectionMode} it is
 * operating in. The mode table and mode-to-definition lookup live in
 * {@link ConnectivityManager}.
 * <p>
 * Package-private — not part of the public SDK API.
 *
 * @see ResolvedModeDefinition
 * @see ModeResolutionTable
 */
interface ModeAware {

    /**
     * Switches the data source to operate with the given mode definition.
     * The implementation stops the current synchronizers and starts the new
     * definition's synchronizers without re-running initializers
     * (per CONNMODE spec 2.0.1).
     *
     * @param newDefinition the resolved initializer/synchronizer factories for
     *                      the target mode
     */
    void switchMode(@NonNull ResolvedModeDefinition newDefinition);
}
