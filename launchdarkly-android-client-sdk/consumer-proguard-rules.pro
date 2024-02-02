# LaunchDarkly Android SDK proguard rules
#
# For more details, see https://developer.android.com/studio/build/shrink-code
#
# Basically, at a minimum we *must* include here:
#
# - Any class that will be serialized or deserialized with Gson. That is, if we are
# ever passing this class to Gson.fromJson() or Gson.toJson(), it must appear here.
# We do not need to include it if we are just using the Gson streaming API directly
# to read or write properties of that class, so that Gson itself does not actually
# interact with the class.
#
# - Any class that is referenced in Gson serialization-related annotations.

-keep class com.launchdarkly.sdk.AttributeRef { <fields>; }
-keep class com.launchdarkly.sdk.LDContext { <fields>; }
-keep class com.launchdarkly.sdk.LDValue { <fields>; }
-keep class * extends com.launchdarkly.sdk.LDValue { <fields>; }
-keep class com.launchdarkly.sdk.EvaluationReason { <fields>; }
-keep enum com.launchdarkly.sdk.EvaluationReason$* { *; }
-keep class com.launchdarkly.sdk.EvaluationDetail { <fields>; }

-keep enum com.launchdarkly.sdk.android.ConnectionInformation$ConnectionMode { *; }
-keep class com.launchdarkly.sdk.android.ConnectionInformationState { <fields>; }
-keep class com.launchdarkly.sdk.android.DataModel$Flag { <fields>; }
-keep class com.launchdarkly.sdk.android.EnvironmentData { <fields>; }
-keep class com.launchdarkly.sdk.android.LDFailure { <fields>; }
-keep class com.launchdarkly.sdk.android.LDInvalidResponseCodeFailure { <fields>; }
-keep class com.launchdarkly.sdk.android.StreamUpdateProcessor$DeleteMessage { <fields>; }

-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

-dontwarn org.conscrypt.ConscryptHostnameVerifier

-keepattributes *Annotation*,Signature,InnerClasses

# Usually, these should be redundant with directives added by AAPT. We include them
# to be more explicit about what to save in case of build configurations having
# different default directives included.
-keep class com.launchdarkly.sdk.android.ConnectivityReceiver { <init>(); }
-keep class com.launchdarkly.sdk.android.PollingUpdater { <init>(); }
