TEST_HARNESS_PARAMS= -skip "events/disabling"
# can add temporary test skips etc. here
# Currently we are skipping the "events/disabling" tests because the Android SDK has no way to
# disable events. That wasn't an issue earlier because the "events/disabling" tests were getting
# automatically skipped by sdk-test-harness for a different reason: they rely on the
# ServiceEndpoints API, which the Android SDK didn't previously support.

build-contract-tests:
	@cd contract-tests && ../gradlew --no-daemon -s assembleDebug -PdisablePreDex

start-emulator:
	@scripts/start-emulator.sh

start-contract-test-service:
	@scripts/start-test-service.sh

run-contract-tests:
	@curl -s https://raw.githubusercontent.com/launchdarkly/sdk-test-harness/v1.0.0/downloader/run.sh \
      | VERSION=v2 PARAMS="-url http://localhost:8001 -host 10.0.2.2 -skip-from testharness-suppressions.txt -debug $(TEST_HARNESS_PARAMS)" sh

contract-tests: build-contract-tests start-emulator start-contract-test-service run-contract-tests

.PHONY: build-contract-tests start-emulator start-contract-test-service run-contract-tests contract-tests
