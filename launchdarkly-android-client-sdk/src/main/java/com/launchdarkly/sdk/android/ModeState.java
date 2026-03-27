package com.launchdarkly.sdk.android;

import java.util.Objects;

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
    private final boolean backgroundUpdatingDisabled;

    ModeState(boolean foreground, boolean networkAvailable, boolean backgroundUpdatingDisabled) {
        this.foreground = foreground;
        this.networkAvailable = networkAvailable;
        this.backgroundUpdatingDisabled = backgroundUpdatingDisabled;
    }

    boolean isForeground() {
        return foreground;
    }

    boolean isNetworkAvailable() {
        return networkAvailable;
    }

    boolean isBackgroundUpdatingDisabled() {
        return backgroundUpdatingDisabled;
    }


    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        ModeState modeState = (ModeState) o;
        return foreground == modeState.foreground && networkAvailable == modeState.networkAvailable && backgroundUpdatingDisabled == modeState.backgroundUpdatingDisabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(foreground, networkAvailable, backgroundUpdatingDisabled);
    }
}
