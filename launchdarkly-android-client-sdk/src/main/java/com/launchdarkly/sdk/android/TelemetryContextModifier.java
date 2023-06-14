package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.ContextMultiBuilder;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;

import java.util.ArrayList;
import java.util.List;

public class TelemetryContextModifier implements IContextModifier {

    // TODO: determine proper home for these constants.
    private static final String LD_APPLICATION_KIND = "ld_application";
    private static final String LD_DEVICE_KIND = "ld_device";
    public static final String ATTR_ID = "id";
    public static final String ATTR_NAME = "name";
    public static final String ATTR_VERSION = "version";
    public static final String ATTR_VERSION_NAME = "versionName";
    public static final String ATTR_MANUFACTURER = "manufacturer";
    public static final String ATTR_MODEL = "model";
    public static final String ATTR_LOCALE = "locale";
    public static final String ATTR_OS = "os";
    public static final String ATTR_FAMILY = "family";


    private PersistentDataStoreWrapper persistentData;
    private EnvironmentReporter environmentReporter;

    public TelemetryContextModifier(PersistentDataStoreWrapper persistentData,
                                    EnvironmentReporter environmentReporter) {
        this.persistentData = persistentData;
        this.environmentReporter = environmentReporter;
    }

    @Override
    public LDContext modifyContext(LDContext context) {

        // TODO: update with collision detection logic

        // make cloned builder for context
        ContextMultiBuilder builder = LDContext.multiBuilder();
        List<LDContext> contextList = new ArrayList<>();
        if (context.isMultiple()) {
            for (int i = 0; i < context.getIndividualContextCount(); i++) {
                contextList.add(context.getIndividualContext(i));
            }
        } else {
            contextList.add(context);
        }

        contextList.add(makeLDApplicationKindContext());
        contextList.add(makeLDDeviceKindContext());

        for (LDContext c : contextList) {
            builder.add(c);
        }

        return builder.build();
    }

    private LDContext makeLDApplicationKindContext() {

        // TODO: add logic for detecting if no properties are available and if no properties are available, exclude the context kind

        ContextKind ldApplicationKind = ContextKind.of(LD_APPLICATION_KIND);
        String key = persistentData.getOrGenerateContextKey(ldApplicationKind);

        return LDContext.builder(ldApplicationKind, key)
                .set(ATTR_ID, LDValue.of(environmentReporter.getApplicationInfo().getApplicationId()))
                .set(ATTR_NAME, LDValue.of(environmentReporter.getApplicationInfo().getApplicationName()))
                .set(ATTR_VERSION, LDValue.of(environmentReporter.getApplicationInfo().getApplicationVersion()))
                .set(ATTR_VERSION_NAME, LDValue.of(environmentReporter.getApplicationInfo().getApplicationVersionName()))
                .build();
    }

    private LDContext makeLDDeviceKindContext() {
        ContextKind ldDeviceKind = ContextKind.of(LD_DEVICE_KIND);
        String key = persistentData.getOrGenerateContextKey(ldDeviceKind);

        return LDContext.builder(ldDeviceKind, key)
                .set(ATTR_MANUFACTURER, LDValue.of(environmentReporter.getManufacturer()))
                .set(ATTR_MODEL, LDValue.of(environmentReporter.getModel()))
                .set(ATTR_LOCALE, LDValue.of(environmentReporter.getLocale()))
                .set(ATTR_OS, new ObjectBuilder()
                        .put(ATTR_FAMILY, environmentReporter.getOSFamily())
                        .put(ATTR_VERSION, environmentReporter.getOSVersion())
                        .build()
                )
                .build();
    }
}