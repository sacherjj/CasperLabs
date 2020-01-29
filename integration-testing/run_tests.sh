#!/bin/bash -e

PYTEST_ARGS=" -vv -ra"

echo "Unique run num in run_tests.sh: ${UNIQUE_RUN_NUM}"

# $TAG_NAME should have value of "DRONE-####" from docker_run_tests.sh in CI
if [[ -n $TAG_NAME ]] && [[ "$TAG_NAME" != "latest" ]]; then
    # We only want to limit maxfail in CI
    PYTEST_ARGS="${PYTEST_ARGS} --maxfail=3 --tb=short"
else
    # We want to compile contracts if run locally
    ./build_contracts.sh || true
fi

if [[ "${TEST_RUN_ARGS}" == "" ]]; then
    TEST_RUN_ARGS=$@
fi



TEST_FILTER=(-k "\"R1\"")
echo "Test Filter: " "${TEST_FILTER[@]}"

pip install pipenv
pipenv sync
pipenv run ./client/CasperLabsClient/install.sh

# Cannot get the quoted -k arguments passed in via variable replacement.
# I would love to do that if someone can show me where I'm messing up.
case ${UNIQUE_RUN_NUM} in
  [0])
  pipenv run pytest -k "R0" ${PYTEST_ARGS} ${TEST_RUN_ARGS}  ;;
  [1])
  pipenv run pytest -k "R1" ${PYTEST_ARGS} ${TEST_RUN_ARGS}
  ;;
  [2])
  pipenv run pytest -k "not R0 or R1" ${PYTEST_ARGS} ${TEST_RUN_ARGS}
  ;;
  *)
  pipenv run pytest ${PYTEST_ARGS} ${TEST_RUN_ARGS}
  ;;
esac
