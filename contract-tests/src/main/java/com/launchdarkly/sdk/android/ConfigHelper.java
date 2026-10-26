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
     * CENTINEL seat-cell TLS seam (the v3 TLS data face: skipVerifyPeer /
     * customCAFile): provisions the socket factory + trust manager the SDK's
     * own {@code LDUtil.makeHttpProperties} honors for every HTTP stack
     * (poll / stream / events). Module-internal, no public API grows;
     * absent params reset the slots so one scenario's TLS faces never leak
     * into the next client.
     */
    public static void configureTls(LDConfig.Builder builder,
            Boolean skipVerifyPeer, String customCAFile) {
        javax.net.ssl.SSLSocketFactory socketFactory = null;
        javax.net.ssl.X509TrustManager trustManager = null;
        if (Boolean.TRUE.equals(skipVerifyPeer)) {
            trustManager = new LenientTrustManager();
            // OkHttp binds the stack via sslSocketFactory(factory, trustManager)
            // (the shared core's HttpProperties applies the pair together), so
            // the skip-verify face needs a factory that matches the permissive
            // trust manager — a plain default-JSSE context wraps fine when the
            // trust manager rides in from the caller.
            try {
                javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
                ssl.init(null, new javax.net.ssl.TrustManager[] {trustManager}, null);
                socketFactory = ssl.getSocketFactory();
            } catch (java.security.GeneralSecurityException e) {
                throw new RuntimeException("TLS provisioning failed", e);
            }
        } else if (customCAFile != null) {
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(customCAFile);
                java.security.cert.CertificateFactory certFactory =
                        java.security.cert.CertificateFactory.getInstance("X.509");
                java.util.Collection<? extends java.security.cert.Certificate> cas =
                        certFactory.generateCertificates(in);
                in.close();
                java.security.KeyStore ks = java.security.KeyStore.getInstance(
                        java.security.KeyStore.getDefaultType());
                ks.load(null, null);
                int i = 0;
                for (java.security.cert.Certificate ca : cas) {
                    ks.setCertificateEntry("ca" + i++, ca);
                }
                javax.net.ssl.TrustManagerFactory tmf =
                        javax.net.ssl.TrustManagerFactory.getInstance(
                                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);
                for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
                    if (tm instanceof javax.net.ssl.X509TrustManager) {
                        trustManager = (javax.net.ssl.X509TrustManager) tm;
                    }
                }
                javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
                sslContext.init(null, new javax.net.ssl.TrustManager[] {trustManager}, null);
                socketFactory = sslContext.getSocketFactory();
            } catch (java.security.GeneralSecurityException | java.io.IOException e) {
                throw new RuntimeException("TLS provisioning failed: " + customCAFile, e);
            }
        }
        LDUtil.testTlsSocketFactory = socketFactory;
        LDUtil.testTlsTrustManager = trustManager;
    }

    /** The skip-verify face's trust manager: no certificate validation. */
    private static final class LenientTrustManager implements javax.net.ssl.X509TrustManager {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
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
