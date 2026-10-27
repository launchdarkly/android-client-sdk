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
        SdkConfigDataSystemParams dataSystem;
        SdkConfigWrapperParams wrapper;
        SdkConfigTlsParams tls;
        SdkConfigProxyParams proxy;
    }

    /** The v3 TLS data face (v3 service-spec tls params): `skipVerifyPeer`
     * and `customCAFile` (the run CA path the {{runtime:ca_file}} placeholder
     * resolves to at the grader). */
    public static class SdkConfigTlsParams {
        Boolean skipVerifyPeer;
        String customCAFile;
    }

    /** The harness `proxy` shape (service_spec 387-388, the `http-proxy`
     * capability): the SDK routes every connection through the named proxy
     * (the `httpProxy` URL carries its own optional userinfo credentials). */
    public static class SdkConfigProxyParams {
        String httpProxy;
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

    /** cp.schema sdkConfiguration/properties/wrapper (v3:L384): the
     * X-LaunchDarkly-Wrapper header config. Declared under the
     * `wrapper` capability. */
    public static class SdkConfigWrapperParams {
        String name;
        String version;
    }

    public static class SdkConfigClientSideParams {
        LDContext initialContext;
        /** CENTINEL census seat-cell seam (the user-type class's own law): the
         * harness's legacy initial-user wire (the LDUser JSON shape the harness's
         * InitialUser face serves; {email, key, name, ...}). Raw JsonElement:
         * the legacy-user wire parses through the seat's own legacy-user arm
         * (buildLegacyUserContext), not the context deserializer. */
        com.google.gson.JsonElement initialUser;
        boolean evaluationReasons;
        boolean useReport;
        boolean includeEnvironmentAttributes;
        /** The harness client-side environment face: the payload filter the
         * poll request URL joins (the ENVFILTER law's own `filter` query
         * parameter). */
        SdkConfigEnvironmentParams environment;

        // CENTINEL seat extensions (campaign client-INTERFACE section 5): a blind storage
        // scope tag whose clients share one local store (PERSIST.5), and bootstrap flag
        // data in the all-flags-state shape for the SDK's pre-connection serve.
        @SerializedName("x-cp-storage-scope") String xCpStorageScope;
        @SerializedName("x-cp-bootstrap") java.util.Map<String, Object> xCpBootstrap;
    }

    public static class SdkConfigEnvironmentParams {
        String filter;
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
        Map<String, Object> afterTrack;
        Map<String, Object> beforeIdentify;
        Map<String, Object> afterIdentify;
    }

    public static class HookErrors {
        String beforeEvaluation;
        String afterEvaluation;
        String afterTrack;
    }

    public static class SdkConfigDataSystemParams {
        Boolean useDefaultDataSystem;
        SdkConfigConnectionModeConfig connectionModeConfig;
        List<SdkConfigDataInitializer> initializers;
        List<SdkConfigDataSynchronizer> synchronizers;
    }

    public static class SdkConfigConnectionModeConfig {
        String initialConnectionMode;
        Map<String, SdkConfigModeDefinition> customConnectionModes;
    }

    public static class SdkConfigModeDefinition {
        List<SdkConfigDataInitializer> initializers;
        List<SdkConfigDataSynchronizer> synchronizers;
    }

    public static class SdkConfigDataInitializer {
        SdkConfigPollParams polling;
    }

    public static class SdkConfigDataSynchronizer {
        SdkConfigStreamParams streaming;
        SdkConfigPollParams polling;
    }

    public static class CommandParams {
        public String command;
        EvaluateFlagParams evaluate;
        EvaluateAllFlagsParams evaluateAll;
        IdentifyEventParams identifyEvent;
        CustomEventParams customEvent;
        ContextBuildParams contextBuild;
        ContextConvertParams contextConvert;
        RegisterFlagChangeListenerParams registerFlagChangeListener;
        UnregisterListenerParams unregisterListener;
        ContextComparisonParams contextComparison;
        @com.google.gson.annotations.SerializedName("x-cp-create-no-credential") XcpCreateNoCredentialParams xcpCreateNoCredential;
        SetOverrideParams setOverride;
        RemoveOverrideParams removeOverride;
        @com.google.gson.annotations.SerializedName("x-cp-closed-evaluate") EvaluateFlagParams xcpClosedEvaluate;
        @com.google.gson.annotations.SerializedName("x-cp-set-streaming") SetStreamingParams xcpSetStreaming;
        public @com.google.gson.annotations.SerializedName("x-cp-logs") XcpLogsParams xcpLogs;
    }

    /** CP-SPEC 10 x-cp-logs params: `action: "drain"` (the only action the
     * seat prices; other actions refuse named). */
    public static class XcpLogsParams {
        public String action;
    }

    /** x-cp-set-streaming arm params (cp.schema x-cp-set-streaming: `state`
     * boolean|null — true forces streaming, false forces polling, null resets
     * the SDK's automatic mode resolution). */
    public static class SetStreamingParams {
        Boolean state;
    }

    public static class SetOverrideParams {
        String flagKey;
        LDValue value;
    }

    public static class RemoveOverrideParams {
        String flagKey;
    }

    /** x-cp-create-no-credential request arm (the harness's own x-cp face). */
    public static class XcpCreateNoCredentialParams {
        LDContext initialContext;
    }

    /** The context-comparison response (spec): `equals` prices the SDK's own
     * LDContext equality (kind+key+attrs). */
    public static class ContextComparisonResponse {
        Boolean equals;
    }

    /** service_spec listener command params (L706/L754): the seat echoes
     * listenerId in every notification for the listener. */
    public static class RegisterFlagChangeListenerParams {
        String listenerId;
        URI callbackUri;
    }

    public static class UnregisterListenerParams {
        String listenerId;
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

    /** The identify hook series' callback payload (spec HOOK 1.4.x). */
    public static class IdentifySeriesCallbackParams {
        public com.launchdarkly.sdk.android.integrations.IdentifySeriesContext identifySeriesContext;
        public Map<String, Object> identifySeriesData;
        public com.launchdarkly.sdk.android.integrations.IdentifySeriesResult identifyResult;
        public String stage;
    }

    public static class TrackSeriesCallbackParams {
        public Map<String, Object> evaluationSeriesData;
        TrackSeriesContext trackSeriesContext;
        String stage;    }

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

    public static class ContextComparisonParams {
        ContextBuildParams context1;
        ContextBuildParams context2;
    }

    public static class ContextConvertParams {
        String input;
    }
}
