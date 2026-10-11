package com.launchdarkly.seat;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The CP/stdio callback wheel: service→runner `callback` envelopes answered by the
 * runner on stdin (CP-SPEC §7). The SDK's hook machinery POSTs to the configured
 * callback URI; with a `cp-callback://<channel>/<token>` URI (CP-SPEC 7.2) the seat
 * MUST NOT dial anything — it emits the callback envelope and awaits the runner's
 * response echo before completing the SDK-side call.
 *
 * Single main-thread writer: all envelope writes funnel through
 * {@link SeatServe.Bus}'s stdout writes; reply envelopes arrive on stdin and are
 * routed here by the seat's main loop via {@link #onReply}.
 */
final class CpCallbackBus {
  /** Scheme of the CP/stdio callback URIs (CP-SPEC 7.2). */
  static final String SCHEME = "cp-callback";
  /** How long the dispatcher waits for the runner's callback reply (CP op budget). */
  static final long REPLY_TIMEOUT_MS = 30_000;

  private final SeatServe.Bus bus;
  private final AtomicInteger nextId = new AtomicInteger();
  private final ConcurrentHashMap<Integer, CompletableFuture<Integer>> awaits =
      new ConcurrentHashMap<>();

  CpCallbackBus(SeatServe.Bus bus) {
    this.bus = bus;
  }

  boolean owns(URI uri) {
    return uri != null && SCHEME.equals(uri.getScheme());
  }

  /**
   * Emits a service→runner callback and blocks for the runner's reply envelope.
   *
   * @param channel the callback channel (hook, flagChangeListener, ...)
   * @param uri     the configured callback URI, verbatim
   * @param path    the appended path, or null
   * @param payload the harness callback body (already JSON-shaped)
   * @return true when the runner answered 2xx
   * @throws IllegalStateException on timeout or session failure
   */
  boolean post(String channel, URI uri, String path, JsonElement payload) {
    CompletableFuture<Integer> reply = new CompletableFuture<>();
    int id = nextId.incrementAndGet();
    awaits.put(id, reply);
    try {
      bus.callback(id, channel, uri == null ? null : uri.toString(), path, payload);
      Integer status = reply.get(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      if (status == null) {
        throw new IllegalStateException("cp-callback " + channel + ": null status");
      }
      return status >= 200 && status <= 299;
    } catch (Exception e) {
      Throwable t = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null
          ? e.getCause() : e;
      throw new IllegalStateException("cp-callback " + channel + " failed: "
          + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()), t);
    } finally {
      awaits.remove(id);
    }
  }

  /**
   * Routed by the main pump thread for every envelope it reads that is not a
   * well-formed request envelope: the runner's reply to one of my callback
   * envelopes ({@code op} "callback" with {@code ok/status}). Returns true when
   * the envelope consumed a pending callback id.
   */
  boolean onReply(JsonObject env) {
    String op = SeatServe.str(env, "op");
    if (!"callback".equals(op)) {
      return false;
    }
    JsonElement idEl = env.get("id");
    if (idEl == null || idEl.isJsonNull()) {
      System.err.println("[cp-seat] callback reply has no id");
      return false;
    }
    int id;
    try {
      id = idEl.getAsInt();
    } catch (Exception e) {
      System.err.println("[cp-seat] callback reply id unparsable");
      return false;
    }
    CompletableFuture<Integer> reply = awaits.get(id);
    if (reply == null) {
      System.err.println("[cp-seat] callback reply id=" + id + " matched no pending callback (pending: " + awaits.keySet() + ")");
      return false;
    }
    int status = 200;
    JsonElement statusEl = env.get("status");
    if (statusEl != null && !statusEl.isJsonNull()) {
      try {
        status = statusEl.getAsInt();
      } catch (Exception ignored) {
      }
    }
    boolean ok = Boolean.TRUE.equals(SeatServe.asBoolean(env.get("ok")));
    reply.complete(ok ? status : (status < 200 || status > 299 ? status : 500));
    return true;
  }
}
