package com.launchdarkly.sdk.android.subsystems;

import com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder;
import java.net.URI;

/**
 * Specifies the base service URIs used by SDK components.
 * <p>
 * See {@link ServiceEndpointsBuilder} for more details on these properties.
 *
 * @since 3.3.0
 */
public final class ServiceEndpoints {
    private final URI streamingBaseUri;
    private final URI pollingBaseUri;
    private final URI eventsBaseUri;

    /**
     * Used internally by the SDK to store service endpoints.
     * @param streamingBaseUri the base URI for the streaming service
     * @param pollingBaseUri the base URI for the polling service
     * @param eventsBaseUri the base URI for the events service
     */
    public ServiceEndpoints(URI streamingBaseUri, URI pollingBaseUri, URI eventsBaseUri) {
        this.streamingBaseUri = streamingBaseUri;
        this.pollingBaseUri = pollingBaseUri;
        this.eventsBaseUri = eventsBaseUri;
    }

    /**
     * The base URI for the streaming service.
     * @return the base URI, or null
     */
    public URI getStreamingBaseUri() {
        return streamingBaseUri;
    }

    /**
     * The base URI for the polling service.
     * @return the base URI, or null
     */
    public URI getPollingBaseUri() {
        return pollingBaseUri;
    }

    /**
     * The base URI for the events service.
     * @return the base URI, or null
     */
    public URI getEventsBaseUri() {
        return eventsBaseUri;
    }
}
