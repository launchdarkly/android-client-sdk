package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;

import java.net.URI;

abstract class StandardEndpoints {
    private StandardEndpoints() {}

    static final URI DEFAULT_STREAMING_BASE_URI = URI.create("https://clientstream.launchdarkly.com");
    static final URI DEFAULT_POLLING_BASE_URI = URI.create("https://clientsdk.launchdarkly.com");
    static final URI DEFAULT_EVENTS_BASE_URI = URI.create("https://mobile.launchdarkly.com");

    static final String STREAMING_REQUEST_BASE_PATH = "/meval";
    static final String POLLING_REQUEST_GET_BASE_PATH = "/msdk/evalx/contexts";
    static final String POLLING_REQUEST_REPORT_BASE_PATH = "/msdk/evalx/context";
    static final String DIAGNOSTIC_EVENTS_REQUEST_PATH = "/mobile/events/diagnostic";

    // The client-facing analytics events POST path: the client-v5 mobile law
    // (`mockld/events_service.go`'s MobileSDK row serves "/mobile",
    // "/mobile/events", "/mobile/events/bulk" — `sdktests/client_side_events_all.go`'s
    // requestPathMatcher's mobile arm; upstream main's
    // StandardEndpoints.ANALYTICS_EVENTS_REQUEST_PATH constant prices the same
    // "/mobile/events/bulk" template, and the SDK's own androidTest law
    // LDClientEventTest asserts it). The former js-client route
    // `/events/bulk/{environmentId}` (a79fca3a) is never served by the
    // client-v5 mobile table — every analytics POST priced an unrecognized
    // path and every event expectation timed out; the mobile credential rides
    // the request's Authorization header, not the path.
    static String analyticsEventsRequestPath(String mobileKey) {
        return "/mobile/events/bulk";
    }

    static final String FDV2_POLLING_REQUEST_GET_BASE_PATH = "/sdk/poll/eval";
    static final String FDV2_POLLING_REQUEST_REPORT_BASE_PATH = "/sdk/poll/eval";
    static final String FDV2_STREAMING_REQUEST_BASE_PATH = "/sdk/stream/eval";

    /**
     * Internal method to decide which URI a given component should connect to.
     * <p>
     * Always returns some URI, falling back on the default if necessary, but logs a warning if we detect that the application
     * set some custom endpoints but not this one.
     *
     * @param serviceEndpointsValue the value set in ServiceEndpoints (this is either the default URI, a custom URI, or null)
     * @param defaultValue the constant default URI value defined in StandardEndpoints
     * @param description a human-readable string for the type of endpoint being selected, for logging purposes
     * @param logger the logger to which we should print the warning, if needed
     * @return the base URI we should connect to
     */
    static URI selectBaseUri(URI serviceEndpointsValue, URI defaultValue, String description, LDLogger logger) {
        if (serviceEndpointsValue != null) {
            return serviceEndpointsValue;
        }
        logger.warn("You have set custom ServiceEndpoints without specifying the {} base URI; connections may not work properly", description);
        return defaultValue;
    }

    /**
     * Internal method to determine whether a given base URI was set to a custom value or not.
     * <p>
     * This boolean value is only used for our diagnostic events. We only check if the value
     * differs from the default; if the base URI was "overridden" in configuration, but
     * happens to be equal to the default URI, we don't count that as custom
     * for the purposes of this diagnostic.
     *
     * @param serviceEndpointsValue the value set in ServiceEndpoints
     * @param defaultValue the constant default URI value defined in StandardEndpoints
     * @return true iff the base URI was customized
     */
    static boolean isCustomBaseUri(URI serviceEndpointsValue, URI defaultValue) {
        return serviceEndpointsValue != null && !serviceEndpointsValue.equals(defaultValue);
    }
}
