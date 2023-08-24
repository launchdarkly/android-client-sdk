package com.launchdarkly.sdk.android;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.sdk.android.env.EnvironmentReporterBuilder;
import com.launchdarkly.sdk.android.env.IEnvironmentReporter;
import com.launchdarkly.sdk.android.integrations.ApplicationInfoBuilder;
import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EnvironmentReporterBuilderTest {

    /**
     * Requirement 1.2.5.2 - Prioritized sourcing application info attributes
     */
    @Test
    public void prioritizesProvidedApplicationInfo() {

        Application application = ApplicationProvider.getApplicationContext();
        EnvironmentReporterBuilder builder1 = new EnvironmentReporterBuilder();
        builder1.enableCollectionFromPlatform(application);
        IEnvironmentReporter reporter1 = builder1.build();
        ApplicationInfo reporter1Output = reporter1.getApplicationInfo();

        EnvironmentReporterBuilder builder2 = new EnvironmentReporterBuilder();
        ApplicationInfo manualInfoInput = new ApplicationInfoBuilder().applicationId("manualAppID").createApplicationInfo();
        builder2.setApplicationInfo(manualInfoInput);
        builder2.enableCollectionFromPlatform(application);
        IEnvironmentReporter reporter2 = builder2.build();
        ApplicationInfo reporter2Output = reporter2.getApplicationInfo();

        Assert.assertNotEquals(reporter1Output.getApplicationId(), reporter2Output.getApplicationId());
        Assert.assertEquals(manualInfoInput.getApplicationId(), reporter2Output.getApplicationId());
    }

    @Test
    public void defaultsToSDKValues() {
        IEnvironmentReporter reporter = new EnvironmentReporterBuilder().build();
        Assert.assertEquals(LDPackageConsts.SDK_NAME, reporter.getApplicationInfo().getApplicationId());
        Assert.assertEquals(LDPackageConsts.SDK_NAME, reporter.getApplicationInfo().getApplicationName());
        Assert.assertEquals(BuildConfig.VERSION_NAME, reporter.getApplicationInfo().getApplicationVersion());
        Assert.assertEquals(BuildConfig.VERSION_NAME, reporter.getApplicationInfo().getApplicationVersionName());
    }

    @Test
    public void fallBackWhenIDMissing() {
        EnvironmentReporterBuilder builder = new EnvironmentReporterBuilder();
        ApplicationInfo manualInfoInput = new ApplicationInfoBuilder().applicationName("imNotAnID!").createApplicationInfo();
        builder.setApplicationInfo(manualInfoInput);
        IEnvironmentReporter reporter = builder.build();
        Assert.assertEquals(LDPackageConsts.SDK_NAME, reporter.getApplicationInfo().getApplicationId());
        Assert.assertEquals(LDPackageConsts.SDK_NAME, reporter.getApplicationInfo().getApplicationName());
        Assert.assertEquals(BuildConfig.VERSION_NAME, reporter.getApplicationInfo().getApplicationVersion());
        Assert.assertEquals(BuildConfig.VERSION_NAME, reporter.getApplicationInfo().getApplicationVersionName());
    }
}
