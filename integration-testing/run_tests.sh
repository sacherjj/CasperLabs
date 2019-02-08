#!/bin/bash -e

tag=latest
if [[ -n $DRONE_BUILD_NUMBER ]]; then
	# Mind our own business on Drone CI with concurrent jobs
	tag=DRONE-$DRONE_BUILD_NUMBER
fi

export DEFAULT_IMAGE=casperlabs-integration-testing:$tag

cp Dockerfile ..
cp .dockerignore ..
sed "s/io.casperlabs\/node:latest/io.casperlabs\/node:$tag/" Dockerfile |\
	docker build -t $DEFAULT_IMAGE -f - ..
rm ../Dockerfile
rm ../.dockerignore
pipenv run py.test -v "$@"