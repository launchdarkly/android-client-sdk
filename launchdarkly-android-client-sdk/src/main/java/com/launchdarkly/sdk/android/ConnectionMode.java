package com.launchdarkly.sdk.android;

/**
 * Named connection modes for the FDv2 data system. Each mode maps to a
 * {@link ModeDefinition} that specifies which initializers and synchronizers to run.
 * <p>
 * Package-private — not part of the public SDK API.
 *
 * @see ModeDefinition
 */
enum ConnectionMode {
    STREAMING,
    POLLING,
    OFFLINE,
    ONE_SHOT,
    BACKGROUND
}
