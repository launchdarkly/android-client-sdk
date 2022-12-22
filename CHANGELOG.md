# Change log


All notable changes to the LaunchDarkly Android SDK will be documented in this file. This project adheres to [Semantic Versioning](http://semver.org).

## [3.3.1] - 2022-12-21
### Fixed:
- If the application is in the background when the SDK is started, the SDK will go into polling mode and immediately make a flag data request to LaunchDarkly. Previously, in this scenario the first poll would not happen until the background poll interval elapsed, so the SDK would effectively never have flag data at initialization time for an app or service that started in the background.

## [4.0.1] - 2022-12-19
### Fixed:
- If the application is in the background when the SDK is started, the SDK will go into polling mode and immediately make a flag data request to LaunchDarkly. Previously, in this scenario the first poll would not happen until the background poll interval elapsed, so the SDK would effectively never have flag data at initialization time for an app or service that started in the background.

## [4.0.0] - 2022-12-07
The latest version of this SDK supports LaunchDarkly's new custom contexts feature. Contexts are an evolution of a previously-existing concept, "users." Contexts let you create targeting rules for feature flags based on a variety of different information, including attributes pertaining to users, organizations, devices, and more. You can even combine contexts to create "multi-contexts." 

This feature is only available to members of LaunchDarkly's Early Access Program (EAP). If you're in the EAP, you can use contexts by updating your SDK to the latest version and, if applicable, updating your Relay Proxy. Outdated SDK versions do not support contexts, and will cause unpredictable flag evaluation behavior.

If you are not in the EAP, only use single contexts of kind "user", or continue to use the user type if available. If you try to create contexts, the context will be sent to LaunchDarkly, but any data not related to the user object will be ignored.

For detailed information about this version, please refer to the list below. For information on how to upgrade from the previous version, please read the [migration guide](https://docs.launchdarkly.com/sdk/client-side/android/migration-3-to-4).

### Added:
- In `com.launchDarkly.sdk`, the types `LDContext` and `ContextKind` define the new context model.
- For all SDK methods that took an `LDUser` parameter, there is now an overload that takes an `LDContext`. The SDK still supports `LDUser` for now, but `LDContext` is the preferred model and `LDUser` may be removed in a future version.
- The `TestData` class in `com.launchdarkly.sdk.android.integrations` is a new way to inject feature flag data programmatically into the SDK for testing—either with fixed values for each flag, or with targeting logic that can return different values for different contexts.

### Changed _(breaking changes from 3.x)_:
- It was previously allowable to set a user key to an empty string. In the new context model, the key is not allowed to be empty. Trying to use an empty key will cause evaluations to fail and return the default value.
- There is no longer such a thing as a `secondary` meta-attribute that affects percentage rollouts. If you set an attribute with that name in `LDContext`, it will simply be a custom attribute like any other.
- The `anonymous` attribute in `LDUser` is now a simple boolean, with no distinction between a false state and a null state.

### Changed (behavioral changes):
- The SDK no longer uses Android's `AlarmManager` API to schedule background polling of flag data. Instead, it uses a simple worker thread. `AlarmManager` notifications could wake up a sleeping device, which is not desirable just for getting flag data.
- Analytics event data now uses a new JSON schema due to differences between the context model and the old user model.
- The SDK no longer adds `device` and `os` values to the user attributes. Applications that wish to use device/OS information in feature flag rules must explicitly add such information.

### Removed:
- Removed all types, fields, and methods that were deprecated as of the most recent 3.x release.
- Removed the `secondary` meta-attribute in `LDUser` and `LDUser.Builder`.
- The `alias` method no longer exists because alias events are not needed in the new context model.
- The `autoAliasingOptOut` and `inlineUsersInEvents` options no longer exist because they are not relevant in the new context model.

## [3.3.0] - 2022-12-02
The primary purpose of this release is to introduce newer APIs for SDK configuration, corresponding to how configuration will work in the upcoming 4.0 release. The corresponding older APIs are now deprecated; switching from them to the newer ones now will facilitate migrating to 4.0 in the future. This also brings the Android SDK's API closer in line with other current LaunchDarkly SDKs, such as the Java SDK and the .NET SDKs.

Previously, most configuration options were set by setter methods in `LDConfig.Builder`. These are being superseded by builders that are specific to one area of functionality: for instance, `Components.streamingDataSource()` and `Components.pollingDataSource()` provide builders/factories that have options specific to streaming or polling, and the SDK's many options related to analytics events are now in a builder returned by `Components.sendEvents()`. Using this newer API makes it clearer which options are for what, and makes it impossible to write contradictory configurations like `.stream(true).pollingIntervalMillis(30000)`.

The new configuration builders also include some options for SDK behavior that could not previously be configured; see "Added".

### Added:
- `Components`, containing factory methods for the various configuration builders.
- Configuration builder classes in `com.launchdarkly.sdk.android.integrations`: `StreamingDataSourceBuilder`, `PollingDataSourceBuilder`, `EventProcessorBuilder`, `HttpConfigurationBuilder`, `ServiceEndpointsBuilder`.
- It is now possible to entirely disable analytics events, by setting `LDConfig.Builder.events()` to `Components.noEvents()`.
- It is now possible to substitute a test fixture for the analytics events subsystem, by creating a custom implementation of `com.launchdarkly.sdk.android.subsystems.EventProcessor`.
- It is now possible to change the initial delay for reconnecting after a stream connection failure, with `StreamingDataSourceBuilder.initialReconnectDelayMillis()`.

### Deprecated:
(all in `LDConfig.Builder`)
- `pollingIntervalMillis`, `stream`: see `PollingDataSourceBuilder`.
- `backgroundPollingIntervalMillis`: see `PollingDataSourceBuilder` and `StreamingDataSourceBuilder`.
- `allAttributesPrivate`, `diagnosticRecordingIntervalMillis`, `eventsCapacity`, `eventsFlushIntervalMillis`, `inlineUsersInEvents`, `privateAttributes`: see `EventProcessorBuilder`.
- `connectionTimeoutMillis`, `headerTransform`, `useReport`, `wrapperName`, `wrapperVersion`: see `HttpConfigurationBuilder`.
- `streamUri`, `pollUri`, `eventsUri`: See `ServiceEndpointsBuilder`.

## [3.2.3] - 2022-11-16
### Fixed:
- The SDK no longer updates SharedPreferences data during every flag evaluation. It was using this to store summary counters for analytics events; however, the small chance that a subset of summary data could be lost, if the application terminated before events were delivered, was outweighed by the performance cost (and other types of analytics data were not being stored like this anyway). It now uses a simpler in-memory data structure. ([#194](https://github.com/launchdarkly/android-client-sdk/issues/194))

## [3.2.2] - 2022-10-27
### Fixed:
- The SDK was using a connection pool with a keep-alive interval of at least 10 minutes for polling requests. This has been removed and each request now uses a new connection. The keep-alive behavior was not desirable for foreground polling: foreground polling is only done if streaming was explicitly disabled, which would likely be because the application does _not_ want to leave a connection open. And it was of no use for background polling, since the interval for that is at least an hour. One undesirable consequence was that if the 10-minute interval expired after the device had gone to sleep, the small amount of network traffic involved in shutting down the connection could wake the device up again.

## [3.2.1] - 2022-09-28
### Fixed:
- The SDK now detects and cancels any repeating polling task that might have been left over from a previous run of the application. The potential problem was that if an application crashed or otherwise did not shut down cleanly, an "alarm" notification used by the SDK for polling could continue to exist, causing the application to be started again and to keep polling for LaunchDarkly flag data, even though the user intended to shut down the application. With this fix, such an unintended restart could still happen once, but the SDK will detect this condition and stop the notification from continuing to fire. In the future the SDK may be changed more broadly to stop using the AlarmManager API so that such restarts cannot happen at all, but this fix mitigates the problem in the meantime. ([#188](https://github.com/launchdarkly/android-client-sdk/issues/188))

## [3.2.0] - 2022-08-23
The purpose of this release is to introduce a new logging facade, [`com.launchdarkly.logging`](https://github.com/launchdarkly/java-logging), to streamline how logging works in LaunchDarkly Java and Android code.

Previously, the Android SDK always used Timber for logging. This sometimes led to conflicts with an application's separate use of Timber, as described in [#88](https://github.com/launchdarkly/android-client-sdk/issues/88) and [#147](https://github.com/launchdarkly/android-client-sdk/issues/147).

In this release, the default behavior is still to use Timber, but the logging facade can also be configured programmatically to do simple Android logging without Timber, or to forward output to another framework such as `java.util.logging`, or to multiple destinations, or to capture output in memory. In a future major version release, the default behavior may be changed so that the SDK does not require Timber as a dependency.

### Added:
- In [`LDConfig.Builder`](https://javadoc.io/doc/com.launchdarkly/launchdarkly-android-client-sdk/latest/com/launchdarkly/sdk/android/LDConfig.Builder.html), the new methods `logAdapter`, `logLevel`, and `loggerName`, for the new logging capabilities mentioned above.
- New class `LDTimberLogging` for configuring the SDK's Timber integration.
- New class `LDAndroidLogging` for configuring the SDK to use the Android logging API without Timber.

## [3.1.8] - 2022-08-23
### Changed:
- Changed throttling/jitter logic that used `java.util.Random` to use `java.security.SecureRandom`. Even though in this case it is not being used for any cryptographic purpose, but only to produce a pseudo-random delay, static analysis tools may still report every use of `java.util.Random` as a security risk by default. The purpose of this change is simply to avoid such warnings; it has no practical effect on the behavior of the SDK.

### Fixed:
- The map of existing `LDClient` instances was not being cleared after calling `close()`. ([#108](https://github.com/launchdarkly/android-client-sdk/issues/108))
- Fixed a bug that caused an `ExecutorService` object to be unnecessarily created when `flush()` was called.
- The SDK did not correctly persist versioning information when a flag was deleted or archived. In an edge case where flag updates are received out of order, this could cause a deleted flag to appear to be undeleted.
- Setting `baseUri` or `streamUri` to a URI with a trailing slash could cause requests to fail. Now the SDK works correctly regardless of whether these URIs have a trailing slash or not.
- The SDK was including `"anonymous": false` in analytics event data for users where the `anonymous` property had not been set at all. In the current user model, `"anonymous": false` is subtly different from not setting the property (flag rules referencing `anonymous` will only work if it is explicitly set), so the event data should accurately represent this by omitting the property if it was omitted.
- Fixed a bug that could cause a NullPointerException when calling `variation` methods, in an edge case where the SDK received inconsistent data of a kind that the LaunchDarkly services would not normally send (an evaluation result with a value but no variation). This should not be possible in practice, but could happen in test scenarios.

## [3.1.7] - 2022-08-17
### Fixed:
- All Timber logs now use a consistent tag `LaunchDarklySdk`. (Thanks, [audkar](https://github.com/launchdarkly/android-client-sdk/pull/178)!)

## [3.1.6] - 2022-08-01
### Added:
- CI builds now include the [SDK test harness](https://github.com/launchdarkly/sdk-test-harness), a standardized contract test suite that validates the SDK's behavior against simulated LaunchDarkly endpoints.

### Fixed:
- Deadlock between `Throttler` and `ConnectivityManager`. (Thanks, [res0nance](https://github.com/launchdarkly/android-client-sdk/pull/163)!)
- Remove object-level locking in `LDClient` that caused synchronous `init()`s to unnecessarily block other methods, resulting in ANRs.

## [3.1.5] - 2022-05-05

### Fixed
- Prevent `NullPointerException` when event buffer is full and `diagnosticOptOut` is true. (Thanks, [mattyway](https://github.com/launchdarkly/android-client-sdk/pull/160)!)

## [3.1.4] - 2022-03-23
### Fixed
- Removed Android Appcompat dependency.
- Bump version of okhttp from 4.9.1 to 4.9.2.
- Prevent multiple allocations of the DiagnosticEventProcessor.
- Removed application `android:label` attribute from the SDK's manifest. (Thanks, [Exaper](https://github.com/launchdarkly/android-client-sdk/pull/156)!)

## [3.1.3] - 2022-02-24
### Fixed
- Add explicit proguard directives for keeping BroadcastReceivers
- Bump version of git-publish gradle plugin from 3.0.0 to 3.0.1.

## [3.1.2] - 2021-12-17
### Fixed
- Bump version of gson dependency from 2.8.6 to 2.8.9.

## [3.1.1] - 2021-09-03
### Fixed
- Removed the `android:allowBackup` tag from the SDK's `AndroidManifest.xml` file to avoid requiring applications to explicitly replace the tag if given a different value. ([#138](https://github.com/launchdarkly/android-client-sdk/issues/138))
- Changed the SDK's network detection check to consider network transport over a VPN as a connected state. This fixes an issue where the SDK would prevent network requests on a VPN due to considering the network to be unavailable. (Thanks, [lguipeng](https://github.com/launchdarkly/android-client-sdk/pull/137)!)

## [3.1.0] - 2021-08-06
### Added
- The SDK now supports the ability to control the proportion of traffic allocation to an experiment. This works in conjunction with a new platform feature now available to early access customers.

## [3.0.2] - 2021-07-16
### Fixed
- Catch `SecurityException` when thrown on call to `getNetworkCapabilities` used to detect current network availability. ([#129](https://github.com/launchdarkly/android-client-sdk/issues/129))
- Explicitely flag `PendingIntent`s as `FLAG_IMMUTABLE` on Android SDK versions that support doing so. Explicitly specifying mutability is required when targeting Android S+. ([#133](https://github.com/launchdarkly/android-client-sdk/issues/133))

## [3.0.1] - 2021-06-25
### Fixed
- The Android manifest has been updated to explicitly specify the `android:exported` attribute on declared `receiver` elements. This is to meet [new requirements](https://developer.android.com/about/versions/12/behavior-changes-12#exported) in the upcoming Android 12 release.
- Increased the compile-time dependency on `jackson-databind` to 2.10.5.1, due to [CVE-2020-25649](https://nvd.nist.gov/vuln/detail/CVE-2020-25649).
- Update the dependency on the shared [launchdarkly/java-sdk-common](https://github.com/launchdarkly/java-sdk-common) to 1.1.2 to prevent Jackson from showing up as a transitive dependency in tools that inspect module metadata.

## [2.14.2] - 2021-06-02
### Fixed
- Added check to prevent `NullPointerException` in `DiagnosticEventProcessor.stopScheduler` when `LDClient.close` is called before the application is foregrounded when the SDK was initialized in the background. ([#127](https://github.com/launchdarkly/android-client-sdk/issues/127))
- Log message warning that JSON flag was requested as a String has been updated to include the key of the flag requested to assist in discovering which flag is being requested with an unexpected type. ([#116](https://github.com/launchdarkly/android-client-sdk/issues/116))

## [3.0.0] - 2021-05-07
This major version has an accompanying [Migration Guide](https://docs.launchdarkly.com/sdk/client-side/android/migration-2-to-3). Please see the guide for more information on updating to this version of the SDK, as the following is just a summary of the changes.

Usages of `Gson` provided types have been removed from the public API, replacing `JsonElement` with `LDValue` provided by the SDK. `LDValue` can represent the same values as a `JsonElement`, but has a diferent API. See the [API documentation](https://launchdarkly.github.io/android-client-sdk/com/launchdarkly/sdk/LDValue.html) for a detailed reference.
### Added
- `LDConfig.Builder` customization:
  * The `autoAliasingOptOut` configuration option that is used to control the new automatic aliasing behavior of the `identify` method; by setting `autoAliasingOptOut` to true, `identify` will not automatically generate alias events.
  * The `headerTransform` configuration option that supersedes the previous `additionalHeaders` configuration option by allowing fully dynamic updating of headers for requests the SDK makes to the LaunchDarkly service.
  * The `privateAttributes` configuration option that replaces `setPrivateAttributeNames`, specifying the private attributes as vararg `UserAttribute` arguments rather than a `Set<String>`. This allows easily specifying built-in attributes.
- `LDUser(String)` constructor that creates a fully default user.
- New accessors for `LDUser`
  * `getAttribute(UserAttribute)` for programmatically retrieving attribute values.
  * `getCustomAttributes()` for retrieving the currently set custom attributes.
  * `getPrivateAttributes()` for retrieving the attributes set to be private on this user.
  * `isAttributePrivate(UserAttribute)` for checking if a given attribute is private.
  * Getters for all built-in attributes, e.g. `getName()`
- New `LDUser.Builder` methods  overloads for `custom` and `privateCustom`:
  * `custom(String, boolean)` and `privateCustom(String, boolean)` for setting custom attributes to boolean values.
  * `custom(String, int)`, `privateCustom(String, int)`, `custom(String, double)`, and `privateCustom(String, double)` for setting custom attributes to numeric values.
  * `custom(String, LDValue)` and `privateCustom(String, LDValue)` for setting custom attributes to arbitrary data.
- The `UserAttribute` class, which provides a less error-prone way to refer to user attribute names in configuration. This class can also be used to get arbitrary attribute- `LDClient` functionality:
  * The `alias` method that is used to associate two user objects for analytics purposes with an alias event.
  * `jsonValueVariation` and `jsonValueVariationDetail`. These are equivalent to the removed `jsonVariation` and `jsonVariationDetail` other than using `LDValue` instead of `JsonElement`.
  * `trackData(String, LDValue)` which replaces `track(String, JsonElement)`. Other than changing to use `LDValue` the behavior is the same.
  * `trackMetric(String, LDValue, double)` which replaces `track(String, JsonElement, Double)`. This also uses `LDValue` rather than `JsonElement`, and requires a metric value. Otherwise use `trackData`.
- The `LDGson` and `LDJackson` classes, which allow SDK classes like `LDUser` to be easily converted to or from JSON using the popular Gson and Jackson frameworks.
- `EvaluationDetail.fromValue` and `EvaluationDetail.error` factory methods.
- `LDHeaderUpdater` interface for the new `headerTransform` configuration option.
### Fixed
- Fixed an issue where the SDK could log error level messages when attempting to send diagnostic events without an internet connection. The SDK will no longer attempt to send diagnostic events when an internet connection is known to be unavailable, and will not log an error level message if the connection fails. Thanks to @valeriyo for reporting ([#107](https://github.com/launchdarkly/android-client-sdk/issues/107)).
- Fixed an issue where `LDUser` instances created before calling `LDClient.init` without specifying a key would have the key `UNKNOWN_ANDROID` rather than a device unique key.
- Fixed an issue where flags listeners would be informed of changes to unchanged flags whenever the SDK receives an entire flag set (on a new stream connection, a poll request, or any stream updates behind a relay proxy).
- Fixed an issue where a `NullPointerException` is thrown if `LDClient.close()` is called multiple times.
- Improved the proguard/R8 configuration to allow more optimization. Thanks to @valeriyo for requesting ([#106](https://github.com/launchdarkly/android-client-sdk/issues/106))
- Fixed a potential issue where the SDK could cause additional throttling on requests to the backend service when previously throttled requests had been cancelled before completion.
### Changed (requirements/dependencies/build)
- Migrated from using the Android Support Libraries to using AndroidX from Jetpack. Using AndroidX requires the `android.useAndroidX` Android Gradle plugin flag to be set to `true` in your application's `gradle.properties` file. If your application previously set the `android.enableJetifier` Android Gradle plugin flag to `true` in it's `gradle.properties` file soley for the LaunchDarkly SDK, this flag can now be removed. Thanks to everyone who requested this enhancement ([#103](https://github.com/launchdarkly/android-client-sdk/issues/103)).
- The minimum Android API version has been raised from API level 16 (Android 4.1 Jelly Bean) to API level 21 (Android 5.0 Lollipop).
- The SDK no longer has a dependency on Google Play Services. This dependency was only used on pre-21 Android API levels to improve TLS 1.2 compatibility, as the minimum Android version has been raised to 21, the dependency is no longer necessary.
- The SDK is now built with modern Gradle (6.7, Android plugin 4.1.3) and uses Java 8.
### Changed (API)
- Package names have changed: the main SDK classes are now in `com.launchdarkly.sdk` and `com.launchdarkly.sdk.android`.
- All `LDConfig.Builder` setters have been renamed to remove the `set` prefix, e.g. `LDConfig.Builder.setMobileKey` has been renamed to `LDConfig.Builder.mobileKey`.
- `LDClient` API changes:
  * `boolVariation` and `intVariation` no longer use nullable object types for argument and return values, instead using primitive types, e.g. `Boolean boolVariation(String, Boolean)` became `boolean boolVariation(String, boolean)`.
  * `boolVariationDetail` and `intVariationDetail` no longer use nullable object types for argument values, instead using primitive types, e.g. `boolVariationDetail(String, Boolean)` became `boolVariationDetail(String, boolean)`.
  * `allFlags()` now returns `Map<String, LDValue>` rather than `Map<String, ?>`. Rather than the returned `Map` containing `Boolean`, `Float`, and `String` typed objects, with JSON values represented as strings, the `Map` contains `LDValue` typed objects which return the source type (including complex types such as JSON arrays and objects).
- `EvaluationDetail.getVariationIndex()` now returns `int` instead of `Integer`. No variation index is now represented as the constant `EvaluationReason.NO_VARIATION`.
- `EvaluationReason` is now a single concrete class rather than an abstract base class. Usages of the sub-classes can be replaced with the base class.
### Changed (behavioral)
- The default polling domain (configurable with `LDConfig.Builder.pollUri`) has changed from `app.launchdarkly.com` to `clientsdk.launchdarkly.com`.
- The default `eventsUri` used to send events to the service has changed from `https://mobile.launchdarkly.com/mobile` to `https://mobile.launchdarkly.com`. The SDK will now append the expected endpoint path (`/mobile`) to the configured `Uri`, which is more consistent with other LaunchDarkly SDKs.
- For compatibility with older SDK behavior, the `LDClient.stringVariation` method could be used to retrieve JSON flags in a serialized representation. This compatibility behavior has been removed, and attempts to request a JSON valued flag using `stringVariation` will behave the same as other mismatched type variation calls.
- The `LDClient.identify` method will now automatically generate an alias event when switching from an anonymous to a known user. This event associates the two users for analytics purposes as they most likely represent a single person. This behavior can be disabled with the `autoAliasingOptOut` configuration option.
- All log messages are now tagged `LaunchDarklySdk` for easier filtering. Thanks to @valeriyo for the suggestion ([#113](https://github.com/launchdarkly/android-client-sdk/issues/113)).
- `LDUser` now overrides `equals`, `hashCode`, and `toString` with appropriate implementations.
- `LDUser.Builder.country(String)` and `LDUser.Builder.privateCountry(String)` no longer attempt to look up the country from the provided `String` (attempting to match it as an ISO-3166-1 alpha-2, alpha-3 code; or a country name) and set the country to the resultant IOS-3166-1 alpha-2 only if successful. The SDK no longer gives this attribute special behavior, and sets the user's country attribute directly as the provided `String`.
### Removed
- `LDConfig.Builder`:
  * `setBaseUri(Uri)` has been removed. Please use `setPollUri(Uri)` instead.
  * `setAdditionalHeaders(Map<String,String>)` has been removed. Please use `headerTransform(LDHeaderUpdater)` instead.
  * `setPrivateAttributeNames(Set<String>)` has been removed. Please use `privateAttributes(UserAttribute...)` instead.
- `LDUser.Builder`:
  * `country(LDCountryCode)` and `privateCountry(LDCountryCode)` have been removed. Use `country(String)` or `privateCountry(String)` to set the country value on a user.
  * `custom(String, Number)` and `privateCustom(String, Number)` have been removed. Use the `(String, int)` or `(String, double)` overloads instead.
  * `custom(String, Boolean)` and `privateCustom(String, Boolean)` have been removed. Use `custom(String, boolean)` or `privateCustom(String, boolean)` instead.
  * `custom(String, List<String>)`, `LDUser.customString(String, List<String>)`, `LDUser.privateCustomString(String, List<String>)`. Use `custom(String, LDValue)` and `privateCustom(String, LDValue)` instead.
  * `customNumber(String, List<Number>)` and `LDUser.privateCustomNumber(String, List<Number>)`. Use `custom(String, LDValue)` and `privateCustom(String, LDValue)` instead.
- `LDClient`:
  * `floatVariation` and `floatVariationDetail` have been removed. Use `doubleVariation` and `doubleVariationDetail` instead.
  * `jsonVariation` and `jsonVariationDetail` have been removed. Use `jsonValueVariation` and `jsonValueVariationDetail` instead.
  * `track(String, JsonElement)` and `track(String, JsonElement, Double)` overloads have been removed, please use the designated methods `trackData(String, LDValue)` and `trackMetric(String, LDValue, double)` instead.
- The public constructor for `EvaluationDetail` has been hidden. Use the new factory methods `EvaluationDetail.fromValue` and `EvaluationDetail.error` instead.
- The concrete sub-classes of `EvaluationReason` have been removed in favor of making `EvaluationReason` a concrete class. The accessors on the sub-classes have been moved to the base class. Instead of using `instanceOf` to determine the type, use `getKind()`.
- `LDCountryCode` has been removed as no SDK APIs use this class.
- All classes and interfaces in the `com.launchdarkly.sdk.android.flagstore`, `com.launchdarkly.sdk.android.gson`, `com.launchdarkly.sdk.android.response`, and `com.launchdarkly.sdk.android.tls` packages. These classes and interfaces were not intended for external use.
- `Debounce`, `FeatureFlagFetcher`, `SummaryEventSharedPreferences`, `UserSummaryEventSharedPreferences`, and `Util` in `com.launchdarkly.sdk.android`. These deprecated classes and interfaces were not intended for external use.


## [2.14.1] - 2021-01-14
### Fixed
- Before this release, the SDK could cause an uncaught exception on certain Android implementations, when scheduling a future poll request under certain situations. This fix extends a previous fix implemented in the [2.9.1 release](https://github.com/launchdarkly/android-client-sdk/releases/tag/2.9.1) of the SDK, which catches `SecurityException`s thrown by the alarm manager when registering an alarm for the next poll. This `SecurityException` was introduced by Samsung on their Lollipop and later Android implementions, and is thrown when the application has at least 500 existing alarms when registering a new alarm. After recent reports of the alarm manager throwing an `IllegalStateException` rather than a `SecurityException` under the same conditions but different Android implementations, this release broadens the exception handling when scheduling a poll request to safeguard against other exception types.

## [2.14.0] - 2020-12-17
### Added
- Added `LDConfig.Builder.setPollUri` configuration setter that is equivalent to the now deprecated `setBaseUri`.
- Added `LDConfig.getPollUri` configuration getter that is equivalent to the now deprecated `getPollUri`.
- Added `LDClient.doubleVariation` for getting floating point flag values as a `double`. This is preferred over the now deprecated `floatVariation`.
### Fixed
- Improved event summarization logic to avoid potential runtime exceptions. Thanks to @yzheng988 for reporting ([#105](https://github.com/launchdarkly/android-client-sdk/issues/105)).
- Internal throttling logic would sometimes delay new poll or stream connections even when there were no recent connections. This caused switching active user contexts using `identify` to sometimes delay retrieving the most recent flags, and therefore delay the completion of the returned `Future`.
### Changed
- The maximum delay the internal throttling logic could delay a flag request has been reduced to 60 seconds.
### Deprecated
- Deprecated `LDConfig.Builder.setBaseUri` and `LDConfig.getBaseUri`, please use `setPollUri` and `getPollUri` instead.
- Deprecated `LDClient.floatVariation`, please use `doubleVariation` for evaluating flags with floating point values.

## [2.13.0] - 2020-08-07
### Added
- Allow specifying additional headers to be included on HTTP requests to LaunchDarkly services using `LDConfig.Builder.setAdditionalHeaders`. This feature is to enable certain proxy configurations, and is not needed for normal use.

## [2.12.0] - 2020-05-29
### Added
- Added a new configuration option, `maxCachedUsers` to LDConfig. This option allows configuration of the limit to how many users have their flag values cached locally in the device's SharedPreferences.
### Fixed
- Fixed a NPE that could occur when calling a variation methods with a flag key that does not exist locally or is of the wrong type. This issue could only occur if a null fallback value was provided.
- Previously, the SDK manifest required the SET_ALARM permission. This permission was never used, so it has been removed.
### Changed
- For polling requests, the SDK uses OkHttp with a cache configured. Previously the cache directory was set to the main application cache directory. This has been changed to a subdirectory of the application cached directory.
### Deprecated
- Packages that contained only deprecated classes. `flagstore`, `flagstore.sharedprefs`, `gson`, `response`, and `tls`.

## [2.11.0] - 2020-02-28
### Added
- The SDK now periodically sends diagnostic data to LaunchDarkly, describing the version and configuration of the SDK, the Android API SDK version number, and performance statistics. No credentials, Android device IDs, or other identifiable values are included. This behavior can be disabled with `LDConfig.Builder.setDiagnosticOptOut(boolean)` or configured with `LDConfig.Builder.setDiagnosticRecordingInterval(int)`.
- New `LDConfig.Builder` field setters `setWrapperName(String)` and `setWrapperVersion(String)`. These allow a library wrapping the SDK (for example, the React Native SDK) to identify itself for usage data.
### Fixed
- Fixed an issue where in some cases the future associated with an `init` or `identify` call would never complete if the network status or foreground state changed before the future had completed. Also improved test coverage of this behavior.
### Deprecated
- `UserSummaryEventSharedPreferences`, `SummaryEventSharedPreferences`, `FeatureFlagFetcher`, `Util`, and `Debounce`. These classes were only intended for internal SDK use, and will be removed in the next major release to reduce the number of exposed classes.

## [2.10.0] - 2020-01-30
### Added
- The SDK now specifies a uniquely identifiable request header when sending events to LaunchDarkly to ensure that events are only processed once, even if the SDK sends them two times due to a failed initial attempt.
### Deprecated
- All classes in sub-packages, which were only intended for use by the SDK. These classes will be removed in the next major release.
- `LDCountryCode`, as well as `LDUser` setters that took `LDCountryCode` as an argument. The `String` overloads should be used instead, as these will be removed in the next major release. Until that release the additional validation on the country fields will remain, see the Javadoc for more information.

## [2.9.1] - 2020-01-03
### Fixed:
- Removed possibility of fatal `SecurityException` on Samsung devices that would be triggered when the SDK attempted to register an alarm to trigger a future poll when the application process already had 500 alarms registered. This limit is only present on Samsung's versions of Android Lollipop and later. The SDK will now catch this error if it occurs to prevent killing the host application.
- Rarely, the client would deliver its initial "identify" event to LaunchDarkly immediately rather than waiting for the configured flush interval.
- Fixed some malformed Javadoc comments.

## [2.9.0] - 2019-10-25
### Added
- Added support for new LaunchDarkly experimentation features. See `LDClient.track(String, JsonElement, Double)` for recording numeric metrics.
- Substantially improved test coverage for SDK behavior in different Application states (network connectivity and backgrounding).
### Fixed
- The `Future` returned by `LDClient.identify` could not complete as intended for certain connectivity states. When in a background state this could not complete until the next background polling cycle, or never if background polling was disabled.

## [2.8.5] - 2019-07-29
### Added:
- Added a CircleCI badge to the project readme.
### Fixed
- Fix a bug introduced in 2.8.0 that could cause the SDK to enter a bad state where it would no longer connect to the flag stream if `identify()` was called rapidly.
- Reverted an unintentional behavior change introduced in 2.8.0 when `LDClient.init` is given zero as the timeout argument. Before 2.8.0, this would not wait for initialization and return the client immediately. For 2.8.0-2.8.4 this was changed to wait indefinitely for initialization, 2.8.5 restores the earlier behavior.

## [2.8.4] - 2019-06-14
### Fixed
- Deadlock when waiting on main thread for `identify` call.
- ConcurrentModificationException caused by PollingUpdater or ConnectivityReceiver iterating over LDClient instances during initialization.

## [2.8.3] - 2019-05-22
### Added
- Improved error handling on flag store migration.
### Fixed
- ClassCastException when migrating flag store from certain early Android SDK versions.

## [2.8.2] - 2019-05-14
### Fixed
- Thread leak (introduced in 2.8.0) when calling `identify()` on `LDClient` instances.

## [2.8.1] - 2019-05-06
### Changed
- Changed the artifact id from `com.launchdarkly:launchdarkly-android-client` to `com.launchdarkly:launchdarkly-android-client-sdk`
- Changed repository references to use the new URL

There are no other changes in this release. Substituting `com.launchdarkly:launchdarkly-android-client` version 2.8.0 with `com.launchdarkly:launchdarkly-android-client-sdk` version 2.8.1 will not affect functionality.

## [2.8.0] - 2019-05-03
### Added
- LDAllFlagsListener interface that can be registered to a LDClient instance. The SDK will call the listener's onChange method whenever it receives an update, passing a list of any flag keys that were updated to the listener.
- Class ConnectionInformation that contains information about the current status of the SDK. This class can be retrieved with the method getConnectionInformation on a LDClient instance and contains information on the current connectivity mode, timestamps of most recent successful and unsuccessful connections to LaunchDarkly, as well as information on the most recent connection failure (if any).
- LDStatusListener interface that can be registered to a LDClient instance. The interface has a method onConnectionModeChanged that will be called when the LDClient instance transitions between connectivity modes (due to foreground status changes, network connectivity changing, or explicitly being set). The listener also has a method onInternalFailure that will be called when the instance experiences a failure updating it's flag store. The SDK is expected to recover from these failures, the listener is supplied to allow more informed monitoring of any underlying reasons the flag store may be not up to date.
- Demo of new ConnectionInformation functionality to example application.
### Removed
- Internal usages of Guava
### Fixed
- Potential issue that could cause dropping of flag updates if SDK received updates in close succession.
- SDK will no longer assume that the application is started in the foreground, which can be untrue if started by Android's ActivityManager in response to a broadcast.
### Note on future releases

The LaunchDarkly SDK repositories are being renamed for consistency. This repository is now `android-client-sdk` rather than `android-client`.

The artifact id will also change. In the 2.8.0 release, it is still `com.launchdarkly:launchdarkly-android-client`; in all future releases, it will be `com.launchdarkly:launchdarkly-android-client-sdk`. No further updates to the `com.launchdarkly:launchdarkly-android-client` artifact will be published after this release.

## [2.7.0] - 2019-04-02
### Added
- The new configuration option `setEvaluationReasons(true)` causes LaunchDarkly to report information about how each feature flag value was determined; you can access this information with the new client methods `boolVariationDetail`, `stringVariationDetail`, etc. The new methods return an object that contains both the flag value and a "reason" object which will tell you, for instance, if the user was individually targeted for the flag or was matched by one of the flag's rules, or if the flag returned the default value due to an error. For more information, see the SDK Reference Guide on [evaluation reasons](https://docs.launchdarkly.com/sdk/concepts/evaluation-reasons).
- The new client method `getVersion()` returns the version string of the SDK.
### Fixed
- Bug causing `boolVariation`, `intVariation`, and `floatVariation` to always return `null` if `fallback` argument was `null`.
- Potential issue where environment versions for flag updates could compare incorrectly due to floating point coercion.
- Summary events for unknown flags (flags evaluated without any stored value, variation, or version) now include the returned value as intended.
- Inaccurate events caused by data for flag version and variation being unsynchronized with flag value.
- Bug causing some events to be dropped from summary counts due to data race in sending and updating summary events.
- Potential `ClassCastException` crash on some devices due to old version of OkHttp.
- Improved documentation comments throughout.
- Crash on migration when no primary mobile key is specified.
### Removed
- CircleCI V1 config file
## [2.6.0] - 2019-01-22
### Added
- Support for connecting to multiple environments through LDClient interface.
- Security provider hot patch for devices without support for TLSv1.2 (requires Google Play Services to be successful).
### Changed
- Use Timber formatting instead of String concatenation in logging.
- Replace Log calls with Timber in example app.
### Fixed
- Client now parses and stores flag version information correctly in polling mode, allowing these fields to be included in feature and summary events.
- Prevent example app from permanently closing LDClient on first backgrounding.
### Removed
- Support for Android Ice Cream Sandwich, 4.0.3, API 15
## [2.5.4] - 2018-10-25
### Changed
- Outbound HTTP requests now have an authentication scheme token in `Authorization` request headers

### Fixed
- Polling for flag updates might block the main thread
- Refactored map synchronization to avoid crashes in apps build with Gradle 3.3.0-alpha11
- Restored support for network connectivity detection in Android 7.0+ devices

## [2.5.3] - 2018-09-27
### Fixed
- Restored support for initializing `LDClient` on non-main threads

## [2.5.2] - 2018-09-11
### Fixed
- Handling of `Future` returned by `LDClient.init()`
- `Date` HTTP header parsing specifies US locale

## [2.5.1] - 2018-08-13
### Fixed
- `ClassCastException` when calling `variation` methods due to internal storage schema changes between releases 2.3.x and 2.4.0.
- `LDUser.Builder.custom()` no longer returns `UnsupportedOperationException`.

## [2.5.0] - 2018-06-12
### Changed
- `LDClient#identify(LDUser)` now returns a `Future<Void>` so that the app can be notified when flag values have been refreshed for the updated user.

## [2.4.1] - 2018-06-06
### Fixed
- Removed the unused `com.noveogroup.android:android-logger` dependency that prevented some consuming apps from assembling.

## [2.4.0] - 2018-06-03
### Added
- To reduce the network bandwidth used for analytics events, feature request events are now sent as counters rather than individual events, and user details are now sent only at intervals rather than in each event. These behaviors can be modified through the LaunchDarkly UI and with the new configuration option `inlineUsersInEvents`. For more details, read [Data Export](https://docs.launchdarkly.com/home/data-export).
- New method `setInlineUsersInEvents` in `LDConfig`. When `true` includes the full user (excluding private attributes) in analytics `feature` and `custom` events. When `false` includes only the `userKey`. Default: `false`.

### Changed
- Updated `Timber` dependency to version `4.7.0`.

## [2.3.2] - 2018-05-02
### Fixed
- Application class removed from SDK, to avoid conflict with apps

## [2.3.1] - 2018-04-20
### Changed
- SDK logging is now using [Timber](https://github.com/JakeWharton/timber).
- Increased the maximum backoff time for stream reconnection to 1 hour.
- The `setOnline()` method may be throttled if called too frequently.

## [2.3.0] - 2018-03-21
### Added
- Support for enhanced feature streams, facilitating reduced SDK initialization times.

## [2.2.1] - 2018-03-11
### Changed
- The minimum polling interval is now 5 minutes, and the default event publishing interval is 30 seconds.
- HTTP requests are cached in the app's cache directory.
- The SDK now provides a `consumer-proguard-rules.pro` file containing recommended ProGuard rules.
- Due to a Guava dependency update, we recommend a new ProGuard rule which you may need to add if the rules in `consumer-proguard-rules.pro` are not applied automatically:
```
-dontwarn com.google.errorprone.annotations.**
```

### Fixed
- Restored support for Java 1.7.

## [2.2.0] - 2018-01-25
## Added
- Support for specifying [private user attributes](https://docs.launchdarkly.com/home/users/attributes) in order to prevent user attributes from being sent in analytics events back to LaunchDarkly. See the `allAttributesPrivate` and `setPrivateAttributeNames` methods on `LDConfig.Builder` as well as the `privateX` methods on `LDUser.Builder`.

## [2.1.1] - 2017-11-27
### Fixed
- `AndroidManifest.xml` no longer enforces `supportsRtl="true"`
- Client no longer reconnects after detecting an invalidated mobile key
- Client can be initialized outside the main thread. Thanks @jonathanmgrimm!

## [2.1.0] - 2017-10-13
### Added
- `LDConfig.Builder#setUseReport` method to allow switching the request verb from `GET` to `REPORT`. Do not use unless advised by LaunchDarkly.

### Changed
- `LDClient.init` validates that its arguments are non-null.

### Fixed
- Stream connections are closed completely when the app enters background mode.
- Fewer HTTP requests are made to the LaunchDarkly service when feature flags are updated frequently.
- Potential `NullPointerException` in the `variation` methods.
- Removed spurious error when `LDClient` is initialized while the device is offline.

## [2.0.5] - 2017-06-18
### Fixed
- Potential `ConcurrentModificationException` with `LDClient#unregisterFeatureFlagListener`

## [2.0.4] - 2017-05-26
### Changed
- Modified default and minimum background polling intervals.
- Improved http client lifecycle management.
- Improved offline saving of flags when switching users.

## [2.0.3] - 2017-05-18
### Changed
- Even better thread safety in UserManager when removing change listeners.

## [2.0.2] - 2017-05-03
### Changed
- Improved thread safety in UserManager when removing change listeners.
- Streamlined SDK initialization.

## [2.0.1] - 2017-04-28
### Fixed
- The `Future<LDClient>` returned from `LDClient.init` now also waits for the feature flag rules to be retrieved.

## [2.0.0] - 2017-04-10
### Added
- More configurable flag update mechanisms including the ability to disable streaming. See README.md for details.

### Changed
- API BREAKING CHANGE: Guava ListenableFuture is no longer returned from LDClient. Instead we're returning java.util.concurrent.Future.
- Added configurable background polling.
- Improved Json variation handling.
- Improved stream connection lifecycle management.
- Removed SLF4J logger in LDUser.
- Updated suggested Proguard rules for a smaller footprint.

### Fixed
- [Update to Latest version of OkHttp](https://github.com/launchdarkly/android-client-sdk/issues/20)

## [1.0.1] - 2016-11-17
### Added
- Suggested Proguard rules now include keep instructions. This should help with some GSON serialization issues.
- Log when GSON serialization problem may be occuring.

### Changed
- Updated GSON and okhttp-eventsource dependencies

## [1.0.0] - 2016-09-29
- First release of Android SDK.
