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
        if (this == o) return true;
        if (!(o instanceof ModeState)) return false;
        ModeState that = (ModeState) o;
        return foreground == that.foreground
                && networkAvailable == that.networkAvailable
                && backgroundUpdatingDisabled == that.backgroundUpdatingDisabled;
    }

    @Override
    public int hashCode() {
        int result = Boolean.hashCode(foreground);
        result = 31 * result + Boolean.hashCode(networkAvailable);
        result = 31 * result + Boolean.hashCode(backgroundUpdatingDisabled);
        return result;
    }
}
