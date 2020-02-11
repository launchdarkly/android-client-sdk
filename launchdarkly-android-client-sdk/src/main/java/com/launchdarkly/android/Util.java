package com.launchdarkly.android;

import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Used internally by the SDK.
 */
@Deprecated
public class Util {
    // Android API v16 doesn't support Objects.equals()
    public static <T> boolean objectsEqual(@Nullable T a, @Nullable T b) {
        return a == b || (a != null && a.equals(b));
    }

    @NonNull
    public static <T> Map<String, T> sharedPrefsGetAllGson(SharedPreferences sharedPreferences, Class<T> typeOf) {
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

    public static <T> T sharedPrefsGetGson(SharedPreferences sharedPreferences, Class<T> typeOf, String key) {
        String data = sharedPreferences.getString(key, null);
        if (data == null) return null;
        try {
            return GsonCache.getGson().fromJson(data, typeOf);
        } catch (Exception ignored) {
            return null;
        }
    }

    public interface ResultCallback<T> {
        void onSuccess(T result);
        void onError(Throwable e);
    }
}
