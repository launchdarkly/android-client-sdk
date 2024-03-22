package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.ConnectionInformation;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DataSource;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSink;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import androidx.annotation.NonNull;

/**
 * A mechanism for providing dynamically updatable feature flag state in a simplified form to an SDK
 * client in test scenarios.
 * <p>
 * This mechanism does not use any external resources. It provides only the data that the
 * application has put into it using the {@link #update(FlagBuilder)} method.
 * <p>
 * The example code below uses a simple boolean flag, but more complex configurations are possible using
 * the methods of the {@link FlagBuilder} that is returned by {@link #flag(String)}.
 *
 * <pre><code>
 *     TestData td = TestData.dataSource();
 *     td.update(testData.flag("flag-key-1").booleanFlag().variation(true));
 *
 *     LDConfig config = new LDConfig.Builder()
 *         .mobileKey("my-mobile-key")
 *         .dataSource(td)
 *         .build();
 *     LDClient client = new LDClient(sdkKey, config, initialContext, startWaitSeconds);
 *
 *     // flags can be updated at any time:
 *     td.update(testData.flag("flag-key-2")
 *         .variationForUser("some-user-key", false));
 * </code></pre>
 *
 * @since 4.0.0
 */
public final class TestData implements ComponentConfigurer<DataSource> {
    private final Object lock = new Object();
    private final Map<String, Integer> currentFlagVersions = new HashMap<>();
    private final Map<String, FlagBuilder> currentBuilders = new HashMap<>();
    private final List<DataSourceImpl> instances = new CopyOnWriteArrayList<>();

    /**
     * Creates a new instance of the test data source.
     * <p>
     * See {@link TestData} for details.
     *
     * @return a new configurable test data source
     */
    public static TestData dataSource() {
        return new TestData();
    }

    private TestData() {}

    /**
     * Creates or copies a {@link FlagBuilder} for building a test flag configuration.
     * <p>
     * If this flag key has already been defined in this {@code TestData} instance, then the builder
     * starts with the same configuration that was last provided for this flag.
     * <p>
     * Otherwise, it starts with a new default configuration in which the flag has {@code true} and
     * {@code false} variations, and is {@code true} by default for all contexts. You can change
     * any of those properties, and provide more complex behavior, using the {@link FlagBuilder}\
     * methods.
     * <p>
     * Once you have set the desired configuration, pass the builder to {@link #update(FlagBuilder)}.
     *
     * @param key the flag key
     * @return a flag configuration builder
     * @see #update(FlagBuilder)
     */
    public FlagBuilder flag(String key) {
        FlagBuilder existingBuilder;
        synchronized (lock) {
            existingBuilder = currentBuilders.get(key);
        }
        if (existingBuilder != null) {
            return new FlagBuilder(existingBuilder);
        }
        return new FlagBuilder(key).booleanFlag();
    }

    /**
     * Updates the test data with the specified flag configuration.
     * <p>
     * This has the same effect as if a flag were added or modified on the LaunchDarkly dashboard.
     * It immediately propagates the flag change to any {@code LDClient} instance(s) that you have
     * already configured to use this {@code TestData}. If no {@code LDClient} has been started yet,
     * it simply adds this flag to the test data which will be provided to any {@code LDClient} that
     * you subsequently configure.
     * <p>
     * Any subsequent changes to this {@link FlagBuilder} instance do not affect the test data,
     * unless you call {@link #update(FlagBuilder)} again.
     *
     * @param flagBuilder a flag configuration builder
     * @return the same {@code TestData} instance
     * @see #flag(String)
     */
    public TestData update(FlagBuilder flagBuilder) {
        String key = flagBuilder.key;
        FlagBuilder clonedBuilder = new FlagBuilder(flagBuilder);
        int newVersion;

        synchronized (lock) {
            int oldVersion = currentFlagVersions.containsKey(key) ?
                    currentFlagVersions.get(key).intValue() : 0;
            newVersion = oldVersion + 1;
            currentFlagVersions.put(key, newVersion);
            currentBuilders.put(key, clonedBuilder);
        }

        for (DataSourceImpl instance: instances) {
            instance.doUpdate(clonedBuilder, newVersion);
        }

        return this;
    }

    @Override
    public DataSource build(ClientContext clientContext) {
        DataSourceImpl instance = new DataSourceImpl(clientContext.getEvaluationContext(),
                clientContext.getDataSourceUpdateSink());
        synchronized (lock) {
            instances.add(instance);
        }
        return instance;
    }

    private Map<String, Flag> makeInitData(LDContext context) {
        Map<String, Flag> data = new HashMap<>();
        synchronized (lock) {
            for (Map.Entry<String, FlagBuilder> kv: currentBuilders.entrySet()) {
                int version = currentFlagVersions.containsKey(kv.getKey()) ?
                        currentFlagVersions.get(kv.getKey()).intValue() : 1;
                data.put(kv.getKey(), kv.getValue().createFlag(version, context));
            }
        }
        return data;
    }

    private void closedInstance(DataSourceImpl instance) {
        synchronized (lock) {
            instances.remove(instance);
        }
    }

    /**
     * A builder for feature flag configurations to be used with {@link TestData}.
     *
     * @see TestData#flag(String)
     * @see TestData#update(FlagBuilder)
     */
    public static final class FlagBuilder {
        private static final int TRUE_VARIATION_FOR_BOOLEAN = 0;
        private static final int FALSE_VARIATION_FOR_BOOLEAN = 1;

        /**
         * A functional interface for use with {@link #variationFunc(VariationFunc)} or
         * {@link #variationIndexFunc(VariationFunc)}.
         * @param <T> the return type
         */
        public static interface VariationFunc<T> {
            /**
             * Returns the result for the specified context, or null if undefined.
             * @param context the evaluation context
             * @return the result or null
             */
            public T apply(LDContext context);
        }

        final String key;
        CopyOnWriteArrayList<LDValue> variations;
        int defaultVariation;
        Map<ContextKind, Map<String, Integer>> variationByContextKey;
        VariationFunc<Integer> variationFunc;

        private FlagBuilder(String key) {
            this.key = key;
            variations = new CopyOnWriteArrayList<>();
            defaultVariation = 0;
            variationByContextKey = new HashMap<>();
        }

        private FlagBuilder(FlagBuilder from) {
            this.key = from.key;
            this.variations = new CopyOnWriteArrayList<>(from.variations);
            this.defaultVariation = from.defaultVariation;
            this.variationByContextKey = new HashMap<>();
            for (Map.Entry<ContextKind, Map<String, Integer>> kv: from.variationByContextKey.entrySet()) {
                this.variationByContextKey.put(kv.getKey(), new HashMap<>(kv.getValue()));
            }
            this.variationFunc = from.variationFunc;
        }

        private boolean isBooleanFlag() {
            return variations.size() == 2 &&
                    variations.get(TRUE_VARIATION_FOR_BOOLEAN).equals(LDValue.of(true)) &&
                    variations.get(FALSE_VARIATION_FOR_BOOLEAN).equals(LDValue.of(false));
        }

        /**
         * A shortcut for setting the flag to use the standard boolean configuration.
         * <p>
         * This is the default for all new flags created with {@link TestData#flag(String)}. The flag
         * will have two variations, {@code true} and {@code false} (in that order); it will return
         * {@code false} whenever targeting is off, and {@code true} when targeting is on if no other
         * settings specify otherwise.
         *
         * @return the builder
         */
        public FlagBuilder booleanFlag() {
            return isBooleanFlag() ? this :
                    variations(LDValue.of(true), LDValue.of(false));
        }

        /**
         * Sets the flag to return the specified boolean variation for all contexts by default.
         * <p>
         * The flag's variations are set to {@code true} and {@code false} if they are not already
         * (equivalent to calling {@link #booleanFlag()}).
         *
         * @param variation the desired true/false variation to be returned by default
         * @return the builder
         */
        public FlagBuilder variation(boolean variation) {
            return booleanFlag().variation(variationForBoolean(variation));
        }

        /**
         * Sets the flag to return the specified variation for all contexts by default.
         * <p>
         * The variation is specified by number, out of whatever variation values have already been
         * defined.
         *
         * @param variationIndex the desired variation: 0 for the first, 1 for the second, etc.
         * @return the builder
         */
        public FlagBuilder variation(int variationIndex) {
            this.defaultVariation = variationIndex;
            return this;
        }

        /**
         * Sets the flag to return the specified variation for all contexts by default.
         * <p>
         * The value may be of any JSON type, as defined by {@link LDValue}. If the value matches
         * one of the values previously specified with {@link #variations(LDValue...)}, then the
         * variation index is set to the index of that value. Otherwise, the value is added to the
         * variation list.
         *
         * @param value the desired value to be returned for all contexts
         * @return the builder
         */
        public FlagBuilder variation(LDValue value) {
            defaultVariation = findOrAddVariationValue(value);
            variationFunc = null;
            return this;
        }

        /**
         * Sets the flag to return the specified boolean variation for a specific user key,
         * overriding any other defaults.
         * <p>
         * The flag's variations are set to {@code true} and {@code false} if they are not already
         * (equivalent to calling {@link #booleanFlag()}).
         *
         * @param userKey a user key
         * @param variation the desired true/false variation to be returned for this user
         * @return the builder
         * @see #variationForUser(String, int)
         * @see #variationForUser(String, LDValue)
         * @see #variationForKey(ContextKind, String, boolean)
         */
        public FlagBuilder variationForUser(String userKey, boolean variation) {
            return this.variationForKey(ContextKind.DEFAULT, userKey, variation);
        }

        /**
         * Sets the flag to return the specified variation for a specific user key, overriding any
         * other defaults.
         *
         * @param userKey a user key
         * @param variationIndex the desired variation: 0 for the first, 1 for the second, etc.
         * @return the builder
         * @see #variationForUser(String, boolean)
         * @see #variationForUser(String, LDValue)
         * @see #variationForKey(ContextKind, String, int)
         */
        public FlagBuilder variationForUser(String userKey, int variationIndex) {
            return variationForKey(ContextKind.DEFAULT, userKey, variationIndex);
        }

        /**
         * Sets the flag to return the specified variation for a specific user key, overriding any
         * other defaults.
         * <p>
         * The value may be of any JSON type, as defined by {@link LDValue}. If the value matches
         * one of the values previously specified with {@link #variations(LDValue...)}, then the
         * variation index is set to the index of that value. Otherwise, the value is added to the
         * variation list.
         *
         * @param userKey a user key
         * @param value the desired variation value
         * @return the builder
         * @see #variationForUser(String, boolean)
         * @see #variationForUser(String, int)
         * @see #variationForKey(ContextKind, String, LDValue)
         */
        public FlagBuilder variationForUser(String userKey, LDValue value) {
            return variationForUser(userKey, findOrAddVariationValue(value));
        }

        /**
         * Sets the flag to return the specified boolean variation for a specific context by kind
         * and key, overriding any other defaults.
         * <p>
         * The flag's variations are set to {@code true} and {@code false} if they are not already
         * (equivalent to calling {@link #booleanFlag()}).
         *
         * @param contextKind the context kind (if null, {@link ContextKind#DEFAULT} is used)
         * @param contextKey the context key
         * @param variation the desired true/false variation to be returned for this context
         * @return the builder
         * @see #variationForKey(ContextKind, String, int)
         * @see #variationForKey(ContextKind, String, LDValue)
         * @see #variationForUser(String, boolean) 
         */
        public FlagBuilder variationForKey(ContextKind contextKind, String contextKey, boolean variation) {
            return booleanFlag().variationForKey(contextKind, contextKey, variationForBoolean(variation));
        }

        /**
         * Sets the flag to return the specified variation for a specific context by kind and key,
         * overriding any other defaults.
         *
         * @param contextKind the context kind (if null, {@link ContextKind#DEFAULT} is used)
         * @param contextKey the context key
         * @param variationIndex the desired variation: 0 for the first, 1 for the second, etc.
         * @return the builder
         * @see #variationForKey(ContextKind, String, boolean)
         * @see #variationForKey(ContextKind, String, LDValue)
         * @see #variationForUser(String, int)
         */
        public FlagBuilder variationForKey(ContextKind contextKind, String contextKey, int variationIndex) {
            contextKind = contextKind == null ? ContextKind.DEFAULT : contextKind;
            Map<String, Integer> targets = variationByContextKey.get(contextKind);
            if (targets == null) {
                targets = new HashMap<>();
                variationByContextKey.put(contextKind, targets);
            }
            targets.put(contextKey, variationIndex);
            return this;
        }

        /**
         * Sets the flag to return the specified variation for a specific context by kind and key,
         * overriding any other defaults.
         * <p>
         * The value may be of any JSON type, as defined by {@link LDValue}. If the value matches
         * one of the values previously specified with {@link #variations(LDValue...)}, then the
         * variation index is set to the index of that value. Otherwise, the value is added to the
         * variation list.
         *
         * @param contextKind the context kind (if null, {@link ContextKind#DEFAULT} is used)
         * @param contextKey the context key
         * @param value the desired variation value
         * @return the builder
         * @see #variationForKey(ContextKind, String, boolean)
         * @see #variationForKey(ContextKind, String, int)
         * @see #variationForUser(String, LDValue)
         */
        public FlagBuilder variationForKey(ContextKind contextKind, String contextKey, LDValue value) {
            return variationForKey(contextKind, contextKey, findOrAddVariationValue(value));
        }

        /**
         * Sets the flag to use a function to determine whether to return true or false for any
         * given context.
         * <p>
         * This function takes an evaluation context and returns {@code true}, {@code false}, or
         * {@code null}. A {@code null} result means that the flag will fall back to its default
         * variation.
         * <p>
         * The flag's variations are set to {@code true} and {@code false} if they are not already
         * (equivalent to calling {@link #booleanFlag()}).
         * <p>
         * This function is called only if the context was not specifically targeted with
         * {@link #variationForUser(String, boolean)} or {@link #variationForKey(ContextKind, String, boolean)}.
         *
         * @param variationFunc a function to determine the variation
         * @return the builder
         */
        public FlagBuilder variationFunc(final VariationFunc<Boolean> variationFunc)
        {
            return booleanFlag().variationIndexFunc(new VariationFunc<Integer>() {
                @Override
                public Integer apply(LDContext context) {
                    Boolean v = variationFunc.apply(context);
                    return v == null ? null : variationForBoolean(v.booleanValue());
                }
            });
        }

        /**
         * Sets the flag to use a function to determine the variation index to return for any given
         * context.
         * <p>
         * This function takes an evaluation context and returns an {@link LDValue}. The value
         * must match one of the values already specified with {@link #variations(LDValue...)};
         * otherwise, the function will have no effect.
         * <p>
         * This function is called only if the context was not specifically targeted with a method
         * such as {@link #variationForUser(String, int)} or {@link #variationForKey(ContextKind, String, int)}.
         *
         * @param variationFunc a function to determine the variation
         * @return the builder
         */
        public FlagBuilder variationValueFunc(VariationFunc<LDValue> variationFunc)
        {
            return variationIndexFunc(new VariationFunc<Integer>() {
                @Override
                public Integer apply(LDContext context) {
                    LDValue v = variationFunc.apply(context);
                    return variations.contains(v) ? variations.indexOf(v) : null;
                }
            });
        }

        /**
         * Sets the flag to use a function to determine the variation index to return for any given
         * context.
         * <p>
         * This function takes an evaluation context and returns an integer variation index or
         * {@code null}. A {@code null} result means that the flag will fall back to its default
         * variation.
         * <p>
         * This function is called only if the context was not specifically targeted with
         * {@link #variationForUser(String, int)} or {@link #variationForKey(ContextKind, String, int)}.
         *
         * @param variationFunc a function to determine the variation
         * @return the builder
         */
        public FlagBuilder variationIndexFunc(VariationFunc<Integer> variationFunc)
        {
            this.variationFunc = variationFunc;
            return this;
        }

        /**
         * Changes the allowable variation values for the flag.
         * <p>
         * The value may be of any JSON type, as defined by {@link LDValue}. For instance, a boolean flag
         * normally has {@code LDValue.of(true), LDValue.of(false)}; a string-valued flag might have
         * {@code LDValue.of("red"), LDValue.of("green")}; etc.
         *
         * @param values the desired variations
         * @return the builder
         */
        public FlagBuilder variations(LDValue... values) {
            variations.clear();
            for (LDValue v: values) {
                variations.add(v);
            }
            return this;
        }

        private int findOrAddVariationValue(LDValue value) {
            if (!variations.contains(value)) {
                variations.add(value);
            }
            return variations.indexOf(value);
        }

        private Integer findVariationInTargets(LDContext context) {
            if (context.isMultiple()) {
                for (int i = 0; i < context.getIndividualContextCount(); i++) {
                    Integer result = findVariationInTargets(context.getIndividualContext(i));
                    if (result != null) {
                        return result;
                    }
                }
                return null;
            }
            Map<String, Integer> targets = variationByContextKey.get(context.getKind());
            return targets == null ? null : targets.get(context.getKey());
        }

        Flag createFlag(int version, LDContext context) {
            Integer targetedVariation = findVariationInTargets(context);
            if (targetedVariation == null && variationFunc != null) {
                targetedVariation = variationFunc.apply(context);
            }
            int variation = targetedVariation == null ? defaultVariation : targetedVariation.intValue();
            LDValue value = variation < 0 || variation >= variations.size() ? LDValue.ofNull() :
                    variations.get(variation);
            EvaluationReason reason = targetedVariation == null ? EvaluationReason.fallthrough() :
                    EvaluationReason.targetMatch();
            return new Flag(key, value, version, null, variation,
                    false, false, null, reason);
        }

        private static int variationForBoolean(boolean value) {
            return value ? TRUE_VARIATION_FOR_BOOLEAN : FALSE_VARIATION_FOR_BOOLEAN;
        }
    }

    private final class DataSourceImpl implements DataSource {
        final LDContext context;
        final DataSourceUpdateSink updates;

        DataSourceImpl(LDContext context, DataSourceUpdateSink updates) {
            this.context = context;
            this.updates = updates;
        }

        @Override
        public void start(@NonNull Callback<Boolean> resultCallback) {
            updates.init(context, makeInitData(context));
            updates.setStatus(ConnectionInformation.ConnectionMode.STREAMING, null);
            resultCallback.onSuccess(true);
        }

        @Override
        public void stop(@NonNull Callback<Void> completionCallback) {
            closedInstance(this);
        }

        void doUpdate(FlagBuilder flagBuilder, int version) {
            Flag flag = flagBuilder.createFlag(version , context);
            updates.upsert(context, flag);
        }
    }
}
