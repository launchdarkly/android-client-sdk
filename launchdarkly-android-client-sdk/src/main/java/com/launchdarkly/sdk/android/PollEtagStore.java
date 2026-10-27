package com.launchdarkly.sdk.android;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The poll driver's ETag store face (harness v3 tRun-site {@code "e-tag"} law:
 * each poll request's {@code If-None-Match} replays the tag the server served
 * for that same request face — the poll base plus the context slot — and a
 * later poll for a different context keeps its own slot). The seats the store
 * rides are keyed by the request URI the tag corresponds to, so the store is
 * neither hand-copied nor instance-bound: a relaunch (one storage scope) and
 * a context switch re-key the same stored tags, while a slotless face (a
 * fresh context) sends no header.
 *
 * Package-private seat-cell face; nothing public grows. Provisioned per
 * client via {@link ConfigHelper} (a scope-less client gets a fresh store;
 * clients of one storage scope share one store) — the default slot map
 * {@link LDUtil#pollEtags} serves ordinary clients.
 */
final class PollEtagStore {
    private final ConcurrentHashMap<String, String> etags = new ConcurrentHashMap<>();

    String get(String requestKey) {
        return etags.get(requestKey);
    }

    void put(String requestKey, String etag) {
        if (requestKey == null || etag == null || etag.isEmpty()) {
            return;
        }
        etags.put(requestKey, etag);
    }
}
