package com.launchdarkly.sdktest;

import com.google.gson.annotations.SerializedName;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.integrations.EvaluationSeriesContext;
import com.launchdarkly.sdk.android.integrations.TrackSeriesContext;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * These classes are all the data we might send or receive from the contract test harness.
 * We use Gson to magically serialize these classes to/from JSON.
 */
public abstract class Representations {
    public static class Status {
        String name;
        String[] capabilities;
        String clientVersion;
    }

    public static class CreateInstanceParams {
        SdkConfigParams configuration;
        String tag;
    }

    public static class SdkConfigParams {
        String credential;
        Long startWaitTimeMs;
        boolean initCanFail;
        SdkConfigStreamParams streaming;
        SdkConfigPollParams polling;
        SdkConfigEventParams events;
        SdkConfigTagParams tags;
        SdkConfigClientSideParams clientSide;
        SdkConfigServiceEndpointParams serviceEndpoints;
        SdkConfigHookParams hooks;
    }

    public static class SdkConfigStreamParams {
        String baseUri;
        Long initialRetryDelayMs;
    }

    public static class SdkConfigPollParams {
        String baseUri;
        Long pollIntervalMs;
    }

    public static class SdkConfigEventParams {
        String baseUri;
        boolean allAttributesPrivate;
        int capacity;
        boolean enableDiagnostics;
        String[] globalPrivateAttributes;
        Long flushIntervalMs;
    }

    public static class SdkConfigTagParams {
        String applicationId;
        String applicationName;
        String applicationVersion;
        String applicationVersionName;
    }

    public static class SdkConfigServiceEndpointParams {
        String streaming;
        String polling;
        String events;
    }

    public static class SdkConfigClientSideParams {
        LDContext initialContext;
        boolean evaluationReasons;
        boolean useReport;
        boolean includeEnvironmentAttributes;
    }

    public static class SdkConfigHookParams {
        List<HookConfig> hooks;
    }

    public static class HookConfig {
        String name;
        URI callbackUri;
        HookData data;
        HookErrors errors;
    }

    public static class HookData {
        Map<String, Object> beforeEvaluation;
        Map<String, Object> afterEvaluation;
    }

    public static class HookErrors {
        String beforeEvaluation;
        String afterEvaluation;
        String afterTrack;
    }

    public static class CommandParams {
        String command;
        EvaluateFlagParams evaluate;
        EvaluateAllFlagsParams evaluateAll;
        IdentifyEventParams identifyEvent;
        CustomEventParams customEvent;
        ContextBuildParams contextBuild;
        ContextConvertParams contextConvert;
    }

    public static class EvaluateFlagParams {
        String flagKey;
        String valueType;
        LDValue defaultValue;
        boolean detail;
    }

    public static class EvaluateFlagResponse {
        LDValue value;
        Integer variationIndex;
        EvaluationReason reason;
    }

    public static class EvaluateAllFlagsParams {
        boolean clientSideOnly;
        boolean detailsOnlyForTrackedFlags;
        boolean withReasons;
    }

    public static class EvaluateAllFlagsResponse {
        Map<String, LDValue> state;
    }

    public static class EvaluationSeriesCallbackParams {
        EvaluationSeriesContext evaluationSeriesContext;
        Map<String, Object> evaluationSeriesData;
        EvaluationDetail<LDValue> evaluationDetail;
        String stage;
    }

    public static class TrackSeriesCallbackParams {
        TrackSeriesContext trackSeriesContext;
        String stage;
    }

    public static class IdentifyEventParams {
        LDContext context;
    }

    public static class CustomEventParams {
        String eventKey;
        LDValue data;
        boolean omitNullData;
        Double metricValue;
    }

    public static class ContextBuildParams {
        ContextBuildSingleParams single;
        ContextBuildSingleParams[] multi;
    }

    public static class ContextBuildSingleParams {
        public String kind;
        public String key;
        public String name;
        public Boolean anonymous;
        public String secondary;
        @SerializedName("private") public String[] privateAttrs;
        public Map<String, LDValue> custom;
    }

    public static class ContextBuildResponse {
        String output;
        String error;
    }

    public static class ContextConvertParams {
        String input;
    }
}
