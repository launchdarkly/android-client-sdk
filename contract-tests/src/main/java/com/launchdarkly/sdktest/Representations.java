package com.launchdarkly.sdktest;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;

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
    }

    public static class SdkConfigStreamParams {
        String baseUri;
        long initialRetryDelayMs;
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
        String applicationVersion;
    }

    public static class SdkConfigClientSideParams {
        LDContext initialContext;
        boolean evaluationReasons;
        boolean useReport;
    }

    public static class CommandParams {
        String command;
        EvaluateFlagParams evaluate;
        EvaluateAllFlagsParams evaluateAll;
        IdentifyEventParams identifyEvent;
        CustomEventParams customEvent;
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

    public static class IdentifyEventParams {
        LDContext context;
    }

    public static class CustomEventParams {
        String eventKey;
        LDValue data;
        boolean omitNullData;
        Double metricValue;
    }
}
