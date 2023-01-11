package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.LDUtil;
import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;

import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import static com.launchdarkly.sdk.android.integrations.HttpConfigurationBuilder.DEFAULT_CONNECT_TIMEOUT_MILLIS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class HttpConfigurationBuilderTest {
    private static final String MOBILE_KEY = "mobile-key";
    private static final ClientContext BASIC_CONTEXT = new ClientContext(null, null, null, null,
            "", false, null, false, MOBILE_KEY, null);

    private static Map<String, String> buildBasicHeaders() {
        Map<String, String> ret = new HashMap<>();
        ret.put("Authorization", LDUtil.AUTH_SCHEME + MOBILE_KEY);
        ret.put("User-Agent", LDUtil.USER_AGENT_HEADER_VALUE);
        return ret;
    }

    private static <K, V> Map<K, V> toMap(Iterable<Map.Entry<K, V>> entries) {
        Map<K, V> ret = new HashMap<>();
        for (Map.Entry<K, V> e: entries) {
            ret.put(e.getKey(), e.getValue());
        }
        return ret;
    }

    @Test
    public void testDefaults() {
        HttpConfiguration hc = Components.httpConfiguration().build(BASIC_CONTEXT);
        assertEquals(DEFAULT_CONNECT_TIMEOUT_MILLIS, hc.getConnectTimeoutMillis());
        assertEquals(buildBasicHeaders(), toMap(hc.getDefaultHeaders()));
    }

    @Test
    public void testConnectTimeout() {
        HttpConfiguration hc = Components.httpConfiguration()
                .connectTimeoutMillis(999)
                .build(BASIC_CONTEXT);
        assertEquals(999, hc.getConnectTimeoutMillis());
    }

    @Test
    public void testWrapperNameOnly() {
        HttpConfiguration hc = Components.httpConfiguration()
                .wrapper("Scala", null)
                .build(BASIC_CONTEXT);
        assertEquals("Scala", toMap(hc.getDefaultHeaders()).get("X-LaunchDarkly-Wrapper"));
    }

    @Test
    public void testWrapperWithVersion() {
        HttpConfiguration hc = Components.httpConfiguration()
                .wrapper("Scala", "0.1.0")
                .build(BASIC_CONTEXT);
        assertEquals("Scala/0.1.0", toMap(hc.getDefaultHeaders()).get("X-LaunchDarkly-Wrapper"));
    }

    @Test
    public void testApplicationTags() {
        ApplicationInfo info = new ApplicationInfo("authentication-service", "1.0.0");
        ClientContext contextWithTags = new ClientContext(null, info, null, null,
                "", false, null, false, MOBILE_KEY, null);
        HttpConfiguration hc = Components.httpConfiguration()
                .build(contextWithTags);
        assertEquals("application-id/authentication-service application-version/1.0.0",
                toMap(hc.getDefaultHeaders()).get("X-LaunchDarkly-Tags"));
    }
}
