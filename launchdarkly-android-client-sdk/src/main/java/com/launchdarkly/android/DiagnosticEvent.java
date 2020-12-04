package com.launchdarkly.android;

import android.os.Build;

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

    static class DiagnosticConfiguration {
        private final boolean customBaseURI;
        private final boolean customEventsURI;
        private final boolean customStreamURI;
        private final int eventsCapacity;
        private final int connectTimeoutMillis;
        private final long eventsFlushIntervalMillis;
        private final boolean streamingDisabled;
        private final boolean allAttributesPrivate;
        private final long pollingIntervalMillis;
        private final long backgroundPollingIntervalMillis;
        private final boolean inlineUsersInEvents;
        private final boolean useReport;
        private final boolean backgroundPollingDisabled;
        private final boolean evaluationReasonsRequested;
        private final int mobileKeyCount;
        private final int diagnosticRecordingIntervalMillis;
        private final int maxCachedUsers;

        DiagnosticConfiguration(LDConfig config) {
            this.customBaseURI = !LDConfig.DEFAULT_POLL_URI.equals(config.getPollUri());
            this.customEventsURI = !LDConfig.DEFAULT_EVENTS_URI.equals(config.getEventsUri());
            this.customStreamURI = !LDConfig.DEFAULT_STREAM_URI.equals(config.getStreamUri());
            this.eventsCapacity = config.getEventsCapacity();
            this.connectTimeoutMillis = config.getConnectionTimeoutMillis();
            this.eventsFlushIntervalMillis = config.getEventsFlushIntervalMillis();
            this.streamingDisabled = !config.isStream();
            this.allAttributesPrivate = config.allAttributesPrivate();
            this.pollingIntervalMillis = config.getPollingIntervalMillis();
            this.backgroundPollingIntervalMillis = config.getBackgroundPollingIntervalMillis();
            this.inlineUsersInEvents = config.inlineUsersInEvents();
            this.useReport = config.isUseReport();
            this.backgroundPollingDisabled = config.isDisableBackgroundPolling();
            this.evaluationReasonsRequested = config.isEvaluationReasons();
            this.mobileKeyCount = config.getMobileKeys().size();
            this.diagnosticRecordingIntervalMillis = config.getDiagnosticRecordingIntervalMillis();
            this.maxCachedUsers = config.getMaxCachedUsers();
        }

    }

    static class Init extends DiagnosticEvent {
        final DiagnosticSdk sdk;
        final DiagnosticConfiguration configuration;
        final DiagnosticPlatform platform = new DiagnosticPlatform();

        Init(long creationDate, DiagnosticId diagnosticId, LDConfig config) {
            super("diagnostic-init", creationDate, diagnosticId);
            this.sdk = new DiagnosticSdk(config);
            this.configuration = new DiagnosticConfiguration(config);
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
