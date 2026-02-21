package com.launchdarkly.sdk.android;

import androidx.annotation.VisibleForTesting;

import com.launchdarkly.logging.LDLogger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Dns;

/**
 * A DNS resolver that caches successful lookups and falls back to stale cache
 * entries when a fresh resolution fails. This is particularly useful on mobile
 * networks where DNS can be unreliable during network transitions.
 * <p>
 * On API 34+ Android provides native stale-DNS support via
 * {@code DnsOptions.StaleDnsOptions}, so this class is only used on older
 * platform versions. See {@code ComponentsImpl.StreamingDataSourceBuilderImpl}
 * for the conditional wiring.
 * <p>
 * Instances of this class are thread-safe and designed to be shared across
 * multiple OkHttpClient instances so that the cache persists even when the
 * HTTP client is recreated (e.g. on EventSource reconnections).
 */
final class CachingDns implements Dns {

    @VisibleForTesting
    static final long DEFAULT_TTL_MS = 10 * 60 * 1000; // 10 minutes

    private final Dns delegate;
    private final long ttlMs;
    private final LDLogger logger;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    static final class CacheEntry {
        final List<InetAddress> addresses;
        final long expiresAtMs;

        CacheEntry(List<InetAddress> addresses, long expiresAtMs) {
            this.addresses = addresses;
            this.expiresAtMs = expiresAtMs;
        }

        boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }

    CachingDns(Dns delegate, long ttlMs, LDLogger logger) {
        this.delegate = delegate;
        this.ttlMs = ttlMs;
        this.logger = logger;
    }

    CachingDns(LDLogger logger) {
        this(Dns.SYSTEM, DEFAULT_TTL_MS, logger);
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(hostname);

        if (entry != null && !entry.isExpired(now)) {
            return entry.addresses;
        }

        try {
            List<InetAddress> addresses = delegate.lookup(hostname);
            cache.put(hostname, new CacheEntry(addresses, now + ttlMs));
            return addresses;
        } catch (UnknownHostException e) {
            if (entry != null) {
                logger.warn(
                        "DNS lookup failed for {}, falling back to cached address (age {}ms)",
                        hostname, now - (entry.expiresAtMs - ttlMs)
                );
                return entry.addresses;
            }
            throw e;
        }
    }

    @VisibleForTesting
    CacheEntry getCacheEntry(String hostname) {
        return cache.get(hostname);
    }
}
