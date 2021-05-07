package com.launchdarkly.sdk.android;

public interface ConnectionInformation {

    /**
     * Enumerated type defining the possible values of {@link ConnectionInformation#getConnectionMode()}.
     */
    enum ConnectionMode {
        STREAMING(true, true),
        POLLING(true, true),
        BACKGROUND_POLLING(true, true),
        BACKGROUND_DISABLED(true, true),
        OFFLINE(true, false),
        SET_OFFLINE(false, false),
        SHUTDOWN(false, false);

        private boolean transitionOnNetwork;
        private boolean transitionOnForeground;

        ConnectionMode(boolean transitionOnNetwork, boolean transitionOnForeground) {
            this.transitionOnNetwork = transitionOnNetwork;
            this.transitionOnForeground = transitionOnForeground;
        }

        boolean isTransitionOnNetwork() {
            return transitionOnNetwork;
        }

        boolean isTransitionOnForeground() {
            return transitionOnForeground;
        }
    }

    ConnectionMode getConnectionMode();

    LDFailure getLastFailure();

    Long getLastSuccessfulConnection();

    Long getLastFailedConnection();
}
