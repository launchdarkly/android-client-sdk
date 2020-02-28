package com.launchdarkly.android;

class DiagnosticSdk {

    final String name = "android-client-sdk";
    final String version = BuildConfig.VERSION_NAME;
    final String wrapperName;
    final String wrapperVersion;

    DiagnosticSdk(LDConfig config) {
        this.wrapperName = config.getWrapperName();
        this.wrapperVersion = config.getWrapperVersion();
    }
}
