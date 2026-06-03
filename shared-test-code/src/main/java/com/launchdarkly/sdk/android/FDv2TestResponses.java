package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.DataModel.Flag;

/**
 * Builds FDv2 polling response JSON from {@link Flag} instances for use
 * with {@code MockWebServer} or similar test HTTP servers (JSON polling bodies or SSE for
 * streaming).
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

    /**
     * Builds an FDv2 polling response for a partial transfer ({@code xfer-changes}): one
     * {@code put-object} with kind {@code flag-eval}, then {@code payload-transferred}.
     * Use this to exercise partial (non-full) changeset paths—for example when the envelope
     * carries {@code version} while the inner {@code object} omits {@code version}, as in
     * streamed flag-eval updates.
     *
     * @param flagKey          flag key on the {@code put-object}
     * @param envelopeVersion  top-level {@code version} on the {@code put-object} envelope
     * @param objectJson       raw JSON object for the {@code object} field (valid JSON object text)
     * @return JSON string suitable for {@code MockResponse.setBody(...)}
     */
    public static String pollResponseBodyXferChangesFlagEval(
            String flagKey,
            int envelopeVersion,
            String objectJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"events\":[");
        sb.append("{\"event\":\"server-intent\",\"data\":{\"payloads\":[")
                .append("{\"id\":\"p1\",\"target\":101,\"intentCode\":\"xfer-changes\",\"reason\":\"\"}")
                .append("]}}");
        sb.append(",{\"event\":\"put-object\",\"data\":{")
                .append("\"version\":").append(envelopeVersion).append(",")
                .append("\"kind\":\"flag-eval\",")
                .append("\"key\":\"").append(flagKey).append("\",")
                .append("\"object\":").append(objectJson)
                .append("}}");
        sb.append(",{\"event\":\"payload-transferred\",\"data\":{")
                .append("\"state\":\"(p:p1:101)\",\"version\":101")
                .append("}}");
        sb.append("]}");
        return sb.toString();
    }

    /**
     * One Server-Sent Event line block for FDv2 ({@code event:} + {@code data:} + blank line).
     */
    public static String fdv2SseEvent(String eventName, String jsonData) {
        return "event: " + eventName + "\ndata: " + jsonData + "\n\n";
    }

    /**
     * SSE body for the first stream connection only: full transfer ({@code xfer-full}), one
     * {@code put-object}, {@code payload-transferred}. Use with a second connection that serves
     * {@link #streamingSseBodyXferChangesPartialOnly} so the test can gate the partial response.
     */
    public static String streamingSseBodyXferFullOnly(Flag initialFlag, String flagKey) {
        String putObjectFull =
                "{\"version\":"
                        + initialFlag.getVersion()
                        + ",\"kind\":\"flag-eval\",\"key\":\""
                        + flagKey
                        + "\",\"object\":"
                        + initialFlag.toJson()
                        + "}";
        StringBuilder sb = new StringBuilder();
        sb.append(
                fdv2SseEvent(
                        "server-intent",
                        "{\"payloads\":[{\"id\":\"p1\",\"target\":100,\"intentCode\":\"xfer-full\",\"reason\":\"\"}]}"));
        sb.append(fdv2SseEvent("put-object", putObjectFull));
        sb.append(
                fdv2SseEvent("payload-transferred", "{\"state\":\"(p:p1:100)\",\"version\":100}"));
        return sb.toString();
    }

    /**
     * SSE body for a follow-up stream connection: partial {@code xfer-changes} with a
     * {@code flag-eval} put (inner object may omit {@code version} depending on
     * {@code partialObjectJson}).
     */
    public static String streamingSseBodyXferChangesPartialOnly(
            String flagKey, int partialEnvelopeVersion, String partialObjectJson) {
        String putObjectPartial =
                "{\"version\":"
                        + partialEnvelopeVersion
                        + ",\"kind\":\"flag-eval\",\"key\":\""
                        + flagKey
                        + "\",\"object\":"
                        + partialObjectJson
                        + "}";
        StringBuilder sb = new StringBuilder();
        sb.append(
                fdv2SseEvent(
                        "server-intent",
                        "{\"payloads\":[{\"id\":\"p1\",\"target\":101,\"intentCode\":\"xfer-changes\",\"reason\":\"\"}]}"));
        sb.append(fdv2SseEvent("put-object", putObjectPartial));
        sb.append(
                fdv2SseEvent("payload-transferred", "{\"state\":\"(p:p1:101)\",\"version\":101}"));
        return sb.toString();
    }

    /**
     * SSE body for a single long-lived stream: full transfer ({@code xfer-full}) then partial
     * ({@code xfer-changes}) with a {@code flag-eval} put whose inner object omits {@code version}
     * when {@code partialObjectJson} is built that way.
     */
    public static String streamingSseBodyFullXferThenXferChangesPartial(
            Flag initialFlag,
            String flagKey,
            int partialEnvelopeVersion,
            String partialObjectJson) {
        return streamingSseBodyXferFullOnly(initialFlag, flagKey)
                + streamingSseBodyXferChangesPartialOnly(
                        flagKey, partialEnvelopeVersion, partialObjectJson);
    }
}
