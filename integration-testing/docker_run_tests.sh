#!/usr/bin/env bash

docker run -v /tmp:/tmp -v /var/run/docker.sock:/var/run/docker.sock --rm=true casperlabs/integration-testing:test