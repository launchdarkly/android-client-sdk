package com.launchdarkly.android;

import java.util.List;

public interface LDAllFlagsListener {

    void onChange(List<String> flagKey);

}
