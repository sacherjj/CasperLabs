#!/usr/bin/env bash

set -e

CL_SERVER_BOOTSTRAP=${CL_SERVER_BOOTSTRAP:-""}

# Unless specified, connect to each other node, so there's no specific bootstrap,
# therefore we can restart any node without worrying that it will not try to reconnect.
if [ -z "$CL_SERVER_BOOTSTRAP" ]; then
    cd $HOME/.casperlabs/nodes
    while read -r BOOTSTRAP_HOSTNAME ; do
        if [ "$BOOTSTRAP_HOSTNAME" != "$HOSTNAME" ]; then
            BOOTSTRAP_ID=$(cat $BOOTSTRAP_HOSTNAME/node-id)
            BOOTSTRAP="casperlabs://${BOOTSTRAP_ID}@${BOOTSTRAP_HOSTNAME}?protocol=40400&discovery=40404"
            export CL_SERVER_BOOTSTRAP="$BOOTSTRAP $CL_SERVER_BOOTSTRAP"
        fi
    done < <(ls)
    cd -
fi

echo CL_SERVER_BOOTSTRAP = $CL_SERVER_BOOTSTRAP

exec /usr/bin/casperlabs-node run --server-host $HOSTNAME
