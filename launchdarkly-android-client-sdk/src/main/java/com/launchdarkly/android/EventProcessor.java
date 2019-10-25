package com.launchdarkly.android;

interface EventProcessor {
    void start();
    void stop();
    void flush();
}
