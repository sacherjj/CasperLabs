#!/usr/bin/env sh

set -e

if [ "$CL_SERVER_STANDALONE" = "true" ]; then
	# Start the bootstrap node with a fixed key to get a deterministic ID.
	exec ./bin/node run -s \
	    --server-host $HOSTNAME \
		--tls-certificate $HOME/.casperlabs/bootstrap/node.certificate.pem \
		--tls-key $HOME/.casperlabs/bootstrap/node.key.pem \
		--casper-validator-private-key $CL_VALIDATOR_PRIVATE_KEY \
        --casper-validator-public-key $CL_VALIDATOR_PUBLIC_KEY \
        --grpc-socket $CL_GRPC_SOCKET
else
	exec ./bin/node run \
	    --server-host $HOSTNAME \
		--server-bootstrap $CL_SERVER_BOOTSTRAP \
		--casper-validator-private-key $CL_VALIDATOR_PRIVATE_KEY \
        --casper-validator-public-key $CL_VALIDATOR_PUBLIC_KEY \
        --grpc-socket $CL_GRPC_SOCKET
fi
