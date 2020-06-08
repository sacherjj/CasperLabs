#!/usr/bin/env bash

# Shutdown highway network
cd ../../hack/docker || exit
make down
make node-0/down
make node-1/down
make node-2/down
