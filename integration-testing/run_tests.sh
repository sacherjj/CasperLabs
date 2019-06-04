#!/bin/bash -e

pipenv sync
pipenv run client/CasperClient/install.sh
pipenv run py.test -v "$@"
pipenv run python3 ./docker_cleanup_assurance.py