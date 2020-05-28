#!/usr/bin/env bash

# Shutdown highway network
pushd ../../hack/docker || exit
make node-0/down
make node-1/down
make node-2/down
popd || exit
