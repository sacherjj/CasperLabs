#!/usr/bin/env bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

cd ${DIR}

python run_codegen.py
python setup.py install
python ${DIR}/tests/test_casper_client.py
