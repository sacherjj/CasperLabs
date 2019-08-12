#!/usr/bin/env bash

set -e
python3 -m pip install pipenv
pipenv sync
pipenv run pre-commit install
EXCLUDE_PATTERN="ignore_test_.*.py\|.*_pb2.py\|.*_pb2_grpc.py\|CasperLabsClient\/build\/lib\/\|.eggs"
pipenv run pre-commit run --files $(find . -type f -name \*.py | grep -v $EXCLUDE_PATTERN)
if [[ $? -eq 0 ]]; then
    exit 0
else
    exit 1
fi
