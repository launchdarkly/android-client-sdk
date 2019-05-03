# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in ~/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:
-keep class com.launchdarkly.android.** { *; }
-keep class org.apache.http.** { *; }
-keep public class com.google.android.gms.* { public * ; }
-dontwarn com.google.android.gms.**
-dontwarn okio.**
-dontwarn okhttp3.**
-dontwarn org.apache.http.**
-dontwarn org.slf4j.**
-dontwarn com.google.common.**
-dontwarn java.nio.file.*
-dontwarn javax.annotation.**
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.ClassValue
-dontwarn com.google.j2objc.annotations.Weak
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

-dontwarn com.google.errorprone.annotations.**
