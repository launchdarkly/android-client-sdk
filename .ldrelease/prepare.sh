#!/bin/bash

# See: https://github.com/launchdarkly/project-releaser/blob/master/docs/templates/gradle.md

set -eu

if [ -n "${LD_RELEASE_DRY_RUN:-}" ]; then
  android_sonatype_username=""
  android_sonatype_password=""
else
  android_sonatype_username="$(cat "${LD_RELEASE_SECRETS_DIR}/android_sonatype_username")"
  android_sonatype_password="$(cat "${LD_RELEASE_SECRETS_DIR}/android_sonatype_password")"
fi

# temporary hack to allow us to override the hard-coded key ID and password in tests - we should
# move this value into AWS
android_code_signing_key_id=CA2B31DA
android_code_signing_password=$(cat "${LD_RELEASE_SECRETS_DIR}/android_code_signing_passphrase")

mkdir -p ~/.gradle
cat >~/.gradle/gradle.properties <<EOF
signing.keyId = ${android_code_signing_key_id}
signing.password = ${android_code_signing_password}
signing.secretKeyRingFile = ${LD_RELEASE_SECRETS_DIR}/android_code_signing_keyring
ossrhUsername = ${android_sonatype_username}
ossrhPassword = ${android_sonatype_password}
sonatypeUsername = ${android_sonatype_username}
sonatypePassword = ${android_sonatype_password}
nexusUsername = ${android_sonatype_username}
nexusPassword = ${android_sonatype_password}
systemProp.org.gradle.internal.launcher.welcomeMessageEnabled = false
EOF
