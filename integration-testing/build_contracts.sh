#!/usr/bin/env bash

set -o errexit

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
EE_DIR="${DIR}/../execution-engine"

pushd ${EE_DIR}

make setup build-integration-contracts

popd
