#!/usr/bin/env sh

set -e

if [ "$HOSTNAME" = "$BOOTSTRAP_HOSTNAME" ]; then
    # Start the bootstrap node with a fixed key to get a deterministic ID.
    exec ./bin/casperlabs-node run -s \
        --server-host $HOSTNAME \
        --grpc-socket $CL_GRPC_SOCKET \
        --metrics-prometheus
else
    # Connect to the bootstrap node.
    BOOTSTRAP_ID=$(cat $HOME/.casperlabs/bootstrap/node-id)
    exec ./bin/casperlabs-node run \
        --server-host $HOSTNAME \
        --server-bootstrap "casperlabs://${BOOTSTRAP_ID}@${BOOTSTRAP_HOSTNAME}?protocol=40400&discovery=40404" \
        --grpc-socket $CL_GRPC_SOCKET \
        --metrics-prometheus
fi
