package com.launchdarkly.sdk.android;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of {@link ClientState} that also allows the state to be changed.
 */
final class ClientStateImpl implements ClientState {
    private final AtomicBoolean forcedOffline;

    ClientStateImpl(boolean forcedOffline) {
        this.forcedOffline = new AtomicBoolean(forcedOffline);
    }

    @Override
    public boolean isForcedOffline() {
        return forcedOffline.get();
    }

    /**
     * Changes the state of {@link #isForcedOffline()}. This is deliberately not part of the
     * ClientStateProvider interface, since most SDK components are not allowed to update this
     * state.
     * @param forcedOffline the new value
     * @return the old value
     */
    public boolean setForcedOffline(boolean forcedOffline) {
        return this.forcedOffline.getAndSet(forcedOffline);
    }
}
