#!/usr/bin/env bash

# Starts the contract test service in the specified Android emulator.
#
# This script assumes that adb is on the path. After it starts the test app on the specified
# emulator, it will wait to confirm that the process appears to be running on the device, and then
# forward the port to localhost, before exiting.
#
# Optional params:
# LOCAL_PORT: the test service will bind to localhost on this port
# ANDROID_PORT: the Android port, which uniquely identifies a running virtual device.
#               If you have multiple virtual devices running (e.g. via Android Studio),
#               you may have to override this port number to something else. Must be the same
#               value as the ANDROID_PORT from start-emulator.sh.
# CONTRACT_TESTS_APK: the contract-tests APK to install and run on the device.

set -eo pipefail

LOCAL_PORT=${LOCAL_PORT:-8001}
ANDROID_PORT=${ANDROID_PORT:-5554}
SERIAL_NUMBER=emulator-${ANDROID_PORT}
CONTRACT_TESTS_APK=${CONTRACT_TESTS_APK:-contract-tests/build/outputs/apk/debug/contract-tests-debug.apk}

# Install APK to emulator
adb -s ${SERIAL_NUMBER} install -t -r -d ${CONTRACT_TESTS_APK}

# Run APK on emulator
adb -s ${SERIAL_NUMBER} shell am start -n com.launchdarkly.sdktest/.MainActivity -e PORT $LOCAL_PORT

TIMEFORMAT='App started in %R seconds'
APP_PID=""
time {
  while [[ "$APP_PID" == "" ]]; do
    APP_PID=`adb -s ${SERIAL_NUMBER} shell pidof -s com.launchdarkly.sdktest` || APP_PID=""
    if [[ "$APP_PID" == "" ]]; then sleep 1; fi
  done
}

# Forward connections to emulator
adb -s ${SERIAL_NUMBER} forward tcp:$LOCAL_PORT tcp:$LOCAL_PORT

echo "Test service started. Run 'adb logcat' to see live log output"
