#!/usr/bin/env bash
set -e

PYTHON=python3.7

$PYTHON -m pip install pipenv
$PYTHON -m pipenv sync
$PYTHON -m pipenv run pre-commit install
EXCLUDE_PATTERN="ignore_test_.*.py\|.*_pb2.py\|.*_pb2_grpc.py\|CasperLabsClient\/build\/lib\/\|.eggs"
$PYTHON -m pipenv run pre-commit run --files $(find . -type f -name \*.py | grep -v $EXCLUDE_PATTERN) || (echo pre-commit run failed:; cat /root/.cache/pre-commit/pre-commit.log)
