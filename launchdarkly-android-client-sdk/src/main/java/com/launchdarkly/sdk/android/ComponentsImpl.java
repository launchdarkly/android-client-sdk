package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder;
import com.launchdarkly.sdk.android.interfaces.ServiceEndpoints;

class ComponentsImpl {
    static final class ServiceEndpointsBuilderImpl extends ServiceEndpointsBuilder {
        @Override
        public ServiceEndpoints createServiceEndpoints() {
            // If *any* custom URIs have been set, then we do not want to use default values for any that were not set,
            // so we will leave those null. That way, if we decide later on (in other component factories, such as
            // EventProcessorBuilder) that we are actually interested in one of these values, and we
            // see that it is null, we can assume that there was a configuration mistake and log an
            // error.
            if (streamingBaseUri == null && pollingBaseUri == null && eventsBaseUri == null) {
                return new ServiceEndpoints(
                        StandardEndpoints.DEFAULT_STREAMING_BASE_URI,
                        StandardEndpoints.DEFAULT_POLLING_BASE_URI,
                        StandardEndpoints.DEFAULT_EVENTS_BASE_URI
                );
            }
            return new ServiceEndpoints(streamingBaseUri, pollingBaseUri, eventsBaseUri);
        }
    }

}
