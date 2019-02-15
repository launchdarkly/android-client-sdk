package com.launchdarkly.android.response;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.support.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Created by jamesthacker on 4/12/18.
 */

abstract class BaseUserSharedPreferences {

    SharedPreferences sharedPreferences;

    public void clear() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.clear();
        editor.apply();
    }

    @SuppressLint("ApplySharedPref")
    @Nullable
    public JsonObject getValueAsJsonObject(String flagResponseKey) {
        String storedFlag;
        try {
            storedFlag = sharedPreferences.getString(flagResponseKey, null);
        } catch (ClassCastException castException) {
            // An old version of shared preferences is stored, so clear it.
            // The flag responses will get re-synced with the server
            sharedPreferences.edit().clear().commit();
            return null;
        }

        if (storedFlag == null) {
            return null;
        }

        JsonElement element = new JsonParser().parse(storedFlag);
        if (element instanceof JsonObject) {
            return (JsonObject) element;
        }

        return null;
    }
}
