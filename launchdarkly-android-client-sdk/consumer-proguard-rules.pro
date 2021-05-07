# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in ~/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

-keep class com.launchdarkly.sdk.LDValue { <fields>; }
-keep class * extends com.launchdarkly.sdk.LDValue { <fields>; }
-keep class com.launchdarkly.sdk.LDUser { <fields>; }
-keep class com.launchdarkly.sdk.EvaluationReason { <fields>; }
-keep enum com.launchdarkly.sdk.EvaluationReason$* { *; }
-keep class com.launchdarkly.sdk.EvaluationDetail { <fields>; }

-keep class com.launchdarkly.sdk.android.Flag { <fields>; }
-keep class com.launchdarkly.sdk.android.FlagsResponse { <fields>; }
-keep class com.launchdarkly.sdk.android.DeleteFlagResponse { <fields>; }

-keep class com.launchdarkly.sdk.android.Event { <fields>; }
-keep class com.launchdarkly.sdk.android.AliasEvent { <fields>; }
-keep class com.launchdarkly.sdk.android.CustomEvent { <fields>; }
-keep class com.launchdarkly.sdk.android.FeatureRequestEvent { <fields>; }
-keep class com.launchdarkly.sdk.android.GenericEvent { <fields>; }
-keep class com.launchdarkly.sdk.android.IdentifyEvent { <fields>; }
-keep class com.launchdarkly.sdk.android.SummaryEvent { <fields>; }

-keep class com.launchdarkly.sdk.android.SummaryEventStore$FlagCounter { <fields>; }
-keep class com.launchdarkly.sdk.android.SummaryEventStore$FlagCounters { <fields>; }

-keep class com.launchdarkly.sdk.android.DiagnosticEvent { <fields>; }
-keep class com.launchdarkly.sdk.android.DiagnosticEvent$* { <fields>; }
-keep class com.launchdarkly.sdk.android.DiagnosticId { <fields>; }
-keep class com.launchdarkly.sdk.android.DiagnosticSdk { <fields>; }

-keep class com.launchdarkly.sdk.android.LDFailure { <fields>; }
-keep enum com.launchdarkly.sdk.android.LDFailure$FailureType { *; }
-keep class com.launchdarkly.sdk.android.LDInvalidResponseCodeFailure { <fields>; }
-keep enum com.launchdarkly.sdk.android.ConnectionInformation$ConnectionMode { *; }
-keep class com.launchdarkly.sdk.android.ConnectionInformationState { <fields>; }

-keepattributes Signature
-keepattributes *Annotation*

-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

-dontwarn org.conscrypt.ConscryptHostnameVerifier