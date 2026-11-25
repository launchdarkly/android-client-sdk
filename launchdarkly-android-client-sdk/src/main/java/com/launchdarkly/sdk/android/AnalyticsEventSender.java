package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.internal.http.HttpHelpers;
import com.launchdarkly.sdk.internal.http.HttpProperties;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
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

    private final OkHttpClient client;
    private final boolean ownsClient;
    private final Headers baseHeaders;
    private final boolean compress;
    private final LDLogger logger;

    /**
     * @param compress whether to send bodies gzip-encoded; the batch on disk stays plain JSON, and each
     *                 attempt compresses it again
     */
    AnalyticsEventSender(HttpProperties httpProperties, boolean compress, LDLogger logger) {
        this.compress = compress;
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

    /** What one post came to. */
    static final class Result {
        /** Whether the service took the events. */
        final boolean success;
        /**
         * For a failure, whether it is unlikely to pass soon: a 4xx other than a refusal of the payload
         * ({@link LDUtil#isEventPayloadRefused}), 408 and 429. This only decides how long the next post
         * waits.
         */
        final boolean unexpected;
        /** The HTTP status, or 0 where no response arrived. */
        final int status;
        /** The wait a failed response asked for with {@code Retry-After}, or -1 where it asked for none. */
        final long retryAfterMillis;
        /** The service's clock, from an accepted response's {@code Date}, or null. */
        final Date serverTime;

        Result(boolean success, boolean unexpected, int status, long retryAfterMillis, Date serverTime) {
            this.success = success;
            this.unexpected = unexpected;
            this.status = status;
            this.retryAfterMillis = retryAfterMillis;
            this.serverTime = serverTime;
        }
    }

    /**
     * Posts a batch once. The retry is the caller's, so that the wait before it does not hold a thread.
     *
     * @param payload the JSON request body, sent from the array it is in rather than copied out of it
     * @param eventCount how many events the body holds, for logging
     * @param payloadId identifies this delivery, the same on every attempt at it
     * @param eventsBaseUri the events service
     * @return whether the events arrived and, where they did not, how long the next attempt should wait
     */
    Result sendBatch(EventLogFormat.Payload payload, int eventCount, String payloadId, URI eventsBaseUri) {
        URI uri = HttpHelpers.concatenateUriPath(eventsBaseUri,
                StandardEndpoints.ANALYTICS_EVENTS_REQUEST_PATH);
        Request.Builder builder = new Request.Builder()
                .url(uri.toString())
                .headers(baseHeaders)
                .addHeader("Content-Type", "application/json")
                .addHeader(EVENT_SCHEMA_HEADER, EVENT_SCHEMA_VERSION)
                .addHeader(PAYLOAD_ID_HEADER, payloadId);
        byte[] gzipped = compress ? gzip(payload) : null;
        if (gzipped != null) {
            builder.addHeader("Content-Encoding", "gzip")
                    .post(RequestBody.create(gzipped, JSON));
        } else {
            builder.post(RequestBody.create(payload.bytes, JSON, 0, payload.length));
        }
        Request request = builder.build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                logger.debug("Posted {} event(s)", eventCount);
                return new Result(true, false, response.code(), -1, parseDate(response));
            }
            logger.warn("Error posting events: HTTP status {}", response.code());
            // A refusal of the payload waits like a 400: the caller drops the batch after a second in a row,
            // and the batches behind it are not to be held back an extended wait meanwhile.
            boolean unexpected = LDUtil.isHttpErrorUnexpected(response.code())
                    && !LDUtil.isEventPayloadRefused(response.code());
            return new Result(false, unexpected, response.code(), retryAfterMillis(response), null);
        } catch (IOException e) {
            logger.warn("Error posting events: {}", LogValues.exceptionSummary(e));
            // No response, TLS and certificate failures included, takes the normal backoff.
            return new Result(false, false, 0, -1, null);
        }
    }

    /** The body gzip-encoded, or null where encoding failed and it goes as it is. */
    private byte[] gzip(EventLogFormat.Payload payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, payload.length / 4));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(payload.bytes, 0, payload.length);
        } catch (IOException e) {
            logger.warn("Could not compress events, sending them uncompressed: {}", LogValues.exceptionSummary(e));
            return null;
        }
        return out.toByteArray();
    }

    /** {@code Retry-After} as seconds or as an HTTP date, or -1 where it is absent or unreadable. */
    private static long retryAfterMillis(Response response) {
        String value = response.header("Retry-After");
        if (value == null) {
            return -1;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? -1 : TimeUnit.SECONDS.toMillis(seconds);
        } catch (NumberFormatException e) {
            // An HTTP date, then.
        }
        Date date;
        try {
            date = response.headers().getDate("Retry-After");
        } catch (RuntimeException e) {
            return -1;
        }
        return date == null ? -1 : Math.max(0, date.getTime() - System.currentTimeMillis());
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
