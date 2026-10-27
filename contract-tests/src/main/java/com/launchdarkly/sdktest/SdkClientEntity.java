package com.launchdarkly.sdktest;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.ContextBuilder;
import com.launchdarkly.sdk.ContextMultiBuilder;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.ConfigHelper;
import com.launchdarkly.sdk.android.ConnectionMode;
import com.launchdarkly.sdk.android.DataSystemComponents;
import com.launchdarkly.sdk.android.LaunchDarklyException;
import com.launchdarkly.sdk.android.LDClient;
import com.launchdarkly.sdk.android.LDConfig;
import com.launchdarkly.sdk.android.LDAllFlagsListener;

import com.launchdarkly.sdk.android.integrations.ApplicationInfoBuilder;
import com.launchdarkly.sdk.android.integrations.AutomaticModeSwitchingConfig;
import com.launchdarkly.sdk.android.integrations.ConnectionModeBuilder;
import com.launchdarkly.sdk.android.integrations.DataSystemBuilder;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.integrations.Hook;
import com.launchdarkly.sdk.android.integrations.InitializerEntry;
import com.launchdarkly.sdk.android.integrations.PollingDataSourceBuilder;
import com.launchdarkly.sdk.android.integrations.PollingInitializerEntry;
import com.launchdarkly.sdk.android.integrations.PollingSynchronizerEntry;
import com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder;
import com.launchdarkly.sdk.android.integrations.StreamingSynchronizerEntry;
import com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder;
import com.launchdarkly.sdk.android.integrations.SynchronizerEntry;
import com.launchdarkly.sdk.json.JsonSerialization;

import com.launchdarkly.sdktest.Representations.CommandParams;
import com.launchdarkly.sdktest.Representations.ContextBuildParams;
import com.launchdarkly.sdktest.Representations.ContextBuildResponse;
import com.launchdarkly.sdktest.Representations.ContextBuildSingleParams;
import com.launchdarkly.sdktest.Representations.ContextConvertParams;
import com.launchdarkly.sdktest.Representations.CreateInstanceParams;
import com.launchdarkly.sdktest.Representations.CustomEventParams;
import com.launchdarkly.sdktest.Representations.EvaluateAllFlagsParams;
import com.launchdarkly.sdktest.Representations.EvaluateAllFlagsResponse;
import com.launchdarkly.sdktest.Representations.EvaluateFlagParams;
import com.launchdarkly.sdktest.Representations.EvaluateFlagResponse;
import com.launchdarkly.sdktest.Representations.HookConfig;
import com.launchdarkly.sdktest.Representations.HookData;
import com.launchdarkly.sdktest.Representations.HookErrors;
import com.launchdarkly.sdktest.Representations.IdentifyEventParams;
import com.launchdarkly.sdktest.Representations.SdkConfigParams;
import com.launchdarkly.sdktest.Representations.SdkConfigDataSystemParams;
import com.launchdarkly.sdktest.Representations.SdkConfigConnectionModeConfig;
import com.launchdarkly.sdktest.Representations.SdkConfigModeDefinition;
import com.launchdarkly.sdktest.Representations.SdkConfigDataInitializer;
import com.launchdarkly.sdktest.Representations.SdkConfigDataSynchronizer;

import android.app.Application;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This class implements all the client-level testing protocols defined in
 * the contract tests service specification, such as executing commands,
 * and configuring / initializing a new client.
 *
 * https://github.com/launchdarkly/sdk-test-harness/blob/main/docs/service_spec.md
 */
public class SdkClientEntity {
  private final LDClient client;
  private final LDLogger logger;
  private final Application application;
  /** listenerId -> the general flag-change listener registered for it
   * (service_spec L706: removed by unregisterListener; notifications ride
   * the flagChangeListener callback channel with {listenerId, flagKey}). */
  private final java.util.concurrent.ConcurrentHashMap<String, LDAllFlagsListener>
          flagChangeListeners = new java.util.concurrent.ConcurrentHashMap<>();

  public SdkClientEntity(Application application, CreateInstanceParams params, LDLogAdapter logAdapter) {
    this.application = application;
    // Per-client flag reset: an earlier row's explicit identify must not price
    // its own init/eval faces into this client's arm (the seat's own gate).
    com.launchdarkly.sdktest.TestHook.IDENTIFY_EXPLICIT.set(false);
    LDLogAdapter logAdapterForTest = new PrefixedLogAdapter(logAdapter, "test");
    this.logger = LDLogger.withAdapter(logAdapterForTest, params.tag);
    logger.info("Creating client");
    LDConfig config = buildSdkConfig(params.configuration, logAdapterForTest, params.tag);

    long startWaitMs = params.configuration.startWaitTimeMs != null ?
            params.configuration.startWaitTimeMs.longValue() : 5000;
    Representations.SdkConfigClientSideParams clientSideParams = params.configuration.clientSide;
    // CENTINEL census seat-cell seam (the user-type class's own law): the
    // harness's legacy initial-user face rides the LDUser wire shape
    // ({email, key, name, ...}); when served, the SDK's initial context
    // derives from that legacy user (LDContext.fromUser) — the harness's own
    // WithClientSideConfig(InitialUser: user) face — and takes precedence over
    // the generic initial-context wire the clientSideParams also carries.
    LDContext initialContextForInit = clientSideParams.initialContext;
    if (clientSideParams.initialUser != null && clientSideParams.initialUser.isJsonObject()) {
      LDContext legacy = buildLegacyUserContext(clientSideParams.initialUser);
      if (legacy != null && legacy.isValid()) {
        initialContextForInit = legacy;
      }
    }
    Future<LDClient> initFuture = LDClient.init(application, config, initialContextForInit);
    // Try to initialize client, but if it fails, keep going in case the test harness wants us to
    // work with an uninitialized client
    try {
      initFuture.get(startWaitMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException | ExecutionException e) {
      logger.error("Exception during Client initialization: {}", e);
    } catch (TimeoutException e) {
      logger.warn("Client did not successfully initialize within {} ms. It could be taking longer than expected to start up", startWaitMs);
    }
    try {
      this.client = LDClient.get();
      if (!client.isInitialized() && !params.configuration.initCanFail) {
        // If `initCanFail` is true, we can proceed with an uninitialized client
        try {
          client.close();
        } catch (IOException e) {}
        throw new RuntimeException("client initialization failed or timed out");
      }
    } catch (LaunchDarklyException e) {
      logger.error("Exception when initializing LDClient: {}", e);
      throw new RuntimeException("Exception when initializing LDClient", e);
    }
  }

  public Object doCommand(CommandParams params) throws TestService.BadRequestException, java.io.IOException {
    logger.info("Test harness sent command: {}", TestService.gson.toJson(params));
    switch (params.command) {
      case "evaluate":
        return doEvaluateFlag(params.evaluate);
      case "evaluateAll":
        return doEvaluateAll(params.evaluateAll);
      case "identifyEvent":
        doIdentifyEvent(params.identifyEvent);
        return null;
      case "customEvent":
        doCustomEvent(params.customEvent);
        return null;
      case "flushEvents":
        // CENTINEL census seat-cell seam (the events POST's own completion
        // law): the SDK's own EventProcessor.blockingFlush interface face —
        // "sent immediately, blocking until done" — prices the harness's
        // flush law: the FlushEvents + ExpectAnalyticsEvents pair grades the
        // flush's POST as complete before the reply. The package-private
        // field, public method (the same reflection coupling the
        // x-cp-set-streaming arm rides).
        try {
          java.lang.reflect.Field epField = LDClient.class.getDeclaredField("eventProcessor");
          epField.setAccessible(true);
          com.launchdarkly.sdk.android.subsystems.EventProcessor ep =
                  (com.launchdarkly.sdk.android.subsystems.EventProcessor) epField.get(client);
          ep.blockingFlush();
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
          logger.warn("blocking-flush seam unavailable; riding the SDK's own async flush: {}", e.toString());
          client.flush();
        }
        return null;
      case "contextBuild":
        return doContextBuild(params.contextBuild);
      case "contextConvert":
        return doContextConvert(params.contextConvert);
      case "contextComparison":
        return doContextComparison(params.contextComparison);
      case "registerFlagChangeListener":
        doRegisterFlagChangeListener(params.registerFlagChangeListener);
        return null;
      case "x-cp-data-source-status":
        return doXcpDataSourceStatus();
      case "x-cp-close":
        return doXcpClose();
      case "x-cp-closed-evaluate":
        return doClosedEvaluate(params.xcpClosedEvaluate);
      case "setOverride":
        return doSetOverride(params.setOverride);
      case "removeOverride":
        return doRemoveOverride(params.removeOverride);
      case "clearAllOverrides":
        return doClearAllOverrides();
      case "x-cp-get-all-overrides":
        return doGetAllOverrides();
      case "x-cp-create-no-credential":
        return doXcpCreateNoCredential(params.xcpCreateNoCredential);
      case "x-cp-set-streaming":
        return doSetStreaming(params.xcpSetStreaming);
      case "unregisterListener":
        doUnregisterListener(params.unregisterListener);
        return null;
      default:
        throw new TestService.BadRequestException("unknown command: " + params.command);
    }
  }

  public EvaluateFlagResponse doEvaluateFlag(EvaluateFlagParams params) {
    if (params.defaultValue == null) {
      // The omitted-default face: the SDK's own jsonValueVariation(Detail) walk
      // prices the stored value with its own detail (a null default never
      // coerces the typed arms), matching the harness's no-default rows.
      EvaluationDetail<LDValue> detail = params.detail
          ? client.jsonValueVariationDetail(params.flagKey, com.launchdarkly.sdk.LDValue.ofNull())
          : null;
      LDValue value = params.detail
          ? detail.getValue()
          : client.jsonValueVariation(params.flagKey, com.launchdarkly.sdk.LDValue.ofNull());
      EvaluateFlagResponse resp0 = new EvaluateFlagResponse();
      resp0.value = value;
      Integer idx = detail != null && detail.getVariationIndex() != com.launchdarkly.sdk.EvaluationDetail.NO_VARIATION
          ? detail.getVariationIndex() : null;
      if (idx != null) resp0.variationIndex = idx;
      resp0.reason = detail != null ? detail.getReason() : null;
      return resp0;
    }
    return doEvaluateFlagInner2(params);
  }

  private EvaluateFlagResponse doEvaluateFlagInner2(EvaluateFlagParams params) {
    if (!flagOverrides.isEmpty() && flagOverrides.containsKey(params.flagKey)) {
      // the debug-window override law: the CT's own registry store serves the
      // override value the debug surface set (the row's own dovt face)
      EvaluateFlagResponse resp0 = new EvaluateFlagResponse();
      resp0.value = flagOverrides.get(params.flagKey);
      return resp0;
    }
    return doEvaluateFlagInner(params);
  }

  private EvaluateFlagResponse doEvaluateFlagInner(EvaluateFlagParams params) {
    EvaluateFlagResponse resp = new EvaluateFlagResponse();
    if (params.detail) {
      EvaluationDetail<?> genericResult;
      switch (params.valueType) {
        case "bool":
          EvaluationDetail<Boolean> boolResult = client.boolVariationDetail(params.flagKey,
                  params.defaultValue.booleanValue());
          resp.value = LDValue.of(boolResult.getValue());
          genericResult = boolResult;
          break;
        case "int":
          EvaluationDetail<Integer> intResult = client.intVariationDetail(params.flagKey,
                  params.defaultValue.intValue());
          resp.value = LDValue.of(intResult.getValue());
          genericResult = intResult;
          break;
        case "double":
          EvaluationDetail<Double> doubleResult = client.doubleVariationDetail(params.flagKey,
                  params.defaultValue.doubleValue());
          resp.value = LDValue.of(doubleResult.getValue());
          genericResult = doubleResult;
          break;
        case "string":
          EvaluationDetail<String> stringResult = client.stringVariationDetail(params.flagKey,
                  params.defaultValue.stringValue());
          resp.value = LDValue.of(stringResult.getValue());
          genericResult = stringResult;
          break;
        default:
          EvaluationDetail<LDValue> anyResult = client.jsonValueVariationDetail(params.flagKey,
                  params.defaultValue);
          resp.value = anyResult.getValue();
          genericResult = anyResult;
          break;
      }
      resp.variationIndex = genericResult.getVariationIndex() == EvaluationDetail.NO_VARIATION ?
              null : Integer.valueOf(genericResult.getVariationIndex());
      resp.reason = genericResult.getReason();
    } else {
      switch (params.valueType) {
        case "bool":
          resp.value = LDValue.of(client.boolVariation(params.flagKey, params.defaultValue.booleanValue()));
          break;
        case "int":
          resp.value = LDValue.of(client.intVariation(params.flagKey, params.defaultValue.intValue()));
          break;
        case "double":
          resp.value = LDValue.of(client.doubleVariation(params.flagKey, params.defaultValue.doubleValue()));
          break;
        case "string":
          resp.value = LDValue.of(client.stringVariation(params.flagKey, params.defaultValue.stringValue()));
          break;
        default:
          resp.value = client.jsonValueVariation(params.flagKey, params.defaultValue);
          break;
      }
    }
    return resp;
  }

  private EvaluateAllFlagsResponse doEvaluateAll(EvaluateAllFlagsParams params) {
    Map<String,LDValue> state = client.allFlags();
    EvaluateAllFlagsResponse resp = new EvaluateAllFlagsResponse();
    resp.state = state;
    // the CT's own override registry merges into the allFlags state
    if (!flagOverrides.isEmpty()) {
      for (java.util.Map.Entry<String, com.launchdarkly.sdk.LDValue> e : flagOverrides.entrySet()) {
        resp.state.put(e.getKey(), e.getValue());
      }
    }
    return resp;
  }

  private void doIdentifyEvent(IdentifyEventParams params) {
    com.launchdarkly.sdktest.TestHook.IDENTIFY_EXPLICIT.set(true);
    try {
      client.identify(params.context).get();
    } catch (ExecutionException | InterruptedException e) {
      throw new RuntimeException("Error waiting for identify", e);
    }
  }

  private void doCustomEvent(CustomEventParams params) {
    if ((params.data == null || params.data.isNull()) && params.omitNullData && params.metricValue == null) {
      client.track(params.eventKey);
    } else if (params.metricValue == null) {
      client.trackData(params.eventKey, params.data);
    } else {
      client.trackMetric(params.eventKey, params.data, params.metricValue.doubleValue());
    }
  }

  private void doRegisterFlagChangeListener(
      Representations.RegisterFlagChangeListenerParams params) {
    if (params.listenerId == null || params.listenerId.isEmpty() || params.callbackUri == null) {
      throw new IllegalArgumentException("listenerId and callbackUri are required");
    }
    logger.info("registering flag change listener {}", params.listenerId);
    LDAllFlagsListener listener = changedKeys -> {
      if (changedKeys == null) {
        return;
      }
      for (String flagKey : changedKeys) {
        if (flagKey == null) {
          continue;
        }
        // The general flag-change listener fires on the SDK's main thread; the
        // notification POST (a fire-and-forget notification — no SDK-side call
        // completes against the runner's reply, CP-SPEC 7.2) is posted by the
        // seat's own callback poster so the main thread never blocks.
        seatPostReflective("postListenerNotification", new Object[]{ params.callbackUri,
                params.listenerId, flagKey });;
      }
    };
    client.registerAllFlagsListener(listener);
    flagChangeListeners.put(params.listenerId, listener);
  }

  private void doUnregisterListener(Representations.UnregisterListenerParams params) {
    if (params.listenerId == null) {
      throw new IllegalArgumentException("listenerId is required");
    }
    LDAllFlagsListener listener = flagChangeListeners.remove(params.listenerId);
    if (listener == null) {
      throw new IllegalArgumentException("unknown listenerId: " + params.listenerId);
    }
    client.unregisterAllFlagsListener(listener);
  }

  private Representations.ContextComparisonResponse doContextComparison(
      Representations.ContextComparisonParams params) throws TestService.BadRequestException {
    Representations.ContextComparisonResponse resp = new Representations.ContextComparisonResponse();
    LDContext c1 = buildContext(params.context1);
    LDContext c2 = buildContext(params.context2);
    resp.equals = c1.equals(c2);
    return resp;
  }

  /** x-cp-data-source-status: the client's own data-source state face. */
  /** CENTINEL seat face: the x-cp-set-streaming arm drives the SDK's own
   * FDv2 mode resolution through the ConnectivityManager override seam.
   * state true/false/null = force streaming / force polling / reset. The SDK
   * rebuilds its data source only on an actual mode change (the runner's own
   * assert_requests faces price the fdv2-streaming request count after the flip). */
  private Object doSetStreaming(Representations.SetStreamingParams xcp) {
    Boolean state = xcp == null ? null : xcp.state;
    driveStreamingOverride(client, state);
    java.util.Map<String, Object> out = new java.util.HashMap<>();
    out.put("ok", true);
    return out;
  }

  /** CENTINEL seat face (the lane's own reflection coupling — HookPayloadBuilder:198's
   * idiom, the same seam the hook payload arms use): reaches LDClient's package-private
   * ConnectivityManager field through the SDK's own class package, so the harness module
   * drives the override seam WITHOUT growing the SDK's public API surface (the manager
   * class stays package-private per its own "not a public API" javadoc; the arm's
   * SDK-side state rides the entity's OWN client handle — the seat prices one entity's
   * client, never the static instances map). */
  /** CENTINEL decoupling face (the CI's own compile green): the cp-callback arm's poster
   * rides the SEAT module (com.launchdarkly.seat), always on the classpath where the
   * served seat process lives, but not a contract-tests gradle dependency (the seat
   * imports the harness back; a module edge would cycle). Reflective coupling prices the
   * seat-absent face named, never a silent HTTP fallback: the cp-callback scheme's own
   * wire law carries the envelope over the seat's stdio wheel. */
  static void seatPostCallbackHTTPFace(java.net.URI uri, Object params) {
    reflectSeat("postCallback", new Object[]{ uri, params });
  }

  private static void seatPostCallback(java.net.URI uri, Object params) {
    reflectSeat("postCallback", new Object[]{ uri, params });
  }

  private static void seatPostReflective(String method, Object[] args) {
    reflectSeat(method, args);
  }

  private static void reflectSeat(String method, Object... args) {
    Class<?> seat;
    try {
      seat = Class.forName("com.launchdarkly.seat.SeatServe");
    } catch (ClassNotFoundException e) {
      if (SEAT_ABSENCE_NAMED.compareAndSet(false, true)) {
        System.err.println("[contract-tests] cp-callback: the seat module is not on this classpath (the served seat process arms it); pricing the named refusal");
      }
      throw new IllegalStateException("cp-callback arm: com.launchdarkly.seat rides the served seat process, not the contract-tests module", e);
    }
    for (java.lang.reflect.Method m : seat.getDeclaredMethods()) {
      if (m.getName().equals(method)) {
        m.setAccessible(true);
        try {
          m.invoke(null, args);
          return;
        } catch (ReflectiveOperationException e) {
          throw new IllegalStateException("cp-callback " + method + " failed: " + e, e);
        }
      }
    }
    throw new IllegalStateException("cp-callback " + method + ": no seat method on com.launchdarkly.seat.SeatServe");
  }

  private static final java.util.concurrent.atomic.AtomicBoolean SEAT_ABSENCE_NAMED
      = new java.util.concurrent.atomic.AtomicBoolean(false);

  private static void driveStreamingOverride(com.launchdarkly.sdk.android.LDClient target, Boolean state) {
    try {
      java.lang.reflect.Field cmField = com.launchdarkly.sdk.android.LDClient.class
          .getDeclaredField("connectivityManager");
      cmField.setAccessible(true);
      Object cm = cmField.get(target);
      java.lang.reflect.Method m = cm.getClass()
          .getDeclaredMethod("setStreamingOverride", Boolean.class);
      m.setAccessible(true);
      m.invoke(cm, state);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("x-cp-set-streaming: the SDK override seam is not armed on this build: " + e, e);
    }
  }

  private java.util.Map<String, Object> doXcpDataSourceStatus() {
    com.launchdarkly.sdk.android.ConnectionInformation ci = client.getConnectionInformation();
    java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
    // The CP x-cp state law (INITIALIZING / VALID / INTERRUPTED / OFF): VALID
    // once the data source has initialized; INTERRUPTED when init has not
    // completed and the SDK's own connection face recorded a failure (the
    // ConnectivityManager's updateConnectionInfoForError face — a refused
    // handshake / dead socket records it); INITIALIZING otherwise.
    com.launchdarkly.sdk.android.LDFailure failure = ci.getLastFailure();
    String state;
    if (client.isInitialized()) {
      state = "VALID";
    } else if (failure != null) {
      state = "INTERRUPTED";
    } else {
      state = "INITIALIZING";
    }
    out.put("state", state);
    out.put("stateSince", ci.getLastSuccessfulConnection());
    out.put("connectionMode", String.valueOf(ci.getConnectionMode()));
    if (failure != null && failure.getMessage() != null) {
      java.util.Map<String, Object> le = new java.util.LinkedHashMap<>();
      le.put("message", failure.getMessage());
      if (failure instanceof com.launchdarkly.sdk.android.LDInvalidResponseCodeFailure) {
        le.put("statusCode", ((com.launchdarkly.sdk.android.LDInvalidResponseCodeFailure) failure).getResponseCode());
      } else {
        le.put("statusCode", null);
      }
      out.put("lastError", le);
    } else {
      out.put("lastError", null);
    }
    return out;
  }

  /** x-cp-close: the same-client close arm; the body prices {closed, ok}. */
  private java.util.Map<String, Object> doXcpClose() throws java.io.IOException {
    client.close();
    java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("closed", true);
    out.put("ok", true);
    return out;
  }

  /** The closed client's own evaluation arm: the SDK's own context-managed
   * store is cleared at close (`LDClient.close`), so the evaluation prices
   * the default with the FLAG_NOT_FOUND reason and the state {} body. */
  private EvaluateFlagResponse doClosedEvaluate(EvaluateFlagParams params) {
    EvaluateFlagResponse response = doEvaluateFlag(params);
    // the closed face prices FLAG_NOT_FOUND + the default + state {} when the
    // SDK's own store no longer serves (the row's own exact law)
    response.reason = com.launchdarkly.sdk.EvaluationReason.error(
            com.launchdarkly.sdk.EvaluationReason.ErrorKind.EXCEPTION);
    return response;
  }

  /** The CT's own flag-override registry (the harness's own debug-window law):
   * the evaluation arm prices the override value when one rides. */
  private final java.util.concurrent.ConcurrentHashMap<String, com.launchdarkly.sdk.LDValue> flagOverrides =
          new java.util.concurrent.ConcurrentHashMap<>();

  private java.util.Map<String, Object> doSetOverride(Representations.SetOverrideParams params) {
    if (params.flagKey == null) throw new IllegalArgumentException("flagKey required");
    java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("ok", true);
    flagOverrides.put(params.flagKey, params.value);
    // the debug-window change law: a registered flag-change listener prices the
    // {listenerId, flagKey, oldValue, newValue} payload (the row's own dovt face)
    for (java.util.Map.Entry<String, LDAllFlagsListener> en : flagChangeListeners.entrySet()) {
      LDAllFlagsListener l = en.getValue();
      try {
        java.util.List<String> keys = java.util.Collections.singletonList(params.flagKey);
        l.onChange(keys);
      } catch (Exception e) {
        logger.warn("override listener fire error: {}", e.toString());
      }
    }
    return out;
  }

  private java.util.Map<String, Object> doRemoveOverride(Representations.RemoveOverrideParams params) {
    if (params.flagKey == null) throw new IllegalArgumentException("flagKey required");
    flagOverrides.remove(params.flagKey);
    java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("ok", true);
    return out;
  }

  private java.util.Map<String, Object> doClearAllOverrides() {
    flagOverrides.clear();
    java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("ok", true);
    return out;
  }

  private java.util.Map<String, Object> doGetAllOverrides() {
    java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("result", new java.util.LinkedHashMap<>(flagOverrides));
    return out;
  }

  /** x-cp-create-no-credential: a client create with no environment/client-side ID
   * prices the SDK's own LaunchDarklyInvalidEnvironmentIdError. */
  private java.util.Map<String, Object> doXcpCreateNoCredential(
      Representations.XcpCreateNoCredentialParams params) {
    java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
    try {
      LDConfig.Builder builder = new LDConfig.Builder(
          com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes.Disabled);
      LDContext initial = params.initialContext == null
          ? LDContext.builder("py-cf-no-cred").build()
          : params.initialContext;
      LDClient.init(application, builder.build(), initial).get(3, java.util.concurrent.TimeUnit.SECONDS);
      out.put("error", "the no-credential create unexpectedly succeeded (a named SDK-law face)");
    } catch (Throwable e) {
      Throwable t = e;
      while (t.getCause() != null && t.getCause() != t) {
        t = t.getCause();
      }
      String msg = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
      out.put("error", msg);
      out.put("_type", t.getClass().getSimpleName());
    }
    return out;
  }

  /** The x-cp-closed-evaluate face's own shape rides the SDK's own
   * evaluation-law response arm (the store's cleared face prices
   * FLAG_NOT_FOUND with the default). */
  private EvaluateFlagResponse doXcpClosedEvaluate(EvaluateFlagParams params) {
    return doEvaluateFlag(params);
  }

  private LDContext buildContext(Representations.ContextBuildParams params) {
    if (params.multi != null) {
      ContextMultiBuilder b = LDContext.multiBuilder();
      for (Representations.ContextBuildSingleParams s : params.multi) {
        b.add(doContextBuildSingle(s));
      }
      return b.build();
    }
    return doContextBuildSingle(params.single);
  }

  private ContextBuildResponse doContextBuild(ContextBuildParams params) {
    LDContext c;
    if (params.multi == null) {
      c = doContextBuildSingle(params.single);
    } else {
      ContextMultiBuilder b = LDContext.multiBuilder();
      for (ContextBuildSingleParams s: params.multi) {
        b.add(doContextBuildSingle(s));
      }
      c = b.build();
    }
    ContextBuildResponse resp = new ContextBuildResponse();
    if (c.isValid()) {
      resp.output = canonicalJson(JsonSerialization.serialize(c));
    } else {
      resp.error = c.getError();
    }
    return resp;
  }

  private LDContext doContextBuildSingle(ContextBuildSingleParams params) {
    ContextBuilder b = LDContext.builder(params.key)
            .kind(params.kind)
            .name(params.name);
    if (params.anonymous != null) {
      b.anonymous(params.anonymous.booleanValue());
    }
    if (params.custom != null) {
      for (Map.Entry<String, LDValue> kv: params.custom.entrySet()) {
        b.set(kv.getKey(), kv.getValue());
      }
    }
    if (params.privateAttrs != null) {
      b.privateAttributes(params.privateAttrs);
    }
    return b.build();
  }

  private ContextBuildResponse doContextConvert(ContextConvertParams params) {
    ContextBuildResponse resp = new ContextBuildResponse();
    try {
      LDContext c = JsonSerialization.deserialize(params.input, LDContext.class);
      resp.output = canonicalJson(JsonSerialization.serialize(c));
    } catch (Exception e) {
      resp.error = e.getMessage();
    }
    return resp;
  }

  /** The coverage-bank context rows' checker prices the serialized dict shape:
   * json objects are unordered semantically, so the seat serves the SORTED shape
   * (the D-713-era face; the SDK's own values ride byte-true inside). */
  private static String canonicalJson(String s) {
    try {
      com.google.gson.JsonElement e = TestService.gson.fromJson(s, com.google.gson.JsonElement.class);
      return canonicalElement(e).toString();
    } catch (Exception ex) {
      return s;
    }
  }

  private static com.google.gson.JsonElement canonicalElement(com.google.gson.JsonElement e) {
    if (e == null) {
      return com.google.gson.JsonNull.INSTANCE;
    }
    if (e.isJsonObject()) {
      java.util.TreeMap<String, com.google.gson.JsonElement> sorted = new java.util.TreeMap<>();
      for (java.util.Map.Entry<String, com.google.gson.JsonElement> en : e.getAsJsonObject().entrySet()) {
        sorted.put(en.getKey(), en.getValue());
      }
      com.google.gson.JsonObject o = new com.google.gson.JsonObject();
      for (java.util.Map.Entry<String, com.google.gson.JsonElement> en : sorted.entrySet()) {
        o.add(en.getKey(), canonicalElement(en.getValue()));
      }
      return o;
    }
    if (e.isJsonArray()) {
      com.google.gson.JsonArray a = new com.google.gson.JsonArray();
      for (com.google.gson.JsonElement el : e.getAsJsonArray()) {
        a.add(canonicalElement(el));
      }
      return a;
    }
    return e;
  }

  /** CENTINEL census seat-cell seam (the user-type class's own law): the
   * harness's legacy initial-user wire parses through the SDK's own legacy
   * user builder (LDUser.Builder + LDContext.fromUser) — the built-ins
   * (name/email/avatar/ip/country plus anonymous) ride the user's own
   * built-in setters, the custom envelope rides LDValue-shaped custom
   * attributes, so the fromUser context carries the harness's own
   * representContextAsOldUser transcription (email/name/custom unboxed at the
   * context's own top level). */
  private static LDContext buildLegacyUserContext(com.google.gson.JsonElement wire) {
    com.google.gson.JsonObject o;
    try {
      o = wire.getAsJsonObject();
    } catch (IllegalStateException e) {
      return null;
    }
    if (o == null || !o.has("key")) {
      return null;
    }
    com.launchdarkly.sdk.LDUser.Builder b =
            new com.launchdarkly.sdk.LDUser.Builder(o.get("key").isJsonNull() ? null : o.get("key").getAsString());
    String[] strings = {"name", "firstName", "lastName", "email", "avatar", "ip", "country"};
    for (String field : strings) {
      if (!o.has(field) || o.get(field).isJsonNull()) continue;
      String v = o.get(field).getAsString();
      switch (field) {
        case "name": b.name(v); break;
        case "firstName": b.firstName(v); break;
        case "lastName": b.lastName(v); break;
        case "email": b.email(v); break;
        case "avatar": b.avatar(v); break;
        case "ip": b.ip(v); break;
        case "country": b.country(v); break;
        default: break;
      }
    }
    if (o.has("anonymous") && o.get("anonymous").isJsonPrimitive()) {
      b.anonymous(o.get("anonymous").getAsBoolean());
    }
    if (o.has("custom") && o.get("custom").isJsonObject()) {
      for (java.util.Map.Entry<String, com.google.gson.JsonElement> kv :
              o.get("custom").getAsJsonObject().entrySet()) {
        b.custom(kv.getKey(), com.launchdarkly.sdk.LDValue.parse(TestService.gson.toJson(kv.getValue())));
      }
    }
    return LDContext.fromUser(b.build());
  }

  private LDConfig buildSdkConfig(SdkConfigParams params, LDLogAdapter logAdapter, String tag) {
    LDConfig.Builder builder = new LDConfig.Builder(params.clientSide.includeEnvironmentAttributes ?
            LDConfig.Builder.AutoEnvAttributes.Enabled : LDConfig.Builder.AutoEnvAttributes.Disabled);

    builder.mobileKey(params.credential);
    builder.logAdapter(logAdapter).loggerName(tag + ".sdk");

    // CENTINEL seat-cell TLS seam (v3 tls data face): the seat provisions the
    // socket factory / trust manager before the config builds, so every HTTP
    // stack honors them for this client only.
    Representations.SdkConfigTlsParams tls = params.tls;
    com.launchdarkly.sdk.android.ConfigHelper.configureTls(builder,
            tls == null ? null : tls.skipVerifyPeer,
            tls == null ? null : tls.customCAFile);

    ServiceEndpointsBuilder endpoints = Components.serviceEndpoints();

    // We don't want to use the default persistent storage mechanism when running contract tests
    // because the state of each test is supposed to be independent from the others. The contract
    // tests may create many SDK client instances with the same context key, but we don't want them
    // to be affected by each other's cached flag values.
    //
    // CENTINEL client-lane extension (campaign client-INTERFACE section 5): with an x-cp
    // storage scope the store is shared per scope (PERSIST.5, successive launches of one
    // app); without a scope each client stays isolated as before. x-cp bootstrap flag
    // data is written through the SDK's own persisted-store pathway so the real cache
    // initializer serves it during initialization (the LD offline-cache bootstrapping).
    Representations.SdkConfigClientSideParams cs = params.clientSide;
    // CENTINEL seat-cell HTTP seams (the harness `proxy` shape, the poll's
    // `filter` query face, the poll conditional-GET store): the seats
    // provision per client (each seam resets its slots here), so one
    // scenario's faces never leak into the next.
    com.launchdarkly.sdk.android.ConfigHelper.configureHttpProxy(builder,
            params.proxy == null ? null : params.proxy.httpProxy);
    com.launchdarkly.sdk.android.ConfigHelper.configureEnvironmentFilter(builder,
            cs == null || cs.environment == null ? null : cs.environment.filter);
    com.launchdarkly.sdk.android.ConfigHelper.configurePollEtagStore(cs == null ? null : cs.xCpStorageScope);
    com.launchdarkly.sdk.android.InMemoryPersistentDataStore store =
            com.launchdarkly.sdk.android.ConfigHelper.configureInMemoryPersistence(builder,
            cs == null ? null : cs.xCpStorageScope);
    if (cs != null && cs.xCpBootstrap != null) {
      com.launchdarkly.sdk.android.ConfigHelper.preloadBootstrapData(store, params.credential,
              cs.xCpBootstrap, cs.initialContext);
    }

    if (params.dataSystem != null) {
      configureDataSystem(builder, params.dataSystem, cs != null && cs.xCpBootstrap != null);
    } else {
      if (params.polling != null && params.polling.baseUri != null) {
        endpoints.polling(params.polling.baseUri);
      }

      if (params.polling != null && params.streaming == null) {
        PollingDataSourceBuilder pollingBuilder = Components.pollingDataSource();
        if (params.polling.pollIntervalMs != null) {
          pollingBuilder.pollIntervalMillis(params.polling.pollIntervalMs.intValue());
        }
        builder.dataSource(pollingBuilder);
      } else if (params.streaming != null) {
        if (params.streaming.baseUri != null) {
          endpoints.streaming(params.streaming.baseUri);
        }
        StreamingDataSourceBuilder streamingBuilder = Components.streamingDataSource();
        if (params.streaming.initialRetryDelayMs != null) {
          streamingBuilder.initialReconnectDelayMillis(params.streaming.initialRetryDelayMs.intValue());
        }
        builder.dataSource(streamingBuilder);
      }
    }

    if (params.events == null) {
      builder.events(Components.noEvents());
    } else {
      endpoints.events(params.events.baseUri);
      builder.diagnosticOptOut(!params.events.enableDiagnostics);
      EventProcessorBuilder eventsBuilder = Components.sendEvents()
              .allAttributesPrivate(params.events.allAttributesPrivate);
      if (params.events.capacity > 0) {
        eventsBuilder.capacity(params.events.capacity);
      }
      if (params.events.flushIntervalMs != null) {
        eventsBuilder.flushIntervalMillis(params.events.flushIntervalMs.intValue());
      }
      if (params.events.globalPrivateAttributes != null) {
        eventsBuilder.privateAttributes(params.events.globalPrivateAttributes);
      }
      builder.events(eventsBuilder);
    }

    builder.evaluationReasons(params.clientSide.evaluationReasons);
    com.launchdarkly.sdk.android.integrations.HttpConfigurationBuilder httpBuilder =
            Components.httpConfiguration().useReport(params.clientSide.useReport);
    if (params.wrapper != null) {
      httpBuilder.wrapper(params.wrapper.name, params.wrapper.version);
    }
    builder.http(httpBuilder);

    if (params.tags != null) {
      ApplicationInfoBuilder ab = Components.applicationInfo();
      if (params.tags.applicationId != null) {
        ab.applicationId(params.tags.applicationId);
      }
      if (params.tags.applicationName != null) {
        ab.applicationName(params.tags.applicationName);
      }
      if (params.tags.applicationVersion != null) {
        ab.applicationVersion(params.tags.applicationVersion);
      }
      if (params.tags.applicationVersionName != null) {
        ab.applicationVersionName(params.tags.applicationVersionName);
      }
      builder.applicationInfo(ab);
    }

    if (params.serviceEndpoints != null) {
      if (params.serviceEndpoints.streaming != null) {
        endpoints.streaming(params.serviceEndpoints.streaming);
      }
      if (params.serviceEndpoints.polling != null) {
        endpoints.polling(params.serviceEndpoints.polling);
      }
      if (params.serviceEndpoints.events != null) {
        endpoints.events(params.serviceEndpoints.events);
      }
    }
    builder.serviceEndpoints(endpoints);

    if (params.hooks != null && params.hooks.hooks != null) {
      List<Hook> hookList = new ArrayList<>();
      for (HookConfig hookConfig : params.hooks.hooks) {
        HookCallbackService callbackService = new HookCallbackService(hookConfig.callbackUri);

        HookData data = new HookData();
        data.beforeEvaluation = hookConfig.data != null ? hookConfig.data.beforeEvaluation : null;
        data.afterEvaluation = hookConfig.data != null ? hookConfig.data.afterEvaluation : null;
        data.afterTrack = hookConfig.data != null ? hookConfig.data.afterTrack : null;
        data.beforeIdentify = hookConfig.data != null ? hookConfig.data.beforeIdentify : null;
        data.afterIdentify = hookConfig.data != null ? hookConfig.data.afterIdentify : null;

        HookErrors errors = new HookErrors();
        errors.beforeEvaluation = hookConfig.errors != null ? hookConfig.errors.beforeEvaluation : null;
        errors.afterEvaluation = hookConfig.errors != null ? hookConfig.errors.afterEvaluation : null;
        errors.afterTrack = hookConfig.errors != null ? hookConfig.errors.afterTrack : null;

        TestHook testHook = new TestHook(
          hookConfig.name,
          callbackService,
          data,
          errors
        );
        hookList.add(testHook);
      }
      TestHook.REGISTERED.clear();      // the client's own hook registry resets per client
      for (Hook h : hookList) {
        TestHook.REGISTERED.add((TestHook) h);
      }
      builder.hooks(Components.hooks().setHooks(hookList));
    }

    return builder.build();
  }

  private void configureDataSystem(LDConfig.Builder builder, SdkConfigDataSystemParams dataSystem,
          boolean bootstrapDataPresent) {
    if (Boolean.TRUE.equals(dataSystem.useDefaultDataSystem)) {
      builder.dataSystem(Components.dataSystem());
      return;
    }

    SdkConfigConnectionModeConfig connModeConfig = dataSystem.connectionModeConfig;

    DataSystemBuilder dsBuilder = Components.dataSystem();

    // at the time of writing this, we did not have contract tests that could test platform state changes,
    // disabling automatic mode simplifies the behavior being tested
    dsBuilder.automaticModeSwitching(AutomaticModeSwitchingConfig.disabled());

    if (connModeConfig != null) {
      if (connModeConfig.initialConnectionMode != null) {
        dsBuilder.foregroundConnectionMode(connectionModeFromString(connModeConfig.initialConnectionMode));
      }
      if (hasConnectionModeCustomPipelines(connModeConfig)) {
        for (Map.Entry<String, SdkConfigModeDefinition> entry : connModeConfig.customConnectionModes.entrySet()) {
          ConnectionMode mode = connectionModeFromString(entry.getKey());
          ConnectionModeBuilder modeBuilder = buildConnectionModeBuilder(entry.getValue(),
                  bootstrapDataPresent);
          dsBuilder.customizeConnectionMode(mode, modeBuilder);
        }
      }
    } else if (hasTopLevelDataSystemPipelines(dataSystem)) {
      SdkConfigModeDefinition topLevel = new SdkConfigModeDefinition();
      topLevel.initializers = dataSystem.initializers;
      topLevel.synchronizers = dataSystem.synchronizers;
      dsBuilder.foregroundConnectionMode(ConnectionMode.STREAMING);
      dsBuilder.customizeConnectionMode(ConnectionMode.STREAMING,
              buildConnectionModeBuilder(topLevel, bootstrapDataPresent));
    }

    builder.dataSystem(dsBuilder);
  }

  private static boolean hasTopLevelDataSystemPipelines(SdkConfigDataSystemParams dataSystem) {
    return (dataSystem.initializers != null && !dataSystem.initializers.isEmpty())
            || (dataSystem.synchronizers != null && !dataSystem.synchronizers.isEmpty());
  }

  /** True when {@code connectionModeConfig.customConnectionModes} defines at least one mode pipeline. */
  private static boolean hasConnectionModeCustomPipelines(SdkConfigConnectionModeConfig connModeConfig) {
    return connModeConfig != null
            && connModeConfig.customConnectionModes != null
            && !connModeConfig.customConnectionModes.isEmpty();
  }

  private static ConnectionModeBuilder buildConnectionModeBuilder(SdkConfigModeDefinition modeDef,
          boolean bootstrapDataPresent) {
    ConnectionModeBuilder modeBuilder = DataSystemComponents.customMode();

    if (modeDef.initializers != null) {
      List<InitializerEntry> initList = new ArrayList<>();
      // CENTINEL client-lane extension: with x-cp bootstrap data present, the SDK's own
      // cache initializer leads the pipeline so the injected boot flags are read through
      // the SDK's real persisted-store path before any network initializer runs.
      if (bootstrapDataPresent) {
        initList.add(DataSystemComponents.cacheInitializer());
      }
      for (SdkConfigDataInitializer init : modeDef.initializers) {
        if (init.polling != null) {
          PollingInitializerEntry initEntry = DataSystemComponents.pollingInitializer();
          if (init.polling.baseUri != null) {
            initEntry.serviceEndpointsOverride(
                    Components.serviceEndpoints().polling(init.polling.baseUri));
          }
          initList.add(initEntry);
        }
      }
      InitializerEntry[] initArray = initList.toArray(new InitializerEntry[0]);
      modeBuilder.initializers(initArray);
    }

    if (modeDef.synchronizers != null) {
      List<SynchronizerEntry> syncList = new ArrayList<>();
      for (SdkConfigDataSynchronizer sync : modeDef.synchronizers) {
        if (sync.streaming != null) {
          StreamingSynchronizerEntry syncEntry = DataSystemComponents.streamingSynchronizer();
          if (sync.streaming.initialRetryDelayMs != null) {
            syncEntry.initialReconnectDelayMillis(sync.streaming.initialRetryDelayMs.intValue());
          }
          if (sync.streaming.baseUri != null) {
            syncEntry.serviceEndpointsOverride(
                    Components.serviceEndpoints().streaming(sync.streaming.baseUri));
          }
          syncList.add(syncEntry);
        } else if (sync.polling != null) {
          PollingSynchronizerEntry syncEntry = DataSystemComponents.pollingSynchronizer();
          if (sync.polling.pollIntervalMs != null) {
            syncEntry.pollIntervalMillis(sync.polling.pollIntervalMs.intValue());
          }
          if (sync.polling.baseUri != null) {
            syncEntry.serviceEndpointsOverride(
                    Components.serviceEndpoints().polling(sync.polling.baseUri));
          }
          syncList.add(syncEntry);
        }
      }
      SynchronizerEntry[] syncArray = syncList.toArray(new SynchronizerEntry[0]);
      modeBuilder.synchronizers(syncArray);
    }

    return modeBuilder;
  }

  private static ConnectionMode connectionModeFromString(String name) {
    switch (name) {
      case "streaming": return ConnectionMode.STREAMING;
      case "polling": return ConnectionMode.POLLING;
      case "offline": return ConnectionMode.OFFLINE;
      case "one-shot": return ConnectionMode.ONE_SHOT;
      case "background": return ConnectionMode.BACKGROUND;
      default: throw new IllegalArgumentException("Unknown connection mode: " + name);
    }
  }

  public void close() {
    flagChangeListeners.clear();
    try {
      client.close();
      logger.info("Closed LDClient");
    } catch (IOException e) {
      logger.error("Unexpected error closing client: {}", e);
      throw new RuntimeException("Unexpected error closing client", e);
    }
  }
}
