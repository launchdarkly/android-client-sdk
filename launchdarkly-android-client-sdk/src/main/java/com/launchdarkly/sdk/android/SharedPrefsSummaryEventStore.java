package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.launchdarkly.sdk.LDValue;

import java.util.HashMap;

/**
 * Used internally by the SDK.
 */
class SharedPrefsSummaryEventStore implements SummaryEventStore {

    private static final String START_DATE_KEY = "$startDate$";

    private final SharedPreferences sharedPreferences;

    SharedPrefsSummaryEventStore(Application application, String name) {
        this.sharedPreferences = application.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    @Override
    public synchronized void addOrUpdateEvent(String flagResponseKey, LDValue value, LDValue defaultVal, Integer version, Integer variation) {
        FlagCounters storedCounters = getFlagCounters(flagResponseKey);

        if (storedCounters == null) {
            storedCounters = new FlagCounters(defaultVal);
        }

        boolean existingCounter = false;
        for (FlagCounter counter: storedCounters.counters) {
            if (counter.matches(version, variation)) {
                counter.count++;
                existingCounter = true;
                break;
            }
        }

        if (!existingCounter) {
            storedCounters.counters.add(new FlagCounter(value, version, variation));
        }

        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(flagResponseKey, GsonCache.getGson().toJson(storedCounters));
        if (!sharedPreferences.contains(START_DATE_KEY)) {
            editor.putLong(START_DATE_KEY, System.currentTimeMillis()).apply();
        }
        editor.apply();

        LDConfig.LOG.d("Updated summary for flagKey %s to %s", flagResponseKey, GsonCache.getGson().toJson(storedCounters));
    }

    @Override
    public synchronized SummaryEvent getSummaryEvent() {
        long startDate = sharedPreferences.getLong(START_DATE_KEY, -1);
        HashMap<String, FlagCounters> features = new HashMap<>();
        for (String key: sharedPreferences.getAll().keySet()) {
            if (START_DATE_KEY.equals(key)) {
                continue;
            }
            features.put(key, getFlagCounters(key));
        }

        if (startDate == -1 || features.size() == 0) {
            return null;
        }

        return new SummaryEvent(startDate, System.currentTimeMillis(), features);
    }

    synchronized FlagCounters getFlagCounters(String flagKey) {
        try {
            String storedJson = sharedPreferences.getString(flagKey, null);
            if (storedJson == null) {
                return null;
            }
            return GsonCache.getGson().fromJson(storedJson, FlagCounters.class);
        } catch (Exception ignored) {
            // Fallthrough to clear
        }
        // An old version of shared preferences is stored, so clear it.
        clear();
        return null;
    }

    @Override
    public synchronized SummaryEvent getSummaryEventAndClear() {
        SummaryEvent summaryEvent = getSummaryEvent();
        clear();
        return summaryEvent;
    }

    public synchronized void clear() {
        sharedPreferences.edit().clear().apply();
    }
}
