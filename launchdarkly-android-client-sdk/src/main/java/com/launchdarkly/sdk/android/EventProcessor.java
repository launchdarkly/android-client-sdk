package com.launchdarkly.sdk.android;

interface EventProcessor {
    void start();
    void stop();
    void flush();
}
