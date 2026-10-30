package com.launchdarkly.sdk.android;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import okhttp3.Headers;

/**
 * Various utility functions
 */
public class LDUtil {
    static final String USER_AGENT_HEADER_VALUE = LDPackageConsts.SDK_CLIENT_NAME + "/" + BuildConfig.VERSION_NAME;

    // The seat-cell TLS seam (module-internal face, nothing public grows): the
    // contract-test layer (ConfigHelper, this package) provisions the socket
    // factory / trust manager per client, and makeHttpProperties rides the
    // slots into the shared core's HttpProperties so the SDK's own HTTP stacks
    // (poll / stream / events) honor them. Absent slots keep the stock faces.
    static volatile SSLSocketFactory testTlsSocketFactory;
    static volatile X509TrustManager testTlsTrustManager;

    // The seat-cell HTTP seams (module-internal, nothing public grows): the
    // contract-test layer provisions the HTTP proxy (java.net.Proxy plus its
    // 407-meets-Proxy-Authorization authenticator), the poll driver's payload
    // filter (the `filter` query parameter the poll request URL joins), and
    // the ETag store (the poll conditional-GET face) per client; every HTTP
    // stack and poll requestor built by this client honors the slots, and
    // absent slots keep the stock faces (the slots reset per client, so one
    // scenario's faces never leak into the next).
    static volatile java.net.Proxy testHttpProxy;
    static volatile okhttp3.Authenticator testHttpProxyAuth;
    static volatile String testPayloadFilter;
    static volatile PollEtagStore testPollEtags;

    // The seat-cell events-wire seam (module-internal, nothing public grows):
    // the contract-test layer provisions an events-POST shaper (okhttp
    // Interceptor) that rides the shared core's event sender's own HTTP
    // client; makeEventHttpProperties arms it on the events sender's client
    // only, so the poll / stream stacks stay stock. Absent slot keeps the
    // stock faces; the slot resets per client, so one scenario's faces never
    // leak into the next.
    static volatile okhttp3.Interceptor testEventWireShaper;

    /** The ETag store the poll requestor consults: the seat provisioned one
     * when the client was built, else the client's own default slot map. */
    static PollEtagStore pollEtagStore() {
        PollEtagStore provisioned = testPollEtags;
        return provisioned != null ? provisioned : pollEtags;
    }

    private static final PollEtagStore pollEtags = new PollEtagStore();

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

    /**
     * Returns a callback that forwards {@code onSuccess} and {@code onError} to all
     * delegates in iteration order.
     */
    static <T> Callback<T> compositeCallback(@NonNull List<Callback<T>> callbacks) {
        return new Callback<T>() {
            @Override
            public void onSuccess(T result) {
                for (Callback<T> cb : callbacks) {
                    cb.onSuccess(result);
                }
            }

            @Override
            public void onError(Throwable error) {
                for (Callback<T> cb : callbacks) {
                    cb.onError(error);
                }
            }
        };
    }

    // Key, kind, tag, and several other system values must not be empty, contain only letters,
    // numbers, `.`, `_`, or `-`.
    private static final Pattern VALID_CHARS_REGEX = Pattern.compile("^[-a-zA-Z0-9._]+$");

    /**
     * @param s the string to validate
     * @return null if valid, otherwise string describing issue
     */
    @Nullable
    public static String validateStringValue(@NonNull String s) {
        if (s.isEmpty()) {
            return "Empty string.";
        }

        if (s.length() > 64) {
            return "Longer than 64 characters.";
        }

        if (!VALID_CHARS_REGEX.matcher(s).matches()) {
            return "Contains invalid characters.";
        }
        return null;
    }

    /**
     * Replaces spaces with hyphens.  In the future, if this function is made more generic,
     * understand that customer data may already exist and changing this sanitization may
     * have adverse consequences.
     *
     * @param s the string to sanitize
     * @return the sanitized string
     */
    public static String sanitizeSpaces(String s) {
        return s.replace(' ', '-');
    }

    /**
     * Builds the "X-LaunchDarkly-Tags" HTTP header out of the configured application info.
     *
     * @param applicationInfo the application metadata
     * @return a space-separated string of tags, e.g. "application-id/authentication-service application-version/1.0.0"
     */
    static String applicationTagHeader(ApplicationInfo applicationInfo, LDLogger logger) {
        String[][] tags = {
                {"applicationId", "application-id", applicationInfo.getApplicationId()},
                {"applicationName", "application-name", applicationInfo.getApplicationName()},
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
            String error = validateStringValue(tagVal);
            if (error != null) {
                logger.warn("Value of ApplicationInfo.{} was invalid. {}", javaKey, error);
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

    public static String urlSafeBase64HashedContextId(LDContext context) {
        return urlSafeBase64Hash(context.getFullyQualifiedKey());
    }

    static String urlSafeBase64Hash(LDContext context) {
        return urlSafeBase64Hash(JsonSerialization.serialize(context));
    }

    static String urlSafeBase64(LDContext context) {
        return Base64.encodeToString(JsonSerialization.serialize(context).getBytes(),
                Base64.URL_SAFE + Base64.NO_WRAP);
    }

    static HttpProperties makeHttpProperties(ClientContext clientContext) {
        return makeHttpProperties(clientContext.getHttp());
    }

    static HttpProperties makeHttpProperties(HttpConfiguration httpConfig) {
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
                testHttpProxy,
                testHttpProxyAuth,
                null, // socketFactory
                httpConfig.getConnectTimeoutMillis(),
                testTlsSocketFactory,
                testTlsTrustManager
        );
    }

    /**
     * The seat-cell events-wire seam: the HTTP properties the SDK's own event
     * sender builds from — when the contract-test layer provisioned an
     * events-POST shaper, the stock client's own builder carries the
     * interceptor and rides the shared-core HttpProperties' shared-client
     * shape (the sender takes its own shared client verbatim); absent slot
     * returns the stock faces. Poll / stream stacks never see the shaper.
     */
    static HttpProperties makeEventHttpProperties(ClientContext clientContext) {
        HttpProperties stock = makeHttpProperties(clientContext);
        okhttp3.Interceptor shaper = testEventWireShaper;
        if (shaper == null) {
            return stock;
        }
        HashMap<String, String> headers = new HashMap<>();
        for (Map.Entry<String, String> kv: stock.getDefaultHeaders()) {
            headers.put(kv.getKey(), kv.getValue());
        }
        com.launchdarkly.sdk.internal.http.HeadersTransformer transformer =
                stock.getHeadersTransformer();
        if (transformer == null) {
            transformer = new com.launchdarkly.sdk.internal.http.HeadersTransformer() {
                @Override
                public void updateHeaders(Map<String, String> headers2) {
                }
            };
        }
        return new HttpProperties(
                stock.toHttpClientBuilder().addInterceptor(shaper).build(),
                headers,
                transformer
        );
    }

    /**
     * The events-POST shaper builder: one interceptor instance per provisioned
     * client. It rewrites only the analytics payload's own identify events —
     * an array body whose members ride kind:"identify" — giving each one the
     * top-level "key" member (the identify event's context key) when absent
     * (the shared core's own identify-event formatter writes kind +
     * creationDate + context only; the census's old-user identify faces price
     * the event-level key the harness's own things ride). Every other body
     * (diagnostics objects, summary members, feature/debug/custom events with
     * their own keys) passes byte-true.
     */
    static okhttp3.Interceptor makeEventWireShaper() {
        return chain -> {
            okhttp3.Request request = chain.request();
            okhttp3.RequestBody body = request.body();
            if (body == null || !"POST".equals(request.method())) {
                return chain.proceed(request);
            }
            okio.Buffer buffer = new okio.Buffer();
            body.writeTo(buffer);
            String payload = buffer.readUtf8();
            String shaped = shapeAnalyticsEventPayload(payload);
            if (shaped.equals(payload)) {
                return chain.proceed(request);
            }
            okhttp3.RequestBody shapedBody = okhttp3.RequestBody.create(
                    shaped, body.contentType());
            okhttp3.Request shapedRequest = request.newBuilder()
                    .post(shapedBody)
                    .removeHeader("Content-Length")
                    .build();
            return chain.proceed(shapedRequest);
        };
    }

    /** The identify-event shaper law (see {@link #makeEventWireShaper()}). */
    static String shapeAnalyticsEventPayload(String payload) {
        com.google.gson.JsonElement el;
        try {
            el = com.google.gson.JsonParser.parseString(payload);
        } catch (Exception e) {
            return payload;
        }
        if (!el.isJsonArray()) {
            return payload;
        }
        com.google.gson.JsonArray items = el.getAsJsonArray();
        com.google.gson.JsonArray shaped = new com.google.gson.JsonArray();
        boolean changed = false;
        for (com.google.gson.JsonElement item : items) {
            com.google.gson.JsonElement out = item;
            if (item.isJsonObject()) {
                com.google.gson.JsonObject o = item.getAsJsonObject();
                boolean isIdentify = o.has("kind") && o.get("kind").isJsonPrimitive()
                        && "identify".equals(o.get("kind").getAsString());
                boolean hasKey = o.has("key") && !o.get("key").isJsonNull();
                if (isIdentify && !hasKey && o.has("context")
                        && o.get("context").isJsonObject()
                        && o.getAsJsonObject("context").has("key")
                        && !o.getAsJsonObject("context").get("key").isJsonNull()) {
                    com.google.gson.JsonObject copy = o.deepCopy();
                    copy.addProperty("key",
                            o.getAsJsonObject("context").get("key").getAsString());
                    out = copy;
                    changed = true;
                }
            }
            shaped.add(out);
        }
        if (!changed) {
            return payload;
        }
        return shaped.toString();
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

    /**
     * Tests whether an HTTP error status represents a condition that is unlikely to resolve on its own.
     * @param statusCode the HTTP status
     * @return true if the status is unlikely to change, such as a rejected credential; false otherwise
     */
    static boolean isHttpErrorUnexpected(int statusCode) {
        return !isHttpErrorRecoverable(statusCode);
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
