package com.launchdarkly.android.response;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * Farhan
 * 2018-01-30
 */
public class UserFlagVersionSharedPreferences implements VersionSharedPreferences {

    private final SharedPreferences versionSharedPrefs;

    public UserFlagVersionSharedPreferences(Application application, String name) {
        this.versionSharedPrefs = application.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    @Override
    public boolean isVersionValid(FlagResponse flagResponse) {
        if (versionSharedPrefs.contains(flagResponse.getKey())) {
            float storedVersion = getStoredVersion(flagResponse);
            return storedVersion < flagResponse.getVersion();
        }
        return true;
    }

    @Override
    public void clear() {
        SharedPreferences.Editor editor = versionSharedPrefs.edit();
        editor.clear();
        editor.apply();
    }

    @Override
    public void saveAll(List<FlagResponse> flagResponseList) {
        SharedPreferences.Editor editor = versionSharedPrefs.edit();

        for (FlagResponse flagResponse : flagResponseList) {
            editor.putFloat(flagResponse.getKey(), flagResponse.getVersion());
        }
        editor.apply();
    }

    @Override
    public void deleteStoredVersion(FlagResponse flagResponse) {
        SharedPreferences.Editor editor = versionSharedPrefs.edit();
        editor.remove(flagResponse.getKey());
        editor.apply();
    }

    @Override
    public void updateStoredVersion(FlagResponse flagResponse) {
        SharedPreferences.Editor editor = versionSharedPrefs.edit();
        editor.putFloat(flagResponse.getKey(), flagResponse.getVersion());
        editor.apply();
    }

    @Override
    public float getStoredVersion(FlagResponse flagResponse) {
        return versionSharedPrefs.getFloat(flagResponse.getKey(), Float.MIN_VALUE);
    }
}
