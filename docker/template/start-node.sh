#!/usr/bin/env sh

set -e

if [ "$HOSTNAME" = "$BOOTSTRAP_HOSTNAME" ]; then
    # Start the bootstrap node with a fixed key to get a deterministic ID.
    exec ./bin/casperlabs-node run -s \
        --server-host $HOSTNAME \
        --tls-certificate $HOME/.casperlabs/bootstrap/node.certificate.pem \
        --tls-key $HOME/.casperlabs/bootstrap/node.key.pem \
        --casper-validator-private-key $CL_VALIDATOR_PRIVATE_KEY \
        --casper-validator-public-key $CL_VALIDATOR_PUBLIC_KEY \
        --grpc-socket $CL_GRPC_SOCKET \
        --metrics-prometheus
else
    # Connect to the bootstrap node.
    BOOTSTRAP_ID=$(cat $HOME/.casperlabs/bootstrap/node-id)
    exec ./bin/casperlabs-node run \
        --server-host $HOSTNAME \
        --server-bootstrap "casperlabs://${BOOTSTRAP_ID}@${BOOTSTRAP_HOSTNAME}?protocol=40400&discovery=40404" \
        --casper-validator-private-key $CL_VALIDATOR_PRIVATE_KEY \
        --casper-validator-public-key $CL_VALIDATOR_PUBLIC_KEY \
        --grpc-socket $CL_GRPC_SOCKET \
        --metrics-prometheus
fi
