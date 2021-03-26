package com.launchdarkly.android;

import java.util.Map;

/**
 * An interface to provide the SDK with a function used to modify HTTP headers before each request
 * to the LaunchDarkly service.
 */
public interface LDHeaderUpdater {
    /**
     * An application provided method for dynamic configuration of HTTP headers.
     *
     * @param headers The unmodified headers the SDK prepared for the request
     */
    void updateHeaders(Map<String, String> headers);
}
