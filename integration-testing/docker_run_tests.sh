#!/usr/bin/env bash

if [[ -n $DOCKER_BUILD_NUMBER ]]; then
    docker run -v /tmp:/tmp -v /var/run/docker.sock:/var/run/docker.sock --rm=true -e "DOCKER_BUILD_NUMBER=$DOCKER_BUILD_NUMBER" casperlabs/integration-testing:test
else
    docker run -v /tmp:/tmp -v /var/run/docker.sock:/var/run/docker.sock --rm=true casperlabs/integration-testing:test
fi
