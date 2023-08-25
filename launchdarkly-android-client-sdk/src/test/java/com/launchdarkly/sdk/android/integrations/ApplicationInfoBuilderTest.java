package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;

import org.junit.Assert;
import org.junit.Test;

public class ApplicationInfoBuilderTest {

    @Test
    public void ignoresInvalidValues() {
        ApplicationInfoBuilder b = new ApplicationInfoBuilder();
        b.logger = LDLogger.none();
        b.applicationId("im#invalid");
        b.applicationName("im#invalid");
        b.applicationVersion("im#invalid");
        b.applicationVersionName("im#invalid");
        ApplicationInfo info = b.createApplicationInfo();
        Assert.assertNull(info.getApplicationId());
        Assert.assertNull(info.getApplicationName());
        Assert.assertNull(info.getApplicationVersion());
        Assert.assertNull(info.getApplicationVersionName());
    }

    @Test
    public void sanitizesValues() {
        ApplicationInfoBuilder b = new ApplicationInfoBuilder();
        b.logger = LDLogger.none();
        b.applicationId("id has spaces");
        b.applicationName("name has spaces");
        b.applicationVersion("version has spaces");
        b.applicationVersionName("version name has spaces");
        ApplicationInfo info = b.createApplicationInfo();
        Assert.assertEquals("id-has-spaces", info.getApplicationId());
        Assert.assertEquals("name-has-spaces", info.getApplicationName());
        Assert.assertEquals("version-has-spaces", info.getApplicationVersion());
        Assert.assertEquals("version-name-has-spaces", info.getApplicationVersionName());
    }

    @Test
    public void nullValueIsValid() {
        ApplicationInfoBuilder b = new ApplicationInfoBuilder();
        b.logger = LDLogger.none();
        b.applicationId("myID"); // first non-null
        ApplicationInfo info = b.createApplicationInfo();
        Assert.assertEquals("myID", info.getApplicationId());

        b.applicationId(null); // now back to null
        ApplicationInfo info2 = b.createApplicationInfo();
        Assert.assertNull(info2.getApplicationId());
    }
}
