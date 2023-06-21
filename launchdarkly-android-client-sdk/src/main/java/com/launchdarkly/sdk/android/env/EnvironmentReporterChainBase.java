package com.launchdarkly.sdk.android.env;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;

/**
 * Base implementation for using {@link IEnvironmentReporter}s in a chain of responsibility pattern.
 */
class EnvironmentReporterChainBase implements IEnvironmentReporter {

    private static final String UNKNOWN = "unknown";

    // the next reporter in the chain if there is one
    @Nullable
    protected EnvironmentReporterChainBase next;

    public void setNext(EnvironmentReporterChainBase next) {
        this.next = next;
    }

    @NonNull
    @Override
    public ApplicationInfo getApplicationInfo() {
        return next != null ? next.getApplicationInfo() : new ApplicationInfo(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
    }

    @NonNull
    @Override
    public String getManufacturer() {
        return next != null ? next.getManufacturer() : UNKNOWN;
    }

    @NonNull
    @Override
    public String getModel() {
        return next != null ? next.getModel() : UNKNOWN;
    }

    @NonNull
    @Override
    public String getLocale() {
        return next != null ? next.getLocale() : UNKNOWN;
    }

    @NonNull
    @Override
    public String getOSFamily() {
        return next != null ? next.getOSFamily() : UNKNOWN;
    }

    @NonNull
    @Override
    public String getOSVersion() {
        return next != null ? next.getOSVersion() : UNKNOWN;
    }
}
