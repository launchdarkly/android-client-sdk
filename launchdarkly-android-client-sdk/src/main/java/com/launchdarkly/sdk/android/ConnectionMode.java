package com.launchdarkly.sdk.android;

/**
 * Enumerates the built-in FDv2 connection modes. Each mode maps to a
 * {@link ModeDefinition} that specifies which initializers and synchronizers
 * are active when the SDK is operating in that mode.
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

    static final ConnectionMode STREAMING = new ConnectionMode("STREAMING");
    static final ConnectionMode POLLING = new ConnectionMode("POLLING");
    static final ConnectionMode OFFLINE = new ConnectionMode("OFFLINE");
    static final ConnectionMode ONE_SHOT = new ConnectionMode("ONE_SHOT");
    static final ConnectionMode BACKGROUND = new ConnectionMode("BACKGROUND");

    private final String name;

    private ConnectionMode(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
