package com.launchdarkly.android;

class DiagnosticId {

    final String diagnosticId;
    final String sdkKeySuffix;

    DiagnosticId(String diagnosticId, String sdkKey) {
        this.diagnosticId = diagnosticId;
        if (sdkKey == null) {
            sdkKeySuffix = null;
        } else {
            this.sdkKeySuffix = sdkKey.substring(Math.max(0, sdkKey.length() - 6));
        }
    }
}
