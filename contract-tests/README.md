# contract-tests service

A test service that runs inside an Android emulator, that follows the [contract-tests specification](https://github.com/launchdarkly/sdk-test-harness/blob/main/docs/service_spec.md).

## Running locally

You can run the contract tests locally via the `Makefile`, which will:
* start up an emulator
* run the contract-test service APK in the emulator
* forward port 8001 (using `adb forward`) so that we can hit the HTTP server running inside the emulator
  * important: this part is something you can't do with Android Studio, which is why I don't use Android Studio to run the contract tests
* run the [SDK test harness](https://github.com/launchdarkly/sdk-test-harness) against the service

To run the contract tests and all its dependencies at once:
```sh
$ make contract-tests
```

For a pseudo-interactive dev flow, I use [watchexec](https://github.com/watchexec/watchexec):
```sh
# start up the emulator once
$ make start-emulator
# anytime code changes, rebuild and restart the test service
$ watchexec make build-contract-tests start-contract-test-service
# meanwhile, run the test harness whenever you want
$ make run-contract-tests
```

For this to work, there are some prerequisites that must be on your machine:

### Install Android Studio

Even though we won't use it to run the contract tests, installing Android Studio will give us all the ingredients necessary for things to work.

### [Android command-line tools](https://developer.android.com/studio/command-line)

The following programs should already be present thanks to Android Studio, but need to be
available on your `$PATH` to be run at the command line:

* `adb`
* `avdmanager`
* `emulator`
* `sdkmanager`

This is what my `~/.zshrc` looks like, to make that happen:

```sh
export PATH="/Users/alex/Library/Android/sdk/cmdline-tools/latest/bin:$PATH" # avdmanager, sdkmanager
export PATH="/Users/alex/Library/Android/sdk/platform-tools/:$PATH"          # adb
export PATH="/Users/alex/Library/Android/sdk/emulator/:$PATH"                # emulator
```

### System images

The contract-tests scripts expect at least one Android system image (i.e. an image of Android OS that can be run as an emulator)
to be installed. If you've done any Android development already, there should be at least one that works. `start-emulator.sh`
will automatically pick the latest applicable image when creating the emulator.

You can verify by calling `sdkmanager --list_installed`. You're looking for something like:
```
  Path                                                     | Version    | Description                                   | Location
  -------                                                  | -------    | -------                                       | -------
  ...                                                        ...          ...                                             ...
  system-images;android-32;google_apis;arm64-v8a           | 3          | Google APIs ARM 64 v8a System Image           | system-images/android-32/google_apis/arm64-v8a
```
but swap `arm64-v8a` with `x86` if you aren't on an M1 machine.