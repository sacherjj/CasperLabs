#!/bin/bash -e

tag=latest
if [[ -n $DRONE_BUILD_NUMBER ]]; then
	# Mind our own business on Drone CI with concurrent jobs
	tag=DRONE-$DRONE_BUILD_NUMBER
fi

export DEFAULT_IMAGE=casperlabs-integration-testing:$tag

sed "s/io.casperlabs\/node:latest/io.casperlabs\/node:$tag/" Dockerfile |\
	docker build -t $DEFAULT_IMAGE -f - .

pipenv run py.test -v "$@"
