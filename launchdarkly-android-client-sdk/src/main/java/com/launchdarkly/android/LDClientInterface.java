package com.launchdarkly.android;

import android.app.Application;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.EvaluationDetail;

import com.google.gson.JsonElement;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * The interface for the LaunchDarkly SDK client.
 * <p>
 * To obtain a client instance, use {@link LDClient} methods such as {@link LDClient#init(Application, LDConfig, LDUser)}.
 */
public interface LDClientInterface extends Closeable {
    /**
     * Checks whether the client is ready to return feature flag values. This is true if either
     * the client has successfully connected to LaunchDarkly and received feature flags, or the
     * client has been put into offline mode (in which case it will return only default flag values).
     *
     * @return true if the client is initialized or offline
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
     * Tracks that a user performed an event, and provides an additional numeric value for custom metrics.
     *
     * @param eventName   the name of the event
     * @param data        an {@link LDValue} containing additional data associated with the event; if not applicable,
     *                    you may pass either {@code null} or {@link LDValue#ofNull()}
     * @param metricValue A numeric value used by the LaunchDarkly experimentation feature in
     *                    numeric custom metrics. This field will also be returned as part of the
     *                    custom event for Data Export.
     */
    void trackMetric(String eventName, LDValue data, double metricValue);

    /**
     * Tracks that a user performed an event, and provides additional custom data.
     *
     * @param eventName the name of the event
     * @param data      an {@link LDValue} containing additional data associated with the event
     */
    void trackData(String eventName, LDValue data);

    /**
     * Tracks that a user performed an event.
     *
     * @param eventName the name of the event
     */
    void track(String eventName);

    /**
     * Sets the current user, retrieves flags for that user, then sends an Identify Event to LaunchDarkly.
     * The 5 most recent users' flag settings are kept locally.
     *
     * @param user The user for evaluation and event reporting
     * @return Future whose success indicates this user's flag settings have been stored locally and are ready for evaluation.
     */
    Future<Void> identify(LDUser user);

    /**
     * Sends all pending events to LaunchDarkly.
     */
    void flush();

    /**
     * Returns a map of all feature flags for the current user. No events are sent to LaunchDarkly.
     *
     * @return a map of all feature flags
     */
    Map<String, LDValue> allFlags();

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
    boolean boolVariation(String flagKey, boolean fallback);

    /**
     * Returns the flag value for the current user, along with information about how it was calculated.
     *
     * Note that this will only work if you have set {@code evaluationReasons} to true with
     * {@link LDConfig.Builder#evaluationReasons(boolean)}. Otherwise, the {@code reason} property of the result
     * will be null.
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag (see {@link #boolVariation(String, boolean)})
     * @return an {@link EvaluationDetail} object containing the value and other information.
     *
     * @since 2.7.0
     */
    EvaluationDetail<Boolean> boolVariationDetail(String flagKey, boolean fallback);

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
    int intVariation(String flagKey, int fallback);

    /**
     * Returns the flag value for the current user, along with information about how it was calculated.
     *
     * Note that this will only work if you have set {@code evaluationReasons} to true with
     * {@link LDConfig.Builder#evaluationReasons(boolean)}. Otherwise, the {@code reason} property of the result
     * will be null.
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag (see {@link #intVariation(String, int)})
     * @return an {@link EvaluationDetail} object containing the value and other information.
     *
     * @since 2.7.0
     */
    EvaluationDetail<Integer> intVariationDetail(String flagKey, int fallback);

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
    double doubleVariation(String flagKey, double fallback);

    /**
     * Returns the flag value for the current user, along with information about how it was calculated.
     *
     * Note that this will only work if you have set {@code evaluationReasons} to true with
     * {@link LDConfig.Builder#evaluationReasons(boolean)}. Otherwise, the {@code reason} property of the result
     * will be null.
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag (see {@link #doubleVariation(String, double)})
     * @return an {@link EvaluationDetail} object containing the value and other information.
     */
    EvaluationDetail<Double> doubleVariationDetail(String flagKey, double fallback);

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
     * Note that this will only work if you have set {@code evaluationReasons} to true with
     * {@link LDConfig.Builder#evaluationReasons(boolean)}. Otherwise, the {@code reason} property of the result
     * will be null.
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag (see {@link #stringVariation(String, String)})
     * @return an {@link EvaluationDetail} object containing the value and other information.
     *
     * @since 2.7.0
     */
    EvaluationDetail<String> stringVariationDetail(String flagKey, String fallback);

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
    LDValue jsonValueVariation(String flagKey, LDValue fallback);

    /**
     * Returns the flag value for the current user, along with information about how it was calculated.
     *
     * Note that this will only work if you have set {@code evaluationReasons} to true with
     * {@link LDConfig.Builder#evaluationReasons(boolean)}. Otherwise, the {@code reason} property of the result
     * will be null.
     *
     * @param flagKey key for the flag to evaluate
     * @param fallback fallback value in case of errors evaluating the flag (see {@link #jsonValueVariation(String, LDValue)})
     * @return an {@link EvaluationDetail} object containing the value and other information.
     *
     */
    EvaluationDetail<LDValue> jsonValueVariationDetail(String flagKey, LDValue fallback);

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
}
