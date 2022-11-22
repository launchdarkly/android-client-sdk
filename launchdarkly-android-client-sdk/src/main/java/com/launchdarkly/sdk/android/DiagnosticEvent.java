package com.launchdarkly.sdk.android;

import android.os.Build;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.android.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.android.subsystems.DiagnosticDescription;

import java.util.List;

@SuppressWarnings({"unused", "FieldCanBeLocal"}) // fields are for JSON serialization only
class DiagnosticEvent {

    final String kind;
    final long creationDate;
    final DiagnosticId id;

    DiagnosticEvent(String kind, long creationDate, DiagnosticId id) {
        this.kind = kind;
        this.creationDate = creationDate;
        this.id = id;
    }

    static class DiagnosticPlatform {
        final String name = "Android";
        final int androidSDKVersion = Build.VERSION.SDK_INT;

        DiagnosticPlatform() {
        }
    }

    static LDValue makeConfigurationInfo(LDConfig config) {
        ObjectBuilder builder = LDValue.buildObject()
                .put("customBaseURI",
                        !StandardEndpoints.DEFAULT_POLLING_BASE_URI.equals(config.serviceEndpoints.getPollingBaseUri()))
                .put("customEventsURI",
                        !StandardEndpoints.DEFAULT_EVENTS_BASE_URI.equals(config.serviceEndpoints.getEventsBaseUri()))
                .put("customStreamURI",
                        !StandardEndpoints.DEFAULT_STREAMING_BASE_URI.equals(config.serviceEndpoints.getStreamingBaseUri()))
                .put("backgroundPollingDisabled", config.isDisableBackgroundPolling())
                .put("evaluationReasonsRequested", config.isEvaluationReasons())
                .put("mobileKeyCount", config.getMobileKeys().size())
                .put("maxCachedUsers", config.getMaxCachedUsers())
                .put("autoAliasingOptOut", config.isAutoAliasingOptOut());
        mergeComponentProperties(builder, config.events);
        mergeComponentProperties(builder, config.dataSource);
        mergeComponentProperties(builder, config.http);
        return builder.build();
    }

    private static void mergeComponentProperties(ObjectBuilder builder, ComponentConfigurer<?> componentConfigurer) {
        if (componentConfigurer instanceof DiagnosticDescription) {
            LDValue description = ((DiagnosticDescription)componentConfigurer).describeConfiguration(null);
            for (String key: description.keys()) {
                builder.put(key, description.get(key));
            }
        }
    }

    static class Init extends DiagnosticEvent {
        final DiagnosticSdk sdk;
        final LDValue configuration;
        final DiagnosticPlatform platform = new DiagnosticPlatform();

        Init(long creationDate, DiagnosticId diagnosticId, LDConfig config) {
            super("diagnostic-init", creationDate, diagnosticId);
            this.sdk = new DiagnosticSdk(config);
            this.configuration = makeConfigurationInfo(config);
        }
    }

    static class Statistics extends DiagnosticEvent {
        long dataSinceDate;
        long droppedEvents;
        long eventsInLastBatch;
        List<StreamInit> streamInits;

        Statistics(long creationDate, DiagnosticId id, long dataSinceDate, long droppedEvents,
                   long eventsInLastBatch, List<StreamInit> streamInits) {
            super("diagnostic", creationDate, id);
            this.dataSinceDate = dataSinceDate;
            this.droppedEvents = droppedEvents;
            this.eventsInLastBatch = eventsInLastBatch;
            this.streamInits = streamInits;
        }
    }

    static class StreamInit {
        long timestamp;
        int durationMillis;
        boolean failed;

        StreamInit(long timestamp, int durationMillis, boolean failed) {
            this.timestamp = timestamp;
            this.durationMillis = durationMillis;
            this.failed = failed;
        }
    }
}
