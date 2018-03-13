package com.launchdarkly.android.response;

import java.util.List;

/**
 * Farhan
 * 2018-01-30
 */
public interface VersionSharedPreferences {

    boolean isVersionValid(FlagResponse flagResponse);

    void clear();

    void saveAll(List<FlagResponse> flagResponseList);

    void deleteStoredVersion(FlagResponse flagResponse);

    void updateStoredVersion(FlagResponse flagResponse);

    float getStoredVersion(FlagResponse flagResponse);
}
