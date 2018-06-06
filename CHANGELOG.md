# Change log


All notable changes to the LaunchDarkly Android SDK will be documented in this file. This project adheres to [Semantic Versioning](http://semver.org).

## [2.4.1] - 2018-06-06
### Fixed
- Removed the unused `com.noveogroup.android:android-logger` dependency that prevented some consuming apps from assembling.

## [2.4.0] - 2018-06-03
### Added
- To reduce the network bandwidth used for analytics events, feature request events are now sent as counters rather than individual events, and user details are now sent only at intervals rather than in each event. These behaviors can be modified through the LaunchDarkly UI and with the new configuration option `inlineUsersInEvents`. For more details, see [Analytics Data Stream Reference](https://docs.launchdarkly.com/v2.0/docs/analytics-data-stream-reference).
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
- Support for specifying [private user attributes](https://docs.launchdarkly.com/docs/private-user-attributes) in order to prevent user attributes from being sent in analytics events back to LaunchDarkly. See the `allAttributesPrivate` and `setPrivateAttributeNames` methods on `LDConfig.Builder` as well as the `privateX` methods on `LDUser.Builder`.

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
- [Update to Latest version of OkHttp](https://github.com/launchdarkly/android-client/issues/20)

## [1.0.1] - 2016-11-17
### Added
- Suggested Proguard rules now include keep instructions. This should help with some GSON serialization issues. 
- Log when GSON serialization problem may be occuring.

### Changed
- Updated GSON and okhttp-eventsource dependencies

## [1.0.0] - 2016-09-29
- First release of Android SDK.
