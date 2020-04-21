#!/bin/bash -e

PYTEST_ARGS="-vv -ra "

echo "Unique run num in run_tests.sh: ${UNIQUE_RUN_NUM}"

# $TAG_NAME should have value of "DRONE-####" from docker_run_tests.sh in CI
if [[ -n $TAG_NAME ]] && [[ "$TAG_NAME" != "latest" ]]; then
    # We only want to limit maxfail in CI
    PYTEST_ARGS="${PYTEST_ARGS} --maxfail=3 --tb=short"
else
    # We want to compile contracts if run locally
    ./build_contracts.sh || echo "build_contracts.sh Failed. Only OK if running locally."
fi

if [[ "$TEST_RUN_ARGS" == "" ]]; then
    TEST_RUN_ARGS=$@
fi



pip install pipenv
pipenv sync
pipenv run client/CasperLabsClient/build.sh
pipenv run client/CasperLabsClient/install.sh

# Cannot get the quoted -k arguments passed in via variable replacement.
# So I'm doing the full line via case.
case ${UNIQUE_RUN_NUM} in
  [0])
  pipenv run pytest -k "R0" ${PYTEST_ARGS} ${TEST_RUN_ARGS}
  ;;
  [1])
  pipenv run pytest -k "R1" ${PYTEST_ARGS} ${TEST_RUN_ARGS}
  ;;
  [2])
  pipenv run pytest -k "R2" ${PYTEST_ARGS} ${TEST_RUN_ARGS}
  ;;
  [3])
  pipenv run pytest -k "not R0 and not R1 and not R2" ${PYTEST_ARGS} ${TEST_RUN_ARGS}
  ;;
  [5])
  # Using 5 as special code for dev CI to only run one test
  pipenv run pytest -k "test_clarity" ${PYTEST_ARGS} ${TEST_RUN_ARGS}
  ;;
  *)
  pipenv run pytest ${PYTEST_ARGS} ${TEST_RUN_ARGS}
  ;;
esac
