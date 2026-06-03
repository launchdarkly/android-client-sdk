package com.launchdarkly.sdk.android;

/**
 * Enumerates the built-in FDv2 connection modes. Each mode maps to a
 * pipeline of initializers and synchronizers that are active when the SDK
 * is operating in that mode.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * <p>
 * Not to be confused with {@link ConnectionInformation.ConnectionMode}, which
 * is the public FDv1 enum representing the SDK's current connection state
 * (e.g. POLLING, STREAMING, SET_OFFLINE). This class is an internal FDv2
 * concept describing the <em>desired</em> data-acquisition pipeline.
 * <p>
 * This is a closed enum — custom connection modes (spec 5.3.5 TBD) are not
 * supported in this release.
 * <p>
 * The SDK's {@link com.launchdarkly.sdk.android.integrations.DataSystemBuilder}
 * allows you to customize which initializers and synchronizers run in each mode.
 * <p>
 * On mobile, when automatic mode switching is enabled, the SDK resolves a
 * {@link ConnectionMode} from platform state in the same order as
 * {@link ModeResolutionTable} (see {@link com.launchdarkly.sdk.android.integrations.DataSystemBuilder#foregroundConnectionMode}
 * and {@link com.launchdarkly.sdk.android.integrations.DataSystemBuilder#backgroundConnectionMode}).
 * The default built-in table ({@link ModeResolutionTable#MOBILE}) evaluates as follows:
 * <ol>
 *   <li>No network &rarr; {@link #OFFLINE}</li>
 *   <li>Background with {@link LDConfig.Builder#disableBackgroundUpdating(boolean)} set
 *       &rarr; {@link #OFFLINE}</li>
 *   <li>Background (network available, background updates not disabled) &rarr;
 *       configured background mode (default {@link #BACKGROUND})</li>
 *   <li>Foreground with network &rarr; configured foreground mode (default {@link #STREAMING})</li>
 * </ol>
 *
 * @see com.launchdarkly.sdk.android.integrations.DataSystemBuilder
 * @see com.launchdarkly.sdk.android.integrations.ConnectionModeBuilder
 */
public final class ConnectionMode {

    /**
     * The SDK uses a streaming connection in the foreground, with polling as a fallback.
     */
    public static final ConnectionMode STREAMING = new ConnectionMode("streaming");

    /**
     * The SDK polls for updates at a regular interval.
     */
    public static final ConnectionMode POLLING = new ConnectionMode("polling");

    /**
     * The SDK does not make any network requests. It may still serve cached data.
     */
    public static final ConnectionMode OFFLINE = new ConnectionMode("offline");

    /**
     * The SDK makes a single poll request and then stops.
     */
    public static final ConnectionMode ONE_SHOT = new ConnectionMode("one-shot");

    /**
     * The SDK polls at a low frequency while the application is in the background.
     */
    public static final ConnectionMode BACKGROUND = new ConnectionMode("background");

    private final String name;

    private ConnectionMode(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
