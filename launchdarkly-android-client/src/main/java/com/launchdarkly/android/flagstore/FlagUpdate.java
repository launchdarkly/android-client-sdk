package com.launchdarkly.android.flagstore;

public interface FlagUpdate {

    Flag updateFlag(Flag before);
    String flagToUpdate();

}
