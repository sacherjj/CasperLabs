#!/usr/bin/env bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

cd ${DIR}

rm -rf dist/*
python setup.py sdist
pip install dist/casperlabs_client-*
