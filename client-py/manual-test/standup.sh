#!/usr/bin/env bash

# build contracts to use
pushd ../../execution-engine || exit
make build-contracts-rs
popd

# Stand up highway network
cd ../../hack/docker || exit
make up-all
