package com.launchdarkly.sdk.android;

/**
 * Snapshot of the current platform state used as input to
 * {@link ModeResolutionTable#resolve(ModeState)}.
 * <p>
 * Immutable value object — all fields are set in the constructor with no setters.
 * <p>
 * Package-private — not part of the public SDK API.
 *
 * @see ModeResolutionTable
 * @see ModeResolutionEntry
 */
final class ModeState {

    private final boolean foreground;
    private final boolean networkAvailable;

    ModeState(boolean foreground, boolean networkAvailable) {
        this.foreground = foreground;
        this.networkAvailable = networkAvailable;
    }

    boolean isForeground() {
        return foreground;
    }

    boolean isNetworkAvailable() {
        return networkAvailable;
    }
}
