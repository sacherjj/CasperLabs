#!/usr/bin/env bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

cd ${DIR}

pushd ../../
make docker-build-all
popd

pushd ../../hack/docker
make node-0 up
popd

casperlabs_client