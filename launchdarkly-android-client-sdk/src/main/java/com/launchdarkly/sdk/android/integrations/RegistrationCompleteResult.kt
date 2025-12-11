package com.launchdarkly.sdk.android.integrations

import com.launchdarkly.sdk.android.integrations.RegistrationCompleteResult.Failure.PluginFailure

sealed class RegistrationCompleteResult {
    object Success : RegistrationCompleteResult()
    data class Failure(val failures: List<PluginFailure>) : RegistrationCompleteResult() {
        data class PluginFailure(val pluginName: String, val message: String?, val cause: Throwable?)
    }

    companion object {
        @JvmStatic
        fun success() = Success

        @JvmStatic
        fun failure(failures: List<PluginFailure>) = Failure(failures)
    }
}
