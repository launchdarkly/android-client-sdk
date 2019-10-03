#!/bin/bash
# Copyright (c) Facebook, Inc. and its affiliates.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
#
# Extracted from https://github.com/facebook/react-native/blob/master/scripts/android-setup.sh

# GW 2019-10-03: Retrieved from https://github.com/facebookincubator/spectrum/commit/019555535d042e8e0154a42475f76f2aaada0c6f
# A copy of the MIT license text at https://github.com/facebookincubator/spectrum/blob/master/LICENSE has been reproduced in this directory.

echo "Waiting for Android Virtual Device to finish booting..."
bootanim=""
export PATH=$(dirname $(dirname $(command -v android)))/platform-tools:$PATH
until [[ "$bootanim" =~ "stopped" ]]; do
  sleep 5
  bootanim=$(adb -e shell getprop init.svc.bootanim 2>&1)
  echo "boot animation status=$bootanim"
done
echo "Android Virtual Device is ready."
