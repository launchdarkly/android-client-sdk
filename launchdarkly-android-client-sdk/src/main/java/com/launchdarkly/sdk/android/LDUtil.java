package com.launchdarkly.sdk.android;

import android.util.Base64;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;
import com.launchdarkly.sdk.internal.http.HeadersTransformer;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.sdk.json.JsonSerialization;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.Headers;

class LDUtil {
    static final String AUTH_SCHEME = "api_key ";
    static final String USER_AGENT_HEADER_VALUE = "AndroidClient/" + BuildConfig.VERSION_NAME;

    static <T> Callback<T> noOpCallback() {
        return new Callback<T>() {
            @Override
            public void onSuccess(T result) {
            }

            @Override
            public void onError(Throwable error) {
            }
        };
    }

    // Tag values must not be empty, and only contain letters, numbers, `.`, `_`, or `-`.
    private static Pattern TAG_VALUE_REGEX = Pattern.compile("^[-a-zA-Z0-9._]+$");

    /**
     * Builds the "X-LaunchDarkly-Tags" HTTP header out of the configured application info.
     *
     * @param applicationInfo the application metadata
     * @return a space-separated string of tags, e.g. "application-id/authentication-service application-version/1.0.0"
     */
    static String applicationTagHeader(ApplicationInfo applicationInfo, LDLogger logger) {
        String[][] tags = {
                {"applicationId", "application-id", applicationInfo.getApplicationId()},
                {"applicationVersion", "application-version", applicationInfo.getApplicationVersion()},
                {"applicationVersionName", "application-version-name", applicationInfo.getApplicationVersionName()}
        };
        List<String> parts = new ArrayList<>();
        for (String[] row : tags) {
            String javaKey = row[0];
            String tagKey = row[1];
            String tagVal = row[2];
            if (tagVal == null) {
                continue;
            }
            if (!TAG_VALUE_REGEX.matcher(tagVal).matches()) {
                logger.warn("Value of ApplicationInfo.{} contained invalid characters and was discarded", javaKey);
                continue;
            }
            if (tagVal.length() > 64) {
                logger.warn("Value of ApplicationInfo.{} was longer than 64 characters and was discarded", javaKey);
                continue;
            }
            parts.add(tagKey + "/" + tagVal);
        }
        return String.join(" ", parts);
    }

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
