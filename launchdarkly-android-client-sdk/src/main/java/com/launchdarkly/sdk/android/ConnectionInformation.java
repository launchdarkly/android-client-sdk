package com.launchdarkly.sdk.android;

public interface ConnectionInformation {

    /**
     * Enumerated type defining the possible values of {@link ConnectionInformation#getConnectionMode()}.
     */
    enum ConnectionMode {
        STREAMING(true),
        POLLING(true),
        BACKGROUND_POLLING(true),
        BACKGROUND_DISABLED(false),
        OFFLINE(false),
        SET_OFFLINE(false),
        SHUTDOWN(false);

        private boolean connectionActive;

        ConnectionMode(boolean connectionActive) {
            this.connectionActive = connectionActive;
        }

        boolean isConnectionActive() {
            return connectionActive;
        }
    }

    ConnectionMode getConnectionMode();

    LDFailure getLastFailure();

    Long getLastSuccessfulConnection();

    Long getLastFailedConnection();
}
