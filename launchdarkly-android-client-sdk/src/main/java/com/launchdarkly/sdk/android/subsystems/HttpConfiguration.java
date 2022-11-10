package com.launchdarkly.sdk.android.subsystems;

import com.launchdarkly.sdk.android.LDHeaderUpdater;
import com.launchdarkly.sdk.android.integrations.HttpConfigurationBuilder;

import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.emptyMap;

/**
 * Encapsulates top-level HTTP configuration that applies to all SDK components.
 * <p>
 * Use {@link HttpConfigurationBuilder} to construct an instance.
 * <p>
 * The SDK's built-in components use OkHttp as the HTTP client implementation, but since OkHttp types
 * are not surfaced in the public API and custom components might use some other implementation, this
 * class only provides the properties that would be used to create an HTTP client; it does not create
 * the client itself. SDK implementation code uses its own helper methods to do so.
 *
 * @since 3.3.0
 */
public final class HttpConfiguration {
    private final int connectTimeoutMillis;
    private final Map<String, String> defaultHeaders;
    private final LDHeaderUpdater headerTransform;
    private final boolean useReport;

    /**
     * Creates an instance.
     *
     * @param connectTimeoutMillis see {@link #getConnectTimeoutMillis()}
     * @param defaultHeaders see {@link #getDefaultHeaders()}
     * @param headerTransform see {@link #getHeaderTransform()}
     * @param useReport see {@link #isUseReport()}
     */
    public HttpConfiguration(
            int connectTimeoutMillis,
            Map<String, String> defaultHeaders,
            LDHeaderUpdater headerTransform,
            boolean useReport
    ) {
        super();
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.defaultHeaders = defaultHeaders == null ? emptyMap() : new HashMap<>(defaultHeaders);
        this.headerTransform = headerTransform;
        this.useReport = useReport;
    }

    /**
     * The connection timeout. This is the time allowed for the underlying HTTP client to connect
     * to the LaunchDarkly server.
     *
     * @return the connection timeout in milliseconds
     */
    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    /**
     * Returns the basic headers that should be added to all HTTP requests from SDK components to
     * LaunchDarkly services, based on the current SDK configuration.
     *
     * @return a list of HTTP header names and values
     */
    public Iterable<Map.Entry<String, String>> getDefaultHeaders() {
        return defaultHeaders.entrySet();
    }

    /**
     * Returns the callback for modifying request headers, if any.
     *
     * @return the callback for modifying request headers
     */
    public LDHeaderUpdater getHeaderTransform() {
        return headerTransform;
    }

    /**
     * The setting for whether to use the HTTP REPORT method.
     *
     * @return true to use HTTP REPORT
     */
    public boolean isUseReport() {
        return useReport;
    }
}
