#!/usr/bin/env bash

set -e

export CL_SERVER_BOOTSTRAP=""
BOOTSTRAP_HOSTNAMES=${BOOTSTRAP_HOSTNAMES:-""}

for BOOTSTRAP_HOSTNAME in $BOOTSTRAP_HOSTNAMES; do
    if [ "$BOOTSTRAP_HOSTNAME" != "$HOSTNAME" ]; then
        BOOTSTRAP_ID=$(cat $HOME/.casperlabs/nodes/$BOOTSTRAP_HOSTNAME/node-id)
        BOOTSTRAP="casperlabs://${BOOTSTRAP_ID}@${BOOTSTRAP_HOSTNAME}?protocol=40400&discovery=40404"
        export CL_SERVER_BOOTSTRAP="$BOOTSTRAP $CL_SERVER_BOOTSTRAP"
        export CL_CASPER_STANDALONE=false
    fi
done

export CL_CASPER_STANDALONE=${CL_CASPER_STANDALONE:-true}

exec /usr/bin/casperlabs-node run --server-host $HOSTNAME
