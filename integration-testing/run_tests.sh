#!/bin/bash -e

#tag=test
#if [[ -n $DRONE_BUILD_NUMBER ]]; then
	# Mind our own business on Drone CI with concurrent jobs
#	tag=DRONE-$DRONE_BUILD_NUMBER
#fi

#export DEFAULT_IMAGE=casperlabs\/node:$tag
# The above is duplicated in casperlabsnode.py

cp -r resources /tmp
pipenv run py.test -v "$@"