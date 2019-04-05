#!/bin/bash -e
mkdir /tmp/DRONE-${DRONE_BUILD_NUMBER}
cp -r resources /tmp/DRONE-${DRONE_BUILD_NUMBER}
pipenv run py.test -v "$@"