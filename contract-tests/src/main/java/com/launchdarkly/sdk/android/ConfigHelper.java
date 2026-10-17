package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.LDContext;

import java.util.concurrent.ConcurrentHashMap;

public class ConfigHelper {
    // This method exists because Config.Builder.persistentDataStore is currently package-private -
    // using a custom persistence component is not yet part of the public API.
    public static LDConfig.Builder configureIsolatedInMemoryPersistence(LDConfig.Builder builder) {
        builder.persistentDataStore(new InMemoryPersistentDataStore());
        return builder;
    }

    /**
     * CENTINEL client-lane extension (campaign client-INTERFACE section 5, PERSIST.5):
     * with a storage scope every client created under the same scope tag shares one local
     * store (successive launches of one app); without one each client stays isolated as
     * before. Returns the store wired into the builder so the caller can preload data.
     */
    private static final ConcurrentHashMap<String, InMemoryPersistentDataStore> SCOPED_STORES =
            new ConcurrentHashMap<String, InMemoryPersistentDataStore>();

    public static InMemoryPersistentDataStore configureInMemoryPersistence(LDConfig.Builder builder,
            String storageScope) {
        InMemoryPersistentDataStore store = storageScope == null
                ? new InMemoryPersistentDataStore()
                : SCOPED_STORES.computeIfAbsent(storageScope, k -> new InMemoryPersistentDataStore());
        builder.persistentDataStore(store);
        return store;
    }

    /**
     * CENTINEL client-lane extension: writes bootstrap flag data (the campaign's
     * x-cp-bootstrap all-flags-state JSON) through the SDK's own persisted-store
     * pathway, so the real {@code FDv2CacheInitializer} serves the values during
     * client initialization — exactly the LD offline-cache bootstrapping contract.
     *
     * The all-flags-state shape carries values at the top level with per-flag
     * metadata under {@code $flagsState}; the store format is the flat
     * {@code {flagKey: Flag}} map, so the conversion keeps key/value/version/variation.
     */
    public static void preloadBootstrapData(InMemoryPersistentDataStore store,
            String mobileKey, java.util.Map<String, Object> bootData, LDContext initialContext) {
        if (store == null || bootData == null || initialContext == null) {
            return;
        }
        // Mirrors PersistentDataStoreWrapper's own namespacing: the per-environment
        // namespace hashes the mobile key; the per-context key hashes the context's
        // fully-qualified key. The literal prefixes live in the wrapper (private); the
        // SDK's hash helpers are package-visible to this same-package caller.
        String namespace = "LaunchDarkly_" + LDUtil.urlSafeBase64Hash(mobileKey == null ? "" : mobileKey);
        String key = "flags_" + LDUtil.urlSafeBase64HashedContextId(initialContext);

        com.google.gson.Gson gson = new com.google.gson.Gson();
        com.google.gson.JsonObject flags = new com.google.gson.JsonObject();
        Object stateRaw = bootData.get("$flagsState");
        java.util.Map<?, ?> flagsState = stateRaw instanceof java.util.Map ? (java.util.Map<?, ?>) stateRaw : null;
        for (java.util.Map.Entry<String, Object> e : bootData.entrySet()) {
            String flagKey = e.getKey();
            if (flagKey.startsWith("$")) {
                continue; // $flagsState / $valid metadata
            }
            com.google.gson.JsonObject f = new com.google.gson.JsonObject();
            f.addProperty("key", flagKey);
            f.add("value", gson.toJsonTree(e.getValue()));
            int version = 1;
            Integer variation = null;
            if (flagsState != null && flagsState.get(flagKey) instanceof java.util.Map<?, ?>) {
                java.util.Map<?, ?> fs = (java.util.Map<?, ?>) flagsState.get(flagKey);
                if (fs.get("version") instanceof Number) {
                    version = ((Number) fs.get("version")).intValue();
                }
                if (fs.get("variation") instanceof Number) {
                    variation = ((Number) fs.get("variation")).intValue();
                }
            }
            f.addProperty("version", version);
            if (variation != null) {
                f.addProperty("variation", variation);
            }
            flags.add(flagKey, f);
        }
        store.setValue(namespace, key, gson.toJson(flags));
    }
}
