package com.launchdarkly.android.response;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

import java.util.List;

import timber.log.Timber;

/**
 * Farhan
 * 2018-01-30
 */
public class UserFlagResponseSharedPreferences extends BaseUserSharedPreferences implements FlagResponseSharedPreferences {

    public UserFlagResponseSharedPreferences(Application application, String name) {
        this.sharedPreferences = application.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    @Override
    public boolean isVersionValid(FlagResponse flagResponse) {
        if (flagResponse != null && sharedPreferences.contains(flagResponse.getKey())) {
            int storedVersion = getStoredVersion(flagResponse.getKey());
            return storedVersion < flagResponse.getVersion();
        }
        return true;
    }

    @Override
    public void saveAll(List<FlagResponse> flagResponseList) {
        SharedPreferences.Editor editor = sharedPreferences.edit();

        for (FlagResponse flagResponse : flagResponseList) {
            editor.putString(flagResponse.getKey(), flagResponse.getAsJsonObject().toString());
        }
        editor.apply();
    }

    @Override
    public void deleteStoredFlagResponse(FlagResponse flagResponse) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(flagResponse.getKey());
        editor.apply();
    }

    @Override
    public void updateStoredFlagResponse(FlagResponse flagResponse) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(flagResponse.getKey(), flagResponse.getAsJsonObject().toString());
        editor.apply();
    }

    @Override
    public int getStoredVersion(String flagResponseKey) {
        JsonElement extracted = extractValueFromPreferences(flagResponseKey, "version");
        if (extracted == null || extracted instanceof JsonNull) {
            return -1;
        }

        try {
            return extracted.getAsInt();
        } catch (ClassCastException cce) {
            Timber.e(cce, "Failed to get stored version");
        }

        return -1;
    }

    @Override
    public int getStoredFlagVersion(String flagResponseKey) {
        JsonElement extracted = extractValueFromPreferences(flagResponseKey, "flagVersion");
        if (extracted == null || extracted instanceof JsonNull) {
            return -1;
        }

        try {
            return extracted.getAsInt();
        } catch (ClassCastException cce) {
            Timber.e(cce, "Failed to get stored flag version");
        }

        return -1;
    }

    @Override
    @Nullable
    public Long getStoredDebugEventsUntilDate(String flagResponseKey) {
        JsonElement extracted = extractValueFromPreferences(flagResponseKey, "debugEventsUntilDate");
        if (extracted == null || extracted instanceof JsonNull) {
            return null;
        }

        try {
            return extracted.getAsLong();
        } catch (ClassCastException cce) {
            Timber.e(cce, "Failed to get stored debug events until date");
        }

        return null;
    }

    @Override
    @Nullable
    public boolean getStoredTrackEvents(String flagResponseKey) {
        JsonElement extracted = extractValueFromPreferences(flagResponseKey, "trackEvents");
        if (extracted == null || extracted instanceof JsonNull) {
            return false;
        }

        try {
            return extracted.getAsBoolean();
        } catch (ClassCastException cce) {
            Timber.e(cce, "Failed to get stored trackEvents");
        }

        return false;
    }

    @Override
    public int getStoredVariation(String flagResponseKey) {
        JsonElement extracted = extractValueFromPreferences(flagResponseKey, "variation");
        if (extracted == null || extracted instanceof JsonNull) {
            return -1;
        }

        try {
            return extracted.getAsInt();
        } catch (ClassCastException cce) {
            Timber.e(cce, "Failed to get stored variation");
        }

        return -1;
    }

    @Override
    public boolean containsKey(String key) {
        return sharedPreferences.contains(key);
    }

    @VisibleForTesting
    int getLength() {
        return sharedPreferences.getAll().size();
    }

    @Override
    public int getVersionForEvents(String flagResponseKey) {
        int storedFlagVersion = getStoredFlagVersion(flagResponseKey);
        int storedVersion = getStoredVersion(flagResponseKey);
        return storedFlagVersion == -1 ? storedVersion : storedFlagVersion;
    }

}
