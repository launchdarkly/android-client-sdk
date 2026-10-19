package com.launchdarkly.sdk.android;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Random;

/**
 * Interprets the TTL of an FDv1 fallback directive: how long to stay off FDv2 before
 * attempting to recover to it.
 * <p>
 * Package-private for internal use by the FDv2 data sources.
 */
final class FDv1FallbackTtl {
    /** Used whenever the service supplies no usable TTL. */
    static final long DEFAULT_TTL_SECONDS = 60 * 60;

    private static final String GOODBYE_TTL_FIELD = "protocolFallbackTTL";

    private static final Random random = new Random();

    private FDv1FallbackTtl() {}

    /**
     * Returns the TTL to use for a service-supplied value, in seconds. A value in
     * (0, 1 hour] is used as given, because the service has already jittered it. Any other
     * value, including 0 for an absent or malformed TTL, uses the jittered default, so a
     * fallback is never indefinite.
     */
    static long resolveSeconds(long serviceTtlSeconds) {
        if (serviceTtlSeconds > 0 && serviceTtlSeconds <= DEFAULT_TTL_SECONDS) {
            return serviceTtlSeconds;
        }
        return defaultSeconds();
    }

    /**
     * Returns the default TTL minus a value drawn uniformly from [0, TTL/2], so that SDKs which
     * fell back within the same window do not all re-attempt FDv2 at once.
     */
    static long defaultSeconds() {
        long jitter;
        synchronized (random) {
            jitter = (long) (random.nextDouble() * (DEFAULT_TTL_SECONDS / 2 + 1));
        }
        return DEFAULT_TTL_SECONDS - jitter;
    }

    /**
     * Parses the value of an {@code x-ld-fd-fallback-ttl} header as a whole number of seconds.
     * Returns 0 if the header is absent or malformed.
     */
    static long parseHeader(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Reads the {@code protocolFallbackTTL} field of a goodbye event's data. Returns null if the
     * field is absent, meaning the goodbye carries no fallback directive. Returns 0 if the field
     * is present but not a whole number of seconds.
     */
    @Nullable
    static Long fromGoodbye(@Nullable JsonElement data) {
        if (data == null || !data.isJsonObject()) {
            return null;
        }
        JsonObject obj = data.getAsJsonObject();
        if (!obj.has(GOODBYE_TTL_FIELD)) {
            return null;
        }
        JsonElement field = obj.get(GOODBYE_TTL_FIELD);
        if (field == null || !field.isJsonPrimitive() || !((JsonPrimitive) field).isNumber()) {
            return 0L;
        }
        double seconds = field.getAsDouble();
        if (seconds != Math.rint(seconds) || Double.isInfinite(seconds)) {
            return 0L;
        }
        return (long) seconds;
    }
}
