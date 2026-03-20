package com.launchdarkly.sdk.android;

/**
 * Enumerates the built-in FDv2 connection modes. Each mode maps to a
 * pipeline of initializers and synchronizers that are active when the SDK
 * is operating in that mode.
 * <p>
 * The SDK's {@link com.launchdarkly.sdk.android.integrations.DataSystemBuilder}
 * allows you to customize which initializers and synchronizers run in each mode.
 * <p>
 * On mobile, the SDK automatically transitions between modes based on
 * platform state (foreground/background, network availability). The default
 * resolution is:
 * <ul>
 *   <li>No network &rarr; {@link #OFFLINE}</li>
 *   <li>Background &rarr; {@link #BACKGROUND}</li>
 *   <li>Foreground &rarr; {@link #STREAMING}</li>
 * </ul>
 *
 * @see com.launchdarkly.sdk.android.integrations.DataSystemBuilder
 * @see com.launchdarkly.sdk.android.integrations.ConnectionModeBuilder
 */
public final class ConnectionMode {

    /**
     * The SDK uses a streaming connection in the foreground, with polling as a fallback.
     */
    public static final ConnectionMode STREAMING = new ConnectionMode("STREAMING");

    /**
     * The SDK polls for updates at a regular interval.
     */
    public static final ConnectionMode POLLING = new ConnectionMode("POLLING");

    /**
     * The SDK does not make any network requests. It may still serve cached data.
     */
    public static final ConnectionMode OFFLINE = new ConnectionMode("OFFLINE");

    /**
     * The SDK makes a single poll request and then stops.
     */
    public static final ConnectionMode ONE_SHOT = new ConnectionMode("ONE_SHOT");

    /**
     * The SDK polls at a low frequency while the application is in the background.
     */
    public static final ConnectionMode BACKGROUND = new ConnectionMode("BACKGROUND");

    private final String name;

    private ConnectionMode(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
