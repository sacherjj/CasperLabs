#!/usr/bin/env bash

# Stand up highway network
pushd ../../hack/docker || exit
make node-0/up
make node-1/up
make node-2/up
popd || exit
