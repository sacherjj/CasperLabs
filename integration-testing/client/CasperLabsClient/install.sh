#!/usr/bin/env bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

cd ${DIR}

python setup.py install
# Since we have overridden 'install' command in setup.py and setuptools has
# a bug which does not install deps once you override `install` command
# So we are manually installing deps here.
pip install -r requirements.txt
#python ${DIR}/tests/test_casperlabs_client.py
