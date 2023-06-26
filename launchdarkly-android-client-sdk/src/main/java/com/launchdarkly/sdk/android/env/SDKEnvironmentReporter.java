package com.launchdarkly.sdk.android.env;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.BuildConfig;
import com.launchdarkly.sdk.android.LDPackageConsts;
import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;

/**
 * An {@link IEnvironmentReporter} that reports static SDK information for {@link #getApplicationInfo()}
 * and defers all other attributes to the next reporter in the chain.
 */
class SDKEnvironmentReporter extends EnvironmentReporterChainBase implements IEnvironmentReporter {

    @NonNull
    @Override
    public ApplicationInfo getApplicationInfo() {
        return new ApplicationInfo(
                LDPackageConsts.SDK_NAME,
                BuildConfig.VERSION_NAME,
                LDPackageConsts.SDK_NAME,
                BuildConfig.VERSION_NAME
        );
    }
}
