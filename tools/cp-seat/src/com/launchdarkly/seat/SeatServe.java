package com.launchdarkly.seat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.LDAndroidLogging;
import com.launchdarkly.sdk.json.LDGson;

import com.launchdarkly.sdktest.Representations;
import com.launchdarkly.sdktest.SdkClientEntity;

import android.app.Application;

import java.net.URI;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CENTINEL CP/stdio test service ("seat") for the LaunchDarkly Android client SDK.
 *
 * Speaks the CP envelope protocol (verifier/conformance/protocol/CP-SPEC.md) over
 * stdin/stdout; the createClient/command bodies are the harness v3 service bodies
 * verbatim, translated by the SDK's own contract-test layer
 * (com.launchdarkly.sdktest.SdkClientEntity, as the official harness does).
 */
public final class SeatServe {
  static final String[] CAPABILITIES = {
      "client-side",
      "mobile",
      "service-endpoints",
      "singleton",
      "strongly-typed",
      "tags",
      "auto-env-attributes",
      "inline-context-all",
      "anonymous-redaction",
      "client-prereq-events",
      "client-prereq-cycle-detection",
      "polling-gzip",
      "client-per-context-summaries",
      "client-event-source-http-errors",
      "evaluation-hooks",
      "fdv1-fallback",
      "track-hooks",
      "flag-change-listeners",
      "wrapper",
      "x-cp-storage-scope",
      "inline-context"
  };

  static final Gson gson = new GsonBuilder()
      .registerTypeAdapterFactory(LDGson.typeAdapters())
      // The evaluated-value tree carries explicit LDValue nulls (e.g. {"b":{"c":null}});
      // default Gson would strip them, folding them into "<absent>". serializeNulls keeps
      // the SDK's value shape verbatim (the runner treats explicit nulls as omitted).
      .serializeNulls()
      // No EvaluationReason re-encode: the seat's response arm serves the SDK's own
      // serialization verbatim — the plain reflective Gson off the public java-core's
      // field names (inExperiment / prerequisiteKey), which is exactly what the
      // unmodified upstream android contract-test arm emits (SdkClientEntity resp.reason
      // through this same reflective path). The bank rows asserting the wire spellings
      // ride the campaign's contested register (R6-EVAL-UNCHANGED evidence fork).
      .create();

  private final ConcurrentHashMap<String, SdkClientEntity> clients = new ConcurrentHashMap<>();
  private final AtomicInteger clientCounter = new AtomicInteger(0);
  private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "seat-command-worker");
    t.setDaemon(true);
    return t;
  });
  // The CP/stdio callback wheel (CP-SPEC §7): service→runner callback envelopes
  // answered on stdin. Installed at construction; the hook machinery reaches it
  // through the static {@link #postCallback} hook the harness adapter calls.
  private static volatile Bus currentBus;
  private static volatile CpCallbackBus callbackBus;
  // stdout (CP) is written exclusively through this stream; everything else logs to stderr.
  private final PrintStream out = new PrintStream(
      new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);

  private SeatServe() {}

  /**
   * Main-thread seat loop (Robolectric paused looper owner). Same CP semantics as
   * {@link #serve}: poll stdin, dispatch each envelope on the worker, and run the
   * caller's looper pump between reads so SDK main-thread tasks make progress.
   */
  public static void servePumping(Application app, Runnable pump) {
    System.err.println("[cp-seat] android-client-sdk seat starting (pumping loop)");
    try {
      new SeatServe().runPumping(app, pump);
    } catch (Throwable t) {
      System.err.println("[cp-seat] fatal: " + t);
      t.printStackTrace();
      System.exit(1);
    }
  }

  /** @deprecated single-thread serve() stalls the shadow main looper; prefer {@link #servePumping}. */
  @Deprecated
  public static void serve(Application app) {
    servePumping(app, () -> {});
  }

  void runPumping(Application app, Runnable pump) throws IOException {
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    Bus bus = new Bus(out);
    currentBus = bus;
    if (callbackBus == null) {
      callbackBus = new CpCallbackBus(bus);
    }
    try {
      runPumpingLoop(app, in, bus, pump);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      System.err.println("[cp-seat] pump interrupted; stopping");
      stopAll();
      System.exit(0);
    }
  }

  void runPumpingLoop(Application app, BufferedReader in, Bus bus, Runnable pump)
      throws IOException, InterruptedException {
    while (true) {
      if (in.ready()) {
        String line = in.readLine();
        if (line != null && line.contains("\"op\":\"callback\"")) System.err.println("[cp-seat] seat read reply line: " + line.substring(0, Math.min(60, line.length())));
        if (line == null) {
          // stdin closed == stop (CP-SPEC 4.5)
          System.err.println("[cp-seat] stdin closed; stopping");
          worker.submit(() -> { stopAll(); worker.shutdown(); });
          return;
        }
        handleLine(bus, app, line);
      }
      pump.run();
      // 1 ms pacing keeps the poll cheap; CP op timeouts (>=10 s) are never at risk
      Thread.sleep(1);
    }
  }

  void handleLine(Bus bus, Application app, String line) {
    JsonObject env;
    try {
      env = gson.fromJson(line, JsonObject.class);
    } catch (Exception e) {
      System.err.println("[cp-seat] unparseable envelope, aborting session: " + e);
      System.exit(1);
      return;
    }
    String op = str(env, "op");
    JsonElement idEl = env.has("id") ? env.get("id") : JsonNull.INSTANCE;
    JsonElement bodyEl = env.has("body") ? env.get("body") : JsonNull.INSTANCE;
    // The runner's reply to one of MY callback envelopes (CP-SPEC 7.2): echo id,
    // carries ok/status — consumed by the awaiting SDK-side hook call on the worker.
    if ("callback".equals(op)) {
      if (callbackBus == null || !callbackBus.onReply(env)) {
        System.err.println("[cp-seat] callback reply matched no pending callback: id="
            + (idEl.isJsonNull() ? "null" : idEl));
      }
      return;
    }
    if ("createClient".equals(op)) {
      dispatchCreateClient(bus, app, idEl, bodyEl);
    } else if ("command".equals(op)) {
      dispatchCommand(bus, idEl, bodyEl);
    } else if ("closeClient".equals(op)) {
      dispatchCloseClient(bus, idEl, bodyEl);
    } else if ("status".equals(op)) {
      bus.ok(idEl, op, statusResultBody());
    } else if ("stop".equals(op)) {
      bus.ok(idEl, op, JsonNull.INSTANCE);
      stopAll();
      System.exit(0);
    } else {
      bus.err(idEl, op == null ? "null" : op, 400, "unknown op");
    }
  }

  /**
   * Posts a flag-change listener notification (service_spec L745, payload
   * {listenerId, flagKey} only — no old/new value for the general listener).
   * The SDK's main thread never blocks on the callback round-trip: the
   * envelope leaves the seat's own callback poster thread (a notification
   * completes no SDK-side call, so CP-SPEC 7.2's completion rule does not
   * gate the poster).
   */
  public static void postListenerNotification(java.net.URI callbackUri,
      String listenerId, String flagKey) {
    CpCallbackBus cb = callbackBus;
    if (cb == null) {
      throw new IllegalStateException("cp-callback requested before the seat bus was armed");
    }
    JsonObject payload = new JsonObject();
    payload.addProperty("listenerId", listenerId);
    payload.addProperty("flagKey", flagKey);
    CALLBACK_POSTS.submit(() -> cb.post(callbackUri.getHost(), callbackUri, null, payload));
  }

  /** The notification poster's own single-thread executor (daemon; posts are
   * fire-and-forget from the caller's thread). */
  private static final ExecutorService CALLBACK_POSTS =
      Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "seat-callback-posts");
        t.setDaemon(true);
        return t;
      });

  /** Static hook the harness adapter calls for cp-callback:// URIs (worker thread). */
  public static void postCallback(URI uri, Object params) {
    CpCallbackBus cb = callbackBus;
    if (cb == null) {
      throw new IllegalStateException("cp-callback requested before the seat bus was armed");
    }
    cb.post("hook", uri, null, HookPayloadBuilder.build(params));
  }

  static JsonElement callbackReplyTarget() {  // kept package-visible for unit probes
    return JsonNull.INSTANCE;
  }

  void dispatchCreateClient(Bus bus, Application app, JsonElement idEl, JsonElement bodyEl) {    // Robolectric boots apps on one shadow looper; task execution happens on the SDK's
    // own executors. Dispatch sequentially on the worker so per-client state is ordered.
    worker.submit(() -> {
      try {
        Representations.CreateInstanceParams params = gson.fromJson(bodyEl, Representations.CreateInstanceParams.class);
        String clientId = "c" + clientCounter.incrementAndGet();
        SdkClientEntity entity = new SdkClientEntity(app, params, seatLogAdapter());
        clients.put(clientId, entity);
        JsonObject resp = new JsonObject();
        resp.addProperty("client", clientId);
        bus.ok(idEl, "createClient", resp);
      } catch (Throwable t) {
        System.err.println("[cp-seat] createClient failed: " + t);
        t.printStackTrace();
        bus.err(idEl, "createClient", 500, describe(t));
      }
    });
  }

  /** Seat-side stderr logging for honest causal faces (never on stdout; CP-SPEC 5.1). */
  static com.launchdarkly.logging.LDLogAdapter seatLogAdapter() {
    return com.launchdarkly.logging.Logs.level(
        com.launchdarkly.logging.Logs.toStream(System.err),
        com.launchdarkly.logging.LDLogLevel.DEBUG);
  }

  void dispatchCommand(Bus bus, JsonElement idEl, JsonElement bodyEl) {
    worker.submit(() -> {
      try {
        JsonObject b = bodyEl.getAsJsonObject();
        String clientId = str(b, "client");
        SdkClientEntity entity = clients.get(clientId);
        if (entity == null) {
          bus.err(idEl, "command", 404, "unknown client id");
          return;
        }
        JsonElement req = b.get("request");
        Representations.CommandParams cmd = gson.fromJson(req, Representations.CommandParams.class);
        Object result = entity.doCommand(cmd);
        JsonElement bodyEl2 = result == null ? JsonNull.INSTANCE : gson.toJsonTree(result);
        bus.ok(idEl, "command", bodyEl2);
      } catch (Throwable t) {
        System.err.println("[cp-seat] command failed: " + t);
        t.printStackTrace();
        bus.err(idEl, "command", 500, describe(t));
      }
    });
  }

  void dispatchCloseClient(Bus bus, JsonElement idEl, JsonElement bodyEl) {
    worker.submit(() -> {
      try {
        JsonObject b = bodyEl.getAsJsonObject();
        String clientId = str(b, "client");
        SdkClientEntity entity = clients.remove(clientId);
        if (entity == null) {
          bus.err(idEl, "closeClient", 404, "unknown client id");
          return;
        }
        entity.close();
        // CP-SPEC 4.4: the closeClient response is an EMPTY 2xx on success
        // (body null) — a nested {closed,ok} envelope prices cp.schema invalid.
        bus.ok(idEl, "closeClient", JsonNull.INSTANCE);
      } catch (Throwable t) {
        System.err.println("[cp-seat] closeClient failed: " + t);
        t.printStackTrace();
        bus.err(idEl, "closeClient", 500, describe(t));
      }
    });
  }

  void stopAll() {
    for (String id : clients.keySet()) {
      SdkClientEntity c = clients.remove(id);
      if (c != null) {
        try { c.close(); } catch (Throwable t) { System.err.println("[cp-seat] close " + id + ": " + t); }
      }
    }
  }

  static JsonObject statusResultBody() {
    JsonObject o = new JsonObject();
    o.addProperty("name", "android-client-sdk-seat");
    o.addProperty("clientVersion", com.launchdarkly.sdk.android.BuildConfig.VERSION_NAME);
    JsonArray caps = new JsonArray();
    for (String c : CAPABILITIES) caps.add(new JsonPrimitive(c));
    o.add("capabilities", caps);
    return o;
  }

  static String str(JsonObject o, String k) {
    if (o == null || !o.has(k) || o.get(k) == null || o.get(k).isJsonNull()) return null;
    return o.get(k).getAsString();
  }

  static Boolean asBoolean(JsonElement el) {
    if (el == null || el.isJsonNull()) return null;
    if (el.isJsonPrimitive()) {
      try {
        return el.getAsBoolean();
      } catch (Exception e) {
        return null;
      }
    }
    return null;
  }

  static String describe(Throwable t) {
    String m = t.getMessage();
    return t.getClass().getSimpleName() + ": " + (m == null ? "(no message)" : m);
  }

  /** CP envelope writer. stdout = ONLY CP lines, flushed per line. */
  static final class Bus {
    final PrintStream out;
    Bus(PrintStream out) { this.out = out; }

    void ok(JsonElement idEl, String op, JsonElement bodyEl) {
      out.println(envelope(idEl, op, true, 200, bodyEl, null));
      out.flush();
    }

    /**
     * Service→runner callback (CP-SPEC §7). Message shape (not a response!):
     * the message defs require the base four keys and no reply fields, so this
     * writes cp/id/op/body and nothing else; the runner answers on stdin with
     * its own envelope echoing the id (which carries ok/status).
     */
    void callback(int id, String channel, String uri, String path, JsonElement payload) {
      JsonObject e = new JsonObject();
      e.addProperty("cp", "0");
      e.addProperty("id", id);
      e.addProperty("op", "callback");
      JsonObject b = new JsonObject();
      b.addProperty("channel", channel);
      if (uri != null) b.addProperty("uri", uri); else b.add("uri", JsonNull.INSTANCE);
      if (path != null) b.addProperty("path", path); else b.add("path", JsonNull.INSTANCE);
      b.add("payload", payload == null ? JsonNull.INSTANCE : payload);
      e.add("body", b);
      out.println(envGson.toJson(e));
      out.flush();
    }

    void err(JsonElement idEl, String op, int status, String error) {
      out.println(envelope(idEl, op, false, status, JsonNull.INSTANCE, error));
      out.flush();
    }

    String envelope(JsonElement idEl, String op, boolean ok, int status, JsonElement bodyEl, String error) {
      JsonObject e = new JsonObject();
      e.addProperty("cp", "0");
      e.add("id", idEl == null ? JsonNull.INSTANCE : idEl);
      if (op == null) e.add("op", JsonNull.INSTANCE); else e.addProperty("op", op);
      e.addProperty("ok", ok);
      e.addProperty("status", status);
      e.add("body", bodyEl == null ? JsonNull.INSTANCE : bodyEl);
      // cp.schema $defs/response requires these seven keys exactly
      // (additionalProperties false; "error" present, null on success).
      // envGson serializes explicit nulls; the standard gson omits them, which would
      // break the seven-key required law for every envelope.
      e.add("error", ok ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(error));
      return envGson.toJson(e);
    }
  }

  static final com.google.gson.Gson envGson = new com.google.gson.GsonBuilder().serializeNulls().create();
}
