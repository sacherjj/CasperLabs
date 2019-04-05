#!/bin/bash -e

cp -r resources /tmp
pipenv run py.test -v "$@"