package com.launchdarkly.sdk.android.env;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;

/**
 * An {@link IEnvironmentReporter} that reports the provided {@link ApplicationInfo} for {@link #getApplicationInfo()}
 * and defers all other attributes to the next reporter in the chain.
 */
class ApplicationInfoEnvironmentReporter extends EnvironmentReporterChainBase implements IEnvironmentReporter {

    private ApplicationInfo applicationInfo;

    public ApplicationInfoEnvironmentReporter(ApplicationInfo applicationInfo) {
        this.applicationInfo = applicationInfo;
    }

    @NonNull
    @Override
    public ApplicationInfo getApplicationInfo() {
        // defer to super if required property applicationID is missing
        if (applicationInfo.getApplicationId() == null) {
            return super.getApplicationInfo();
        }
        return applicationInfo;
    }
}
