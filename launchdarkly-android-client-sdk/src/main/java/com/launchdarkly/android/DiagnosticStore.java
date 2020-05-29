package com.launchdarkly.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import timber.log.Timber;

class DiagnosticStore {
    private static final String SHARED_PREFS_BASE_KEY = "LaunchDarkly-";
    private static final String INSTANCE_KEY = "diagnosticInstance";
    private static final String DATA_SINCE_KEY = "dataSinceDate";
    private static final String DROPPED_EVENTS_KEY = "droppedEvents";
    private static final String STREAM_INITS_KEY = "streamInits";
    private static final String EVENT_BATCH_KEY = "eventInLastBatch";

    private final SharedPreferences diagSharedPrefs;
    private final String sdkKey;

    private DiagnosticEvent.Statistics lastStats = null;
    private boolean newId = false;

    DiagnosticStore(Application application, String sdkKey) {
        this.diagSharedPrefs = application.getSharedPreferences(SHARED_PREFS_BASE_KEY + sdkKey +
                "-diag", Context.MODE_PRIVATE);
        this.sdkKey = sdkKey;

        if (Foreground.get(application).isForeground()) {
            try {
                String lastId = diagSharedPrefs.getString(INSTANCE_KEY, null);
                long lastDataSince = diagSharedPrefs.getLong(DATA_SINCE_KEY, -1);
                long lastDroppedEvents = diagSharedPrefs.getLong(DROPPED_EVENTS_KEY, 0);
                long eventsInLastBatch = diagSharedPrefs.getLong(EVENT_BATCH_KEY, 0);
                List<DiagnosticEvent.StreamInit> streamInits = getStreamInits();

                if (lastId != null && lastDataSince != -1) {
                    lastStats = new DiagnosticEvent.Statistics(System.currentTimeMillis(), new DiagnosticId(lastId, sdkKey), lastDataSince, lastDroppedEvents, eventsInLastBatch, streamInits);
                } else {
                    lastStats = null;
                }
            } catch (ClassCastException ignored) {
                lastStats = null;
            }
            resetAllStore();
        } else {
            resetIfInvalid();
        }
    }

    private void resetAllStore() {
        String newId = UUID.randomUUID().toString();
        diagSharedPrefs.edit()
                .putString(INSTANCE_KEY, newId)
                .putLong(DATA_SINCE_KEY, System.currentTimeMillis())
                .putLong(DROPPED_EVENTS_KEY, 0)
                .putLong(EVENT_BATCH_KEY, 0)
                .putString(STREAM_INITS_KEY, "[]")
                .apply();
        this.newId = true;
    }

    private void resetStatsStore(long dataSince) {
        diagSharedPrefs.edit()
                .putLong(DATA_SINCE_KEY, dataSince)
                .putLong(DROPPED_EVENTS_KEY, 0)
                .putLong(EVENT_BATCH_KEY, 0)
                .putString(STREAM_INITS_KEY, "[]")
                .apply();
    }

    private List<DiagnosticEvent.StreamInit> getStreamInits() {
        String streamInitsString = diagSharedPrefs.getString(STREAM_INITS_KEY, "[]");
        List<DiagnosticEvent.StreamInit> streamInits;
        try {
            DiagnosticEvent.StreamInit[] streamInitsArr = GsonCache.getGson().fromJson(streamInitsString, DiagnosticEvent.StreamInit[].class);
            streamInits = Arrays.asList(streamInitsArr);
        } catch (Exception ex) {
            Timber.w(ex, "Invalid stream inits array in diagnostic data store");
            streamInits = null;
        }
        return streamInits;
    }

    private void resetIfInvalid() {
        try {
            String lastId = diagSharedPrefs.getString(INSTANCE_KEY, null);
            long lastDataSince = diagSharedPrefs.getLong(DATA_SINCE_KEY, -1);
            long droppedEvents = diagSharedPrefs.getLong(DROPPED_EVENTS_KEY, -1);
            long eventsInBatch = diagSharedPrefs.getLong(EVENT_BATCH_KEY, -1);
            String streamInitsString = diagSharedPrefs.getString(STREAM_INITS_KEY, "[]");
            // Throws JsonSyntaxException if invalid
            GsonCache.getGson().fromJson(streamInitsString, DiagnosticEvent.StreamInit[].class);
            if (lastId == null || lastDataSince == -1 || droppedEvents == -1 || eventsInBatch == -1) {
                resetAllStore();
            }
        } catch (ClassCastException | JsonSyntaxException ignored) {
            resetAllStore();
        }
    }

    DiagnosticId getDiagnosticId() {
        String instanceId = diagSharedPrefs.getString(INSTANCE_KEY, null);
        return new DiagnosticId(instanceId, sdkKey);
    }

    DiagnosticEvent.Statistics getLastStats() {
        return lastStats;
    }

    boolean isNewId() {
        return newId;
    }

    long getDataSince() {
        return diagSharedPrefs.getLong(DATA_SINCE_KEY, System.currentTimeMillis());
    }

    DiagnosticEvent.Statistics getCurrentStatsAndReset() {
        List<DiagnosticEvent.StreamInit> streamInits = getStreamInits();
        long currentTime = System.currentTimeMillis();
        DiagnosticEvent.Statistics event =
                new DiagnosticEvent.Statistics(currentTime,
                        getDiagnosticId(),
                        diagSharedPrefs.getLong(DATA_SINCE_KEY, -1),
                        diagSharedPrefs.getLong(DROPPED_EVENTS_KEY, -1),
                        diagSharedPrefs.getLong(EVENT_BATCH_KEY, 0),
                        streamInits);
        resetStatsStore(currentTime);
        return event;
    }

    void incrementDroppedEventCount() {
        diagSharedPrefs.edit()
                .putLong(DROPPED_EVENTS_KEY, diagSharedPrefs.getLong(DROPPED_EVENTS_KEY, 0) + 1)
                .apply();
    }

    void addStreamInit(long timestamp, int durationMs, boolean failed) {
        Gson gson = GsonCache.getGson();
        DiagnosticEvent.StreamInit streamInit = new DiagnosticEvent.StreamInit(timestamp, durationMs, failed);
        ArrayList<DiagnosticEvent.StreamInit> streamInits = new ArrayList<>();
        try {
            String streamInitsString = diagSharedPrefs.getString(STREAM_INITS_KEY, "[]");
            DiagnosticEvent.StreamInit[] streamInitsArr = gson.fromJson(streamInitsString, DiagnosticEvent.StreamInit[].class);
            streamInits.addAll(Arrays.asList(streamInitsArr));
        } catch (Exception unused) { }
        streamInits.add(streamInit);
        diagSharedPrefs.edit()
                .putString(STREAM_INITS_KEY, gson.toJson(streamInits))
                .apply();
    }

    void recordEventsInLastBatch(long eventsInLastBatch) {
        diagSharedPrefs.edit()
                .putLong(EVENT_BATCH_KEY, eventsInLastBatch)
                .apply();
    }
}