#!/usr/bin/env bash

set -e
python3 -m pip install pipenv
pipenv sync
pipenv run pre-commit install
pipenv run pre-commit run --files $(git diff --relative --name-only $DRONE_BRANCH..temp)
if [[ $? -eq 0 ]]; then
    exit 0
else
    exit 1
fi
