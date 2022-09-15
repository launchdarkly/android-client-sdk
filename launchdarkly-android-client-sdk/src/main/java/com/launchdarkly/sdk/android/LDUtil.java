package com.launchdarkly.sdk.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;
import com.launchdarkly.sdk.internal.http.HeadersTransformer;
import com.launchdarkly.sdk.internal.http.HttpProperties;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

class LDUtil {
    static HttpProperties makeHttpProperties(
            LDConfig config,
            String mobileKey
    ) {
        HashMap<String, String> baseHeaders = new HashMap<>();
        baseHeaders.put("User-Agent", LDConfig.USER_AGENT_HEADER_VALUE);
        if (mobileKey != null) {
            baseHeaders.put("Authorization", LDConfig.AUTH_SCHEME + mobileKey);
        }
        if (config.getWrapperName() != null) {
            String wrapperVersion = "";
            if (config.getWrapperVersion() != null) {
                wrapperVersion = "/" + config.getWrapperVersion();
            }
            baseHeaders.put("X-LaunchDarkly-Wrapper", config.getWrapperName() + wrapperVersion);
        }
        HeadersTransformer headersTransformer = null;
        if (config.getHeaderTransform() != null) {
            headersTransformer = new HeadersTransformer() {
                @Override
                public void updateHeaders(Map<String, String> headers) {
                    config.getHeaderTransform().updateHeaders(headers);
                }
            };
        }

        return new HttpProperties(
                config.getConnectionTimeoutMillis(),
                baseHeaders,
                headersTransformer,
                null, // proxy
                null, // proxyAuth
                null, // socketFactory
                config.getConnectionTimeoutMillis(),
                null, // sslSocketFactory
                null // trustManager
        );
    }

    static Long getStoreValueAsLong(PersistentDataStore store, String namespace, String key) {
        String value = store.getValue(namespace, key);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Looks at the Android device status to determine if the device is online.
     *
     * @param context Context for getting the ConnectivityManager
     * @return whether device is connected to the internet
     */
    static boolean isInternetConnected(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= 23) {
                Network net = cm.getActiveNetwork();
                if (net == null)
                    return false;

                NetworkCapabilities nwc = cm.getNetworkCapabilities(net);

                // the older solution was cleaner but android went and
                // deprecated it :^)
                // hasTransport(NET_CAPABILITY_INTERNET) always returns false on emulators
                // so we check these instead
                return nwc != null && (
                        nwc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                || nwc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                                || nwc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                                || nwc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                                || nwc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                );
            } else {
                NetworkInfo active = cm.getActiveNetworkInfo();
                return active != null && active.isConnectedOrConnecting();
            }
        } catch (SecurityException ignored) {
            // See https://issuetracker.google.com/issues/175055271
            // We should fallback to assuming network is available
            return true;
        }
    }

    /**
     * Looks at both the Android device status and the environment's {@link LDClient} to determine if any network calls should be made.
     *
     * @param context         Context for getting the ConnectivityManager
     * @param environmentName Name of the environment to get the LDClient for
     * @return whether the device is connected to the internet and the LDClient instance is online
     */
    static boolean isClientConnected(Context context, String environmentName) {
        boolean deviceConnected = isInternetConnected(context);
        try {
            return deviceConnected && !LDClient.getForMobileKey(environmentName).isOffline();
        } catch (LaunchDarklyException e) {
            LDClient.getSharedLogger().error("Exception caught when getting LDClient: {}", LogValues.exceptionSummary(e));
            LDClient.getSharedLogger().debug(LogValues.exceptionTrace(e));
            return false;
        }
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
