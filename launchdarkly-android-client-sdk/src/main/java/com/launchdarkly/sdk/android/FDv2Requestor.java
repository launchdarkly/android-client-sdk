package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Interface for making FDv2 polling requests.
 * <p>
 * An implementation fetches a FDv2 payload from the LaunchDarkly server and returns a
 * {@link FDv2PayloadResponse} containing the list of raw {@link FDv2Event}s. The caller is
 * responsible for processing the events through {@link com.launchdarkly.sdk.internal.fdv2.sources.FDv2ProtocolHandler}.
 */
interface FDv2Requestor extends Closeable {

    /**
     * Encapsulates the response from an FDv2 polling request.
     */
    final class FDv2PayloadResponse {
        @Nullable
        private final List<FDv2Event> events;
        private final boolean successful;
        private final int statusCode;

        private FDv2PayloadResponse(
                @Nullable List<FDv2Event> events,
                boolean successful,
                int statusCode) {
            this.events = events;
            this.successful = successful;
            this.statusCode = statusCode;
        }

        /** Creates a successful response with parsed events. */
        static FDv2PayloadResponse success(@NonNull List<FDv2Event> events, int statusCode) {
            return new FDv2PayloadResponse(events, true, statusCode);
        }

        /**
         * Creates a successful 304 Not Modified response indicating no change since the
         * last request.
         */
        static FDv2PayloadResponse notModified() {
            return new FDv2PayloadResponse(null, true, 304);
        }

        /** Creates an unsuccessful response with the HTTP status code. */
        static FDv2PayloadResponse failure(int statusCode) {
            return new FDv2PayloadResponse(null, false, statusCode);
        }

        /** The parsed FDv2 events; null for 304 or unsuccessful responses. */
        @Nullable
        public List<FDv2Event> getEvents() {
            return events;
        }

        /** True if the request succeeded (including 304 Not Modified). */
        public boolean isSuccess() {
            return successful;
        }

        /** The HTTP status code, e.g. 200, 304, 401, 500. */
        public int getStatusCode() {
            return statusCode;
        }
    }

    /**
     * Issues an FDv2 polling request.
     * <p>
     * If {@code selector} is non-empty, its state is sent as the {@code basis} query parameter
     * so the server can return an incremental changeset rather than a full payload. If the
     * request returns 304, the returned response has {@link FDv2PayloadResponse#isSuccess()}
     * true and {@link FDv2PayloadResponse#getStatusCode()} == 304; no events are present.
     *
     * @param selector the current selector, used as the {@code basis} query parameter;
     *                 use {@link Selector#EMPTY} if none
     * @return a Future that completes with the response or with an exception on network failure
     */
    @NonNull
    Future<FDv2PayloadResponse> poll(@NonNull Selector selector);
}
