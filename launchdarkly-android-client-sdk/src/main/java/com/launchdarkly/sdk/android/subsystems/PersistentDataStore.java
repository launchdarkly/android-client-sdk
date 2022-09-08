package com.launchdarkly.sdk.android.subsystems;

import java.util.Map;

/**
 * Interface for a data store that holds feature flag data and other SDK properties in a simple
 * string format.
 * <p>
 * The SDK has a default implementation which uses the Android {@code SharedPreferences} API. A
 * custom implementation of this interface could store data somewhere else, or use that API in a
 * different way.
 * <p>
 * Each data item is uniquely identified by the combination of a "namespace" and a "key", and has
 * a string value. These are defined as follows:
 * <ul>
 *     <li> Both the namespace and the key are non-empty strings. </li>
 *     <li> Both the namespace and the key contain only alphanumeric characters, hyphens, and
 *     underscores.</li>
 *     <li> The value can be any non-null string, including an empty string. </li>
 * </ul>
 * <p>
 * The SDK will also provide its own caching layer on top of the persistent data store; the data
 * store implementation should not provide caching, but simply do every query or update that the
 * SDK tells it to do.
 * <p>
 * Error handling is defined as follows: if any data store operation encounters an I/O error, or
 * is otherwise unable to complete its task, it should throw an exception to make the SDK aware
 * of this. The SDK will decide whether to log the exception.
 *
 * @since 4.0.0
 */
public interface PersistentDataStore {
    /**
     * Attempts to retrieve a string value from the store.
     *
     * @param storeNamespace the namespace identifier
     * @param key the unique key within that namespace
     * @return the value, or null if not found
     */
    String getValue(String storeNamespace, String key);

    /**
     * Attempts to update or remove a string value in the store.
     *
     * @param storeNamespace the namespace identifier
     * @param key the unique key within that namespace
     * @param value the new value, or null to remove the key
     */
    void setValue(String storeNamespace, String key, String value);

    /**
     * Attempts to update multiple values atomically.
     *
     * @param storeNamespace the namespace identifier
     * @param keysAndValues the keys and values to update
     */
    void setValues(String storeNamespace, Map<String, String> keysAndValues);

    /**
     * Removes any values that currently exist in the given namespace.
     *
     * @param storeNamespace the namespace identifier
     * @param fullyDelete true to purge all data structures related to the namespace, false to
     *                    simply leave it empty
     */
    void clear(String storeNamespace, boolean fullyDelete);
}
