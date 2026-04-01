package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.DataModel.Flag;

/**
 * Builds FDv2 polling response JSON from {@link Flag} instances for use
 * with {@code MockWebServer} or similar test HTTP servers.
 * <p>
 * The response wraps flags in the standard FDv2 event sequence:
 * {@code server-intent} (xfer-full) followed by one {@code put-object}
 * per flag (kind {@code flag-eval}), then {@code payload-transferred}.
 * <p>
 * This is a test-only artifact; it is not shipped in the SDK.
 */
public final class FDv2TestResponses {

    private FDv2TestResponses() {}

    /**
     * Builds a complete FDv2 polling response body containing the given flags.
     *
     * @param flags one or more flags to include as {@code put-object} events
     * @return JSON string suitable for {@code MockResponse.setBody(...)}
     */
    public static String pollResponseBody(Flag... flags) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"events\":[");

        sb.append("{\"event\":\"server-intent\",\"data\":{\"payloads\":[")
          .append("{\"id\":\"p1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"\"}")
          .append("]}}");

        for (Flag flag : flags) {
            sb.append(",{\"event\":\"put-object\",\"data\":{")
              .append("\"version\":").append(flag.getVersion()).append(",")
              .append("\"kind\":\"flag-eval\",")
              .append("\"key\":\"").append(flag.getKey()).append("\",")
              .append("\"object\":").append(flag.toJson())
              .append("}}");
        }

        sb.append(",{\"event\":\"payload-transferred\",\"data\":{")
          .append("\"state\":\"(p:p1:100)\",\"version\":100")
          .append("}}");

        sb.append("]}");
        return sb.toString();
    }
}
