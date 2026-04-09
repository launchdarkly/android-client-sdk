SUPPRESSION_FILE=testharness-suppressions.txt
SUPPRESSION_FILE_FDV2=testharness-suppressions-fdv2.txt

TEST_HARNESS_PARAMS_V2= -status-timeout 60
TEST_HARNESS_PARAMS_V3= -status-timeout 60

build-contract-tests:
	@cd contract-tests && ../gradlew --no-daemon -s assembleDebug -PdisablePreDex

start-emulator:
	@scripts/start-emulator.sh

start-contract-test-service:
	@scripts/start-test-service.sh

# Note that only the last version of the tests have the stop-service-at-end flag set, so the contract test service will be stopped after the tests are run.
run-contract-tests:
	@echo "Running SDK contract test v2..."
	@curl $${GITHUB_TOKEN:+ -H "Authorization: Token $${GITHUB_TOKEN}"} \
      -s https://raw.githubusercontent.com/launchdarkly/sdk-test-harness/v2/downloader/run.sh \
      | VERSION=v2 PARAMS="-url http://localhost:8001 -host 10.0.2.2 -debug -stop-service-at-end -skip-from $(SUPPRESSION_FILE) $(TEST_HARNESS_PARAMS_V2)" sh

# Uncomment this, update v3 version, and replace existing run-contract-tests once sdk-test-harness releases a version that includes FDv2 client contract tests.
#
# run-contract-tests:
# 	@echo "Running SDK contract test v2..."
# 	@curl $${GITHUB_TOKEN:+ -H "Authorization: Token $${GITHUB_TOKEN}"} \
#       -s https://raw.githubusercontent.com/launchdarkly/sdk-test-harness/v2/downloader/run.sh \
#       | VERSION=v2 PARAMS="-url http://localhost:8001 -host 10.0.2.2 -debug -skip-from $(SUPPRESSION_FILE) $(TEST_HARNESS_PARAMS_V2)" sh
# 	@echo "Running SDK contract test v3..."
# 	@curl $${GITHUB_TOKEN:+ -H "Authorization: Token $${GITHUB_TOKEN}"} \
#       -s https://raw.githubusercontent.com/launchdarkly/sdk-test-harness/v3.0.0-alpha.4/downloader/run.sh \
#       | VERSION=v3.0.0-alpha.4 PARAMS="-url http://localhost:8001 -host 10.0.2.2 -debug -stop-service-at-end -skip-from $(SUPPRESSION_FILE_FDV2) $(TEST_HARNESS_PARAMS_V3)" sh

contract-tests: build-contract-tests start-emulator start-contract-test-service run-contract-tests

.PHONY: build-contract-tests start-emulator start-contract-test-service run-contract-tests contract-tests
