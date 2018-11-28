package com.launchdarkly.android;

import android.os.Build;

import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import timber.log.Timber;

class HttpClientBuilderGenerator {
    private static OkHttpClient.Builder okHttpClientBuilder = new OkHttpClient.Builder();

    static {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {     // Older than LOLLIPOP does not have TLSv1.2 enabled by default
            try {
                // This is the main fix here
                okHttpClientBuilder.sslSocketFactory(new TLSV12SocketFactory(), generateTLSv12TrustManager());
            } catch (KeyManagementException | IllegalStateException | NoSuchAlgorithmException | KeyStoreException ex) {
                ex.printStackTrace();
                Timber.e(ex, "TLS generator failure!");
            }
        }
    }

    static OkHttpClient.Builder getOkHttpClientBuilder() {
        return okHttpClientBuilder;
    }

    private static X509TrustManager generateTLSv12TrustManager() throws NoSuchAlgorithmException, KeyStoreException {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init((KeyStore)null);
        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
        if (trustManagers.length == 1 && trustManagers[0] instanceof X509TrustManager) {
            return (X509TrustManager)trustManagers[0];
        }
        throw new IllegalStateException("Unexpected default trust managers: " + Arrays.toString(trustManagers));
    }
}
