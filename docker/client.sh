#!/usr/bin/env bash

set -e

# Deploy a WASM contract by connecting to the docker network using the client image.
# See https://slack-files.com/TDVFB45LG-FFBGDQSBW-bad20239ec

# usage: ./client.sh <node-id> <command> [OPTION...]
# for example:
#
# ./client.sh node-0 deploy $PWD/../../contract-examples/hello-name/define/target/wasm32-unknown-unknown/release \
#     --from 00000000000000000000 \
#     --gas-price 1 \
#     --session /data/helloname.wasm \
#     --payment /data/helloname.wasm
#
# ./client.sh node-0 propose

if [ $# -lt 2 ]; then
    echo "usage: ./client.sh <node-id> <command> [OPTION...]" && exit 1
fi

NODE=$1; shift
CMD=$1; shift

# cmd args
function run_default() {
    docker run --rm \
        --network casperlabs \
        casperlabs/client:latest \
        --host $NODE $CMD $@
}

# cmd vol args
function run_with_vol() {
    VOL=$1; shift
    docker run --rm \
        --network casperlabs \
        --volume $VOL:/data \
        --volume $PWD/keys:/keys \
        casperlabs/client:latest \
        --host $NODE $CMD $@
}


case "$CMD" in
    --*)
        # --help doesn't like --host and --port
        docker run --rm \
            --network casperlabs \
            casperlabs/client:latest \
            $CMD
        ;;

    "deploy")
        # Need to mount the files.
        run_with_vol $@
        ;;

    "vdag")
        # For the slideshow we need to mount a directory to save to.
        if [[ "$1" = --* ]]; then
            run_default $@
        else
            run_with_vol $@
        fi
        ;;

    *)
        run_default $@
        ;;
esac
