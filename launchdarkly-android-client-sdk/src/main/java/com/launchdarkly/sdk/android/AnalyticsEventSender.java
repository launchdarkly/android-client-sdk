package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.internal.events.EventSender;
import com.launchdarkly.sdk.internal.http.HttpHelpers;
import com.launchdarkly.sdk.internal.http.HttpProperties;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.Date;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Posts a batch of analytics events under a payload ID the caller chooses.
 * <p>
 * This exists for the sake of one header. java-sdk-internal's sender invents a payload ID per call,
 * which is the right thing when the events being sent only ever existed in memory: the sole reason to
 * send the same events twice is its own immediate retry, and it holds the ID for that. Once events are
 * kept on disk, a delivery can also be repeated by a later run of the application, after a process died
 * without learning whether the service had received the batch. Those two attempts are the same delivery
 * and have to say so, or the events are counted twice - and counting them twice is worse than losing
 * them, because an inflated exposure count silently biases an experiment rather than visibly shrinking
 * it.
 * <p>
 * The ID belongs to the batch, which is why it is the batch's identity in {@link EventStore} rather than
 * something generated at send time.
 */
final class AnalyticsEventSender implements Closeable {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String EVENT_SCHEMA_HEADER = "X-LaunchDarkly-Event-Schema";
    private static final String EVENT_SCHEMA_VERSION = "4";
    private static final String PAYLOAD_ID_HEADER = "X-LaunchDarkly-Payload-ID";
    /** One immediate retry, as the other SDKs do, for a failure that may just be a bad moment. */
    private static final long RETRY_DELAY_MILLIS = 1000;

    private final OkHttpClient client;
    private final boolean ownsClient;
    private final Headers baseHeaders;
    private final LDLogger logger;

    AnalyticsEventSender(HttpProperties httpProperties, LDLogger logger) {
        this.logger = logger;
        if (httpProperties.getSharedHttpClient() == null) {
            this.client = httpProperties.toHttpClientBuilder().build();
            this.ownsClient = true;
        } else {
            this.client = httpProperties.getSharedHttpClient();
            this.ownsClient = false;
        }
        this.baseHeaders = httpProperties.toHeadersBuilder().build();
    }

    /**
     * Posts a batch, retrying once if the failure looks temporary.
     *
     * @param body the JSON request body
     * @param eventCount how many events the body holds, for logging
     * @param payloadId identifies this delivery, the same on every attempt at it
     * @param eventsBaseUri the events service
     * @return whether the events arrived, whether the SDK must stop, and the service's clock
     */
    EventSender.Result sendBatch(byte[] body, int eventCount, String payloadId, URI eventsBaseUri) {
        URI uri = HttpHelpers.concatenateUriPath(eventsBaseUri,
                StandardEndpoints.ANALYTICS_EVENTS_REQUEST_PATH);

        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new EventSender.Result(false, false, null);
                }
                logger.warn("Will retry posting {} event(s) after a failure", eventCount);
            }

            Request request = new Request.Builder()
                    .url(uri.toString())
                    .headers(baseHeaders)
                    .addHeader("Content-Type", "application/json")
                    .addHeader(EVENT_SCHEMA_HEADER, EVENT_SCHEMA_VERSION)
                    .addHeader(PAYLOAD_ID_HEADER, payloadId)
                    .post(RequestBody.create(body, JSON))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    logger.debug("Posted {} event(s)", eventCount);
                    return new EventSender.Result(true, false, parseDate(response));
                }
                logger.warn("Error posting events: HTTP status {}", response.code());
                if (!LDUtil.isHttpErrorRecoverable(response.code())) {
                    return new EventSender.Result(false, true, null);
                }
            } catch (IOException e) {
                logger.warn("Error posting events: {}", LogValues.exceptionSummary(e));
            }
        }
        return new EventSender.Result(false, false, null);
    }

    /**
     * The service's own clock, which the SDK trusts over the device's when deciding whether a debug
     * window has closed.
     */
    private Date parseDate(Response response) {
        try {
            return response.headers().getDate("Date");
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void close() {
        if (!ownsClient) {
            return;
        }
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
