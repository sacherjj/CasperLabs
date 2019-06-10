#!/bin/bash -e

PYTEST_ARGS=""

# We only want to limit maxfail in CI
# $TAG_NAME should have value of "DRONE-####" from docker_run_tests.sh in CI
if [[ -n $TAG_NAME ]] && [[ "$TAG_NAME" != "test" ]]; then
    PYTEST_ARGS="--maxfail=3"
fi

pipenv sync
pipenv run client/CasperClient/install.sh
pipenv run py.test ${PYTEST_ARGS} -v "$@"
pipenv run python3 ./docker_cleanup_assurance.py
