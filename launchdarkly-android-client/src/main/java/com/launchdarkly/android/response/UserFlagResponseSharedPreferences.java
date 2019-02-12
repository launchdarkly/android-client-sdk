package com.launchdarkly.android.response;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.launchdarkly.android.response.interpreter.UserFlagResponseParser;

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
        if (flagResponse != null) {
            FlagResponse storedFlag = getStoredFlagResponse(flagResponse.getKey());
            if (storedFlag != null) {
                return storedFlag.getVersion() < flagResponse.getVersion();
            }
        }
        return true;
    }

    @Override
    public void saveAll(List<FlagResponse> flagResponseList) {
        SharedPreferences.Editor editor = sharedPreferences.edit();

        for (FlagResponse flagResponse : flagResponseList) {
            String s = flagResponse.getAsJsonObject().toString();
            android.util.Log.d("ELI", "*** " + flagResponse.getKey() + ": " + s);
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
    public FlagResponse getStoredFlagResponse(String key) {
        JsonObject jsonObject = getValueAsJsonObject(key);
        return jsonObject == null ? null : UserFlagResponseParser.parseFlag(jsonObject, key);
    }

    @Override
    public boolean containsKey(String key) {
        return sharedPreferences.contains(key);
    }

    @VisibleForTesting
    int getLength() {
        return sharedPreferences.getAll().size();
    }
}
