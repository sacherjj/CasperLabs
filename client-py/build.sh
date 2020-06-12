#!/usr/bin/env bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

cd ${DIR}

rm -rf dist/*

# Verify .wasm has been generated
count=`ls -1 casperlabs_client/*.wasm 2>/dev/null | wc -l`
if [ $count == 0 ]
then
  echo "--------------------------------------------"
  echo "REQUIRED FILES MISSING"
  echo ".wasm files have not been built"
  echo "Run with 'make build-python-client' in root"
  echo "--------------------------------------------"
  exit 1
fi

pipenv sync
pipenv run python setup.py sdist
