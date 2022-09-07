TEST_HARNESS_PARAMS= \
	-skip events/custom/basic/inline \
	-skip events/user/inlineUsers=true

build-contract-tests:
	@cd contract-tests && ../gradlew --no-daemon -s assembleDebug -PdisablePreDex

start-emulator:
	@scripts/start-emulator.sh

start-contract-test-service:
	@scripts/start-test-service.sh

run-contract-tests:
	@curl -s https://raw.githubusercontent.com/launchdarkly/sdk-test-harness/v1.0.0/downloader/run.sh \
      | VERSION=v1 PARAMS="-url http://localhost:8001 -host 10.0.2.2 -skip-from testharness-suppressions.txt -debug $(TEST_HARNESS_PARAMS)" sh

contract-tests: build-contract-tests start-emulator start-contract-test-service run-contract-tests

.PHONY: build-contract-tests start-emulator start-contract-test-service run-contract-tests contract-tests
