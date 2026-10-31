package com.launchdarkly.sdk.testapp;

import android.text.TextUtils;

import com.launchdarkly.sdk.android.LDPackageConsts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Posts one analytics payload of {@link #TARGET_BYTES} straight to the events service, with the headers the
 * SDK's sender uses, and reports what came back. Through its own connection rather than the SDK, so neither
 * the event store, the batch size limit nor the retry state is involved: one request, one answer.
 * <p>
 * The events are valid custom events, so a service that takes the payload records them.
 */
final class OversizedPostProbe {
    static final int TARGET_BYTES = 20 * 1024 * 1024;

    private static final String PATH = "/mobile/events/bulk";
    private static final int EVENT_DATA_CHARS = 900;
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    /** Long enough for 20 MB to go up over a slow connection before the answer. */
    private static final int READ_TIMEOUT_MILLIS = 180_000;
    private static final int RESPONSE_BODY_SHOWN = 2_000;
    private static final int WRITE_CHUNK_BYTES = 64 * 1024;

    private OversizedPostProbe() {
    }

    /** Blocks for the whole upload and the answer; never on the main thread. */
    static String run(String mobileKey, String eventsBaseUri) {
        String payloadId = UUID.randomUUID().toString();
        long creationDate = System.currentTimeMillis();
        byte[] event = ("{\"kind\":\"custom\",\"key\":\"oversized-post-probe\",\"creationDate\":" + creationDate
                + ",\"context\":{\"kind\":\"user\",\"key\":\"oversized-post-probe\"},\"data\":\""
                + repeat('x', EVENT_DATA_CHARS) + "\"}").getBytes(StandardCharsets.UTF_8);
        int eventCount = (TARGET_BYTES - 1) / (event.length + 1);
        long bodyLength = 2L + (long) eventCount * event.length + (eventCount - 1);

        StringBuilder report = new StringBuilder();
        String url = eventsBaseUri + PATH;
        report.append(String.format(Locale.US, "POST %s\n%d events, %,d bytes\nPayload ID %s\n\n",
                url, eventCount, bodyLength, payloadId));

        HttpURLConnection connection = null;
        long started = System.nanoTime();
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setFixedLengthStreamingMode((int) bodyLength);
            connection.setRequestProperty("Authorization", mobileKey);
            connection.setRequestProperty("User-Agent", LDPackageConsts.SDK_CLIENT_NAME + "/"
                    + com.launchdarkly.sdk.android.BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-LaunchDarkly-Event-Schema", "4");
            connection.setRequestProperty("X-LaunchDarkly-Payload-ID", payloadId);

            IOException uploadFailure = null;
            try (OutputStream out = connection.getOutputStream()) {
                writeBody(out, event, eventCount);
            } catch (IOException e) {
                // A service refusing the size may answer and close before taking the whole body; what it
                // answered can still be read.
                uploadFailure = e;
            }
            long uploadedNanos = System.nanoTime() - started;
            if (uploadFailure != null) {
                report.append(String.format(Locale.US, "Upload failed after %.1f s: %s\n",
                        uploadedNanos / 1e9, uploadFailure));
            } else {
                report.append(String.format(Locale.US, "Uploaded in %.1f s\n", uploadedNanos / 1e9));
            }

            int status = connection.getResponseCode();
            report.append(String.format(Locale.US, "Answered after %.1f s\n\nHTTP %d %s\n",
                    (System.nanoTime() - started) / 1e9, status,
                    connection.getResponseMessage() == null ? "" : connection.getResponseMessage()));
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() != null) {
                    report.append(header.getKey()).append(": ").append(TextUtils.join(", ", header.getValue()))
                            .append('\n');
                }
            }
            String responseBody = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (!responseBody.isEmpty()) {
                report.append('\n').append(responseBody).append('\n');
            }
        } catch (IOException | RuntimeException e) {
            report.append(String.format(Locale.US, "No answer after %.1f s: %s\n",
                    (System.nanoTime() - started) / 1e9, e));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return report.toString();
    }

    /**
     * Generated as it goes out, a chunk at a time: the platform's connection may buffer what it is given,
     * and a 20 MB array on top of that is more than a 48 MB heap holds.
     */
    private static void writeBody(OutputStream out, byte[] event, int eventCount) throws IOException {
        byte[] chunk = new byte[WRITE_CHUNK_BYTES];
        int at = 0;
        chunk[at++] = '[';
        for (int i = 0; i < eventCount; i++) {
            if (at + event.length + 2 > chunk.length) {
                out.write(chunk, 0, at);
                out.flush();
                at = 0;
            }
            if (i > 0) {
                chunk[at++] = ',';
            }
            System.arraycopy(event, 0, chunk, at, event.length);
            at += event.length;
        }
        chunk[at++] = ']';
        out.write(chunk, 0, at);
        out.flush();
    }

    private static String readBody(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream read = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while (read.size() < RESPONSE_BODY_SHOWN && (n = stream.read(chunk)) > 0) {
                read.write(chunk, 0, n);
            }
            String text = new String(read.toByteArray(), StandardCharsets.UTF_8);
            return text.length() > RESPONSE_BODY_SHOWN ? text.substring(0, RESPONSE_BODY_SHOWN) + "…" : text;
        }
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
