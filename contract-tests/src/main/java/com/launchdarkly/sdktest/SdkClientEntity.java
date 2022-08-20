package com.launchdarkly.sdktest;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.UserAttribute;
import com.launchdarkly.sdk.android.LaunchDarklyException;
import com.launchdarkly.sdk.android.LDClient;
import com.launchdarkly.sdk.android.LDConfig;
import com.launchdarkly.sdk.android.LDClientControl;

import com.launchdarkly.sdktest.Representations.CommandParams;
import com.launchdarkly.sdktest.Representations.CreateInstanceParams;
import com.launchdarkly.sdktest.Representations.CustomEventParams;
import com.launchdarkly.sdktest.Representations.EvaluateAllFlagsParams;
import com.launchdarkly.sdktest.Representations.EvaluateAllFlagsResponse;
import com.launchdarkly.sdktest.Representations.EvaluateFlagParams;
import com.launchdarkly.sdktest.Representations.EvaluateFlagResponse;
import com.launchdarkly.sdktest.Representations.IdentifyEventParams;
import com.launchdarkly.sdktest.Representations.SdkConfigParams;

import android.app.Application;
import android.net.Uri;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import timber.log.Timber;

/**
 * This class implements all the client-level testing protocols defined in
 * the contract tests service specification, such as executing commands,
 * and configuring / initializing a new client.
 *
 * https://github.com/launchdarkly/sdk-test-harness/blob/main/docs/service_spec.md
 */
public class SdkClientEntity {
  private final LDClient client;

  public SdkClientEntity(Application application, CreateInstanceParams params) {
    Timber.i("Creating client for %s", params.tag);
    LDClientControl.resetInstances();
    Timber.i("Reset global state to allow for another client");
    LDConfig config = buildSdkConfig(params.configuration);
    // Each new client will plant a new Timber tree, so we uproot any existing ones
    // to avoid spamming stdout with duplicate log lines
    Timber.uprootAll();
    long startWaitMs = params.configuration.startWaitTimeMs != null ?
            params.configuration.startWaitTimeMs.longValue() : 5000;
    Future<LDClient> initFuture = LDClient.init(
            application,
            config,
            params.configuration.clientSide.initialUser);
    // Try to initialize client, but if it fails, keep going in case the test harness wants us to
    // work with an uninitialized client
    try {
      initFuture.get(startWaitMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException | ExecutionException e) {
      Timber.e(e, "Exception during Client initialization");
    } catch (TimeoutException e) {
      Timber.w("Client did not successfully initialize within %s ms. It could be taking longer than expected to start up", startWaitMs);
    }
    try {
      this.client = LDClient.get();
      if (!client.isInitialized() && !params.configuration.initCanFail) {
        // If `initCanFail` is true, we can proceed with an uninitialized client
        throw new RuntimeException("client initialization failed or timed out");
      }
    } catch (LaunchDarklyException e) {
      Timber.e(e, "Exception when initializing LDClient");
      throw new RuntimeException("Exception when initializing LDClient", e);
    }
  }

  public Object doCommand(CommandParams params) throws TestService.BadRequestException {
    Timber.i("Test harness sent command: %s", TestService.gson.toJson(params));
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
    client.identify(params.user);
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

  private LDConfig buildSdkConfig(SdkConfigParams params) {
    LDConfig.Builder builder = new LDConfig.Builder();
    builder.mobileKey(params.credential);

    if (params.streaming != null) {
      builder.stream(true);
      if (params.streaming.baseUri != null) {
        builder.streamUri(Uri.parse(params.streaming.baseUri));
      }
      // TODO: initialRetryDelayMs?
    }

    // The only time we should turn _off_ streaming is if polling is configured but NOT streaming
    if (params.streaming == null && params.polling != null) {
      builder.stream(false);
    }

    if (params.polling != null) {
      if (params.polling.baseUri != null) {
        builder.pollUri(Uri.parse(params.polling.baseUri));
      }
      if (params.polling.pollIntervalMs != null) {
        builder.backgroundPollingIntervalMillis(params.polling.pollIntervalMs.intValue());
      }
    }

    if (params.events != null) {
      builder.diagnosticOptOut(!params.events.enableDiagnostics);

      if (params.events.baseUri != null) {
        builder.eventsUri(Uri.parse(params.events.baseUri));
      }
      if (params.events.capacity > 0) {
        builder.eventsCapacity(params.events.capacity);
      }
      if (params.events.flushIntervalMs != null) {
        builder.eventsFlushIntervalMillis(params.events.flushIntervalMs.intValue());
      }
      if (params.events.allAttributesPrivate) {
        builder.allAttributesPrivate();
      }
      if (params.events.flushIntervalMs != null) {
        builder.eventsFlushIntervalMillis(params.events.flushIntervalMs.intValue());
      }
      if (params.events.globalPrivateAttributes != null) {
        String[] attrNames = params.events.globalPrivateAttributes;
        List<UserAttribute> privateAttributes = new ArrayList<>();
        for (String a : attrNames) {
          privateAttributes.add(UserAttribute.forName(a));
        }
        builder.privateAttributes((UserAttribute[]) privateAttributes.toArray(new UserAttribute[]{}));
      }
    }
    // TODO: disable events if no params.events
    builder.evaluationReasons(params.clientSide.evaluationReasons);
    builder.useReport(params.clientSide.useReport);

    return builder.build();
  }

  public void close() {
    try {
      client.close();
      Timber.i("Closed LDClient");
    } catch (IOException e) {
      Timber.e(e, "Unexpected error closing client");
      throw new RuntimeException("Unexpected error closing client", e);
    }
  }
}
