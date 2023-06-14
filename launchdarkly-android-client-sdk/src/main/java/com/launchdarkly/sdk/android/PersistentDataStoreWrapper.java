package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;
import com.launchdarkly.sdk.json.SerializationException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A facade over some implementation of {@link PersistentDataStore}, which adds behavior that
 * should be the same for all implementations, such as the specific data keys we use, the logging
 * of errors, and how data is serialized and deserialized. This allows {@link FlagStoreManager} (and
 * other parts of the SDK that may need to access persistent storage) to be written in a clearer way
 * without embedding many implementation details.
 * <p>
 * See {@link PersistentDataStore} for the rules about what namespaces and keys we can use. It is
 * {@code PersistentDataStoreWrapper}'s responsibility to follow those rules. We are OK as long as
 * we use base64url-encoding for all variables such as context key and mobile key, and use only
 * characters from the base64url set (A-Z, a-z, 0-9, -, and _) for other namespace/key components.
 * <p>
 * Higher-level tasks such as enforcing the "maximum number of cached contexts" limit, coordinating
 * changes to the flag data with changes to the context index, and notifying flag change listeners,
 * are implemented in {@link FlagStoreManager}.
 * <p>
 * All operations of {@link PersistentDataStoreWrapper} and {@link PersistentDataStoreWrapper.PerEnvironmentData}
 * have the following error-handling behavior: if the underlying data store throws an exception,
 * the wrapper catches and logs it, and the operation is a no-op (if it was a setter) or returns
 * null (if it was a getter).
 */
final class PersistentDataStoreWrapper {
    static class SavedConnectionInfo {
        final Long lastSuccessTime;
        final Long lastFailureTime;
        final LDFailure lastFailure;

        SavedConnectionInfo(Long lastSuccessTime, Long lastFailureTime, LDFailure lastFailure) {
            this.lastSuccessTime = lastSuccessTime;
            this.lastFailureTime = lastFailureTime;
            this.lastFailure = lastFailure;
        }
    }

    private static final String GLOBAL_NAMESPACE = "LaunchDarkly";
    private static final String NAMESPACE_PREFIX = "LaunchDarkly_";

    // TODO: We are now generating keys for non-anonymous contexts (the telemetry contexts).
    // At the moment of writing this TODO, the generated keys are using the anonKey prefix.
    private static final String ANON_CONTEXT_KEY_PREFIX = "anonKey_";
    private static final String ENVIRONMENT_METADATA_KEY = "index";
    private static final String ENVIRONMENT_CONTEXT_DATA_KEY_PREFIX = "flags_";
    private static final String ENVIRONMENT_LAST_SUCCESS_TIME_KEY = "lastSuccessfulConnection";
    private static final String ENVIRONMENT_LAST_FAILURE_TIME_KEY = "lastFailedConnection";
    private static final String ENVIRONMENT_LAST_FAILURE_KEY = "lastFailure";

    private final PersistentDataStore persistentStore;

    private final LDLogger logger;
    private final Object storeLock = new Object();

    private Map<ContextKind, String> generatedKeysCache = new HashMap<>();

    private final AtomicBoolean loggedStorageError = new AtomicBoolean(false);

    public PersistentDataStoreWrapper(
            PersistentDataStore persistentStore,
            LDLogger logger
    ) {
        this.persistentStore = persistentStore;
        this.logger = logger;
    }

    /**
     * Returns a {@link PerEnvironmentData} instance for accessing environment-specific storage.
     * The other top-level {@link PersistentDataStoreWrapper} methods are for the few things that
     * are not environment-specific.
     *
     * @param mobileKey the mobile key
     * @return a data access object
     */
    public PerEnvironmentData perEnvironmentData(String mobileKey) {
        return new PerEnvironmentData(mobileKey);
    }

    /**
     * Returns the cached generated key if one exists or generates and saves a key for the
     * specified context kind This is not in {@link PerEnvironmentData} because these generated
     * keys are per device+context kind, not per environment.
     *
     * @param contextKind a context kind
     * @return the cached key, or null if there is none
     */
    public String getOrGenerateContextKey(ContextKind contextKind) {
        synchronized (generatedKeysCache) {
            // do we have a generated key in the cache?
            String cachedKey = generatedKeysCache.get(contextKind);
            if (cachedKey != null) {
                return cachedKey;
            }

            // do we have a generated key in persistence?
            String persistedKey = tryGetValue(GLOBAL_NAMESPACE, ANON_CONTEXT_KEY_PREFIX + contextKind.toString());
            if (persistedKey != null) {
                generatedKeysCache.put(contextKind, persistedKey);
                return persistedKey;
            }

            // don't have a generated key, so generate a key
            final String generatedKey = UUID.randomUUID().toString();
            generatedKeysCache.put(contextKind, generatedKey);

            logger.info("Did not find a generated key for context kind \"{}\". Generating a new one: {}", contextKind, generatedKey);

            // Updating persistent storage may be a blocking I/O call, so don't do it on the main
            // thread. That part doesn't need to be done under this lock anyway - the fact that
            // we've put it into the cachedGeneratedKey map already means any subsequent calls will
            // get that value and not have to hit the persistent store.

            // TODO: Revisit usage of new Thread instead of executor service.
            new Thread(new Runnable() {
                public void run() {
                    trySetValue(GLOBAL_NAMESPACE,
                            ANON_CONTEXT_KEY_PREFIX + contextKind.toString(), generatedKey);
                }
            }).run();

            return generatedKey;
        }
    }

    /**
     * Stores a generated anonymous key for the specified context kind (used when
     * {@link LDConfig.Builder#generateAnonymousKeys(boolean)} is enabled). This is not in
     * {@link PerEnvironmentData} because these generated keys are per device+context kind, not
     * per environment.
     *
     * @param contextKind a context kind
     * @param key         the generated key
     */
    public void setGeneratedContextKey(ContextKind contextKind, String key) {
        trySetValue(GLOBAL_NAMESPACE,
                ANON_CONTEXT_KEY_PREFIX + contextKind.toString(), key);
    }

    /**
     * Provides access to stored data that is specific to a single environment. This object is
     * returned by {@link PersistentDataStoreWrapper#perEnvironmentData(String)}.
     */
    final class PerEnvironmentData {
        private final String environmentNamespace;

        PerEnvironmentData(String mobileKey) {
            this.environmentNamespace = NAMESPACE_PREFIX + LDUtil.urlSafeBase64Hash(mobileKey);
        }

        /**
         * Returns the stored flag data, if any, for a specific context.
         *
         * @param hashedContextId the hashed key of the context
         * @return the {@link EnvironmentData}, or null if not found
         */
        public EnvironmentData getContextData(String hashedContextId) {
            String serializedData = tryGetValue(environmentNamespace,
                    keyForContextId(hashedContextId));
            try {
                return serializedData == null ? null : EnvironmentData.fromJson(serializedData);
            } catch (SerializationException e) {
                return null;
            }
        }

        /**
         * Stores flag data for a specific context, overwriting any previous data for that context.
         *
         * @param hashedContextId the hashed key of the context
         * @param allData         the flag data
         */
        public void setContextData(String hashedContextId, EnvironmentData allData) {
            trySetValue(environmentNamespace, keyForContextId(hashedContextId), allData.toJson());
        }

        /**
         * Removes the stored flag data, if any, for a specific context.
         *
         * @param hashedContextId the hashed key of the context
         */
        public void removeContextData(String hashedContextId) {
            trySetValue(environmentNamespace, keyForContextId(hashedContextId), null);
        }

        /**
         * Retrieves the list of contexts that have stored flag data for this environment.
         *
         * @return a {@link ContextIndex} (never null; will be empty if none have been stored)
         */
        @NonNull
        public ContextIndex getIndex() {
            String serializedData = tryGetValue(environmentNamespace, ENVIRONMENT_METADATA_KEY);
            try {
                return serializedData == null ? new ContextIndex() :
                        ContextIndex.fromJson(serializedData);
            } catch (SerializationException e) {
                return null;
            }
        }

        /**
         * Updates the list of contexts that have stored flag data for this environment.
         *
         * @param contextIndex the context index
         */
        public void setIndex(@NonNull ContextIndex contextIndex) {
            trySetValue(environmentNamespace, ENVIRONMENT_METADATA_KEY, contextIndex.toJson());
        }

        /**
         * Retrieves stored connection status properties.
         *
         * @return a {@link SavedConnectionInfo} (never null; will be empty if none was stored)
         */
        @NonNull
        public SavedConnectionInfo getConnectionInfo() {
            Long lastSuccessTime = tryGetValueAsLong(environmentNamespace, ENVIRONMENT_LAST_SUCCESS_TIME_KEY);
            Long lastFailureTime = tryGetValueAsLong(environmentNamespace, ENVIRONMENT_LAST_FAILURE_TIME_KEY);
            String lastFailureJson = tryGetValue(environmentNamespace, ENVIRONMENT_LAST_FAILURE_KEY);
            LDFailure lastFailure = null;
            if (lastFailureJson != null) {
                try {
                    lastFailure = gsonInstance().fromJson(lastFailureJson, LDFailure.class);
                } catch (Exception e) {}
            }
            return new SavedConnectionInfo(lastSuccessTime, lastFailureTime, lastFailure);
        }

        /**
         * Updates the stored connection status properties
         *
         * @param connectionInfo a {@link SavedConnectionInfo}
         */
        public void setConnectionInfo(@NonNull SavedConnectionInfo connectionInfo) {
            Map<String, String> updates = new HashMap<>();
            updates.put(ENVIRONMENT_LAST_SUCCESS_TIME_KEY,
                    connectionInfo.lastSuccessTime == null ? null : String.valueOf(connectionInfo.lastSuccessTime));
            updates.put(ENVIRONMENT_LAST_FAILURE_TIME_KEY,
                    connectionInfo.lastFailureTime == null ? null : String.valueOf(connectionInfo.lastFailureTime));
            updates.put(ENVIRONMENT_LAST_FAILURE_KEY,
                    connectionInfo.lastFailure == null ? null : gsonInstance().toJson(connectionInfo.lastFailure));
            trySetValues(environmentNamespace, updates);
        }
    }

    private String keyForContextId(String hashedContextId) {
        return ENVIRONMENT_CONTEXT_DATA_KEY_PREFIX + hashedContextId;
    }

    private String tryGetValue(String namespace, String key) {
        try {
            synchronized (storeLock) {
                return persistentStore.getValue(namespace, key);
            }
        } catch (Exception e) {
            maybeLogStoreError(e);
            return null;
        }
    }

    private void trySetValue(String namespace, String key, String value) {
        try {
            synchronized (storeLock) {
                persistentStore.setValue(namespace, key, value);
            }
        } catch (Exception e) {
            maybeLogStoreError(e);
        }
    }

    private void trySetValues(String namespace, Map<String, String> keysAndValues) {
        try {
            synchronized (storeLock) {
                persistentStore.setValues(namespace, keysAndValues);
            }
        } catch (Exception e) {
            maybeLogStoreError(e);
        }
    }

    private void maybeLogStoreError(Exception e) {
        if (loggedStorageError.getAndSet(true)) {
            return;
        }
        LDUtil.logExceptionAtErrorLevel(logger, e, "Failure in persistent data store");
    }

    private Long tryGetValueAsLong(String namespace, String key) {
        String value = tryGetValue(namespace, key);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
