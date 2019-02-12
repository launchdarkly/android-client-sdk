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

    FlagResponse getStoredFlagResponse(String flagResponseKey);

    boolean containsKey(String key);
}
