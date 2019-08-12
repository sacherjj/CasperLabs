#!/bin/bash -e

PYTEST_ARGS="-vv -ra "

# $TAG_NAME should have value of "DRONE-####" from docker_run_tests.sh in CI
if [[ -n $TAG_NAME ]] && [[ "$TAG_NAME" != "test" ]]; then
    # We only want to limit maxfail in CI
    PYTEST_ARGS="${PYTEST_ARGS} --maxfail=3 --tb=short"
elif [[ "$TAG_NAME" != "test" ]]; then
    # We want to compile contracts if run locally
    # But we can't if tag is "test" as we can't compile Rust in local docker.
    ./contracts/build_contracts.sh
fi

if [[ "$TEST_RUN_ARGS" == "" ]]; then
    TEST_RUN_ARGS=$@
fi


pip install pipenv
pipenv sync
pipenv run client/CasperLabsClient/install.sh
pipenv run pytest ${PYTEST_ARGS} $TEST_RUN_ARGS
pipenv run python3 ./docker_cleanup_assurance.py
