package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.android.env.EnvironmentReporterBuilder;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AutoEnvContextModifierTest {

    @Rule
    public LogCaptureRule logging = new LogCaptureRule();

    /**
     * Requirement 1.2.2.1 - Schema adherence
     * Requirement 1.2.2.3 - Adding all attributes
     * Requirement 1.2.2.5 - Schema version in _meta
     * Requirement 1.2.2.7 - Adding all context kinds
     */
    @Test
    public void adheresToSchemaTest() {
        PersistentDataStoreWrapper wrapper = TestUtil.makeSimplePersistentDataStoreWrapper();
        AutoEnvContextModifier underTest = new AutoEnvContextModifier(
                wrapper,
                new EnvironmentReporterBuilder().build(),
                LDLogger.none()
        );

        LDContext input = LDContext.builder(ContextKind.of("aKind"), "aKey")
                .set("dontOverwriteMeBro", "really bro").build();
        LDContext output = underTest.modifyContext(input);

        // it is important that we create this expected context after the code runs because there
        // will be persistence side effects
        ContextKind applicationKind = ContextKind.of(AutoEnvContextModifier.LD_APPLICATION_KIND);
        LDContext expectedAppContext = LDContext.builder(applicationKind, wrapper.getOrGenerateContextKey(applicationKind))
                .set(AutoEnvContextModifier.ATTR_ID, LDPackageConsts.SDK_NAME)
                .set(AutoEnvContextModifier.ATTR_NAME, LDPackageConsts.SDK_NAME)
                .set(AutoEnvContextModifier.ATTR_VERSION, BuildConfig.VERSION_NAME)
                .set(AutoEnvContextModifier.ATTR_VERSION_NAME, BuildConfig.VERSION_NAME)
                .set("_meta", "0.1")
                .build();

        ContextKind deviceKind = ContextKind.of(AutoEnvContextModifier.LD_DEVICE_KIND);
        LDContext expectedDeviceContext = LDContext.builder(deviceKind, wrapper.getOrGenerateContextKey(deviceKind))
                .set(AutoEnvContextModifier.ATTR_MANUFACTURER, "unknown")
                .set(AutoEnvContextModifier.ATTR_MODEL, "unknown")
                .set(AutoEnvContextModifier.ATTR_LOCALE, "unknown")
                .set(AutoEnvContextModifier.ATTR_OS, new ObjectBuilder()
                        .put(AutoEnvContextModifier.ATTR_FAMILY, "unknown")
                        .put(AutoEnvContextModifier.ATTR_VERSION, "unknown")
                        .build())
                .set("_meta", "0.1")
                .build();

        LDContext expectedOutput = LDContext.multiBuilder().add(input).add(expectedAppContext).add(expectedDeviceContext).build();

        Assert.assertEquals(expectedOutput, output);
    }

    /**
     *  Requirement 1.2.2.6 - Don't add kind if already exists
     *  Requirement 1.2.5.1 - Doesn't change customer provided data
     *  Requirement 1.2.7.1 - Log warning when kind already exists
     */
    @Test
    public void doesNotOverwriteCustomerDataTest() {

        PersistentDataStoreWrapper wrapper = TestUtil.makeSimplePersistentDataStoreWrapper();
        AutoEnvContextModifier underTest = new AutoEnvContextModifier(
                wrapper,
                new EnvironmentReporterBuilder().build(),
                logging.logger
        );

        LDContext input = LDContext.builder(ContextKind.of("ld_application"), "aKey")
                .set("dontOverwriteMeBro", "really bro").build();
        LDContext output = underTest.modifyContext(input);

        // it is important that we create this expected context after the code runs because there
        // will be persistence side effects
        ContextKind deviceKind = ContextKind.of(AutoEnvContextModifier.LD_DEVICE_KIND);
        LDContext expectedDeviceContext = LDContext.builder(deviceKind, wrapper.getOrGenerateContextKey(deviceKind))
                .set(AutoEnvContextModifier.ATTR_MANUFACTURER, "unknown")
                .set(AutoEnvContextModifier.ATTR_MODEL, "unknown")
                .set(AutoEnvContextModifier.ATTR_LOCALE, "unknown")
                .set(AutoEnvContextModifier.ATTR_OS, new ObjectBuilder()
                        .put(AutoEnvContextModifier.ATTR_FAMILY, "unknown")
                        .put(AutoEnvContextModifier.ATTR_VERSION, "unknown")
                        .build())
                .set("_meta", "0.1")
                .build();

        LDContext expectedOutput = LDContext.multiBuilder().add(input).add(expectedDeviceContext).build();

        Assert.assertEquals(expectedOutput, output);
        logging.assertWarnLogged("Unable to automatically add environment attributes for " +
                "kind:ld_application. ld_application already exists.");
    }

    /**
     *  Requirement 1.2.5.1 - Doesn't change customer provided data
     */
    @Test
    public void doesNotOverwriteCustomerDataMultiContextTest() {

        PersistentDataStoreWrapper wrapper = TestUtil.makeSimplePersistentDataStoreWrapper();
        AutoEnvContextModifier underTest = new AutoEnvContextModifier(
                wrapper,
                new EnvironmentReporterBuilder().build(),
                LDLogger.none()
        );

        LDContext input1 = LDContext.builder(ContextKind.of("ld_application"), "aKey")
                .set("dontOverwriteMeBro", "really bro").build();
        LDContext input2 = LDContext.builder(ContextKind.of("ld_device"), "anotherKey")
                .set("AndDontOverwriteThisEither", "bro").build();
        LDContext multiContextInput = LDContext.multiBuilder().add(input1).add(input2).build();
        LDContext output = underTest.modifyContext(multiContextInput);

        // input and output should be the same
        Assert.assertEquals(multiContextInput, output);
    }

    /**
     * Requirement 1.2.6.3 - Generated keys are consistent
     */
    @Test
    public void generatesConsistentKeysAcrossMultipleCalls() {
        PersistentDataStoreWrapper wrapper = TestUtil.makeSimplePersistentDataStoreWrapper();
        AutoEnvContextModifier underTest = new AutoEnvContextModifier(
                wrapper,
                new EnvironmentReporterBuilder().build(),
                LDLogger.none()
        );

        LDContext input = LDContext.builder(ContextKind.of("aKind"), "aKey")
                .set("dontOverwriteMeBro", "really bro").build();

        LDContext output1 = underTest.modifyContext(input);
        String key1 = output1.getIndividualContext("ld_application").getKey();

        LDContext output2 = underTest.modifyContext(input);
        String key2 = output2.getIndividualContext("ld_application").getKey();

        Assert.assertEquals(key1, key2);
    }
}
