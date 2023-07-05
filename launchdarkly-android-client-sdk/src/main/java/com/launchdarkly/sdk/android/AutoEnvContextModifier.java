package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.ContextBuilder;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.ContextMultiBuilder;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.android.env.IEnvironmentReporter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * An {@link IContextModifier} that will add a few context kinds with environmental
 * information that is useful out of the box.
 */
public class AutoEnvContextModifier implements IContextModifier {

    static final String LD_APPLICATION_KIND = "ld_application";
    static final String LD_DEVICE_KIND = "ld_device";
    static final String ATTR_ID = "id";
    static final String ATTR_NAME = "name";
    static final String ATTR_VERSION = "version";
    static final String ATTR_VERSION_NAME = "versionName";
    static final String ATTR_MANUFACTURER = "manufacturer";
    static final String ATTR_MODEL = "model";
    static final String ATTR_LOCALE = "locale";
    static final String ATTR_OS = "os";
    static final String ATTR_FAMILY = "family";
    static final String ENV_ATTRIBUTES_VERSION = "envAttributesVersion";
    static final String SPEC_VERSION = "0.1";

    private final PersistentDataStoreWrapper persistentData;
    private final IEnvironmentReporter environmentReporter;
    private final LDLogger logger;

    /**
     * @param persistentData      for retrieving/storing generated context keys
     * @param environmentReporter for retrieving attributes
     * @param logger              for logging messages
     */
    public AutoEnvContextModifier(PersistentDataStoreWrapper persistentData,
                                  IEnvironmentReporter environmentReporter,
                                  LDLogger logger) {
        this.persistentData = persistentData;
        this.environmentReporter = environmentReporter;
        this.logger = logger;
    }

    @Override
    public LDContext modifyContext(LDContext context) {
        ContextMultiBuilder builder = LDContext.multiBuilder();
        builder.add(context);

        // iterate over telemetry context recipes, avoid overwriting customer contexts, add new contexts.
        for (ContextRecipe recipe : makeRecipeList()) {
            if (context.getIndividualContext(recipe.kind) == null) {
                builder.add(makeLDContextFromRecipe(recipe));
            } else {
                logger.warn("Unable to automatically add environment attributes for " +
                        "kind:{}. {} already exists.", recipe.kind, recipe.kind);
            }
        }

        return builder.build();
    }

    /**
     * A {@link ContextRecipe} is a set of callables that will be executed for a given kind
     * to generate the associated {@link LDContext}.  The reason this class exists is to not make
     * platform API calls until the context kind is needed.
     */
    private static class ContextRecipe {
        ContextKind kind;
        Callable<String> keyCallable;
        Map<String, Callable<LDValue>> attributeCallables;

        /**
         * @param kind               that the recipe is for
         * @param keyCallable        that when invoked will retrieve a key for the context
         * @param attributeCallables that when invoked will retrieve values for the attributes (map key)
         */
        public ContextRecipe(ContextKind kind, Callable<String> keyCallable, Map<String, Callable<LDValue>> attributeCallables) {
            this.kind = kind;
            this.keyCallable = keyCallable;
            this.attributeCallables = attributeCallables;
        }
    }

    /**
     * @param recipe to use
     * @return a {@link LDContext} that was generated following the provided {@link ContextRecipe}
     */
    private LDContext makeLDContextFromRecipe(ContextRecipe recipe) {
        try {
            // make builder, iterate over attributes in recipe, run LDValue callables to get values, add to builder
            ContextBuilder builder = LDContext.builder(recipe.kind, recipe.keyCallable.call());
            for (Map.Entry<String, Callable<LDValue>> entry : recipe.attributeCallables.entrySet()) {
                builder.set(entry.getKey(), entry.getValue().call());
            }
            return builder.build();
        } catch (Exception e) {
            // TODO: Java callable interface throws exception, come up with structure for closures to get LDValues that doesn't have exceptions.
            throw new RuntimeException(e);
        }
    }

    /**
     * @return a list of {@link ContextRecipe} that can be used to create contexts of the
     * kind in the recipe.
     */
    private List<ContextRecipe> makeRecipeList() {
        ContextKind ldApplicationKind = ContextKind.of(LD_APPLICATION_KIND);
        Map<String, Callable<LDValue>> applicationCallables = new HashMap<>();
        applicationCallables.put(ENV_ATTRIBUTES_VERSION, () -> LDValue.of(ENV_ATTRIBUTES_VERSION));
        applicationCallables.put(ATTR_ID, () -> LDValue.of(environmentReporter.getApplicationInfo().getApplicationId()));
        applicationCallables.put(ATTR_NAME, () -> LDValue.of(environmentReporter.getApplicationInfo().getApplicationName()));
        applicationCallables.put(ATTR_VERSION, () -> LDValue.of(environmentReporter.getApplicationInfo().getApplicationVersion()));
        applicationCallables.put(ATTR_VERSION_NAME, () -> LDValue.of(environmentReporter.getApplicationInfo().getApplicationVersionName()));

        ContextKind ldDeviceKind = ContextKind.of(LD_DEVICE_KIND);
        Map<String, Callable<LDValue>> deviceCallables = new HashMap<>();
        deviceCallables.put(ENV_ATTRIBUTES_VERSION, () -> LDValue.of(ENV_ATTRIBUTES_VERSION));
        deviceCallables.put(ATTR_MANUFACTURER, () -> LDValue.of(environmentReporter.getManufacturer()));
        deviceCallables.put(ATTR_MODEL, () -> LDValue.of(environmentReporter.getModel()));
        deviceCallables.put(ATTR_LOCALE, () -> LDValue.of(environmentReporter.getLocale()));
        deviceCallables.put(ATTR_OS, () -> new ObjectBuilder()
                .put(ATTR_FAMILY, environmentReporter.getOSFamily())
                .put(ATTR_VERSION, environmentReporter.getOSVersion())
                .build());

        return Arrays.asList(
                new ContextRecipe(
                        ldApplicationKind,
                        () -> persistentData.getOrGenerateContextKey(ldApplicationKind),
                        applicationCallables
                ),
                new ContextRecipe(
                        ldDeviceKind,
                        () -> persistentData.getOrGenerateContextKey(ldDeviceKind),
                        deviceCallables
                )
        );
    }
}