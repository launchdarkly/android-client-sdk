#!/usr/bin/env bash

# Starts an Android emulator to run the contract tests in.
#
# This script assumes that adb, avdmanager, and emulator are on the path. It
# will remember the PID of the last emulator created by this script, and try to kill
# it automatically before starting another one.
#
# This script assumes that you have at least one Android package installed (probably from
# Android Studio), and will automatically pick the latest one to run as an emulator.
#
# Optional params:
# ANDROID_PORT: the Android port, which uniquely identifies a running virtual device.
#               If you have another virtual device running (e.g. via Android Studio),
#               you may have to override this port number to something else.
# AVD_NAME: the name of the AVD to create and run.

set -eo pipefail

ANDROID_PORT=${ANDROID_PORT:-5554}
SERIAL_NUMBER=emulator-${ANDROID_PORT}
AVD_NAME=${AVD_NAME:-launchdarkly-contract-test-emulator}
EMULATOR_PID=.emulator-pid
if [ -z "${AVD_IMAGE}" ]; then
  echo "Using the latest installed Android image"
  AVD_IMAGE=$(sdkmanager --list_installed | awk '{ print $1 }' | grep system-images | sort -k 2 -t ';' | head -1)
  if [ -z "${AVD_IMAGE}" ]; then
    echo "No emulator images installed locally that meet criteria; try overriding AVD_IMAGE variable"
    exit 1
  fi
  echo "Picked ${AVD_IMAGE}"
fi

if [ -f ${EMULATOR_PID} ]; then
  if ps $(cat ${EMULATOR_PID}); then
    echo "Killing previous emulator"
    kill -9 $(cat ${EMULATOR_PID})
  fi
  rm ${EMULATOR_PID}
fi

# Create or recreate the AVD.
echo no | avdmanager create avd -n ${AVD_NAME} -f -k "${AVD_IMAGE}"

# According to https://stackoverflow.com/questions/37063267/high-cpu-usage-with-android-emulator-qemu-system-i386-exe
# the emulator's CPU usage can be greatly reduced by modifying the following properties of the AVD. Not sure if
# that's entirely true - CPU usage seems to go up and down a lot with no apparent cause - but it's worth a try.
AVD_CONFIG=~/.android/avd/${AVD_NAME}.avd/config.ini
echo "hw.audioInput=no" >>${AVD_CONFIG}
echo "hw.audioOutput=no" >>${AVD_CONFIG}

# Start emulator in background
# Note that for some reason things do not work properly in Ubuntu unless we cd to the directory where the emulator is
EMULATOR_PARAMS="-avd ${AVD_NAME} -port ${ANDROID_PORT} -no-audio -no-snapshot"
emulator $EMULATOR_PARAMS &
EMULATOR_PID=$!

echo $EMULATOR_PID > .emulator-pid

# If something goes wrong for the remainder of this script, tear down the emulator automatically
trap "kill -9 $EMULATOR_PID" SIGINT SIGTERM ERR

# Wait for emulator

TIMEFORMAT='Emulator started in %R seconds'

bootanim=""
failcounter=0
timeout_in_sec=360

echo -n "Waiting for emulator to start"

time {
  until [[ "$bootanim" =~ "stopped" ]]; do
    bootanim=`adb -s ${SERIAL_NUMBER} shell getprop init.svc.bootanim 2>&1 &`
    if [[ "$bootanim" =~ "device not found" || "$bootanim" =~ "device offline"
      || "$bootanim" =~ "running" ]]; then
      let "failcounter += 1"
      echo -n "."
      if [[ $failcounter -gt $timeout_in_sec ]]; then
        echo "Timeout ($timeout_in_sec seconds) reached; failed to start emulator"
        TIMEFORMAT=
        exit 1
      fi
    fi
    sleep 2
  done
}

# Remove lock screen
adb -s ${SERIAL_NUMBER} shell input keyevent 82
