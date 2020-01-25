#!/bin/bash -e

PYTEST_ARGS="-vv -ra "

# $TAG_NAME should have value of "DRONE-####" from docker_run_tests.sh in CI
if [[ -n $TAG_NAME ]] && [[ "$TAG_NAME" != "latest" ]]; then
    # We only want to limit maxfail in CI
    PYTEST_ARGS="${PYTEST_ARGS} --maxfail=3 --tb=short"
else
    # We want to compile contracts if run locally
    ./build_contracts.sh
fi

if [[ "$TEST_RUN_ARGS" == "" ]]; then
    TEST_RUN_ARGS=$@
fi


pip install pipenv
pipenv sync
pipenv run client/CasperLabsClient/install.sh
pipenv run pytest ${PYTEST_ARGS} $TEST_RUN_ARGS
