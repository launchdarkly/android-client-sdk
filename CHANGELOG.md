# Change log


All notable changes to the LaunchDarkly Android SDK will be documented in this file. This project adheres to [Semantic Versioning](http://semver.org).

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
