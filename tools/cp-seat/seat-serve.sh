#!/bin/bash
# CENTINEL CP/stdio test-service launcher for the LaunchDarkly Android client SDK
# (`verify.py conform --kind client --service "<this script>"`).
#
# Boots the REAL SDK (v5.17.0 source, LaunchDarkly) under Robolectric 4.14.1 inside a
# JVM on this Linux aarch64 host, and serves the CP envelope protocol on stdin/stdout.
# All SDK logs go to stderr; stdout carries only CP envelope lines (CP-SPEC 5.1).
#
# JVM_HOST_DIR   sdk clone (this repo)
# JARS           coursier-resolved third-party classpath in classpath-deps.txt
exec /home/termag/termag/tools/jdk-17.0.20.1+1/bin/java -Xmx1g -cp \
"/home/termag/termag/projects/android-client-sdk/seat-classes:/home/termag/termag/projects/android-client-sdk/sdk.jar:/home/termag/termag/tools/aar-extracted/core-classes.jar:/home/termag/termag/tools/aar-extracted/timber-classes.jar:/home/termag/termag/tools/aar-extracted/monitor-classes.jar:/home/termag/.cache/coursier/v1/https/repo1.maven.org/maven2/org/nanohttpd/nanohttpd/2.3.1/nanohttpd-2.3.1.jar:$(cat /home/termag/termag/projects/android-client-sdk/tools/cp-seat/classpath-deps.txt)" \
  Seat SeatRs
