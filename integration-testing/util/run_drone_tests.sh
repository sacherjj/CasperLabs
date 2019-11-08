#!/bin/bash -e

# This requires drone installed:
#  curl -L https://github.com/drone/drone-cli/releases/download/v1.1.0/drone_linux_amd64.tar.gz | tar zx
#  sudo install -t /usr/local/bin drone

# This is done in the drone exec, but silent and just looks like it is hanging, while downloading almsot 4GB.
# Doing this prior makes that step very fast.
docker pull casperlabs/buildenv:latest

# Early test for drone being installed.
drone -v foo >/dev/null 2>&1 || { echo >&2 "I require drone but it's not installed.  Aborting.  See comments at top of script for installing."; exit 1; }

cd ../..
make docker-build-all
make docker-build/grpcwebproxy

docker pull selenium/standalone-chrome:3.141.59-xenon

docker tag casperlabs/node:test casperlabs/node:test-DRONE-1
docker tag casperlabs/execution-engine:test casperlabs/execution-engine:test-DRONE-1
docker tag casperlabs/client:test casperlabs/client:test-DRONE-1
docker tag casperlabs/integration-testing:test casperlabs/integration-testing:test-DRONE-1
docker tag casperlabs/explorer:test casperlabs/explorer:test-DRONE-1
docker tag casperlabs/grpcwebproxy:test casperlabs/grpcwebproxy:test-DRONE-1

drone exec --include 'run-integration-tests' --branch trying --trusted --env-file integration-testing/util/env_file  2>&1 | tee integration-testing/util/drone_run.log
