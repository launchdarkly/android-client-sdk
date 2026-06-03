package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.integrations.DataSystemBuilder;

/**
 * <strong>For LaunchDarkly internal use only.</strong> Do not use this class or its methods unless
 * you are maintaining LaunchDarkly-produced code for this SDK (for example the contract-tests
 * harness).
 * <p>
 * Forwards to the package-private data system entry points on {@link LDConfig.Builder} and
 * {@link Components}. Application developers outside LaunchDarkly must not depend on this type;
 * it is not part of the supported public Android SDK API and will be removed in a future release.
 */
public final class InternalDataSystemAccess {

    private InternalDataSystemAccess() {
    }

    public static DataSystemBuilder newBuilder() {
        return Components.dataSystem();
    }

    public static LDConfig.Builder applyToConfig(
            LDConfig.Builder builder,
            DataSystemBuilder dataSystem) {
        return builder.dataSystem(dataSystem);
    }
}
