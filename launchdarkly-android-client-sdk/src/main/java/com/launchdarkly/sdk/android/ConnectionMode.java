package com.launchdarkly.sdk.android;

/**
 * Enumerates the built-in FDv2 connection modes. Each mode maps to a
 * {@link ModeDefinition} that specifies which initializers and synchronizers
 * are active when the SDK is operating in that mode.
 * <p>
 * Not to be confused with {@link ConnectionInformation.ConnectionMode}, which
 * is the public FDv1 enum representing the SDK's current connection state
 * (e.g. POLLING, STREAMING, SET_OFFLINE). This class is an internal FDv2
 * concept describing the <em>desired</em> data-acquisition pipeline.
 * <p>
 * This is a closed enum — custom connection modes (spec 5.3.5 TBD) are not
 * supported in this release.
 * <p>
 * Package-private — not part of the public SDK API.
 *
 * @see ModeDefinition
 * @see ModeResolutionTable
 */
final class ConnectionMode {

    static final ConnectionMode STREAMING = new ConnectionMode("streaming");
    static final ConnectionMode POLLING = new ConnectionMode("polling");
    static final ConnectionMode OFFLINE = new ConnectionMode("offline");
    static final ConnectionMode ONE_SHOT = new ConnectionMode("one-shot");
    static final ConnectionMode BACKGROUND = new ConnectionMode("background");

    private final String name;

    private ConnectionMode(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
