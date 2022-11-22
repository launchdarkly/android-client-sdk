package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.LDHeaderUpdater;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;

/**
 * Contains methods for configuring the SDK's networking behavior.
 * <p>
 * If you want to set non-default values for any of these properties, create a builder with
 * {@link Components#httpConfiguration()}, change its properties with the methods of this class,
 * and pass it to {@link com.launchdarkly.sdk.android.LDConfig.Builder#http(ComponentConfigurer)}:
 * <pre><code>
 *     LDConfig config = new LDConfig.Builder()
 *         .http(
 *           Components.httpConfiguration()
 *             .connectTimeoutMillis(3000)
 *             .proxyHostAndPort("my-proxy", 8080)
 *          )
 *         .build();
 * </code></pre>
 * <p>
 * Note that this class is abstract; the actual implementation is created by calling {@link Components#httpConfiguration()}.
 *
 * @since 3.3.0
 */
public abstract class HttpConfigurationBuilder implements ComponentConfigurer<HttpConfiguration> {
    /**
     * The default value for {@link #connectTimeoutMillis(int)}: ten seconds.
     */
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10000;

    protected int connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS;
    protected LDHeaderUpdater headerTransform;
    protected boolean useReport;
    protected String wrapperName;
    protected String wrapperVersion;

    /**
     * Sets the connection timeout. This is the time allowed for the SDK to make a socket connection to
     * any of the LaunchDarkly services.
     * <p>
     * The default is {@link #DEFAULT_CONNECT_TIMEOUT_MILLIS}.
     *
     * @param connectTimeoutMillis the connection timeout in milliseconds
     * @return the builder
     */
    public HttpConfigurationBuilder connectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis <= 0 ? DEFAULT_CONNECT_TIMEOUT_MILLIS :
                connectTimeoutMillis;
        return this;
    }

    /**
     * Provides a callback for dynamically modifying headers used on requests to LaunchDarkly services.
     *
     * @param headerTransform the transformation to apply to requests
     * @return the builder
     */
    public HttpConfigurationBuilder headerTransform(LDHeaderUpdater headerTransform) {
        this.headerTransform = headerTransform;
        return this;
    }

    /**
     * Sets whether to use the HTTP REPORT method for feature flag requests.
     * <p>
     * By default, polling and streaming connections are made with the GET method, with the context
     * data encoded into the request URI. Using REPORT allows the user data to be sent in the request
     * body instead, which is somewhat more secure and efficient.
     * <p>
     * However, the REPORT method is not always supported by operating systems or network gateways.
     * Therefore it is disabled in the SDK by default. You can enable it if you know your code will
     * not be running in an environment that disallows REPORT.
     *
     * @param useReport true to enable the REPORT method
     * @return the builder
     */
    public HttpConfigurationBuilder useReport(boolean useReport) {
        this.useReport = useReport;
        return this;
    }

    /**
     * For use by wrapper libraries to set an identifying name for the wrapper being used. This will be included in a
     * header during requests to the LaunchDarkly servers to allow recording metrics on the usage of
     * these wrapper libraries.
     *
     * @param wrapperName an identifying name for the wrapper library
     * @param wrapperVersion version string for the wrapper library
     * @return the builder
     */
    public HttpConfigurationBuilder wrapper(String wrapperName, String wrapperVersion) {
        this.wrapperName = wrapperName;
        this.wrapperVersion = wrapperVersion;
        return this;
    }
}
