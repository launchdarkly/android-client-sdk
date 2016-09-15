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
	compile 'com.launchdarkly:launchdarkly-android-client:0.0.2-SNAPSHOT'
	```  
1. In your application configure and initialize the client:

	```
	LDConfig ldConfig = new LDConfig.Builder()
    	.setMobileKey("YOUR_MOBILE_KEY")
    	.build();

   user = new LDUser.Builder("user key")
       .email("fake@example.com")
       .build();

   ListenableFuture<LDClient> initFuture = LDClient.init(this.getApplication(), ldConfig, user);
   LDClient ldClient = initFuture.get(10, TimeUnit.SECONDS);
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
- Tests
- Make Android linter happy