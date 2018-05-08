package com.launchdarkly.android.tls;

import android.support.annotation.NonNull;

import java.io.IOException;

import okhttp3.CipherSuite;
import okhttp3.Handshake;
import okhttp3.Response;
import okhttp3.TlsVersion;
import timber.log.Timber;

/**
 * Intercepts the SSL connection and prints TLS version and CipherSuite in the log.
 */
public class SSLHandshakeInterceptor implements okhttp3.Interceptor {

    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        final Response response = chain.proceed(chain.request());
        printTlsAndCipherSuiteInfo(response);
        return response;
    }

    private void printTlsAndCipherSuiteInfo(Response response) {
        if (response != null) {
            Handshake handshake = response.handshake();
            if (handshake != null) {
                final CipherSuite cipherSuite = handshake.cipherSuite();
                final TlsVersion tlsVersion = handshake.tlsVersion();
                Timber.v("TLS: " + tlsVersion + ", CipherSuite: " + cipherSuite);
            }
        }
    }
}