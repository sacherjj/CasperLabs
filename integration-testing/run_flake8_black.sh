#!/usr/bin/env bash

set -e
python3 -m pip install pipenv
pipenv sync
pipenv run pre-commit install
pipenv run pre-commit run --files $(find . -type f -name \*.py | grep -v "ignore_test_.*.py\|.*_pb2.py\|.*_pb2_grpc.py\|CasperClient\/build\/lib\/")
if [[ $? -eq 0 ]]; then
    exit 0
else
    exit 1
fi
