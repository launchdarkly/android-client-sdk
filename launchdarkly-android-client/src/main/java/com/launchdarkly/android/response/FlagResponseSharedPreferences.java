package com.launchdarkly.android.response;

import java.util.List;

/**
 * Farhan
 * 2018-01-30
 */
public interface FlagResponseSharedPreferences {

    void clear();

    boolean isVersionValid(FlagResponse flagResponse);

    void saveAll(List<FlagResponse> flagResponseList);

    void deleteStoredFlagResponse(FlagResponse flagResponse);

    void updateStoredFlagResponse(FlagResponse flagResponse);

    int getStoredVersion(String flagResponseKey);

    int getStoredFlagVersion(String flagResponseKey);

    Long getStoredDebugEventsUntilDate(String flagResponseKey);

    boolean getStoredTrackEvents(String flagResponseKey);

    int getStoredVariation(String flagResponseKey);

    boolean containsKey(String key);

    int getVersionForEvents(String flagResponseKey);
}
