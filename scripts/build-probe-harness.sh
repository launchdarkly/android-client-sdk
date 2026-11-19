#!/usr/bin/env bash

# Builds the v2 test harness from the v2 branch with two open harness pull requests applied.
# The result is /tmp/probe-harness/sdk-test-harness.

set -euo pipefail

BASE_SHA=c6064e732bc8849d481c04c49d5d4e9bfbd1790f
PR_SHAS="d7e49b00361030a5ac46d896b98fbc241d35e754 71dd4b2477f6e6fd9b1f950c56b2195ec97c9a54"
DIR=/tmp/probe-harness

rm -rf "$DIR"
git clone -q --filter=blob:none https://github.com/launchdarkly/sdk-test-harness.git "$DIR"
cd "$DIR"
git fetch -q origin +refs/pull/667/head:refs/probe/667 +refs/pull/668/head:refs/probe/668
git checkout -q "$BASE_SHA"

for sha in $PR_SHAS; do
  git cat-file -e "$sha^{commit}"
  git diff "$(git merge-base "$BASE_SHA" "$sha")" "$sha" | git apply
done
git diff --stat

GO_VERSION=$(sed -n 's/^go //p' go.mod)
curl -sSfL "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" | tar -C /tmp -xzf -
GOTOOLCHAIN=local /tmp/go/bin/go build -o "$DIR/sdk-test-harness" .
echo "Built $DIR/sdk-test-harness from $BASE_SHA with $PR_SHAS"
