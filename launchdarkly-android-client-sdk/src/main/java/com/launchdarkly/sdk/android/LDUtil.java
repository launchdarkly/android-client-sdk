package com.launchdarkly.sdk.android;

import android.util.Base64;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;
import com.launchdarkly.sdk.internal.http.HeadersTransformer;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.sdk.json.JsonSerialization;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Headers;

class LDUtil {
    static final String AUTH_SCHEME = "api_key ";
    static final String USER_AGENT_HEADER_VALUE = "AndroidClient/" + BuildConfig.VERSION_NAME;

    static Headers makeRequestHeaders(
            @NonNull HttpConfiguration httpConfig,
            Map<String, String> additionalHeaders
    ) {
        HashMap<String, String> baseHeaders = new HashMap<>();
        for (Map.Entry<String, String> kv: httpConfig.getDefaultHeaders()) {
            baseHeaders.put(kv.getKey(), kv.getValue());
        }

        if (additionalHeaders != null) {
            baseHeaders.putAll(additionalHeaders);
        }

        if (httpConfig.getHeaderTransform() != null) {
            httpConfig.getHeaderTransform().updateHeaders(baseHeaders);
        }

        return Headers.of(baseHeaders);
    }

    static <T> void safeCallbackSuccess(ResultCallback<T> listener, T result) {
        if (listener != null) {
            listener.onSuccess(result);
        }
    }

    static void safeCallbackError(ResultCallback<?> listener, Throwable e) {
        if (listener != null) {
            listener.onError(e);
        }
    }

    static String urlSafeBase64Hash(String input) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hash = messageDigest.digest(input.getBytes(Charset.forName("UTF-8")));
            return Base64.encodeToString(hash, Base64.URL_SAFE + Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e); // shouldn't be possible; SHA-256 is built in
        }
    }

    static String base64Url(final LDContext context) {
        return Base64.encodeToString(JsonSerialization.serialize(context).getBytes(),
                Base64.URL_SAFE + Base64.NO_WRAP);
    }

    static HttpProperties makeHttpProperties(ClientContext clientContext) {
        HttpConfiguration httpConfig = clientContext.getHttp();
        HashMap<String, String> baseHeaders = new HashMap<>();
        for (Map.Entry<String, String> kv: httpConfig.getDefaultHeaders()) {
            baseHeaders.put(kv.getKey(), kv.getValue());
        }
        HeadersTransformer headersTransformer = null;
        if (httpConfig.getHeaderTransform() != null) {
            headersTransformer = new HeadersTransformer() {
                @Override
                public void updateHeaders(Map<String, String> headers) {
                    httpConfig.getHeaderTransform().updateHeaders(headers);
                }
            };
        }

        return new HttpProperties(
                httpConfig.getConnectTimeoutMillis(),
                baseHeaders,
                headersTransformer,
                null, // proxy
                null, // proxyAuth
                null, // socketFactory
                httpConfig.getConnectTimeoutMillis(),
                null, // sslSocketFactory
                null // trustManager
        );
    }

    interface ResultCallback<T> {
        void onSuccess(T result);
        void onError(Throwable e);
    }

    /**
     * Tests whether an HTTP error status represents a condition that might resolve on its own if we retry.
     * @param statusCode the HTTP status
     * @return true if retrying makes sense; false if it should be considered a permanent failure
     */
    static boolean isHttpErrorRecoverable(int statusCode) {
        if (statusCode >= 400 && statusCode < 500) {
            switch (statusCode) {
                case 400: // bad request
                case 408: // request timeout
                case 429: // too many requests
                    return true;
                default:
                    return false; // all other 4xx errors are unrecoverable
            }
        }
        return true;
    }

    static void logExceptionAtErrorLevel(LDLogger logger, Throwable ex, String msgFormat, Object... msgArgs) {
        logException(logger, ex, true, msgFormat, msgArgs);
    }

    static void logExceptionAtWarnLevel(LDLogger logger, Throwable ex, String msgFormat, Object... msgArgs) {
        logException(logger, ex, false, msgFormat, msgArgs);
    }

    private static void logException(LDLogger logger, Throwable ex, boolean asError, String msgFormat, Object... msgArgs) {
        String addFormat = msgFormat + " - {}";
        Object exSummary = LogValues.exceptionSummary(ex);
        Object[] args = Arrays.copyOf(msgArgs, msgArgs.length + 1);
        args[msgArgs.length] = exSummary;
        if (asError) {
            logger.error(addFormat, args);
        } else {
            logger.warn(addFormat, args);
        }
        logger.debug(LogValues.exceptionTrace(ex));
    }
}
