package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Random;

/**
 * The TTL of a server-directed FDv1 fallback: how long the SDK stays off FDv2 before attempting
 * to recover to it. A fallback is never indefinite.
 */
public final class FDv1FallbackTTL {
    /** The TTL used whenever the service supplies no usable one, before jitter. */
    static final long DEFAULT_TTL_SECONDS = 60 * 60;

    private static final String GOODBYE_TTL_FIELD = "protocolFallbackTTL";

    private static final Random random = new Random();

    private final long seconds;

    private FDv1FallbackTTL(long seconds) {
        this.seconds = seconds;
    }

    /**
     * A service-supplied TTL in (0, 1 hour] is used as given, because the service has already
     * jittered it. Any other value uses {@link #defaultTTL()}.
     */
    @NonNull
    private static FDv1FallbackTTL fromServiceTTL(long serviceTtlSeconds) {
        if (serviceTtlSeconds > 0 && serviceTtlSeconds <= DEFAULT_TTL_SECONDS) {
            return new FDv1FallbackTTL(serviceTtlSeconds);
        }
        return defaultTTL();
    }

    /**
     * Returns the default TTL of 1 hour minus a value drawn uniformly from [0, 30 minutes], so
     * that SDKs which fell back within the same window do not all re-attempt FDv2 at once.
     *
     * @return the TTL
     */
    @NonNull
    public static FDv1FallbackTTL defaultTTL() {
        long jitter;
        synchronized (random) {
            jitter = (long) (random.nextDouble() * (DEFAULT_TTL_SECONDS / 2 + 1));
        }
        return new FDv1FallbackTTL(DEFAULT_TTL_SECONDS - jitter);
    }

    /**
     * Returns the TTL for the value of an {@code x-ld-fd-fallback-ttl} header, which is a whole
     * number of seconds. An absent or malformed header gives the default TTL.
     */
    @NonNull
    static FDv1FallbackTTL fromHeader(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return defaultTTL();
        }
        try {
            return fromServiceTTL(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return defaultTTL();
        }
    }

    /**
     * Returns the TTL in the {@code protocolFallbackTTL} field of a goodbye event's data, or null
     * if the field is absent, meaning the goodbye carries no fallback directive. A field that is
     * not a whole number of seconds gives the default TTL.
     */
    @Nullable
    static FDv1FallbackTTL fromGoodbye(@Nullable JsonElement data) {
        if (data == null || !data.isJsonObject()) {
            return null;
        }
        JsonObject obj = data.getAsJsonObject();
        if (!obj.has(GOODBYE_TTL_FIELD)) {
            return null;
        }
        JsonElement field = obj.get(GOODBYE_TTL_FIELD);
        if (field == null || !field.isJsonPrimitive() || !((JsonPrimitive) field).isNumber()) {
            return defaultTTL();
        }
        double seconds = field.getAsDouble();
        if (seconds != Math.rint(seconds) || Double.isInfinite(seconds)) {
            return defaultTTL();
        }
        return fromServiceTTL((long) seconds);
    }

    /**
     * @return the TTL in seconds; always in (0, 1 hour]
     */
    public long getSeconds() {
        return seconds;
    }
}
