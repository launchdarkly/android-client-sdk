SUPPRESSION_FILE=testharness-suppressions.txt
SUPPRESSION_FILE_FDV2=testharness-suppressions-fdv2.txt

TEST_HARNESS_PARAMS_V2= -status-timeout 60
TEST_HARNESS_PARAMS_V3= -status-timeout 60

# The downloader script is version-agnostic -- VERSION selects which harness binary it fetches --
# so both suites use the copy from the v2 branch, which retries the release download.
TEST_HARNESS_DOWNLOADER_URL=https://raw.githubusercontent.com/launchdarkly/sdk-test-harness/v2/downloader/run.sh
TEST_HARNESS_DOWNLOADER=build/sdk-test-harness-downloader.sh

build-contract-tests:
	@cd contract-tests && ../gradlew --no-daemon -s assembleDebug -PdisablePreDex

start-emulator:
	@scripts/start-emulator.sh

start-contract-test-service:
	@scripts/start-test-service.sh

$(TEST_HARNESS_DOWNLOADER):
	@mkdir -p $(dir $@)
	@curl $${GITHUB_TOKEN:+ -H "Authorization: Token $${GITHUB_TOKEN}"} \
      --fail -sS -L --retry 5 --retry-delay 2 \
      -o $@ $(TEST_HARNESS_DOWNLOADER_URL)

# Note that only the last version of the tests have the stop-service-at-end flag set, so the contract test service will be stopped after the tests are run.
run-contract-tests: $(TEST_HARNESS_DOWNLOADER)
	@echo "Running SDK contract test v2..."
	@VERSION=v2 PARAMS="-url http://localhost:8001 -host 10.0.2.2 -debug -skip-from $(SUPPRESSION_FILE) $(TEST_HARNESS_PARAMS_V2)" sh $(TEST_HARNESS_DOWNLOADER)
	@echo "Running SDK contract test v3..."
	@VERSION=v3.1.0-alpha.6 PARAMS="-url http://localhost:8001 -host 10.0.2.2 -debug -stop-service-at-end -skip-from $(SUPPRESSION_FILE_FDV2) $(TEST_HARNESS_PARAMS_V3)" sh $(TEST_HARNESS_DOWNLOADER)

contract-tests: build-contract-tests start-emulator start-contract-test-service run-contract-tests

.PHONY: build-contract-tests start-emulator start-contract-test-service run-contract-tests contract-tests
