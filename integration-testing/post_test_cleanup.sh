#!/bin/bash -e

pip install pipenv
pipenv sync
pipenv run python3 casperlabs_local_net/docker_cleanup_assurance.py
