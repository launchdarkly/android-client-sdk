package com.launchdarkly.sdk.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

import androidx.annotation.NonNull;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.UserAttribute;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class LDUtil {

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

    @NonNull
    static <T> Map<String, T> sharedPrefsGetAllGson(SharedPreferences sharedPreferences, Class<T> typeOf) {
        Map<String, ?> flags = sharedPreferences.getAll();
        Map<String, T> deserialized = new HashMap<>();
        for (Map.Entry<String, ?> entry : flags.entrySet()) {
            if (entry.getValue() instanceof String) {
                try {
                    T obj = GsonCache.getGson().fromJson((String) entry.getValue(), typeOf);
                    deserialized.put(entry.getKey(), obj);
                } catch (Exception ignored) {
                }
            }
        }
        return deserialized;
    }

    static <T> T sharedPrefsGetGson(SharedPreferences sharedPreferences, Class<T> typeOf, String key) {
        String data = sharedPreferences.getString(key, null);
        if (data == null) return null;
        try {
            return GsonCache.getGson().fromJson(data, typeOf);
        } catch (Exception ignored) {
            return null;
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

    static class LDUserPrivateAttributesTypeAdapter extends TypeAdapter<LDUser> {
        private final boolean allAttributesPrivate;
        private final Set<UserAttribute> privateAttributes;

        LDUserPrivateAttributesTypeAdapter(
                boolean allAttributesPrivate,
                Set<UserAttribute> privateAttributes
        ) {
            this.allAttributesPrivate = allAttributesPrivate;
            this.privateAttributes = privateAttributes;
        }

        private boolean isPrivate(LDUser user, UserAttribute attribute) {
            return allAttributesPrivate ||
                    privateAttributes.contains(attribute) ||
                    user.isAttributePrivate(attribute);
        }

        private void safeWrite(
            JsonWriter out, LDUser user,
            UserAttribute attrib,
            Set<String> attrs) throws IOException {

            LDValue value = user.getAttribute(attrib);

            // skip null attributes
            if (value.isNull()) {
                return;
            }

            if (isPrivate(user, attrib)) {
                attrs.add(attrib.getName());
            } else {
                out.name(attrib.getName()).value(value.stringValue());
            }
        }

        private void writeAttribs(JsonWriter out, LDUser user, Set<String> names) throws IOException {
            boolean started = false;

            for (UserAttribute entry : user.getCustomAttributes()) {
                if (isPrivate(user, entry)) {
                    names.add(entry.getName());
                } else {
                    if (!started) {
                        out.name("custom");
                        out.beginObject();
                        started = true;
                    }
                    out.name(entry.getName());
                    LDConfig.GSON.getAdapter(LDValue.class).write(out, user.getAttribute(entry));
                }
            }

            if (started) {
                out.endObject();
            }
        }

        private void writePrivateAttribs(JsonWriter out, Set<String> attrs) throws IOException {
            if (attrs.isEmpty()) 
                return;

            out.name("privateAttrs");
            out.beginArray();

            for (String name : attrs)
                out.value(name);

            out.endArray();
        }

        private static final UserAttribute[] OPTIONAL_BUILTINS = {
            UserAttribute.SECONDARY_KEY,
            UserAttribute.IP,
            UserAttribute.EMAIL,
            UserAttribute.NAME,
            UserAttribute.AVATAR,
            UserAttribute.FIRST_NAME,
            UserAttribute.LAST_NAME,
            UserAttribute.COUNTRY
        };

        @Override
        public void write(JsonWriter out, LDUser user) throws IOException {
            if (user == null) {
                out.nullValue();
                return;
            }

            Set<String> privateAttrs = new HashSet<>();

            out.beginObject();

            out.name("key").value(user.getKey());

            if (!user.getAttribute(UserAttribute.ANONYMOUS).isNull()) {
                out.name("anonymous").value(user.isAnonymous());
            }

            for (UserAttribute attrib : OPTIONAL_BUILTINS) {
                safeWrite(out, user, attrib, privateAttrs);
            }

            writeAttribs(out, user, privateAttrs);
            writePrivateAttribs(out, privateAttrs);

            out.endObject();
        }

        @Override
        public LDUser read(JsonReader in) throws IOException {
            return LDConfig.GSON.fromJson(in, LDUser.class);
        }
    }
}
