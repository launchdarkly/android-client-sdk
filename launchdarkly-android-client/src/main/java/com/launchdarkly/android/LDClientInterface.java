package com.launchdarkly.android;


import com.google.gson.JsonElement;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.Future;

public interface LDClientInterface extends Closeable {
    boolean isInitialized();

    boolean isOffline();

    void setOffline();

    void setOnline();

    void track(String eventName, JsonElement data);

    void track(String eventName);

    Future<Void> identify(LDUser user);

    void flush();

    Map<String, ?> allFlags();

    /**
     * Returns the flag value for the current user. Returns <code>fallback</code> when one of the following occurs:
     * <ol>
     * <li>Flag is missing</li>
     * <li>The flag is not of a boolean type</li>
     * <li>Any other error</li>
     * </ol>
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag
     * @return value of the flag or fallback
     */
    Boolean boolVariation(String flagKey, Boolean fallback);

    /**
     * Returns the flag value for the current user, along with information about how it was calculated.
     *
     * Note that this will only work if you have set {@code evaluationReasons} to true in
     * {@link LDConfig.Builder#evaluationReasons}. Otherwise, the {@code reason} property of the result
     * will be null.
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag (see {@link #boolVariation(String, Boolean)})
     * @return an {@link EvaluationDetail} object containing the value and other information.
     */
    EvaluationDetail<Boolean> boolVariationDetail(String flagKey, Boolean fallback);

    /**
     * Returns the flag value for the current user. Returns <code>fallback</code> when one of the following occurs:
     * <ol>
     * <li>Flag is missing</li>
     * <li>The flag is not of a numeric type</li>
     * <li>Any other error</li>
     * </ol>
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag
     * @return value of the flag or fallback
     */
    Integer intVariation(String flagKey, Integer fallback);

    /**
     * Returns the flag value for the current user, along with information about how it was calculated.
     *
     * Note that this will only work if you have set {@code evaluationReasons} to true in
     * {@link LDConfig.Builder#evaluationReasons}. Otherwise, the {@code reason} property of the result
     * will be null.
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag (see {@link #intVariation(String, Integer)})
     * @return an {@link EvaluationDetail} object containing the value and other information.
     */
    EvaluationDetail<Integer> intVariationDetail(String flagKey, Integer fallback);

    /**
     * Returns the flag value for the current user. Returns <code>fallback</code> when one of the following occurs:
     * <ol>
     * <li>Flag is missing</li>
     * <li>The flag is not of a numeric type</li>
     * <li>Any other error</li>
     * </ol>
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag
     * @return value of the flag or fallback
     */
    Float floatVariation(String flagKey, Float fallback);

    /**
     * Returns the flag value for the current user, along with information about how it was calculated.
     *
     * Note that this will only work if you have set {@code evaluationReasons} to true in
     * {@link LDConfig.Builder#evaluationReasons}. Otherwise, the {@code reason} property of the result
     * will be null.
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag (see {@link #floatVariation(String, Float)})
     * @return an {@link EvaluationDetail} object containing the value and other information.
     */
    EvaluationDetail<Float> floatVariationDetail(String flagKey, Float fallback);

    /**
     * Returns the flag value for the current user. Returns <code>fallback</code> when one of the following occurs:
     * <ol>
     * <li>Flag is missing</li>
     * <li>The flag is not of a string type</li>
     * <li>Any other error</li>
     * </ol>
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag
     * @return value of the flag or fallback
     */
    String stringVariation(String flagKey, String fallback);

    /**
     * Returns the flag value for the current user, along with information about how it was calculated.
     *
     * Note that this will only work if you have set {@code evaluationReasons} to true in
     * {@link LDConfig.Builder#evaluationReasons}. Otherwise, the {@code reason} property of the result
     * will be null.
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag (see {@link #stringVariation(String, String)})
     * @return an {@link EvaluationDetail} object containing the value and other information.
     */
    EvaluationDetail<String> stringVariationDetail(String flagKey, String fallback);

    /**
     * Returns the flag value for the current user. Returns <code>fallback</code> when one of the following occurs:
     * <ol>
     * <li>Flag is missing</li>
     * <li>Any other error</li>
     * </ol>
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag
     * @return value of the flag or fallback
     */
    JsonElement jsonVariation(String flagKey, JsonElement fallback);

    /**
     * Returns the flag value for the current user, along with information about how it was calculated.
     *
     * Note that this will only work if you have set {@code evaluationReasons} to true in
     * {@link LDConfig.Builder#evaluationReasons}. Otherwise, the {@code reason} property of the result
     * will be null.
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag (see {@link #jsonVariation(String, JsonElement)})
     * @return an {@link EvaluationDetail} object containing the value and other information.
     */
    EvaluationDetail<JsonElement> jsonVariationDetail(String flagKey, JsonElement fallback);

    void registerFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener);

    void unregisterFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener);

    boolean isDisableBackgroundPolling();
}
