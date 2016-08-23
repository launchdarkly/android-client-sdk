# Prerelease LaunchDarkly Android SDK
NOTE: This is an early build and should not be used in production!

How to use:
Check out the included example app, or follow things here:

1. Declare this dependency:

	```
	compile 'com.launchdarkly:android-client:0.0.1-SNAPSHOT'
	```  
2. In your application configure and initialize the client:

	```
	LDConfig ldConfig = new LDConfig.Builder()
    	.setMobileKey("YOUR_MOBILE_KEY")
    	.build();

   user = new LDUser.Builder("user key")
       .email("fake@example.com")
       .build();

   ldClient = LDClient.init(this.getApplication(), ldConfig, user);
	```
1. Evaluation example:
	
	```
	variationResult = ldClient.stringVariation(featureKey, "default");
	```
1. Updating the User:

	```
 	LDUser updatedUser = new LDUser.Builder(user)
       .email("fake2@example.com")
       .build();

   ldClient.identify(user);
	```

## Known Issues/Features not yet implemented:
- Http client caching
- Background polling
- Realtime Feature Flag change notification.
- Airplane mode
- Tests
- Javadoc