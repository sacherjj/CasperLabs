#!/bin/bash -e

PYTEST_ARGS="-vv -ra "

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


case ${UNIQUE_RUN_NUM} in
  [0])
  TEST_FILTER="--unique_run_num 0 -k \"R0\""
  ;;
  [1])
  TEST_FILTER="--unique_run_num 1 -k \"R1\""
  ;;
  [2])
  TEST_FILTER="--unique_run_num 2 -k \"not R0 or R1\""
  ;;
  *)
  TEST_FILTER=""
  ;;
esac

echo "Test Filter: ${TEST_FILTER}"

pip install pipenv
pipenv sync
pipenv run client/CasperLabsClient/install.sh
pipenv run pytest ${PYTEST_ARGS} "${TEST_RUN_ARGS}" "${TEST_FILTER}"
