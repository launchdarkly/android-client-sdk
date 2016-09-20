# Prerelease LaunchDarkly Android SDK
NOTE: This is an early build and should not be used in production!

How to use:
Check out the included example app, or follow things here:

1. Add the Maven snapshots repo to your repositories:
	
	```
   maven { url "https://oss.sonatype.org/content/groups/public/" }
	```
1. Declare this dependency:

	```
	compile 'com.launchdarkly:launchdarkly-android-client:1.0.0-SNAPSHOT'
	```  
1. In your application configure and initialize the client:

	```
	LDConfig ldConfig = new LDConfig.Builder()
    	.setMobileKey("YOUR_MOBILE_KEY")
    	.build();

   user = new LDUser.Builder("user key")
       .email("fake@example.com")
       .build();

   // NOTE: This method blocks for up to 5 seconds. See Javadoc for nonblocking options.
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
-dontwarn org.apache.http.**
-dontwarn org.slf4j.**
-dontwarn java.nio.file.*
-dontwarn javax.annotation.**
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.ClassValue
-dontwarn com.google.j2objc.annotations.Weak
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
```

## Known Issues/Features not yet implemented:
- Make Android linter happy

Learn more
----------

Check out our [documentation](http://docs.launchdarkly.com) for in-depth instructions on configuring and using LaunchDarkly. You can also head straight to the [complete reference guide for this SDK](https://dash.readme.io/project/launchdarkly/v2.0/docs/android-sdk-reference) or our [Javadocs](http://launchdarkly.github.io/android-client/).

## Testing
Much of the behavior we want to assert is around complicated device state changes such as
app backgrounding, loss of internet connection. These are problematic to test in a programmatic way,
so we rely on a combination of automated emulator tests and manual tests.