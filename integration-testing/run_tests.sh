#!/bin/bash -e

pipenv run client/CasperClient/install.sh
pipenv run py.test -v "$@"
