#!/usr/bin/env bash

# Builds the v2 test harness from an unreleased harness branch.
# The result is /tmp/probe-harness/sdk-test-harness.

set -euo pipefail

BRANCH=rlamb/summary-dates-clock-offset
EXPECTED_SHA=e987cdffc83a98b959783df79276cff9629d8a7c
DIR=/tmp/probe-harness

rm -rf "$DIR"
git clone -q --filter=blob:none -b "$BRANCH" https://github.com/launchdarkly/sdk-test-harness.git "$DIR"
cd "$DIR"
test "$(git rev-parse HEAD)" = "$EXPECTED_SHA"
git log --oneline -3

GO_VERSION=$(sed -n 's/^go //p' go.mod)
curl -sSfL "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" | tar -C /tmp -xzf -
GOTOOLCHAIN=local /tmp/go/bin/go build -o "$DIR/sdk-test-harness" .
echo "Built $DIR/sdk-test-harness from $BRANCH at $EXPECTED_SHA"
