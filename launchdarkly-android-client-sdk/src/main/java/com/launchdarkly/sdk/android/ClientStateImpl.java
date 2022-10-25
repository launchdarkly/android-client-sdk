package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of {@link ClientState} that also allows the state to be changed.
 */
final class ClientStateImpl implements ClientState {
    private final String mobileKey;
    private final String environmentName;
    private final LDLogger logger;
    private final AtomicBoolean forcedOffline;

    ClientStateImpl(
            String mobileKey,
            String environmentName,
            LDLogger logger,
            boolean forcedOffline
    ) {
        this.mobileKey = mobileKey;
        this.environmentName = environmentName;
        this.logger = logger;
        this.forcedOffline = new AtomicBoolean(forcedOffline);
    }

    @Override
    public String getMobileKey() {
        return mobileKey;
    }

    @Override
    public String getEnvironmentName() {
        return environmentName;
    }

    @Override
    public LDLogger getLogger() {
        return logger;
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
    public boolean setForceOffline(boolean forcedOffline) {
        return this.forcedOffline.getAndSet(forcedOffline);
    }
}
