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
import com.launchdarkly.sdk.android.LaunchDarklyException;
import com.launchdarkly.sdk.android.LDClient;
import com.launchdarkly.sdk.android.LDConfig;

import com.launchdarkly.sdk.android.integrations.ApplicationInfoBuilder;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.integrations.Hook;
import com.launchdarkly.sdk.android.integrations.PollingDataSourceBuilder;
import com.launchdarkly.sdk.android.integrations.StreamingDataSourceBuilder;
import com.launchdarkly.sdk.android.integrations.ServiceEndpointsBuilder;
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

  public SdkClientEntity(Application application, CreateInstanceParams params, LDLogAdapter logAdapter) {
    LDLogAdapter logAdapterForTest = new PrefixedLogAdapter(logAdapter, "test");
    this.logger = LDLogger.withAdapter(logAdapterForTest, params.tag);
    logger.info("Creating client");
    LDConfig config = buildSdkConfig(params.configuration, logAdapterForTest, params.tag);
    long startWaitMs = params.configuration.startWaitTimeMs != null ?
            params.configuration.startWaitTimeMs.longValue() : 5000;
    Representations.SdkConfigClientSideParams clientSideParams = params.configuration.clientSide;
    Future<LDClient> initFuture = LDClient.init(application, config, clientSideParams.initialContext);
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

  public Object doCommand(CommandParams params) throws TestService.BadRequestException {
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
        client.flush();
        return null;
      case "contextBuild":
        return doContextBuild(params.contextBuild);
      case "contextConvert":
        return doContextConvert(params.contextConvert);
      default:
        throw new TestService.BadRequestException("unknown command: " + params.command);
    }
  }

  public EvaluateFlagResponse doEvaluateFlag(EvaluateFlagParams params) {
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
    return resp;
  }

  private void doIdentifyEvent(IdentifyEventParams params) {
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
      resp.output = JsonSerialization.serialize(c);
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
      resp.output = JsonSerialization.serialize(c);
    } catch (Exception e) {
      resp.error = e.getMessage();
    }
    return resp;
  }

  private LDConfig buildSdkConfig(SdkConfigParams params, LDLogAdapter logAdapter, String tag) {
    LDConfig.Builder builder = new LDConfig.Builder(params.clientSide.includeEnvironmentAttributes ?
            LDConfig.Builder.AutoEnvAttributes.Enabled : LDConfig.Builder.AutoEnvAttributes.Disabled);

    builder.mobileKey(params.credential);
    builder.logAdapter(logAdapter).loggerName(tag + ".sdk");

    ServiceEndpointsBuilder endpoints = Components.serviceEndpoints();

    // We don't want to use the default persistent storage mechanism when running contract tests
    // because the state of each test is supposed to be independent from the others. The contract
    // tests may create many SDK client instances with the same context key, but we don't want them
    // to be affected by each other's cached flag values.
    ConfigHelper.configureIsolatedInMemoryPersistence(builder);

    if (params.polling != null && params.polling.baseUri != null) {
      // Note that this property can be set even if streaming is enabled
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
    builder.http(
            Components.httpConfiguration().useReport(params.clientSide.useReport)
    );

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
      builder.hooks(Components.hooks().setHooks(hookList));
    }

    return builder.build();
  }

  public void close() {
    try {
      client.close();
      logger.info("Closed LDClient");
    } catch (IOException e) {
      logger.error("Unexpected error closing client: {}", e);
      throw new RuntimeException("Unexpected error closing client", e);
    }
  }
}
