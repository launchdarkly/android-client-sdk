package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;

import com.launchdarkly.logging.LDLogger;

import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Dns;

public class CachingDnsTest {

    private static final LDLogger logger = LDLogger.none();

    private static Dns counting(List<InetAddress> result, AtomicInteger counter) {
        return hostname -> {
            counter.incrementAndGet();
            return result;
        };
    }

    private static Dns failing() {
        return hostname -> {
            throw new UnknownHostException("simulated DNS failure for " + hostname);
        };
    }

    @Test
    public void returnsFreshResultFromDelegate() throws Exception {
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        List<InetAddress> expected = Collections.singletonList(addr);
        AtomicInteger lookups = new AtomicInteger();
        CachingDns dns = new CachingDns(counting(expected, lookups), 60_000, logger);

        List<InetAddress> result = dns.lookup("example.com");
        assertEquals(expected, result);
        assertEquals(1, lookups.get());
    }

    @Test
    public void returnsCachedResultWithinTtl() throws Exception {
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        List<InetAddress> expected = Collections.singletonList(addr);
        AtomicInteger lookups = new AtomicInteger();
        CachingDns dns = new CachingDns(counting(expected, lookups), 60_000, logger);

        dns.lookup("example.com");
        List<InetAddress> result = dns.lookup("example.com");

        assertEquals(expected, result);
        assertEquals(1, lookups.get());
    }

    @Test
    public void refreshesAfterTtlExpires() throws Exception {
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        List<InetAddress> expected = Collections.singletonList(addr);
        AtomicInteger lookups = new AtomicInteger();
        CachingDns dns = new CachingDns(counting(expected, lookups), 0, logger);

        dns.lookup("example.com");
        dns.lookup("example.com");

        assertEquals(2, lookups.get());
    }

    @Test
    public void fallsBackToStaleCacheOnFailure() throws Exception {
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        List<InetAddress> cached = Collections.singletonList(addr);

        AtomicInteger lookups = new AtomicInteger();
        Dns flaky = hostname -> {
            if (lookups.incrementAndGet() == 1) {
                return cached;
            }
            throw new UnknownHostException("simulated failure");
        };

        CachingDns dns = new CachingDns(flaky, 0, logger);
        dns.lookup("example.com");

        List<InetAddress> result = dns.lookup("example.com");
        assertEquals(cached, result);
    }

    @Test(expected = UnknownHostException.class)
    public void throwsWhenDelegateFailsAndNoCacheExists() throws Exception {
        CachingDns dns = new CachingDns(failing(), 60_000, logger);
        dns.lookup("no-such-host.invalid");
    }

    @Test
    public void cachesPerHostname() throws Exception {
        InetAddress addr1 = InetAddress.getByName("10.0.0.1");
        InetAddress addr2 = InetAddress.getByName("10.0.0.2");
        List<InetAddress> list1 = Collections.singletonList(addr1);
        List<InetAddress> list2 = Collections.singletonList(addr2);

        AtomicInteger lookups = new AtomicInteger();
        Dns delegate = hostname -> {
            lookups.incrementAndGet();
            return hostname.equals("a.example.com") ? list1 : list2;
        };
        CachingDns dns = new CachingDns(delegate, 60_000, logger);

        assertEquals(list1, dns.lookup("a.example.com"));
        assertEquals(list2, dns.lookup("b.example.com"));
        assertEquals(2, lookups.get());

        assertEquals(list1, dns.lookup("a.example.com"));
        assertEquals(list2, dns.lookup("b.example.com"));
        assertEquals(2, lookups.get());
    }

    @Test
    public void cacheEntryRecordsExpiration() {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        List<InetAddress> addrs = Collections.singletonList(loopback);

        long now = System.currentTimeMillis();
        CachingDns.CacheEntry entry = new CachingDns.CacheEntry(addrs, now + 5000);

        assertEquals(false, entry.isExpired(now));
        assertEquals(true, entry.isExpired(now + 5000));
        assertEquals(true, entry.isExpired(now + 6000));
    }
}
