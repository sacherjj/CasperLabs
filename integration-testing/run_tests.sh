#!/bin/bash -e

pipenv sync
pipenv run client/CasperClient/install.sh
pipenv run py.test -v "$@"
