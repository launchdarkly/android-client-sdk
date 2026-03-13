package com.launchdarkly.sdk.android;

/**
 * Snapshot of platform state used as input to {@link ModeResolutionTable#resolve(ModeState)}.
 * <p>
 * In this initial implementation, {@code ModeState} carries only platform state with
 * hardcoded Android defaults for foreground/background modes. When user-configurable
 * mode selection is added (CONNMODE 2.2.2), {@code foregroundMode} and
 * {@code backgroundMode} fields will be introduced here.
 * <p>
 * Package-private — not part of the public SDK API.
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
