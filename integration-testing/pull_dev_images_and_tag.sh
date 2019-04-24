#!/usr/bin/env bash

docker pull casperlabs/node:dev
docker pull casperlabs/execution-engine:dev
docker pull casperlabs/client:dev

docker tag casperlabs/node:dev casperlabs/node:test
docker tag casperlabs/execution-engine:dev casperlabs/execution-engine:test
docker tag casperlabs/client:dev casperlabs/client:test
