package com.launchdarkly.sdk.android;

import android.app.Application;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.Hook;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * The interface for the LaunchDarkly SDK client.
 * <p>
 * To obtain a client instance, use {@link LDClient} methods such as {@link LDClient#init(Application, LDConfig, LDContext)}.
 */
public interface LDClientInterface extends Closeable {
    /**
     * Returns true if the client has successfully connected to LaunchDarkly and received feature flags after
     * {@link LDClient#init(Application, LDConfig, LDContext, int)} was called.
     *
     * Also returns true if the SDK knows it will never be able to fetch flag data (such as when the client is set
     * to offline mode or if in one shot configuration, the one shot fails).
     *
     * Otherwise this returns false until the client is able to retrieve latest feature flag data from
     * LaunchDarkly services. This includes not connecting to LaunchDarkly within the start wait time provided to
     * {@link LDClient#init(Application, LDConfig, LDContext, int)} even if the SDK has cached feature flags.
     *
     * @return true if the client is able to retrieve flag data from LaunchDarkly or offline, false if the client has been
     * unable to up to this point.
     */
    boolean isInitialized();

    /**
     * Checks whether the client has been put into offline mode. This is true only if {@link #setOffline()}
     * was called, or if the configuration had {@link LDConfig.Builder#offline(boolean)} set to true,
     * not if the client is simply offline due to a loss of network connectivity.
     *
     * @return true if the client is in offline mode
     */
    boolean isOffline();

    /**
     * Shuts down any network connections maintained by the client and puts the client in offline
     * mode, preventing the client from opening new network connections until
     * <code>setOnline()</code> is called.
     * <p>
     * Note: The client automatically monitors the device's network connectivity and app foreground
     * status, so calling <code>setOffline()</code> or <code>setOnline()</code> is normally
     * unnecessary in most situations.
     */
    void setOffline();

    /**
     * Restores network connectivity for the client, if the client was previously in offline mode.
     * This operation may be throttled if it is called too frequently.
     * <p>
     * Note: The client automatically monitors the device's network connectivity and app foreground
     * status, so calling <code>setOffline()</code> or <code>setOnline()</code> is normally
     * unnecessary in most situations.
     */
    void setOnline();

    /**
     * Tracks that an application-defined event occurred, and provides an additional numeric value
     * for custom metrics.
     * <p>
     * This method creates a "custom" analytics event containing the specified event name (key)
     * the current evaluation context, optional custom data, and a numeric metric value.
     * <p>
     * Note that event delivery is asynchronous, so the event may not actually be sent until
     * later; see {@link #flush()}.
     *
     * @param eventName   the name of the event
     * @param data        an {@link LDValue} containing additional data associated with the event;
     *                    if not applicable, you may pass either {@code null} or
     *                    {@link LDValue#ofNull()}
     * @param metricValue a numeric value used by the LaunchDarkly experimentation feature in
     *                    numeric custom metrics; this field will also be returned as part of the
     *                    custom event for Data Export
     * @see #track(String)
     * @see #trackData(String, LDValue)
     */
    void trackMetric(String eventName, LDValue data, double metricValue);

    /**
     * Tracks that an application-defined event occurred, and provides additional custom data.
     * <p>
     * This method creates a "custom" analytics event containing the specified event name (key)
     * the current evaluation context, and optional custom data. To specify a numeric metric, use
     * {@link #trackMetric(String, LDValue, double)} instead.
     * <p>
     * Note that event delivery is asynchronous, so the event may not actually be sent until
     * later; see {@link #flush()}.
     *
     * @param eventName the name of the event
     * @param data      an {@link LDValue} containing additional data associated with the event;
     *                  if not applicable, you may pass either {@code null} or
     *                  {@link LDValue#ofNull()}
     * @see #track(String)
     * @see #trackMetric(String, LDValue, double)
     */
    void trackData(String eventName, LDValue data);

    /**
     * Tracks that an application-defined event occurred.
     * <p>
     * This method creates a "custom" analytics event containing the specified event name (key)
     * and the current evaluation context. You may attach other data to the event by calling
     * {@link #trackData(String, LDValue)} or {@link #trackMetric(String, LDValue, double)}
     * instead.
     * <p>
     * Note that event delivery is asynchronous, so the event may not actually be sent until
     * later; see {@link #flush()}.
     *
     * @param eventName the name of the event
     * @see #trackData(String, LDValue)
     * @see #trackMetric(String, LDValue, double) 
     */
    void track(String eventName);

    /**
     * Changes the current evaluation context, requests flags for that context from LaunchDarkly if we are online,
     * and generates an analytics event to tell LaunchDarkly about the context.
     * <p>
     * If the SDK is online, the returned {@code Future} is completed once the SDK has received feature
     * flag values for the new context from LaunchDarkly, or received an unrecoverable error. If the SDK
     * is offline, the returned {@code Future} is completed immediately.
     * <p>
     * The SDK normally caches flag settings for recently used evaluation contexts; this behavior
     * can be configured with {@link LDConfig.Builder#maxCachedContexts(int)}.
     *
     * @param context the new evaluation context; see {@link LDClient} for more about
     *   setting the context and optionally requesting a unique key for it
     * @return a Future whose success indicates the flag values for the new evaluation context have
     *   been stored locally and are ready for use
     * @since 3.0.0
     */
    Future<Void> identify(LDContext context);

    /**
     * Sends all pending events to LaunchDarkly.
     */
    void flush();

    /**
     * Returns a map of all feature flags for the current evaluation context. No events are sent to LaunchDarkly.
     *
     * @return a map of all feature flags
     */
    Map<String, LDValue> allFlags();

    /**
     * Returns the boolean value of a feature flag for the current evaluation context.
     * <p>
     * If the flag variation does not have a boolean value, or if an error makes it impossible to
     * evaluate the flag (for instance, if {@code flagKey} does not match any existing flag),
     * {@code defaultValue} is returned.
     *
     * @param flagKey key for the flag to evaluate
     * @param defaultValue default value in case of errors evaluating the flag
     * @return value of the flag or the default value
     */
    boolean boolVariation(String flagKey, boolean defaultValue);

    /**
     * Returns the boolean value of a feature flag for the current evaluation context, along with
     * information about how it was calculated.
     * <p>
     * Note that this will only work if you have set {@code evaluationReasons} to true with
     * {@link LDConfig.Builder#evaluationReasons(boolean)}. Otherwise, the {@code reason} property of the result
     * will be null.
     * <p>
     * The evaluation reason will also be included in analytics events, if you are capturing
     * detailed event data for this flag.
     *
     * @param flagKey key for the flag to evaluate
     * @param defaultValue default value in case of errors evaluating the flag (see {@link #boolVariation(String, boolean)})
     * @return an {@link EvaluationDetail} object containing the value and other information
     *
     * @since 2.7.0
     */
    EvaluationDetail<Boolean> boolVariationDetail(String flagKey, boolean defaultValue);

    /**
     * Returns the integer value of a feature flag for the current evaluation context.
     * <p>
     * If the flag variation has a numeric value that is not an integer, it is rounded toward zero.
     * <p>
     * If the flag variation does not have a numeric value, or if an error makes it impossible to
     * evaluate the flag (for instance, if {@code flagKey} does not match any existing flag),
     * {@code defaultValue} is returned.
     *
     * @param flagKey key for the flag to evaluate
     * @param defaultValue default value in case of errors evaluating the flag
     * @return value of the flag or the default value
     */
    int intVariation(String flagKey, int defaultValue);

    /**
     * Returns the integer value of a feature flag for the current evaluation context, along with
     * information about how it was calculated.
     * <p>
     * Note that this will only work if you have set {@code evaluationReasons} to true with
     * {@link LDConfig.Builder#evaluationReasons(boolean)}. Otherwise, the {@code reason} property of the result
     * will be null.
     * <p>
     * The evaluation reason will also be included in analytics events, if you are capturing
     * detailed event data for this flag.
     * <p>
     * The behavior is otherwise identical to {@link #intVariation}.
     *
     * @param flagKey key for the flag to evaluate
     * @param defaultValue default value in case of errors evaluating the flag (see {@link #intVariation(String, int)})
     * @return an {@link EvaluationDetail} object containing the value and other information
     *
     * @since 2.7.0
     */
    EvaluationDetail<Integer> intVariationDetail(String flagKey, int defaultValue);

    /**
     * Returns the double-precision floating-point numeric value of a feature flag for the
     * current evaluation context.
     * <p>
     * If the flag variation does not have a numeric value, or if an error makes it impossible to
     * evaluate the flag (for instance, if {@code flagKey} does not match any existing flag),
     * {@code defaultValue} is returned.
     *
     * @param flagKey key for the flag to evaluate
     * @param defaultValue default value in case of errors evaluating the flag
     * @return value of the flag or the default value
     */
    double doubleVariation(String flagKey, double defaultValue);

    /**
     * Returns the double-precision floating-point numeric value of a feature flag for the
     * current evaluation context, along with information about how it was calculated.
     *
     * Note that this will only work if you have set {@code evaluationReasons} to true with
     * {@link LDConfig.Builder#evaluationReasons(boolean)}. Otherwise, the {@code reason} property of the result
     * will be null.
     * <p>
     * The evaluation reason will also be included in analytics events, if you are capturing
     * detailed event data for this flag.
     * <p>
     * The behavior is otherwise identical to {@link #doubleVariation}.
     *
     * @param flagKey key for the flag to evaluate
     * @param defaultValue default value in case of errors evaluating the flag (see {@link #doubleVariation(String, double)})
     * @return an {@link EvaluationDetail} object containing the value and other information.
     */
    EvaluationDetail<Double> doubleVariationDetail(String flagKey, double defaultValue);

    /**
     * Returns the string value of a feature flag for the current evaluation context.
     * <p>
     * If the flag variation does not have a string value, or if an error makes it impossible to
     * evaluate the flag (for instance, if {@code flagKey} does not match any existing flag),
     * {@code defaultValue} is returned.
     *
     * @param flagKey key for the flag to evaluate
     * @param defaultValue default value in case of errors evaluating the flag
     * @return value of the flag or the default value
     */
    String stringVariation(String flagKey, String defaultValue);

    /**
     * Returns the string value of a feature flag for the current evaluation context, along with
     * information about how it was calculated.
     * <p>
     * Note that this will only work if you have set {@code evaluationReasons} to true with
     * {@link LDConfig.Builder#evaluationReasons(boolean)}. Otherwise, the {@code reason} property of the result
     * will be null.
     * <p>
     * The evaluation reason will also be included in analytics events, if you are capturing
     * detailed event data for this flag.
     * <p>
     * The behavior is otherwise identical to {@link #stringVariation}.
     *
     * @param flagKey key for the flag to evaluate
     * @param defaultValue default value in case of errors evaluating the flag (see {@link #stringVariation(String, String)})
     * @return an {@link EvaluationDetail} object containing the value and other information.
     *
     * @since 2.7.0
     */
    EvaluationDetail<String> stringVariationDetail(String flagKey, String defaultValue);

    /**
     * Registers a {@link FeatureFlagChangeListener} to be called when the <code>flagKey</code> changes
     * from its current value. If the feature flag is deleted, the <code>listener</code> will be unregistered.
     *
     * @param flagKey  the flag key to attach the listener to
     * @param listener the listener to attach to the flag key
     * @see #unregisterFeatureFlagListener(String, FeatureFlagChangeListener)
     */
    void registerFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener);

    /**
     * Returns the value of a feature flag for the current evaluation context, which may be of any
     * type.
     * <p>
     * The type {@link LDValue} is used to represent any of the value types that can exist in JSON.
     * Use {@link LDValue} methods to examine its type and value.
     *
     * @param flagKey key for the flag to evaluate
     * @param defaultValue default value in case of errors evaluating the flag
     * @return value of the flag or the default value. Result will never be null, but may be LDValue#ofNull()
     */
    LDValue jsonValueVariation(String flagKey, LDValue defaultValue);

    /**
     * Returns the value of a feature flag for the current evaluation context, which may be of any
     * type, along with information about how it was calculated.
     * <p>
     * The type {@link LDValue} is used to represent any of the value types that can exist in JSON.
     * Use {@link LDValue} methods to examine its type and value.
     * <p>
     * Note that this will only work if you have set {@code evaluationReasons} to true with
     * {@link LDConfig.Builder#evaluationReasons(boolean)}. Otherwise, the {@code reason} property of the result
     * will be null.
     * <p>
     * The evaluation reason will also be included in analytics events, if you are capturing
     * detailed event data for this flag.
     * <p>
     * The behavior is otherwise identical to {@link #jsonValueVariation}.
     *
     * @param flagKey key for the flag to evaluate
     * @param defaultValue default value in case of errors evaluating the flag (see {@link #jsonValueVariation(String, LDValue)})
     * @return an {@link EvaluationDetail} object containing the value and other information.
     *
     */
    EvaluationDetail<LDValue> jsonValueVariationDetail(String flagKey, LDValue defaultValue);

    /**
     * Unregisters a {@link FeatureFlagChangeListener} for the <code>flagKey</code>.
     *
     * @param flagKey  the flag key to remove the listener from
     * @param listener the listener to remove from the flag key
     * @see #registerFeatureFlagListener(String, FeatureFlagChangeListener)
     */
    void unregisterFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener);

    /**
     * Gets a {@link ConnectionInformation} object from the client representing the current state
     * of the clients connection.
     *
     * @return An object representing the status of the connection to LaunchDarkly.
     */
    ConnectionInformation getConnectionInformation();

    /**
     * Unregisters a {@link LDStatusListener} so it will no longer be called on connection status updates.
     * @param LDStatusListener the listener to be removed
     */
    void unregisterStatusListener(LDStatusListener LDStatusListener);

    /**
     * Registers a {@link LDStatusListener} to be called on connection status updates.
     * @param LDStatusListener the listener to be called on a connection status update
     */
    void registerStatusListener(LDStatusListener LDStatusListener);

    /**
     * Registers a {@link LDAllFlagsListener} to be called when a flag update is processed by the
     * SDK.
     *
     * @param allFlagsListener the listener to be called with a list of flag keys on a flag update
     */
    void registerAllFlagsListener(LDAllFlagsListener allFlagsListener);

    /**
     * Unregisters a {@link LDAllFlagsListener} so it will no longer be called on flag updates.
     *
     * @param allFlagsListener the listener to be removed
     */
    void unregisterAllFlagsListener(LDAllFlagsListener allFlagsListener);

    /**
     * Checks whether {@link LDConfig.Builder#disableBackgroundUpdating(boolean)} was set to
     * {@code true} in the configuration.
     *
     * @return true if background polling is disabled
     */
    boolean isDisableBackgroundPolling();

    /**
     * Returns the version of the SDK, for instance "2.7.0".
     *
     * @return the version string
     * @since 2.7.0
     */
    String getVersion();

    /**
     * Add a hook to the client. In order to register a hook before the client
     * starts, please use the `hooks` method of {@link LDConfig.Builder}.
     * <p>
     * Hooks provide entry points which allow for observation of SDK functions.
     *
     * @param hook The hook to add.
     */
    void addHook(Hook hook);
}
