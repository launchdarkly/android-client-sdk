# LaunchDarkly Android SDK
This library is compatible with Android SDK versions 15 and up (4.0.3 Ice Cream Sandwich)


How to use:
Check out the included example app, or follow things here:

1. Declare this dependency:

	```
	compile 'com.launchdarkly:launchdarkly-android-client:2.2.1'
	```  
1. In your application configure and initialize the client:

	```
	LDConfig ldConfig = new LDConfig.Builder()
    	.setMobileKey("YOUR_MOBILE_KEY")
    	.build();

   LDUser user = new LDUser.Builder("user key")
       .email("fake@example.com")
       .build();

   // NOTE: This method blocks for up to 5 seconds. See Javadoc or http://docs.launchdarkly.com/docs/android-sdk-reference
   // for nonblocking options.
   LDClient ldClient = LDClient.init(this.getApplication(), ldConfig, user, 5);
   ```
1. Evaluation example:
	
	```
	variationResult = ldClient.stringVariation(flagKey, "fallback");
	```
1. Updating the User:

	```
 	LDUser updatedUser = new LDUser.Builder(user)
       .email("fake2@example.com")
       .build();

   ldClient.identify(user);
	```

## ProGuard Config
If you're using ProGuard add these lines to your config:

```
-keep class com.launchdarkly.android.** { *; }
-keep class org.apache.http.** { *; }
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
```

## Feature Flag Updating
The LaunchDarkly Android SDK defaults to what we have found to be the best combination of low latency updates and minimal battery drain:

1. When the app is foregrounded a [Server-Sent Events](https://en.wikipedia.org/wiki/Server-sent_events) streaming connection is made to LaunchDarkly. This streaming connection stays open as long as your app is in the foreground and is connected to the internet.
1. When the app is backgrounded, the stream connection is terminated and the SDK will poll (with caching) for flag updates every 15 minutes.
1. When the app is foregrounded, we fetch the latest flags and reconnect to the stream. 
1. In either the foreground or background, we don't try to update unless your device has internet connectivity.

This configuration means that you will get near real-time updates for your feature flag values when the app is in the foreground.

###Other Options
If you prefer other options, here they are:

1. Streaming can be disabled in favor of polling updates. To disable streaming call `.setStream(false)` on the `LDConfig.Builder` object.
1. The default polling interval is 5 minutes. To change it call `.setPollingIntervalMillis()` on the `LDConfig.Builder` object.
1. Background polling can be disabled (the app will only receive updates when the app is in the foreground). To disable background updating call `.setDisableBackgroundUpdating(true)` on the `LDConfig.Builder` object.
1. The background polling interval can be adjusted (with the same minimum of 60 seconds). To change it call `.setBackgroundPollingIntervalMillis()` on the `LDConfig.Builder` object.

Example config with streaming disabled and custom polling intervals:

```
 LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .setPollingIntervalMillis(600_000) // 10 minutes
                .setBackgroundPollingIntervalMillis(3_600_000) // 1 hour
                .build();
```
 

## Known Issues/Features not yet implemented:
- Make Android linter happy

Learn more
----------

Check out our [documentation](http://docs.launchdarkly.com) for in-depth instructions on configuring and using LaunchDarkly. You can also head straight to the [complete reference guide for this SDK](http://docs.launchdarkly.com/docs/android-sdk-reference) or our [Javadocs](http://launchdarkly.github.io/android-client/).

## Testing
Much of the behavior we want to assert is around complicated device state changes such as
app backgrounding, loss of internet connection. These are problematic to test in a programmatic way,
so we rely on a combination of automated emulator tests and manual tests.

If, when running tests, the Android Studio build starts throwing countDebugDexMethods and countReleaseDexMethods errors
using the run configuration dropdown, then switch to the command-line and exclude the two DEX methods causing the trouble.

> ./gradlew -x :launchdarkly-android-client:countDebugDexMethods -x :launchdarkly-android-client:countReleaseDexMethods -x :launchdarkly-android-client:signArchives --stacktrace clean build test cAT

About LaunchDarkly
-----------

* LaunchDarkly is a continuous delivery platform that provides feature flags as a service and allows developers to iterate quickly and safely. We allow you to easily flag your features and manage them from the LaunchDarkly dashboard.  With LaunchDarkly, you can:
    * Roll out a new feature to a subset of your users (like a group of users who opt-in to a beta tester group), gathering feedback and bug reports from real-world use cases.
    * Gradually roll out a feature to an increasing percentage of users, and track the effect that the feature has on key metrics (for instance, how likely is a user to complete a purchase if they have feature A versus feature B?).
    * Turn off a feature that you realize is causing performance problems in production, without needing to re-deploy, or even restart the application with a changed configuration file.
    * Grant access to certain features based on user attributes, like payment plan (eg: users on the ‘gold’ plan get access to more features than users in the ‘silver’ plan). Disable parts of your application to facilitate maintenance, without taking everything offline.
* LaunchDarkly provides feature flag SDKs for
    * [Java](http://docs.launchdarkly.com/docs/java-sdk-reference "Java SDK")
    * [JavaScript](http://docs.launchdarkly.com/docs/js-sdk-reference "LaunchDarkly JavaScript SDK")
    * [PHP](http://docs.launchdarkly.com/docs/php-sdk-reference "LaunchDarkly PHP SDK")
    * [Python](http://docs.launchdarkly.com/docs/python-sdk-reference "LaunchDarkly Python SDK")
    * [Python Twisted](http://docs.launchdarkly.com/docs/python-twisted-sdk-reference "LaunchDarkly Python Twisted SDK")
    * [Go](http://docs.launchdarkly.com/docs/go-sdk-reference "LaunchDarkly Go SDK")
    * [Node.JS](http://docs.launchdarkly.com/docs/node-sdk-reference "LaunchDarkly Node SDK")
    * [.NET](http://docs.launchdarkly.com/docs/dotnet-sdk-reference "LaunchDarkly .Net SDK")
    * [Ruby](http://docs.launchdarkly.com/docs/ruby-sdk-reference "LaunchDarkly Ruby SDK")
    * [iOS](http://docs.launchdarkly.com/docs/ios-sdk-reference "LaunchDarkly iOS SDK")
    * [Android](http://docs.launchdarkly.com/docs/android-sdk-reference "LaunchDarkly Android SDK")
* Explore LaunchDarkly
    * [launchdarkly.com](http://www.launchdarkly.com/ "LaunchDarkly Main Website") for more information
    * [docs.launchdarkly.com](http://docs.launchdarkly.com/  "LaunchDarkly Documentation") for our documentation and SDKs
    * [apidocs.launchdarkly.com](http://apidocs.launchdarkly.com/  "LaunchDarkly API Documentation") for our API documentation
    * [blog.launchdarkly.com](http://blog.launchdarkly.com/  "LaunchDarkly Blog Documentation") for the latest product updates
    * [Feature Flagging Guide](https://github.com/launchdarkly/featureflags/  "Feature Flagging Guide") for best practices and strategies

