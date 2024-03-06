package com.launchdarkly.sdk.android;

/**
 * Provides various information about a current or previous connection.
 */
public interface ConnectionInformation {

    /**
     * Enumerated type defining the possible values of {@link ConnectionInformation#getConnectionMode()}.
     */
    enum ConnectionMode {
        /**
         * The SDK is either connected to the flag stream, or is actively attempting to acquire a connection.
         */
        STREAMING(true),

        /**
         * The SDK is in foreground polling mode because it was configured with streaming disabled.
         */
        POLLING(true),

        /**
         * The SDK has detected the application is in the background and has transitioned to battery-saving background
         * polling.
         */
        BACKGROUND_POLLING(true),

        /**
         * The SDK was configured with background polling disabled. The SDK has detected the application is in the
         * background and is not attempting to update the flag cache.
         */
        BACKGROUND_DISABLED(false),

        /**
         * The SDK has detected that the mobile device does not have an active network connection. It has ceased flag
         * update attempts until the network status changes.
         */
        OFFLINE(false),

        /**
         * The SDK has been explicitly set offline, either in the initial configuration, by
         * {@link LDClient#setOffline()}, or as a result of failed authentication to LaunchDarkly. The SDK will stay
         * offline unless {@link LDClient#setOnline()} is called.
         */
        SET_OFFLINE(false),

        /**
         * The shutdown state indicates the SDK has been permanently shutdown as a result of a call to close().
         */
        SHUTDOWN(false);

        private boolean connectionActive;

        ConnectionMode(boolean connectionActive) {
            this.connectionActive = connectionActive;
        }

        /**
         * @return true if connection is active, false otherwise
         */
        boolean isConnectionActive() {
            return connectionActive;
        }
    }

    /**
     * @return the {@link ConnectionMode}
     */
    ConnectionMode getConnectionMode();

    /**
     * @return the last {@link LDFailure}
     */
    LDFailure getLastFailure();

    /**
     * @return millis since epoch when the last successful connection occurred
     */
    Long getLastSuccessfulConnection();

    /**
     * @return millis since epoch when the last connection connection failure occurred
     */
    Long getLastFailedConnection();
}
