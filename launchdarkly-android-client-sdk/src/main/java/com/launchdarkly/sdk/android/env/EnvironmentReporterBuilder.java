package com.launchdarkly.sdk.android.env;

import android.app.Application;

import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder for making an {@link IEnvironmentReporter} with various options.
 */
public class EnvironmentReporterBuilder {

    @Nullable
    private Application application;

    @Nullable
    private ApplicationInfo applicationInfo;

    /**
     * Sets the application info that this environment reporter will report when asked in the future,
     * overriding the automatically sourced {@link ApplicationInfo}
     *
     * @param applicationInfo to report.
     */
    public void setApplicationInfo(ApplicationInfo applicationInfo) {
        this.applicationInfo = applicationInfo;
    }

    /**
     * Enables automatically collecting attributes from the platform.
     *
     * @param application reference for platform calls
     */
    public void enableCollectionFromPlatform(Application application) {
        this.application = application;
    }

    /**
     * @return the {@link IEnvironmentReporter}
     */
    public IEnvironmentReporter build() {
        /**
         * Create chain of responsibility with the following priority order
         * 1. {@link ApplicationInfoEnvironmentReporter} - holds customer override
         * 2. {@link AndroidEnvironmentReporter} - Android platform API next
         * 3. {@link SDKEnvironmentReporter} - Fallback is SDK constants
         */
        List<EnvironmentReporterChainBase> reporters = new ArrayList<>();

        if (applicationInfo != null) {
            reporters.add(new ApplicationInfoEnvironmentReporter(applicationInfo));
        }

        if (application != null) {
            reporters.add(new AndroidEnvironmentReporter(application));
        }

        // always add fallback reporter
        reporters.add(new SDKEnvironmentReporter());

        // build chain of responsibility by iterating on all but last element
        for (int i = 0; i < reporters.size() - 1; i++) {
            reporters.get(i).setNext(reporters.get(i + 1));
        }

        // guaranteed non-empty since fallback reporter is always added
        return reporters.get(0);
    }
}
